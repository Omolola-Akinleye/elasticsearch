/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.security.transport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.remote.RemoteClusterNodesAction;
import org.elasticsearch.action.admin.cluster.shards.ClusterSearchShardsAction;
import org.elasticsearch.action.admin.cluster.state.ClusterStateAction;
import org.elasticsearch.action.admin.indices.resolve.ResolveIndexAction;
import org.elasticsearch.action.fieldcaps.FieldCapabilitiesAction;
import org.elasticsearch.action.search.SearchAction;
import org.elasticsearch.action.search.SearchTransportService;
import org.elasticsearch.action.search.TransportOpenPointInTimeAction;
import org.elasticsearch.action.support.DestructiveOperations;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.ssl.SslConfiguration;
import org.elasticsearch.common.util.Maps;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.common.util.concurrent.RunOnce;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.RemoteConnectionManager;
import org.elasticsearch.transport.SendRequestTransportException;
import org.elasticsearch.transport.TcpTransport;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.transport.TransportActionProxy;
import org.elasticsearch.transport.TransportChannel;
import org.elasticsearch.transport.TransportInterceptor;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.transport.TransportRequestHandler;
import org.elasticsearch.transport.TransportRequestOptions;
import org.elasticsearch.transport.TransportResponse;
import org.elasticsearch.transport.TransportResponseHandler;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.transport.TransportService.ContextRestoreResponseHandler;
import org.elasticsearch.xpack.core.XPackSettings;
import org.elasticsearch.xpack.core.security.SecurityContext;
import org.elasticsearch.xpack.core.security.authc.Authentication;
import org.elasticsearch.xpack.core.security.authc.CrossClusterAccessSubjectInfo;
import org.elasticsearch.xpack.core.security.authc.Subject;
import org.elasticsearch.xpack.core.security.transport.ProfileConfigurations;
import org.elasticsearch.xpack.core.security.user.CrossClusterAccessUser;
import org.elasticsearch.xpack.core.security.user.SystemUser;
import org.elasticsearch.xpack.core.security.user.User;
import org.elasticsearch.xpack.core.ssl.SSLService;
import org.elasticsearch.xpack.security.authc.AuthenticationService;
import org.elasticsearch.xpack.security.authc.CrossClusterAccessAuthenticationService;
import org.elasticsearch.xpack.security.authc.CrossClusterAccessHeaders;
import org.elasticsearch.xpack.security.authz.AuthorizationService;
import org.elasticsearch.xpack.security.authz.AuthorizationUtils;
import org.elasticsearch.xpack.security.authz.PreAuthorizationUtils;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.elasticsearch.transport.RemoteClusterPortSettings.REMOTE_CLUSTER_PROFILE;
import static org.elasticsearch.transport.RemoteClusterPortSettings.REMOTE_CLUSTER_SERVER_ENABLED;
import static org.elasticsearch.transport.RemoteClusterService.REMOTE_CLUSTER_HANDSHAKE_ACTION_NAME;
import static org.elasticsearch.xpack.core.security.authc.Authentication.VERSION_CROSS_CLUSTER_ACCESS_REALM;
import static org.elasticsearch.xpack.security.transport.RemoteClusterCredentialsResolver.RemoteClusterCredentials;

public class SecurityServerTransportInterceptor implements TransportInterceptor {

    private static final Logger logger = LogManager.getLogger(SecurityServerTransportInterceptor.class);
    // package private for testing
    static final Set<String> CROSS_CLUSTER_ACCESS_ACTION_ALLOWLIST;
    static {
        final Stream<String> actions = Stream.of(
            REMOTE_CLUSTER_HANDSHAKE_ACTION_NAME,
            RemoteClusterNodesAction.NAME,
            TransportService.HANDSHAKE_ACTION_NAME,
            SearchAction.NAME,
            ClusterStateAction.NAME,
            ClusterSearchShardsAction.NAME,
            SearchTransportService.FREE_CONTEXT_SCROLL_ACTION_NAME,
            SearchTransportService.FREE_CONTEXT_ACTION_NAME,
            SearchTransportService.CLEAR_SCROLL_CONTEXTS_ACTION_NAME,
            SearchTransportService.DFS_ACTION_NAME,
            SearchTransportService.QUERY_ACTION_NAME,
            SearchTransportService.QUERY_ID_ACTION_NAME,
            SearchTransportService.QUERY_SCROLL_ACTION_NAME,
            SearchTransportService.QUERY_FETCH_SCROLL_ACTION_NAME,
            SearchTransportService.FETCH_ID_SCROLL_ACTION_NAME,
            SearchTransportService.FETCH_ID_ACTION_NAME,
            SearchTransportService.QUERY_CAN_MATCH_NAME,
            SearchTransportService.QUERY_CAN_MATCH_NODE_NAME,
            TransportOpenPointInTimeAction.OPEN_SHARD_READER_CONTEXT_NAME,
            ResolveIndexAction.NAME,
            FieldCapabilitiesAction.NAME,
            FieldCapabilitiesAction.NAME + "[n]",
            "indices:data/read/eql"
        );
        CROSS_CLUSTER_ACCESS_ACTION_ALLOWLIST = actions
            // Include action, and proxy equivalent (i.e., with proxy action prefix)
            .flatMap(name -> Stream.of(name, TransportActionProxy.getProxyAction(name)))
            .collect(Collectors.toUnmodifiableSet());
    }

    private final AuthenticationService authcService;
    private final AuthorizationService authzService;
    private final SSLService sslService;
    private final Map<String, ServerTransportFilter> profileFilters;
    private final ThreadPool threadPool;
    private final Settings settings;
    private final SecurityContext securityContext;
    private final CrossClusterAccessAuthenticationService crossClusterAccessAuthcService;
    private final RemoteClusterCredentialsResolver remoteClusterCredentialsResolver;
    private final Function<Transport.Connection, Optional<String>> remoteClusterAliasResolver;

    public SecurityServerTransportInterceptor(
        Settings settings,
        ThreadPool threadPool,
        AuthenticationService authcService,
        AuthorizationService authzService,
        SSLService sslService,
        SecurityContext securityContext,
        DestructiveOperations destructiveOperations,
        CrossClusterAccessAuthenticationService crossClusterAccessAuthcService,
        RemoteClusterCredentialsResolver remoteClusterCredentialsResolver
    ) {
        this(
            settings,
            threadPool,
            authcService,
            authzService,
            sslService,
            securityContext,
            destructiveOperations,
            crossClusterAccessAuthcService,
            remoteClusterCredentialsResolver,
            RemoteConnectionManager::resolveRemoteClusterAlias
        );
    }

    SecurityServerTransportInterceptor(
        Settings settings,
        ThreadPool threadPool,
        AuthenticationService authcService,
        AuthorizationService authzService,
        SSLService sslService,
        SecurityContext securityContext,
        DestructiveOperations destructiveOperations,
        CrossClusterAccessAuthenticationService crossClusterAccessAuthcService,
        RemoteClusterCredentialsResolver remoteClusterCredentialsResolver,
        // Inject for simplified testing
        Function<Transport.Connection, Optional<String>> remoteClusterAliasResolver
    ) {
        this.settings = settings;
        this.threadPool = threadPool;
        this.authcService = authcService;
        this.authzService = authzService;
        this.sslService = sslService;
        this.securityContext = securityContext;
        this.crossClusterAccessAuthcService = crossClusterAccessAuthcService;
        this.profileFilters = initializeProfileFilters(destructiveOperations);
        this.remoteClusterCredentialsResolver = remoteClusterCredentialsResolver;
        this.remoteClusterAliasResolver = remoteClusterAliasResolver;
    }

    @Override
    public AsyncSender interceptSender(AsyncSender sender) {
        return interceptForAllRequests(
            // Branching based on the feature flag is not strictly necessary here, but it makes it more obvious we are not interfering with
            // non-feature-flagged deployments
            TcpTransport.isUntrustedRemoteClusterEnabled() ? interceptForCrossClusterAccessRequests(sender) : sender
        );
    }

    private AsyncSender interceptForAllRequests(AsyncSender sender) {
        return new AsyncSender() {
            @Override
            public <T extends TransportResponse> void sendRequest(
                Transport.Connection connection,
                String action,
                TransportRequest request,
                TransportRequestOptions options,
                TransportResponseHandler<T> handler
            ) {
                assertNoCrossClusterAccessHeadersInContext();
                final Optional<String> remoteClusterAlias = remoteClusterAliasResolver.apply(connection);
                if (PreAuthorizationUtils.shouldRemoveParentAuthorizationFromThreadContext(remoteClusterAlias, action, securityContext)) {
                    securityContext.executeAfterRemovingParentAuthorization(original -> {
                        sendRequestInner(
                            sender,
                            connection,
                            action,
                            request,
                            options,
                            new ContextRestoreResponseHandler<>(threadPool.getThreadContext().wrapRestorable(original), handler)
                        );
                    });
                } else {
                    sendRequestInner(sender, connection, action, request, options, handler);
                }
            }

            private void assertNoCrossClusterAccessHeadersInContext() {
                assert securityContext.getThreadContext()
                    .getHeader(CrossClusterAccessHeaders.CROSS_CLUSTER_ACCESS_CREDENTIALS_HEADER_KEY) == null
                    : "cross cluster access headers should not be in security context";
                assert securityContext.getThreadContext()
                    .getHeader(CrossClusterAccessSubjectInfo.CROSS_CLUSTER_ACCESS_SUBJECT_INFO_HEADER_KEY) == null
                    : "cross cluster access headers should not be in security context";
            }
        };
    }

    public <T extends TransportResponse> void sendRequestInner(
        AsyncSender sender,
        Transport.Connection connection,
        String action,
        TransportRequest request,
        TransportRequestOptions options,
        TransportResponseHandler<T> handler
    ) {
        // the transport in core normally does this check, BUT since we are serializing to a string header we need to do it
        // ourselves otherwise we wind up using a version newer than what we can actually send
        final TransportVersion minVersion = TransportVersion.min(connection.getTransportVersion(), TransportVersion.CURRENT);

        // Sometimes a system action gets executed like a internal create index request or update mappings request
        // which means that the user is copied over to system actions so we need to change the user
        if (AuthorizationUtils.shouldReplaceUserWithSystem(threadPool.getThreadContext(), action)) {
            securityContext.executeAsSystemUser(
                minVersion,
                original -> sendWithUser(
                    connection,
                    action,
                    request,
                    options,
                    new ContextRestoreResponseHandler<>(threadPool.getThreadContext().wrapRestorable(original), handler),
                    sender
                )
            );
        } else if (AuthorizationUtils.shouldSetUserBasedOnActionOrigin(threadPool.getThreadContext())) {
            AuthorizationUtils.switchUserBasedOnActionOriginAndExecute(
                threadPool.getThreadContext(),
                securityContext,
                minVersion,
                (original) -> sendWithUser(
                    connection,
                    action,
                    request,
                    options,
                    new ContextRestoreResponseHandler<>(threadPool.getThreadContext().wrapRestorable(original), handler),
                    sender
                )
            );
        } else if (securityContext.getAuthentication() != null
            && securityContext.getAuthentication().getEffectiveSubject().getTransportVersion().equals(minVersion) == false) {
                // re-write the authentication since we want the authentication version to match the version of the connection
                securityContext.executeAfterRewritingAuthentication(
                    original -> sendWithUser(
                        connection,
                        action,
                        request,
                        options,
                        new ContextRestoreResponseHandler<>(threadPool.getThreadContext().wrapRestorable(original), handler),
                        sender
                    ),
                    minVersion
                );
            } else {
                sendWithUser(connection, action, request, options, handler, sender);
            }
    }

    // Package private for testing
    Map<String, ServerTransportFilter> getProfileFilters() {
        return profileFilters;
    }

    private AsyncSender interceptForCrossClusterAccessRequests(final AsyncSender sender) {
        return new AsyncSender() {
            @Override
            public <T extends TransportResponse> void sendRequest(
                Transport.Connection connection,
                String action,
                TransportRequest request,
                TransportRequestOptions options,
                TransportResponseHandler<T> handler
            ) {
                final Optional<RemoteClusterCredentials> remoteClusterCredentials = getRemoteClusterCredentials(connection);
                if (remoteClusterCredentials.isPresent()) {
                    sendWithCrossClusterAccessHeaders(remoteClusterCredentials.get(), connection, action, request, options, handler);
                } else {
                    // Send regular request, without cross cluster access headers
                    try {
                        sender.sendRequest(connection, action, request, options, handler);
                    } catch (Exception e) {
                        handler.handleException(new SendRequestTransportException(connection.getNode(), action, e));
                    }
                }
            }

            /**
             * Returns cluster credentials if the connection is remote, cluster credentials are set up for the target cluster, and access
             * via cross cluster access headers is supported for the request type and authenticated subject.
             */
            private Optional<RemoteClusterCredentials> getRemoteClusterCredentials(Transport.Connection connection) {
                final Optional<String> optionalRemoteClusterAlias = remoteClusterAliasResolver.apply(connection);
                if (optionalRemoteClusterAlias.isEmpty()) {
                    logger.trace("Connection is not remote");
                    return Optional.empty();
                }

                final String remoteClusterAlias = optionalRemoteClusterAlias.get();
                final Optional<RemoteClusterCredentials> remoteClusterCredentials = remoteClusterCredentialsResolver.resolve(
                    remoteClusterAlias
                );
                if (remoteClusterCredentials.isEmpty()) {
                    logger.trace("No cluster credentials are configured for remote cluster [{}]", remoteClusterAlias);
                    return Optional.empty();
                }

                final Authentication authentication = securityContext.getAuthentication();
                assert authentication != null : "authentication must be present in security context";
                final Subject effectiveSubject = authentication.getEffectiveSubject();
                if (false == effectiveSubject.getType().equals(Subject.Type.USER)
                    && false == effectiveSubject.getType().equals(Subject.Type.API_KEY)
                    && false == effectiveSubject.getType().equals(Subject.Type.SERVICE_ACCOUNT)) {
                    logger.trace(
                        "Effective subject of request to remote cluster [{}] has an unsupported type [{}]",
                        remoteClusterAlias,
                        effectiveSubject.getType()
                    );
                    return Optional.empty();
                }

                return remoteClusterCredentials;
            }

            private <T extends TransportResponse> void sendWithCrossClusterAccessHeaders(
                final RemoteClusterCredentials remoteClusterCredentials,
                final Transport.Connection connection,
                final String action,
                final TransportRequest request,
                final TransportRequestOptions options,
                final TransportResponseHandler<T> handler
            ) {
                final String remoteClusterAlias = remoteClusterCredentials.clusterAlias();
                if (false == CROSS_CLUSTER_ACCESS_ACTION_ALLOWLIST.contains(action)) {
                    throw new IllegalArgumentException(
                        "action ["
                            + action
                            + "] towards remote cluster ["
                            + remoteClusterAlias
                            + "] cannot be executed because it is not allowed as a cross cluster operation"
                    );
                }
                if (connection.getTransportVersion().before(VERSION_CROSS_CLUSTER_ACCESS_REALM)) {
                    throw new IllegalArgumentException(
                        "Settings for remote cluster ["
                            + remoteClusterAlias
                            + "] indicate cross cluster access headers should be sent but target cluster version ["
                            + connection.getTransportVersion()
                            + "] does not support receiving them"
                    );
                }

                logger.debug(
                    "Sending [{}] request to [{}] with cross cluster access headers for [{}] action",
                    request.getClass(),
                    remoteClusterAlias,
                    action
                );

                final Authentication authentication = securityContext.getAuthentication();
                assert authentication != null : "authentication must be present in security context";

                final User user = authentication.getEffectiveSubject().getUser();
                if (SystemUser.is(user)) {
                    final var crossClusterAccessHeaders = new CrossClusterAccessHeaders(
                        remoteClusterCredentials.credentials(),
                        CrossClusterAccessUser.subjectInfoWithEmptyRoleDescriptors(
                            authentication.getEffectiveSubject().getTransportVersion(),
                            authentication.getEffectiveSubject().getRealm().getNodeName()
                        )
                    );
                    sendWithCrossClusterAccessHeaders(crossClusterAccessHeaders, connection, action, request, options, handler);
                } else if (User.isInternal(user)) {
                    final String message = "internal user [" + user.principal() + "] should not be used for cross cluster requests";
                    assert false : message;
                    throw new IllegalArgumentException(message);
                } else {
                    authzService.getRoleDescriptorsIntersectionForRemoteCluster(
                        remoteClusterAlias,
                        authentication.getEffectiveSubject(),
                        ActionListener.wrap(roleDescriptorsIntersection -> {
                            if (roleDescriptorsIntersection.isEmpty()) {
                                throw authzService.remoteActionDenied(authentication, action, remoteClusterAlias);
                            }
                            final var crossClusterAccessHeaders = new CrossClusterAccessHeaders(
                                remoteClusterCredentials.credentials(),
                                new CrossClusterAccessSubjectInfo(authentication, roleDescriptorsIntersection)
                            );
                            sendWithCrossClusterAccessHeaders(crossClusterAccessHeaders, connection, action, request, options, handler);
                        }, // it's safe to not use a context restore handler here since `getRoleDescriptorsIntersectionForRemoteCluster`
                           // uses a context preserving listener internally, and `sendWithCrossClusterAccessHeaders` uses a context restore
                           // handler
                            e -> handler.handleException(new SendRequestTransportException(connection.getNode(), action, e))
                        )
                    );
                }
            }

            private <T extends TransportResponse> void sendWithCrossClusterAccessHeaders(
                final CrossClusterAccessHeaders crossClusterAccessHeaders,
                final Transport.Connection connection,
                final String action,
                final TransportRequest request,
                final TransportRequestOptions options,
                final TransportResponseHandler<T> handler
            ) {
                final ThreadContext threadContext = securityContext.getThreadContext();
                final var contextRestoreHandler = new ContextRestoreResponseHandler<>(threadContext.newRestorableContext(true), handler);
                try (ThreadContext.StoredContext ignored = threadContext.stashContext()) {
                    crossClusterAccessHeaders.writeToContext(threadContext);
                    sender.sendRequest(connection, action, request, options, contextRestoreHandler);
                } catch (Exception e) {
                    contextRestoreHandler.handleException(new SendRequestTransportException(connection.getNode(), action, e));
                }
            }
        };
    }

    private <T extends TransportResponse> void sendWithUser(
        Transport.Connection connection,
        String action,
        TransportRequest request,
        TransportRequestOptions options,
        TransportResponseHandler<T> handler,
        AsyncSender sender
    ) {
        if (securityContext.getAuthentication() == null) {
            // we use an assertion here to ensure we catch this in our testing infrastructure, but leave the ISE for cases we do not catch
            // in tests and may be hit by a user
            assertNoAuthentication(action);
            throw new IllegalStateException("there should always be a user when sending a message for action [" + action + "]");
        }

        assert securityContext.getParentAuthorization() == null || remoteClusterAliasResolver.apply(connection).isPresent() == false
            : "parent authorization header should not be set for remote cluster requests";

        try {
            sender.sendRequest(connection, action, request, options, handler);
        } catch (Exception e) {
            handler.handleException(new SendRequestTransportException(connection.getNode(), action, e));
        }
    }

    // pkg-private method to allow overriding for tests
    void assertNoAuthentication(String action) {
        assert false : "there should always be a user when sending a message for action [" + action + "]";
    }

    @Override
    public <T extends TransportRequest> TransportRequestHandler<T> interceptHandler(
        String action,
        String executor,
        boolean forceExecution,
        TransportRequestHandler<T> actualHandler
    ) {
        return new ProfileSecuredRequestHandler<>(logger, action, forceExecution, executor, actualHandler, profileFilters, threadPool);
    }

    private Map<String, ServerTransportFilter> initializeProfileFilters(DestructiveOperations destructiveOperations) {
        final Map<String, SslConfiguration> profileConfigurations = ProfileConfigurations.get(settings, sslService, false);

        Map<String, ServerTransportFilter> profileFilters = Maps.newMapWithExpectedSize(profileConfigurations.size() + 1);

        final boolean transportSSLEnabled = XPackSettings.TRANSPORT_SSL_ENABLED.get(settings);
        final boolean remoteClusterPortEnabled = REMOTE_CLUSTER_SERVER_ENABLED.get(settings);
        final boolean remoteClusterServerSSLEnabled = XPackSettings.REMOTE_CLUSTER_SERVER_SSL_ENABLED.get(settings);

        for (Map.Entry<String, SslConfiguration> entry : profileConfigurations.entrySet()) {
            final SslConfiguration profileConfiguration = entry.getValue();
            final String profileName = entry.getKey();
            final boolean useRemoteClusterProfile = remoteClusterPortEnabled && profileName.equals(REMOTE_CLUSTER_PROFILE);
            if (useRemoteClusterProfile) {
                profileFilters.put(
                    profileName,
                    new CrossClusterAccessServerTransportFilter(
                        crossClusterAccessAuthcService,
                        authzService,
                        threadPool.getThreadContext(),
                        remoteClusterServerSSLEnabled && SSLService.isSSLClientAuthEnabled(profileConfiguration),
                        destructiveOperations,
                        securityContext
                    )
                );
            } else {
                profileFilters.put(
                    profileName,
                    new ServerTransportFilter(
                        authcService,
                        authzService,
                        threadPool.getThreadContext(),
                        transportSSLEnabled && SSLService.isSSLClientAuthEnabled(profileConfiguration),
                        destructiveOperations,
                        securityContext
                    )
                );
            }
        }

        return Collections.unmodifiableMap(profileFilters);
    }

    public static class ProfileSecuredRequestHandler<T extends TransportRequest> implements TransportRequestHandler<T> {

        private final String action;
        private final TransportRequestHandler<T> handler;
        private final Map<String, ServerTransportFilter> profileFilters;
        private final ThreadContext threadContext;
        private final String executorName;
        private final ThreadPool threadPool;
        private final boolean forceExecution;
        private final Logger logger;

        ProfileSecuredRequestHandler(
            Logger logger,
            String action,
            boolean forceExecution,
            String executorName,
            TransportRequestHandler<T> handler,
            Map<String, ServerTransportFilter> profileFilters,
            ThreadPool threadPool
        ) {
            this.logger = logger;
            this.action = action;
            this.executorName = executorName;
            this.handler = handler;
            this.profileFilters = profileFilters;
            this.threadContext = threadPool.getThreadContext();
            this.threadPool = threadPool;
            this.forceExecution = forceExecution;
        }

        AbstractRunnable getReceiveRunnable(T request, TransportChannel channel, Task task) {
            final Runnable releaseRequest = new RunOnce(request::decRef);
            request.incRef();
            return new AbstractRunnable() {
                @Override
                public boolean isForceExecution() {
                    return forceExecution;
                }

                @Override
                public void onFailure(Exception e) {
                    try {
                        channel.sendResponse(e);
                    } catch (Exception e1) {
                        e1.addSuppressed(e);
                        logger.warn("failed to send exception response for action [" + action + "]", e1);
                    }
                }

                @Override
                protected void doRun() throws Exception {
                    handler.messageReceived(request, channel, task);
                }

                @Override
                public void onAfter() {
                    releaseRequest.run();
                }
            };
        }

        @Override
        public String toString() {
            return "ProfileSecuredRequestHandler{"
                + "action='"
                + action
                + '\''
                + ", executorName='"
                + executorName
                + '\''
                + ", forceExecution="
                + forceExecution
                + '}';
        }

        @Override
        public void messageReceived(T request, TransportChannel channel, Task task) {
            try (ThreadContext.StoredContext ctx = threadContext.newStoredContextPreservingResponseHeaders()) {
                String profile = channel.getProfileName();
                ServerTransportFilter filter = profileFilters.get(profile);

                if (filter == null) {
                    if (TransportService.DIRECT_RESPONSE_PROFILE.equals(profile)) {
                        // apply the default filter to local requests. We never know what the request is or who sent it...
                        filter = profileFilters.get("default");
                    } else {
                        String msg = "transport profile [" + profile + "] is not associated with a transport filter";
                        throw new IllegalStateException(msg);
                    }
                }
                assert filter != null;

                final AbstractRunnable receiveMessage = getReceiveRunnable(request, channel, task);
                final ActionListener<Void> filterListener;
                if (ThreadPool.Names.SAME.equals(executorName)) {
                    filterListener = new AbstractFilterListener(receiveMessage) {
                        @Override
                        public void onResponse(Void unused) {
                            receiveMessage.run();
                        }
                    };
                } else {
                    final Thread executingThread = Thread.currentThread();
                    filterListener = new AbstractFilterListener(receiveMessage) {

                        @Override
                        public void onResponse(Void unused) {
                            if (executingThread == Thread.currentThread()) {
                                // only fork off if we get called on another thread this means we moved to
                                // an async execution and in this case we need to go back to the thread pool
                                // that was actually executing it. it's also possible that the
                                // thread-pool we are supposed to execute on is `SAME` in that case
                                // the handler is OK with executing on a network thread and we can just continue even if
                                // we are on another thread due to async operations
                                receiveMessage.run();
                            } else {
                                try {
                                    threadPool.executor(executorName).execute(receiveMessage);
                                } catch (Exception e) {
                                    onFailure(e);
                                }
                            }
                        }
                    };
                }
                filter.inbound(action, request, channel, filterListener);

            }
        }
    }

    private abstract static class AbstractFilterListener implements ActionListener<Void> {

        protected final AbstractRunnable receiveMessage;

        protected AbstractFilterListener(AbstractRunnable receiveMessage) {
            this.receiveMessage = receiveMessage;
        }

        @Override
        public void onFailure(Exception e) {
            try {
                receiveMessage.onFailure(e);
            } finally {
                receiveMessage.onAfter();
            }
        }
    }
}
