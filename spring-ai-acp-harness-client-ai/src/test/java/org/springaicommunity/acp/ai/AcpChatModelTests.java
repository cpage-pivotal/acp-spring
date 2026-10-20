package org.springaicommunity.acp.ai;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springaicommunity.acp.client.AgentClient;
import org.springaicommunity.acp.config.AgentOptions;
import org.springaicommunity.acp.event.AgentEvent;
import org.springaicommunity.acp.session.AgentSession;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The adapter between a stateless chat completion and a stateful agent session.
 *
 * <p>
 * The tests that matter are the ones about <em>what gets sent</em>. Everything else —
 * finish reasons, metadata, streaming — is translation, and translation is easy to get
 * right. Deciding which of the caller's messages the agent has already heard is the part
 * that is genuinely a judgement, and the part an application pays for twice if it is
 * wrong.
 */
class AcpChatModelTests {

	private FakeAgentClient client;

	private AcpChatModel model;

	@BeforeEach
	void setUp() {
		client = new FakeAgentClient();
		model = new AcpChatModel(client);
	}

	@Test
	void sendsAPromptAndReturnsTheAssistantText() {
		client.reply("the answer");

		ChatResponse response = model.call(new Prompt("what is it"));

		assertThat(response.getResult().getOutput().getText()).isEqualTo("the answer");
		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
		assertThat(client.sent).isEqualTo("what is it");
	}

	@Test
	@DisplayName("with no session, the whole conversation goes over: the agent knows nothing")
	void sendsTheWholeHistoryToAThrowawaySession() {
		client.reply("ok");

		model.call(new Prompt(List.of(new SystemMessage("be brief"), new UserMessage("first"),
				new AssistantMessage("first answer"), new UserMessage("second"))));

		assertThat(client.sent).isEqualTo("""
				[system]
				be brief

				first

				[assistant]
				first answer

				second""");
		assertThat(client.sessionName).isNull();
	}

	@Test
	@DisplayName("with an open session, only what the agent has not heard goes over")
	void sendsOnlyTheNewMessagesToAnOpenSession() {
		// The whole reason this adapter is not a wrapper. Sending the history again would
		// make the
		// agent read its own previous answers as new instructions, and bill for the
		// conversation on
		// every turn.
		client.reply("ok");
		client.hasSession("review-123");

		model.call(new Prompt(
				List.of(new UserMessage("first"), new AssistantMessage("first answer"), new UserMessage("second")),
				AcpChatOptions.builder().session("review-123").build()));

		assertThat(client.sent).isEqualTo("second");
		assertThat(client.sessionName).isEqualTo("review-123");
	}

	@Test
	@DisplayName("the first turn of a named session still carries everything")
	void sendsTheWholeHistoryWhenTheSessionIsNotOpenYet() {
		client.reply("ok");

		model.call(new Prompt(List.of(new SystemMessage("be brief"), new UserMessage("first")),
				AcpChatOptions.builder().session("review-123").build()));

		assertThat(client.sent).contains("be brief").contains("first");
	}

	@Test
	void refusesAPromptThatAddsNothingTheAgentHasNotSeen() {
		client.hasSession("review-123");

		assertThatThrownBy(
				() -> model.call(new Prompt(List.of(new UserMessage("first"), new AssistantMessage("already answered")),
						AcpChatOptions.builder().session("review-123").build())))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("adds nothing");
	}

	@Test
	void passesTheModelModeAndTimeoutThroughToTheNegotiatedTier() {
		client.reply("ok");

		model.call(new Prompt("hello",
				AcpChatOptions.builder()
					.model("gpt-5.4-mini")
					.mode("plan")
					.timeout(java.time.Duration.ofMinutes(9))
					.build()));

		assertThat(client.options.findModel()).contains("gpt-5.4-mini");
		assertThat(client.options.findMode()).contains("plan");
		assertThat(client.options.findTimeout()).contains(java.time.Duration.ofMinutes(9));
	}

	@Test
	@DisplayName("a plain ChatOptions still carries the one option that means something here")
	void acceptsTheModelFromAnyChatOptions() {
		client.reply("ok");

		model.call(new Prompt("hello", ChatOptions.builder().model("gpt-5.4-mini").build()));

		assertThat(client.options.findModel()).contains("gpt-5.4-mini");
	}

	@Test
	void perCallOptionsWinOverTheModelsDefaults() {
		client.reply("ok");
		AcpChatModel withDefaults = new AcpChatModel(client,
				AcpChatOptions.builder().model("default-model").mode("plan").build());

		withDefaults.call(new Prompt("hello", AcpChatOptions.builder().model("per-call").build()));

		assertThat(client.options.findModel()).contains("per-call");
		assertThat(client.options.findMode()).contains("plan");
	}

	@Test
	@DisplayName("options ACP cannot express are reported, not silently dropped")
	void doesNotPretendToHonorTemperature() {
		// An application that set temperature 0 for determinism deserves to be told it
		// did not get
		// it. ACP gives a client no way to reach the agent's own inference call.
		client.reply("ok");

		model.call(new Prompt("hello", ChatOptions.builder().temperature(0.0).maxTokens(10).build()));

		assertThat(client.sent).isEqualTo("hello");
	}

	@Test
	void streamsTextChunksAndEndsWithTheFinishReason() {
		client.streams(new AgentEvent.Text("one "), new AgentEvent.Text("two"),
				new AgentEvent.Completed(AcpSchema.StopReason.END_TURN));

		StepVerifier.create(model.stream(new Prompt("go")).map(r -> r.getResult().getOutput().getText()))
			.expectNext("one ", "two", "")
			.verifyComplete();
	}

	@Test
	@DisplayName("thoughts, tool calls and plans are agent activity, not assistant output")
	void doesNotStreamTheAgentsInternalActivityAsText() {
		client.streams(new AgentEvent.Thought("hmm"),
				new AgentEvent.ToolCallStarted("t1", "Read the build file", AcpSchema.ToolKind.READ),
				new AgentEvent.Text("done"), new AgentEvent.Completed(AcpSchema.StopReason.END_TURN));

		StepVerifier.create(model.stream(new Prompt("go")).map(r -> r.getResult().getOutput().getText()))
			.expectNext("done", "")
			.verifyComplete();
	}

	@Test
	void surfacesAFailedTurnAsAnError() {
		client.streams(new AgentEvent.Failed(new IllegalStateException("the agent died")));

		StepVerifier.create(model.stream(new Prompt("go"))).verifyErrorMessage("the agent died");
	}

	@Test
	void mapsEachAcpStopReasonToSomethingAnAdvisorRecognises() {
		assertThat(finishReasonFor(AcpSchema.StopReason.END_TURN)).isEqualTo("STOP");
		assertThat(finishReasonFor(AcpSchema.StopReason.MAX_TOKENS)).isEqualTo("LENGTH");
		assertThat(finishReasonFor(AcpSchema.StopReason.REFUSAL)).isEqualTo("CONTENT_FILTER");
		// No OpenAI equivalent, so these keep their own names rather than being
		// flattened.
		assertThat(finishReasonFor(AcpSchema.StopReason.CANCELLED)).isEqualTo("CANCELLED");
		assertThat(finishReasonFor(AcpSchema.StopReason.MAX_TURN_REQUESTS)).isEqualTo("MAX_TURN_REQUESTS");
	}

	@Test
	@DisplayName("ACP usage lands in metadata, not in Spring AI's Usage")
	void reportsContextAndCostWithoutPretendingTheyAreTokenCounts() {
		// Usage is prompt and completion tokens for this call; usage_update is context
		// consumed for
		// the whole session. Putting one in the other's field would put "the conversation
		// so far"
		// where every dashboard reads "this call".
		client.streams(new AgentEvent.Text("hi"), new AgentEvent.UsageUpdated(4441, 1_050_000, 0.008932, "USD"),
				new AgentEvent.Completed(AcpSchema.StopReason.END_TURN));

		ChatResponse last = model.stream(new Prompt("go")).blockLast();

		assertThat(last.getMetadata().<Object>get("acp.context.used")).isEqualTo(4441L);
		assertThat(last.getMetadata().<Object>get("acp.cost.amount")).isEqualTo(0.008932);
		assertThat(last.getMetadata().<Object>get("acp.runtime")).isEqualTo("fake");
	}

	private String finishReasonFor(AcpSchema.StopReason reason) {
		client.streams(new AgentEvent.Completed(reason));
		return model.stream(new Prompt("go")).blockLast().getResult().getMetadata().getFinishReason();
	}

	/**
	 * Records what the model asked of the client, and replays whatever the test set up.
	 */
	private static final class FakeAgentClient implements AgentClient {

		private String sent;

		private String sessionName;

		private AgentOptions options = AgentOptions.none();

		private final AtomicReference<List<AgentEvent>> events = new AtomicReference<>(List.of());

		private final java.util.Set<String> openSessions = new java.util.HashSet<>();

		void reply(String text) {
			streams(new AgentEvent.Text(text), new AgentEvent.Completed(AcpSchema.StopReason.END_TURN));
		}

		void streams(AgentEvent... events) {
			this.events.set(List.of(events));
		}

		void hasSession(String name) {
			openSessions.add(name);
		}

		@Override
		public PromptSpec prompt() {
			return new PromptSpec() {

				@Override
				public PromptSpec session(String name) {
					sessionName = name;
					return this;
				}

				@Override
				public PromptSpec user(String text) {
					sent = text;
					return this;
				}

				@Override
				public PromptSpec options(AgentOptions o) {
					options = o;
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
					List<AgentEvent> replayed = events.get();
					String content = replayed.stream()
						.filter(AgentEvent.Text.class::isInstance)
						.map(AgentEvent.Text.class::cast)
						.map(AgentEvent.Text::text)
						.reduce("", String::concat);
					AgentEvent.Completed completed = replayed.stream()
						.filter(AgentEvent.Completed.class::isInstance)
						.map(AgentEvent.Completed.class::cast)
						.findFirst()
						.orElse(new AgentEvent.Completed(AcpSchema.StopReason.END_TURN));
					return new AgentResponse() {

						@Override
						public String content() {
							return content;
						}

						@Override
						public AgentEvent.Completed completion() {
							return completed;
						}
					};
				}

				@Override
				public AgentStream stream() {
					return () -> Flux.fromIterable(events.get());
				}
			};
		}

		@Override
		public String runtimeId() {
			return "fake";
		}

		@Override
		public Optional<org.springaicommunity.acp.client.AgentInfo> agentInfo() {
			return Optional.empty();
		}

		@Override
		public org.springaicommunity.acp.session.AgentSessions sessions() {
			throw new UnsupportedOperationException();
		}

		@Override
		public Optional<AgentSession> session(String name) {
			return openSessions.contains(name)
					? Optional.of(new org.springaicommunity.acp.session.SessionRegistry().resolve(name, n -> "sid"))
					: Optional.empty();
		}

		@Override
		public AgentSession openSession(String name) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void close() {
		}

	}

}
