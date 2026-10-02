package org.springaicommunity.acp.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.acp.config.McpServerSpec;
import org.springaicommunity.acp.config.Validation;
import org.springaicommunity.acp.runtime.AgentRuntime.McpScope;
import org.springaicommunity.acp.session.SessionPrincipal;

/**
 * Turns the configured MCP servers into the ones a particular session is handed.
 *
 * <p>
 * One per agent connection. For each session it asks the {@link McpCredentialsProvider}
 * about every HTTP server; those it has credentials for are routed through the loopback
 * {@link McpProxy} under a path only that session knows, and the rest go to the agent as
 * configured. The proxy is started the first time a server needs it, so an application
 * without a provider — or whose provider never returns anything — never opens a port.
 *
 * <p>
 * A {@link Grant} lives exactly as long as its session. Closing it removes the session's
 * routes, so an agent that kept a proxy URL from a closed session gets a 404 rather than
 * a token.
 *
 * <p>
 * Unless the agent shares MCP servers across its sessions ({@link McpScope#PROCESS}). Its
 * sessions then all call through the route declared last, so a route per session would
 * send one user's calls out as another's, and strand every session on a 404 once the
 * newest closes. Under that scope the access serves one principal at a time and gives
 * them a single route, re-pointed at their current servers by each grant and removed when
 * their last grant closes.
 */
public final class McpAccess implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(McpAccess.class);

	private final McpCredentialsProvider provider;

	private final Duration requestTimeout;

	private final List<McpRequestFilter> filters;

	private final Object lock = new Object();

	private final McpScope scope;

	/**
	 * Under {@link McpScope#PROCESS}, the principal whose sessions hold grants: at most
	 * one entry. Keyed by an optional so an application without principals is one.
	 */
	private final Map<Optional<SessionPrincipal>, Shared> shared = new HashMap<>();

	private McpProxy proxy;

	private boolean closed;

	/**
	 * @param requestTimeout how long the proxy waits for an upstream to start answering
	 */
	public McpAccess(McpCredentialsProvider provider, Duration requestTimeout) {
		this(provider, requestTimeout, List.of());
	}

	/**
	 * @param filters the agent's own MCP workarounds; while there are any, every HTTP
	 * server goes through the proxy, credentialed or not, since a workaround in the proxy
	 * does nothing for a server the agent reaches directly
	 */
	public McpAccess(McpCredentialsProvider provider, Duration requestTimeout, List<McpRequestFilter> filters) {
		this(provider, requestTimeout, filters, McpScope.SESSION);
	}

	/**
	 * @param scope where the agent keeps a session's MCP servers; see
	 * {@link org.springaicommunity.acp.runtime.AgentRuntime#mcpScope()}
	 */
	public McpAccess(McpCredentialsProvider provider, Duration requestTimeout, List<McpRequestFilter> filters,
			McpScope scope) {
		this.provider = provider == null ? McpCredentialsProvider.none() : provider;
		this.requestTimeout = requestTimeout;
		this.filters = filters == null ? List.of() : List.copyOf(filters);
		this.scope = scope == null ? McpScope.SESSION : scope;
	}

	/**
	 * The servers to declare for one session, with credentialed ones replaced by proxy
	 * routes.
	 *
	 * <p>
	 * Every server's credentials are asked for before any route is published, so a
	 * provider that refuses one server — a user not yet signed in to it — leaves nothing
	 * behind.
	 * <p>
	 * A server declared without a URL of its own reaches the session only if the provider
	 * routes it, with {@link McpCredentials#upstream()}; otherwise it is left out, and
	 * the session opens without it.
	 * @param principal who the session is for, or null
	 * @throws RuntimeException whatever the provider threw, which refuses the session
	 * @throws IllegalStateException under {@link McpScope#PROCESS}, when another
	 * principal's sessions still hold grants
	 */
	public Grant grant(SessionPrincipal principal, List<McpServerSpec> servers) {
		Map<String, McpProxy.Upstream> upstreams = new LinkedHashMap<>();
		List<String> omitted = new ArrayList<>();
		for (McpServerSpec server : servers) {
			if (server instanceof McpServerSpec.Http http) {
				Optional<McpCredentials> credentials = provider.credentialsFor(http, principal);
				URI url = credentials.flatMap(McpCredentials::upstream).orElse(http.url());
				if (url == null) {
					omitted.add(http.name());
				}
				else if (credentials.isPresent() || !filters.isEmpty()) {
					Validation.requireSecureUrl(url, "mcp server '" + http.name() + "' upstream url");
					upstreams.put(http.name(), new McpProxy.Upstream(http.name(), url, http.headers(),
							credentials.orElse(McpCredentials.NONE)));
				}
			}
		}
		if (!omitted.isEmpty()) {
			logger.info("Opening this session without MCP server(s) {}: they have no url of their own, and the "
					+ "credentials provider did not supply one", omitted);
		}
		List<McpServerSpec> offered = servers.stream().filter(server -> !omitted.contains(server.name())).toList();
		if (scope == McpScope.PROCESS) {
			return sharedGrant(Optional.ofNullable(principal), offered, upstreams);
		}
		if (upstreams.isEmpty()) {
			return new Grant(offered, null);
		}

		McpProxy running = proxy();
		String token = running.register(upstreams);
		logger.debug("Routing MCP server(s) {} through the loopback proxy for this session", upstreams.keySet());
		return new Grant(routed(offered, upstreams, running, token), () -> running.unregister(token));
	}

	/**
	 * A grant on the principal's one route, counted so the route outlives every session
	 * but their last.
	 *
	 * <p>
	 * Counted even when nothing is routed. A session handed no route still makes the
	 * agent re-declare the servers it is given, and it can call the ones it is not given
	 * through whatever route the agent already holds — so a second principal is refused
	 * whenever the first holds any grant at all, not just a routed one.
	 */
	private Grant sharedGrant(Optional<SessionPrincipal> principal, List<McpServerSpec> offered,
			Map<String, McpProxy.Upstream> upstreams) {
		synchronized (lock) {
			if (closed) {
				throw new IllegalStateException("MCP access is closed");
			}
			if (!shared.isEmpty() && !shared.containsKey(principal)) {
				throw new IllegalStateException("This agent shares MCP servers across its sessions, so its "
						+ "process serves one principal at a time, and another principal's sessions are open");
			}
			Shared current = shared.computeIfAbsent(principal, key -> new Shared());
			List<McpServerSpec> handed = offered;
			if (!upstreams.isEmpty()) {
				McpProxy running = proxy();
				if (current.token == null) {
					current.token = running.register(upstreams);
				}
				else {
					running.update(current.token, upstreams);
				}
				handed = routed(offered, upstreams, running, current.token);
				logger.debug("Routing MCP server(s) {} through the loopback proxy for this principal",
						upstreams.keySet());
			}
			current.refs++;
			return new Grant(handed, () -> release(principal));
		}
	}

	private void release(Optional<SessionPrincipal> principal) {
		synchronized (lock) {
			Shared current = shared.get(principal);
			if (current == null || --current.refs > 0) {
				return;
			}
			shared.remove(principal);
			if (current.token != null && proxy != null) {
				proxy.unregister(current.token);
			}
		}
	}

	private static List<McpServerSpec> routed(List<McpServerSpec> offered, Map<String, McpProxy.Upstream> upstreams,
			McpProxy running, String token) {
		List<McpServerSpec> handed = new ArrayList<>(offered.size());
		for (McpServerSpec server : offered) {
			handed.add(upstreams.containsKey(server.name())
					? new McpServerSpec.Http(server.name(), running.url(token, server.name()), Map.of()) : server);
		}
		return handed;
	}

	/**
	 * The configured servers a grant actually hands over, described as configured: a
	 * proxy URL would tell a reader of the log nothing about what was asked for, and a
	 * server the grant left out was not asked for at all.
	 */
	public static List<String> describeHanded(List<McpServerSpec> configured, Grant grant) {
		Set<String> handed = new HashSet<>();
		grant.servers().forEach(server -> handed.add(server.name()));
		return configured.stream()
			.filter(server -> handed.contains(server.name()))
			.map(McpServerSpec::describe)
			.toList();
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

	/**
	 * Open proxy routes: one per session holding a grant, or under
	 * {@link McpScope#PROCESS} one per principal. Exposed for tests and diagnostics.
	 */
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
			shared.clear();
		}
		if (running != null) {
			running.close();
		}
	}

	/**
	 * One session's MCP servers, as the agent is to be told about them.
	 *
	 * <p>
	 * Closing is idempotent, because a session can end by several paths and the registry
	 * that owns it does not track which one ran first.
	 */
	public static final class Grant implements AutoCloseable {

		/**
		 * A grant that routes nothing: what a session gets when there is nothing to
		 * protect.
		 */
		public static final Grant NONE = new Grant(List.of(), null);

		private final List<McpServerSpec> servers;

		private final Runnable release;

		private final AtomicBoolean closed = new AtomicBoolean();

		private Grant(List<McpServerSpec> servers, Runnable release) {
			this.servers = List.copyOf(servers);
			this.release = release;
		}

		/**
		 * What to put in {@code session/new}, {@code session/load} or
		 * {@code session/resume}.
		 */
		public List<McpServerSpec> servers() {
			return servers;
		}

		@Override
		public void close() {
			if (release != null && closed.compareAndSet(false, true)) {
				release.run();
			}
		}

	}

	/**
	 * One principal's route under {@link McpScope#PROCESS}, and how many grants hold it.
	 */
	private static final class Shared {

		private String token;

		private int refs;

	}

}
