package org.tanzu.acp.executor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tanzu.acp.client.AgentClient;
import org.tanzu.acp.client.AgentInfo;
import org.tanzu.acp.config.AgentOptions;
import org.tanzu.acp.event.AgentEvent;
import org.tanzu.acp.session.AgentSession;
import org.tanzu.acp.session.AgentSessions;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The migration facade, and in particular the event vocabulary existing consumers parse.
 */
class DefaultAgentExecutorTests {

	private final ScriptedClient client = new ScriptedClient();

	private final AgentExecutor executor = new DefaultAgentExecutor(client);

	@Test
	@DisplayName("a blocking call returns the assistant text and nothing else")
	void execute() {
		client.script = List.of(new AgentEvent.Thought("thinking"), new AgentEvent.Text("hello "),
				new AgentEvent.Text("world"), completed());

		assertThat(executor.execute("hi")).isEqualTo("hello world");
	}

	@Test
	@DisplayName("a named call keeps the session name it was given")
	void executeInSession() {
		client.script = List.of(new AgentEvent.Text("ok"), completed());

		executor.executeInSession("review", "hi", false);

		assertThat(client.lastSession).isEqualTo("review");
	}

	@Test
	@DisplayName("an empty prompt is refused before anything is sent")
	void rejectsABlankPrompt() {
		assertThatThrownBy(() -> executor.execute("  ")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("version and availability come from the connection, not from running the binary")
	void reportsVersionAndAvailability() {
		assertThat(executor.getVersion()).isEqualTo("1.51.0");
		assertThat(executor.isAvailable()).isTrue();

		client.alive.set(false);

		assertThat(executor.isAvailable()).isFalse();
	}

	@Test
	@DisplayName("plain streaming emits assistant text, one element per chunk")
	void streamsText() {
		client.script = List.of(new AgentEvent.Text("one"), new AgentEvent.Thought("ignored"),
				new AgentEvent.Text("two"), completed());

		try (Stream<String> lines = executor.executeStreaming("hi")) {
			assertThat(lines.toList()).containsExactly("one", "two");
		}
	}

	/**
	 * The compatibility boundary. Consumers switch on {@code type} without a default branch and
	 * wait for {@code complete}, so the shapes and the terminal event are both part of the
	 * contract rather than an implementation detail.
	 */
	@Test
	@DisplayName("JSON streaming emits the legacy message/notification/complete vocabulary")
	void streamsLegacyJson() {
		client.script = List.of(new AgentEvent.Text("hello"), new AgentEvent.Thought("reasoning"),
				new AgentEvent.ToolCallStarted("t1", "shell · ls", AcpSchema.ToolKind.EXECUTE),
				new AgentEvent.ToolCallUpdated("t1", AcpSchema.ToolCallStatus.COMPLETED, List.of()), completed());

		try (Stream<String> lines = executor.executeInSessionStreamingJson("review", "hi", true)) {
			List<String> emitted = lines.toList();

			assertThat(emitted).hasSize(5);
			assertThat(emitted.get(0)).contains("\"type\":\"message\"").contains("\"role\":\"assistant\"")
					.contains("hello");
			assertThat(emitted.get(1)).contains("\"type\":\"notification\"").contains("reasoning");
			assertThat(emitted.get(2)).contains("\"type\":\"toolRequest\"").contains("shell");
			assertThat(emitted.get(3)).contains("\"type\":\"toolResponse\"").contains("\"is_error\":false");
			assertThat(emitted.get(4)).contains("\"type\":\"complete\"");
		}
	}

	@Test
	@DisplayName("a turn that fails still ends with exactly one complete")
	void failureStillCompletes() {
		client.script = List.of(new AgentEvent.Text("partial"),
				new AgentEvent.Failed(new IllegalStateException("the agent gave up")));

		try (Stream<String> lines = executor.executeInSessionStreamingJson("review", "hi", false)) {
			List<String> emitted = lines.toList();

			assertThat(emitted).hasSize(3);
			assertThat(emitted.get(1)).contains("\"type\":\"notification\"").contains("the agent gave up");
			assertThat(emitted.get(2)).contains("\"type\":\"complete\"");
		}
	}

	@Test
	@DisplayName("an in-progress tool update produces nothing, since only the outcome is a response")
	void inProgressToolUpdatesAreSilent() {
		client.script = List.of(new AgentEvent.ToolCallUpdated("t1", AcpSchema.ToolCallStatus.IN_PROGRESS, List.of()),
				completed());

		try (Stream<String> lines = executor.executeInSessionStreamingJson("review", "hi", false)) {
			assertThat(lines.toList()).singleElement().asString().contains("\"type\":\"complete\"");
		}
	}

	private static AgentEvent.Completed completed() {
		return new AgentEvent.Completed(AcpSchema.StopReason.END_TURN);
	}

	/** An {@link AgentClient} that replays a fixed list of events. */
	private static final class ScriptedClient implements AgentClient {

		private List<AgentEvent> script = List.of();

		private final AtomicBoolean alive = new AtomicBoolean(true);

		private String lastSession;

		@Override
		public PromptSpec prompt() {
			return new ScriptedSpec();
		}

		@Override
		public String runtimeId() {
			return "goose";
		}

		@Override
		public Optional<AgentInfo> agentInfo() {
			return Optional.of(new AgentInfo("goose", "1.51.0"));
		}

		@Override
		public boolean isAlive() {
			return alive.get();
		}

		@Override
		public AgentSessions sessions() {
			throw new UnsupportedOperationException();
		}

		@Override
		public Optional<AgentSession> session(String name) {
			return Optional.empty();
		}

		@Override
		public AgentSession openSession(String name) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void close() {
		}

		private final class ScriptedSpec implements PromptSpec {

			@Override
			public PromptSpec session(String name) {
				lastSession = name;
				return this;
			}

			@Override
			public PromptSpec user(String text) {
				return this;
			}

			@Override
			public PromptSpec options(AgentOptions options) {
				return this;
			}

			@Override
			public PromptSpec options(java.util.function.Consumer<AgentOptions.Builder> customizer) {
				return this;
			}

			@Override
			public AgentResponse call() {
				StringBuilder content = new StringBuilder();
				script.stream().filter(AgentEvent.Text.class::isInstance).map(AgentEvent.Text.class::cast)
						.forEach(text -> content.append(text.text()));
				return new AgentResponse() {

					@Override
					public String content() {
						return content.toString();
					}

					@Override
					public AgentEvent.Completed completion() {
						return completed();
					}
				};
			}

			@Override
			public AgentStream stream() {
				return () -> Flux.fromIterable(script);
			}
		}
	}
}
