package org.thought.acp.client;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thought.acp.config.AdvertisedSessionConfig;
import org.thought.acp.config.AgentOptions;
import org.thought.acp.config.AgentSettings;
import org.thought.acp.config.ConfigResolver;
import org.thought.acp.config.SessionConfiguration;
import org.thought.acp.event.AgentEvent;
import org.thought.acp.observation.AgentObservations;
import org.thought.acp.runtime.AgentRuntime;
import org.thought.acp.runtime.AgentRuntime.PortableOption;
import org.thought.acp.session.AgentSession;
import org.thought.acp.session.AgentSessions;
import org.thought.acp.session.DefaultAgentSessions;
import org.thought.acp.session.SessionRegistry;
import org.thought.acp.turn.AgentTurn;
import org.thought.acp.turn.SessionUpdateRouter;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.publisher.Flux;

/**
 * The default {@link AgentClient}: one agent connection, many named sessions.
 */
public final class DefaultAgentClient implements AgentClient {

	private static final Logger logger = LoggerFactory.getLogger(DefaultAgentClient.class);

	private final AcpAsyncClient acp;

	private final AgentRuntime runtime;

	private final AgentSettings settings;

	private final SessionRegistry sessions;

	private final SessionUpdateRouter router;

	private final SessionConfigRecorder recorder;

	private final ConfigResolver configResolver;

	private final AgentInfo agentInfo;

	private final AgentSessions sessionOperations;

	private final int protocolVersion;

	private final AgentObservations observations;

	/** Null when the transport cannot tell; see {@code AgentClientFactory.Liveness}. */
	private final java.util.function.BooleanSupplier alive;

	private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

	private final Runnable onClose;

	public DefaultAgentClient(AcpAsyncClient acp, AgentRuntime runtime, AgentSettings settings,
			SessionRegistry sessions, SessionUpdateRouter router, SessionConfigRecorder recorder,
			AcpSchema.InitializeResponse initialized, java.util.function.BooleanSupplier alive, Runnable onClose) {
		this(acp, runtime, settings, sessions, router, recorder, initialized,
				org.thought.acp.protocol.AcpProtocol.V1, AgentObservations.NONE, alive, onClose);
	}

	public DefaultAgentClient(AcpAsyncClient acp, AgentRuntime runtime, AgentSettings settings,
			SessionRegistry sessions, SessionUpdateRouter router, SessionConfigRecorder recorder,
			AcpSchema.InitializeResponse initialized, int protocolVersion, AgentObservations observations,
			java.util.function.BooleanSupplier alive, Runnable onClose) {
		this.protocolVersion = protocolVersion;
		this.observations = observations == null ? AgentObservations.NONE : observations;
		this.acp = acp;
		this.runtime = runtime;
		this.settings = settings;
		this.sessions = sessions;
		this.router = router;
		this.recorder = recorder == null ? new SessionConfigRecorder() : recorder;
		this.configResolver = new ConfigResolver(runtime, settings.onUnsupported());
		this.agentInfo = initialized == null ? null : AgentInfo.from(initialized.agentInfo()).orElse(null);
		this.sessionOperations = new DefaultAgentSessions(acp, runtime.id(), settings,
				initialized == null ? null : initialized.agentCapabilities(), sessions, this.recorder,
				session -> configure(session, settings));
		this.alive = alive;
		this.onClose = onClose == null ? () -> {
		} : onClose;
	}

	@Override
	public boolean isAlive() {
		return !closed.get() && (alive == null || alive.getAsBoolean());
	}

	@Override
	public int protocolVersion() {
		return protocolVersion;
	}

	@Override
	public Optional<AgentInfo> agentInfo() {
		return Optional.ofNullable(agentInfo);
	}

	@Override
	public AgentSessions sessions() {
		return sessionOperations;
	}

	@Override
	public PromptSpec prompt() {
		return new DefaultPromptSpec();
	}

	@Override
	public String runtimeId() {
		return runtime.id();
	}

	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		sessions.all().forEach(s -> closeRemote(s.sessionId()));
		sessions.clear();
		try {
			acp.closeGracefully().block(Duration.ofSeconds(10));
		}
		catch (RuntimeException ex) {
			logger.debug("Graceful close did not complete cleanly", ex);
		}
		finally {
			onClose.run();
		}
	}

	@Override
	public void evictIdleSessions() {
		sessions.evictIdle(settings.sessionTtl()).forEach(s -> closeRemote(s.sessionId()));
	}

	/**
	 * Closes a session on the agent and waits for the acknowledgement.
	 *
	 * <p>Only safe from a thread that is not the one delivering the agent's replies, which in practice
	 * means the lifecycle path: {@link #close()} and idle eviction. See
	 * {@link #closeRemoteWithoutWaiting} for why the distinction is not a stylistic one.
	 */
	private void closeRemote(String sessionId) {
		recorder.forget(sessionId);
		try {
			acp.closeSession(new AcpSchema.CloseSessionRequest(sessionId)).block(Duration.ofSeconds(5));
		}
		catch (RuntimeException ex) {
			// The agent may not support session/close, or may already have dropped the session.
			logger.debug("Could not close session {} on the agent", sessionId, ex);
		}
	}

	/**
	 * Closes a session on the agent without waiting, for the end of a throwaway turn.
	 *
	 * <p>The waiting version cannot be used here, and the reason is worth writing down. A turn's
	 * teardown runs on whichever thread delivered the turn's last event, and for a stdio agent that is
	 * the transport's own inbound thread — the thread that would have to deliver the reply this call is
	 * waiting for. Blocking it deadlocks, and the deadlock is invisible because the five-second timeout
	 * resolves it: every ephemeral turn simply took five seconds longer than it should have, and the
	 * session was left open on the agent anyway. Nothing failed, so nothing said so.
	 */
	private void closeRemoteWithoutWaiting(String sessionId) {
		recorder.forget(sessionId);
		acp.closeSession(new AcpSchema.CloseSessionRequest(sessionId))
				.subscribe(ignored -> {
				}, ex -> logger.debug("Could not close session {} on the agent", sessionId, ex));
	}

	@Override
	public AgentSession openSession(String name) {
		return openSession(name, settings);
	}

	/**
	 * Opens — or reuses — the named session, and records what the agent advertised about it.
	 *
	 * <p>The advertised configuration comes from two places because the SDK splits it: {@code modes}
	 * and {@code models} off the typed response, {@code configOptions} out of the recorder, which is
	 * the only way to see a field {@code NewSessionResponse} drops.
	 */
	private AgentSession openSession(String name, AgentSettings effective) {
		AtomicReference<AdvertisedSessionConfig> advertised = new AtomicReference<>();
		AgentSession session = sessions.resolve(name, n -> {
			AcpSchema.NewSessionResponse response = acp
					.newSession(new AcpSchema.NewSessionRequest(effective.workspace().toString(),
							effective.mcpServers().stream().map(m -> m.toAcp()).toList()))
					.block(effective.timeout());
			if (response == null || response.sessionId() == null) {
				throw new AgentClientException("Agent did not return a session id for '" + n + "'");
			}
			advertised.set(new AdvertisedSessionConfig(recorder.configOptionsFor(response.sessionId()),
					response.modes(), response.models()));
			return response.sessionId();
		});
		if (advertised.get() != null) {
			session.advertised(advertised.get());
		}
		configure(session, effective);
		return session;
	}

	/**
	 * Applies the negotiated tier, once per session rather than once per turn.
	 *
	 * <p>Re-sending the same three options before every turn would be wire traffic that cannot change
	 * anything, so this skips the work when the request is the one already applied. It does not skip
	 * when a turn carried per-request overrides, because then the request genuinely differs from the
	 * session's current state and the agent has to be told.
	 */
	private void configure(AgentSession session, AgentSettings effective) {
		if (isAlreadyRequested(session.configuration(), effective)) {
			return;
		}
		configResolver.apply(acp, session, effective).block(effective.timeout());
	}

	private static boolean isAlreadyRequested(SessionConfiguration applied, AgentSettings effective) {
		return same(applied.of(PortableOption.MODEL).requested(), effective.model())
				&& same(applied.of(PortableOption.PROVIDER).requested(), effective.provider().id())
				&& same(applied.of(PortableOption.MODE).requested(), effective.mode());
	}

	private static boolean same(String applied, String requested) {
		return java.util.Objects.equals(blankToNull(applied), blankToNull(requested));
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private final class DefaultPromptSpec implements PromptSpec {

		private final StringBuilder text = new StringBuilder();

		private String sessionName;

		private AgentOptions options = AgentOptions.none();

		@Override
		public PromptSpec session(String name) {
			this.sessionName = name;
			return this;
		}

		@Override
		public PromptSpec user(String content) {
			if (content != null && !content.isEmpty()) {
				if (!text.isEmpty()) {
					text.append('\n');
				}
				text.append(content);
			}
			return this;
		}

		@Override
		public PromptSpec options(AgentOptions options) {
			this.options = options == null ? AgentOptions.none() : options;
			return this;
		}

		@Override
		public PromptSpec options(java.util.function.Consumer<AgentOptions.Builder> customizer) {
			AgentOptions.Builder builder = AgentOptions.builder();
			customizer.accept(builder);
			return options(builder.build());
		}

		@Override
		public AgentResponse call() {
			StringBuilder content = new StringBuilder();
			AtomicReference<AgentEvent.Completed> completion = new AtomicReference<>();
			AtomicReference<Throwable> failure = new AtomicReference<>();

			stream().events().doOnNext(event -> {
				switch (event) {
					case AgentEvent.Text t -> content.append(t.text());
					case AgentEvent.Completed c -> completion.set(c);
					case AgentEvent.Failed f -> failure.set(f.cause());
					default -> {
					}
				}
			}).blockLast();

			if (failure.get() != null) {
				throw new AgentClientException("Agent turn failed", failure.get());
			}
			if (completion.get() == null) {
				// Would mean the terminal-event contract was broken upstream.
				throw new AgentClientException("Agent turn ended without a terminal event");
			}
			return new DefaultAgentResponse(content.toString(), completion.get());
		}

		@Override
		public AgentStream stream() {
			if (text.isEmpty()) {
				throw new IllegalStateException("prompt is empty; call user(...) before call() or stream()");
			}
			AgentSettings effective = settings.merge(options);
			boolean ephemeral = sessionName == null;
			String name = ephemeral ? "turn-" + UUID.randomUUID() : sessionName;
			List<AcpSchema.ContentBlock> prompt = List.of(new AcpSchema.TextContent(text.toString()));

			Flux<AgentEvent> events = Flux.defer(() -> {
				AgentSession session = openSession(name, effective);
				acquireTurn(session, effective.timeout());
				try {
					// A turn with per-request overrides re-negotiates; an unchanged one does not.
					configure(session, effective);
				}
				catch (RuntimeException ex) {
					session.endTurn();
					if (ephemeral) {
						sessions.remove(name);
						closeRemoteWithoutWaiting(session.sessionId());
					}
					throw ex;
				}
				return AgentTurn.on(acp, router, observations)
						.prompt(session, prompt, effective.timeout(), turnContext(session, effective, ephemeral))
						.doFinally(signal -> {
							session.endTurn();
							if (ephemeral) {
								sessions.remove(name);
								closeRemoteWithoutWaiting(session.sessionId());
							}
						});
			});
			return new DefaultAgentStream(events);
		}

		private void acquireTurn(AgentSession session, Duration timeout) {
			try {
				if (!session.tryBeginTurn(timeout)) {
					throw new AgentClientException(
							"Session '" + session.name() + "' is busy with another turn after " + timeout);
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new AgentClientException("Interrupted waiting for session '" + session.name() + "'", ex);
			}
		}
	}

	/**
	 * What an observation is told about this turn.
	 *
	 * <p>The model reported is the one the session is <em>really</em> using, and the order the three
	 * sources are tried in is the whole point. The negotiated tier's applied value first; then what
	 * the agent says it is currently set to, for the common case where the application asked for no
	 * model at all and the agent is running its own default; and only then the request. A dashboard
	 * grouped by the model an application asked for, while {@code on-unsupported: warn} quietly ran
	 * it on another, would be worse than having no dashboard — and one that said "unknown" for every
	 * application that never set {@code spring.acp.model} would be worse still.
	 */
	private static AgentObservations.TurnContext turnContext(AgentSession session, AgentSettings effective,
			boolean ephemeral) {
		String model = session.configuration().applied(PortableOption.MODEL)
				.or(() -> currentModelOf(session)).orElseGet(effective::model);
		return new AgentObservations.TurnContext(effective.runtime(), session.name(), model, ephemeral);
	}

	/**
	 * The model the agent says this session is set to, when it advertises one.
	 *
	 * <p>Matched by the portable {@code model} category as well as the id, because that pair is all
	 * ACP standardizes — an adapter's candidate ids belong to the resolver, and a tag is not worth
	 * threading one through for.
	 */
	private static Optional<String> currentModelOf(AgentSession session) {
		return session.advertised().select("model", "model")
				.map(AcpSchema.SessionConfigSelect::currentValue).filter(value -> !value.isBlank())
				.or(() -> Optional.ofNullable(session.advertised().findModels().orElse(null))
						.map(AcpSchema.SessionModelState::currentModelId));
	}

	private record DefaultAgentResponse(String content, AgentEvent.Completed completion) implements AgentResponse {
	}

	private record DefaultAgentStream(Flux<AgentEvent> events) implements AgentStream {
	}

	@Override
	public Optional<AgentSession> session(String name) {
		return sessions.find(name);
	}
}
