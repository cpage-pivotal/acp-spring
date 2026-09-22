package org.thought.acp.session;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thought.acp.client.AgentClientException;
import org.thought.acp.client.SessionConfigRecorder;
import org.thought.acp.config.AdvertisedSessionConfig;
import org.thought.acp.config.AgentSettings;
import org.thought.acp.mcp.McpAccess;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * {@link AgentSessions} over one connected agent.
 *
 * <p>Two things here are not obvious from the interface. Pagination is followed to the end rather
 * than exposed, because a cursor is a protocol detail and an agent with more sessions than fit in
 * one page is not a different kind of agent; the walk is bounded so a misbehaving agent that always
 * returns a cursor cannot spin forever. And load and resume are serialized against each other,
 * because recovering the config options those responses drop depends on claiming them immediately
 * after the call that produced them — see {@link SessionConfigRecorder}.
 */
public final class DefaultAgentSessions implements AgentSessions {

	private static final Logger logger = LoggerFactory.getLogger(DefaultAgentSessions.class);

	/** An agent that keeps handing back a cursor is broken; stop rather than paginate forever. */
	private static final int MAX_PAGES = 100;

	private final com.agentclientprotocol.sdk.client.AcpAsyncClient acp;

	private final String runtimeId;

	private final AgentSettings settings;

	private final AcpSchema.AgentCapabilities capabilities;

	private final SessionRegistry registry;

	private final SessionConfigRecorder recorder;

	/** Applies the negotiated tier to a session this class has just bound. */
	private final Consumer<AgentSession> configure;

	/** Guards the claim of config options from a response that does not name its session. */
	private final Object attachLock = new Object();

	/** Routes the re-declared MCP servers through the proxy, exactly as a new session's are. */
	private final McpAccess mcpAccess;

	/**
	 * With an {@link McpAccess} of its own, which nothing closes: fine without a credentials
	 * provider, when it never starts a proxy, and meant for tests. A client passes the one it owns.
	 */
	public DefaultAgentSessions(com.agentclientprotocol.sdk.client.AcpAsyncClient acp, String runtimeId,
			AgentSettings settings, AcpSchema.AgentCapabilities capabilities, SessionRegistry registry,
			SessionConfigRecorder recorder, Consumer<AgentSession> configure) {
		this(acp, runtimeId, settings, capabilities, registry, recorder, configure,
				new McpAccess(settings.mcp().credentials(), settings.timeout()));
	}

	public DefaultAgentSessions(com.agentclientprotocol.sdk.client.AcpAsyncClient acp, String runtimeId,
			AgentSettings settings, AcpSchema.AgentCapabilities capabilities, SessionRegistry registry,
			SessionConfigRecorder recorder, Consumer<AgentSession> configure, McpAccess mcpAccess) {
		this.mcpAccess = mcpAccess;
		this.acp = acp;
		this.runtimeId = runtimeId;
		this.settings = settings;
		this.capabilities = capabilities;
		this.registry = registry;
		this.recorder = recorder;
		this.configure = configure;
	}

	@Override
	public List<StoredSession> list() {
		return list(settings.workspace());
	}

	@Override
	public List<StoredSession> list(Path cwd) {
		require(Operation.LIST);
		List<StoredSession> found = new ArrayList<>();
		String cursor = null;
		for (int page = 0; page < MAX_PAGES; page++) {
			AcpSchema.ListSessionsResponse response = acp
					.listSessions(new AcpSchema.ListSessionsRequest(cwd == null ? null : cwd.toString(), cursor, null))
					.block(settings.timeout());
			if (response == null) {
				break;
			}
			if (response.sessions() != null) {
				response.sessions().stream().filter(info -> info.sessionId() != null).map(StoredSession::from)
						.forEach(found::add);
			}
			cursor = response.nextCursor();
			if (cursor == null || cursor.isBlank()) {
				return List.copyOf(found);
			}
		}
		logger.warn("Runtime '{}' kept returning a session/list cursor after {} pages; stopping with {} session(s)",
				runtimeId, MAX_PAGES, found.size());
		return List.copyOf(found);
	}

	@Override
	public AgentSession load(String name, String sessionId) {
		return load(name, sessionId, currentPrincipal());
	}

	@Override
	public AgentSession resume(String name, String sessionId) {
		return resume(name, sessionId, currentPrincipal());
	}

	@Override
	public AgentSession load(String name, String sessionId, SessionPrincipal principal) {
		require(Operation.LOAD);
		return attach(name, sessionId, Operation.LOAD, principal);
	}

	@Override
	public AgentSession resume(String name, String sessionId, SessionPrincipal principal) {
		require(Operation.RESUME);
		return attach(name, sessionId, Operation.RESUME, principal);
	}

	private SessionPrincipal currentPrincipal() {
		return settings.mcp().principals().current().orElse(null);
	}

	/**
	 * Binds an agent-side session to a local name.
	 *
	 * <p>The registration comes first and is undone if the call fails, so a failed load cannot leave
	 * a name pointing at a session this client never attached to.
	 */
	private AgentSession attach(String name, String sessionId, Operation operation, SessionPrincipal principal) {
		org.thought.acp.config.Validation.requireText(sessionId, "session id");
		McpAccess.Grant grant = mcpAccess.grant(principal, settings.mcpServers());
		AgentSession session = registry.adopt(name, sessionId, principal, grant::close);
		try {
			List<AcpSchema.McpServer> servers = mcpServers(grant);
			AdvertisedSessionConfig advertised;
			synchronized (attachLock) {
				advertised = operation == Operation.LOAD ? sendLoad(sessionId, servers) : sendResume(sessionId, servers);
			}
			session.advertised(advertised);
			configure.accept(session);
			logger.debug("Bound session '{}' to {} via {}", name, sessionId, operation.method());
			return session;
		}
		catch (RuntimeException ex) {
			registry.remove(name);
			throw ex;
		}
	}

	private AdvertisedSessionConfig sendLoad(String sessionId, List<AcpSchema.McpServer> servers) {
		AcpSchema.LoadSessionResponse response = acp
				.loadSession(new AcpSchema.LoadSessionRequest(sessionId, settings.workspace().toString(), servers))
				.block(settings.timeout());
		if (response == null) {
			throw new AgentClientException("Agent '" + runtimeId + "' did not answer session/load for " + sessionId);
		}
		return new AdvertisedSessionConfig(recorder.claimUnattributed(), response.modes(), response.models());
	}

	@SuppressWarnings("deprecation")
	private AdvertisedSessionConfig sendResume(String sessionId, List<AcpSchema.McpServer> servers) {
		AcpSchema.ResumeSessionResponse response = acp.resumeSession(
				new AcpSchema.ResumeSessionRequest(sessionId, settings.workspace().toString(), servers))
				.block(settings.timeout());
		if (response == null) {
			throw new AgentClientException("Agent '" + runtimeId + "' did not answer session/resume for " + sessionId);
		}
		return new AdvertisedSessionConfig(recorder.claimUnattributed(), response.modes(), response.models());
	}

	/**
	 * The MCP servers to re-declare on this attach, named in the log on the way past.
	 *
	 * <p>{@code session/load} and {@code session/resume} carry them exactly as {@code session/new}
	 * does, and fail to connect just as silently; see the note on {@code DefaultAgentClient}. Logged
	 * as configured, since a proxy URL would tell the reader nothing about what was asked for.
	 */
	private List<AcpSchema.McpServer> mcpServers(McpAccess.Grant grant) {
		if (!settings.mcpServers().isEmpty() && logger.isInfoEnabled()) {
			logger.info("Handing {} MCP server(s) to the agent on re-attach: {}. The agent does not report "
					+ "back whether it connected to them.", settings.mcpServers().size(),
					settings.mcpServers().stream().map(org.thought.acp.config.McpServerSpec::describe).toList());
		}
		return grant.servers().stream().map(m -> m.toAcp()).toList();
	}

	@Override
	public void delete(String sessionId) {
		require(Operation.DELETE);
		org.thought.acp.config.Validation.requireText(sessionId, "session id");
		// A deleted session that is still bound here would be a name pointing at nothing.
		registry.all().stream().filter(s -> sessionId.equals(s.sessionId())).map(AgentSession::name)
				.forEach(registry::remove);
		recorder.forget(sessionId);
		acp.deleteSession(new AcpSchema.DeleteSessionRequest(sessionId)).block(settings.timeout());
	}

	@Override
	public void close(String name) {
		registry.remove(name).ifPresent(session -> {
			recorder.forget(session.sessionId());
			if (!supports(Operation.CLOSE)) {
				return;
			}
			try {
				acp.closeSession(new AcpSchema.CloseSessionRequest(session.sessionId()))
						.block(Duration.ofSeconds(5));
			}
			catch (RuntimeException ex) {
				logger.debug("Could not close session {} on the agent", session.sessionId(), ex);
			}
		});
	}

	@Override
	public List<AgentSession> open() {
		return registry.all();
	}

	@Override
	public Optional<AgentSession> find(String name) {
		return registry.find(name);
	}

	@Override
	public boolean supports(Operation operation) {
		if (capabilities == null) {
			return false;
		}
		if (operation == Operation.LOAD) {
			return Boolean.TRUE.equals(capabilities.loadSession());
		}
		AcpSchema.SessionCapabilities session = capabilities.sessionCapabilities();
		if (session == null) {
			return false;
		}
		// ACP signals these by presence: a non-null member means the method exists, whatever it holds.
		return switch (operation) {
			case LIST -> session.list() != null;
			case RESUME -> session.resume() != null;
			case DELETE -> session.delete() != null;
			case CLOSE -> session.close() != null;
			case LOAD -> false;
		};
	}

	private void require(Operation operation) {
		if (!supports(operation)) {
			throw new UnsupportedAgentOperationException(runtimeId, operation);
		}
	}
}
