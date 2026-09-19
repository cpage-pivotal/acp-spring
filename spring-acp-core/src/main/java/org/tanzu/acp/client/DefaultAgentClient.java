package org.tanzu.acp.client;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tanzu.acp.config.AgentOptions;
import org.tanzu.acp.config.AgentSettings;
import org.tanzu.acp.config.ConfigResolver;
import org.tanzu.acp.event.AgentEvent;
import org.tanzu.acp.runtime.AgentRuntime;
import org.tanzu.acp.session.AgentSession;
import org.tanzu.acp.session.SessionRegistry;
import org.tanzu.acp.turn.AgentTurn;
import org.tanzu.acp.turn.SessionUpdateRouter;

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

	private final ConfigResolver configResolver;

	private final Runnable onClose;

	public DefaultAgentClient(AcpAsyncClient acp, AgentRuntime runtime, AgentSettings settings,
			SessionRegistry sessions, SessionUpdateRouter router, Runnable onClose) {
		this.acp = acp;
		this.runtime = runtime;
		this.settings = settings;
		this.sessions = sessions;
		this.router = router;
		this.configResolver = new ConfigResolver(runtime, settings.onUnsupported());
		this.onClose = onClose == null ? () -> {
		} : onClose;
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

	/** Evicts sessions idle past the TTL and closes them on the agent. */
	public void evictIdleSessions() {
		sessions.evictIdle(settings.sessionTtl()).forEach(s -> closeRemote(s.sessionId()));
	}

	private void closeRemote(String sessionId) {
		try {
			acp.closeSession(new AcpSchema.CloseSessionRequest(sessionId)).block(Duration.ofSeconds(5));
		}
		catch (RuntimeException ex) {
			// The agent may not support session/close, or may already have dropped the session.
			logger.debug("Could not close session {} on the agent", sessionId, ex);
		}
	}

	private AgentSession openSession(String name, AgentSettings effective) {
		return sessions.resolve(name, n -> {
			AcpSchema.NewSessionResponse response = acp
					.newSession(new AcpSchema.NewSessionRequest(effective.workspace().toString(),
							effective.mcpServers().stream().map(m -> m.toAcp()).toList()))
					.block(effective.timeout());
			if (response == null || response.sessionId() == null) {
				throw new AgentClientException("Agent did not return a session id for '" + n + "'");
			}
			return response.sessionId();
		});
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
					configResolver.apply(acp, session, effective).block(effective.timeout());
				}
				catch (RuntimeException ex) {
					session.endTurn();
					throw ex;
				}
				return AgentTurn.on(acp, router).prompt(session, prompt, effective.timeout())
						.doFinally(signal -> {
							session.endTurn();
							if (ephemeral) {
								sessions.remove(name);
								closeRemote(session.sessionId());
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

	private record DefaultAgentResponse(String content, AgentEvent.Completed completion) implements AgentResponse {
	}

	private record DefaultAgentStream(Flux<AgentEvent> events) implements AgentStream {
	}

	/** The agent's advertised configuration for a named session, once observed. */
	public Optional<AgentSession> session(String name) {
		return sessions.find(name);
	}
}
