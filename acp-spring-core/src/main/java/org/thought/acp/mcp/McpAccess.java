package org.thought.acp.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thought.acp.config.McpServerSpec;
import org.thought.acp.session.SessionPrincipal;

/**
 * Turns the configured MCP servers into the ones a particular session is handed.
 *
 * <p>One per agent connection. For each session it asks the {@link McpCredentialsProvider} about
 * every HTTP server; those it has credentials for are routed through the loopback {@link McpProxy}
 * under a path only that session knows, and the rest go to the agent as configured. The proxy is
 * started the first time a server needs it, so an application without a provider — or whose
 * provider never returns anything — never opens a port.
 *
 * <p>A {@link Grant} lives exactly as long as its session. Closing it removes the session's routes,
 * so an agent that kept a proxy URL from a closed session gets a 404 rather than a token.
 */
public final class McpAccess implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(McpAccess.class);

	private final McpCredentialsProvider provider;

	private final Duration requestTimeout;

	private final List<McpRequestFilter> filters;

	private final Object lock = new Object();

	private McpProxy proxy;

	private boolean closed;

	/**
	 * @param requestTimeout how long the proxy waits for an upstream to start answering
	 */
	public McpAccess(McpCredentialsProvider provider, Duration requestTimeout) {
		this(provider, requestTimeout, List.of());
	}

	/**
	 * @param filters the agent's own MCP workarounds; while there are any, every HTTP server goes
	 * through the proxy, credentialed or not, since a workaround in the proxy does nothing for a
	 * server the agent reaches directly
	 */
	public McpAccess(McpCredentialsProvider provider, Duration requestTimeout, List<McpRequestFilter> filters) {
		this.provider = provider == null ? McpCredentialsProvider.none() : provider;
		this.requestTimeout = requestTimeout;
		this.filters = filters == null ? List.of() : List.copyOf(filters);
	}

	/**
	 * The servers to declare for one session, with credentialed ones replaced by proxy routes.
	 *
	 * <p>Every server's credentials are asked for before any route is published, so a provider that
	 * refuses one server — a user not yet signed in to it — leaves nothing behind.
	 *
	 * @param principal who the session is for, or null
	 * @throws RuntimeException whatever the provider threw, which refuses the session
	 */
	public Grant grant(SessionPrincipal principal, List<McpServerSpec> servers) {
		Map<String, McpProxy.Upstream> upstreams = new LinkedHashMap<>();
		for (McpServerSpec server : servers) {
			if (server instanceof McpServerSpec.Http http) {
				Optional<McpCredentials> credentials = provider.credentialsFor(http, principal);
				if (credentials.isPresent() || !filters.isEmpty()) {
					upstreams.put(http.name(), new McpProxy.Upstream(http.name(), http.url(), http.headers(),
							credentials.orElse(McpCredentials.NONE)));
				}
			}
		}
		if (upstreams.isEmpty()) {
			return new Grant(servers, null, null);
		}

		McpProxy running = proxy();
		String token = running.register(upstreams);
		List<McpServerSpec> handed = new ArrayList<>(servers.size());
		for (McpServerSpec server : servers) {
			handed.add(upstreams.containsKey(server.name())
					? new McpServerSpec.Http(server.name(), running.url(token, server.name()), Map.of()) : server);
		}
		logger.debug("Routing MCP server(s) {} through the loopback proxy for this session", upstreams.keySet());
		return new Grant(handed, running, token);
	}

	private McpProxy proxy() {
		synchronized (lock) {
			if (closed) {
				throw new IllegalStateException("MCP access is closed");
			}
			if (proxy == null) {
				proxy = McpProxy.start(requestTimeout, filters);
			}
			return proxy;
		}
	}

	/** Open proxy routes, one per session holding a grant. Exposed for tests and diagnostics. */
	public int activeGrants() {
		synchronized (lock) {
			return proxy == null ? 0 : proxy.routeCount();
		}
	}

	/** Stops the proxy, if it was ever started. Every outstanding grant stops working. */
	@Override
	public void close() {
		McpProxy running;
		synchronized (lock) {
			closed = true;
			running = proxy;
			proxy = null;
		}
		if (running != null) {
			running.close();
		}
	}

	/**
	 * One session's MCP servers, as the agent is to be told about them.
	 *
	 * <p>Closing is idempotent, because a session can end by several paths and the registry that
	 * owns it does not track which one ran first.
	 */
	public static final class Grant implements AutoCloseable {

		/** A grant that routes nothing: what a session gets when there is nothing to protect. */
		public static final Grant NONE = new Grant(List.of(), null, null);

		private final List<McpServerSpec> servers;

		private final McpProxy proxy;

		private final String token;

		private final AtomicBoolean closed = new AtomicBoolean();

		private Grant(List<McpServerSpec> servers, McpProxy proxy, String token) {
			this.servers = List.copyOf(servers);
			this.proxy = proxy;
			this.token = token;
		}

		/** What to put in {@code session/new}, {@code session/load} or {@code session/resume}. */
		public List<McpServerSpec> servers() {
			return servers;
		}

		@Override
		public void close() {
			if (proxy != null && closed.compareAndSet(false, true)) {
				proxy.unregister(token);
			}
		}
	}
}
