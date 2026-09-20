package org.thought.acp.boot;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.boot.http.codec.autoconfigure.CodecsAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.thought.acp.client.AgentClient;
import org.thought.acp.client.AgentInfo;
import org.thought.acp.config.AgentOptions;
import org.thought.acp.event.AgentEvent;
import org.thought.acp.session.AgentSession;
import org.thought.acp.session.AgentSessions;
import org.thought.acp.session.StoredSession;
import org.thought.acp.session.UnsupportedAgentOperationException;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The optional endpoint, and mostly the things it refuses.
 *
 * <p>Every test here runs without a {@code Principal}, because that is what an unauthenticated
 * caller looks like to WebFlux and the endpoint's whole posture is about what such a caller can do.
 */
class AcpControllerTests {

	private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(CodecsAutoConfiguration.class, WebFluxAutoConfiguration.class,
					AcpWebFluxAutoConfiguration.class))
			.withUserConfiguration(StubAgent.class)
			.withPropertyValues("spring.acp.controller.enabled=true");

	@Test
	@DisplayName("no controller unless the application asked for one")
	void offByDefault() {
		new ReactiveWebApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(AcpWebFluxAutoConfiguration.class))
				.withUserConfiguration(StubAgent.class)
				.run(context -> assertThat(context).doesNotHaveBean(AcpController.class));
	}

	@Test
	@DisplayName("an unauthenticated request is refused by default")
	void requiresAuthentication() {
		runner.run(context -> client(context).post().uri("/api/acp/prompt").contentType(MediaType.APPLICATION_JSON).bodyValue(body("hello")).exchange()
				.expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED));
	}

	@Test
	@DisplayName("a prompt runs and comes back with the assistant's text")
	void promptsWhenAllowed() {
		runner.withPropertyValues("spring.acp.controller.allow-unauthenticated=true")
				.run(context -> client(context).post().uri("/api/acp/prompt").contentType(MediaType.APPLICATION_JSON).bodyValue(body("hello")).exchange()
						.expectStatus().isOk().expectBody().jsonPath("$.content").isEqualTo("ok")
						.jsonPath("$.stopReason").isEqualTo("END_TURN"));
	}

	@Test
	@DisplayName("an empty prompt is a bad request, not a turn")
	void rejectsAnEmptyPrompt() {
		runner.withPropertyValues("spring.acp.controller.allow-unauthenticated=true")
				.run(context -> client(context).post().uri("/api/acp/prompt").contentType(MediaType.APPLICATION_JSON).bodyValue(body("   ")).exchange()
						.expectStatus().isBadRequest());
	}

	@Test
	@DisplayName("a prompt over the size limit is refused")
	void rejectsAnOversizePrompt() {
		runner.withPropertyValues("spring.acp.controller.allow-unauthenticated=true",
				"spring.acp.controller.max-prompt-chars=10")
				.run(context -> client(context).post().uri("/api/acp/prompt").contentType(MediaType.APPLICATION_JSON).bodyValue(body("x".repeat(11)))
						.exchange().expectStatus().isEqualTo(HttpStatus.CONTENT_TOO_LARGE));
	}

	/**
	 * The one that matters most. Credentials are fixed when the agent process starts, but a request
	 * that could name its own model could still spend the application's budget on a model nobody
	 * chose, so overrides are off until an application turns them on.
	 */
	@Test
	@DisplayName("a request naming its own model is refused unless overrides are enabled")
	void rejectsRequestOverrides() {
		runner.withPropertyValues("spring.acp.controller.allow-unauthenticated=true")
				.run(context -> client(context).post().uri("/api/acp/prompt")
						.contentType(MediaType.APPLICATION_JSON)
						.bodyValue("{\"prompt\":\"hi\",\"model\":\"gpt-6-astra\"}").exchange().expectStatus()
						.isBadRequest());
	}

	@Test
	@DisplayName("a timeout longer than the endpoint allows is refused")
	void rejectsAnOversizeTimeout() {
		runner.withPropertyValues("spring.acp.controller.allow-unauthenticated=true",
				"spring.acp.controller.max-timeout=1m")
				.run(context -> client(context).post().uri("/api/acp/prompt")
						.contentType(MediaType.APPLICATION_JSON)
						.bodyValue("{\"prompt\":\"hi\",\"timeout\":\"PT10M\"}").exchange().expectStatus()
						.isBadRequest());
	}

	@Test
	@DisplayName("an operation the agent does not implement is 501, not 500")
	void reportsUnsupportedOperations() {
		runner.withPropertyValues("spring.acp.controller.allow-unauthenticated=true")
				.run(context -> client(context).get().uri("/api/acp/sessions").exchange().expectStatus()
						.isEqualTo(HttpStatus.NOT_IMPLEMENTED));
	}

	@Test
	@DisplayName("health is unauthenticated and says which runtime answered")
	void healthIsOpen() {
		runner.run(context -> client(context).get().uri("/api/acp/health").exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.available").isEqualTo(true).jsonPath("$.runtime").isEqualTo("fake"));
	}

	@Test
	@DisplayName("the path can be moved")
	void pathIsConfigurable() {
		runner.withPropertyValues("spring.acp.controller.path=/agent")
				.run(context -> client(context).get().uri("/agent/health").exchange().expectStatus().isOk());
	}

	private static WebTestClient client(org.springframework.context.ApplicationContext context) {
		return WebTestClient.bindToApplicationContext(context).build();
	}

	private static String body(String prompt) {
		return "{\"prompt\":\"" + prompt + "\"}";
	}

	@Configuration(proxyBeanMethods = false)
	static class StubAgent {

		@Bean
		AcpProperties acpProperties() {
			return new AcpProperties();
		}

		@Bean
		AgentClient acpAgentClient() {
			return new FakeClient();
		}
	}

	/** Answers every turn with "ok" and implements none of the optional session operations. */
	private static final class FakeClient implements AgentClient {

		@Override
		public PromptSpec prompt() {
			return new FakeSpec();
		}

		@Override
		public String runtimeId() {
			return "fake";
		}

		@Override
		public Optional<AgentInfo> agentInfo() {
			return Optional.of(new AgentInfo("fake", "1.0"));
		}

		@Override
		public AgentSessions sessions() {
			return new FakeSessions();
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

		private static final class FakeSpec implements PromptSpec {

			@Override
			public PromptSpec session(String name) {
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
				return new AgentResponse() {

					@Override
					public String content() {
						return "ok";
					}

					@Override
					public AgentEvent.Completed completion() {
						return new AgentEvent.Completed(AcpSchema.StopReason.END_TURN);
					}
				};
			}

			@Override
			public AgentStream stream() {
				return () -> Flux.just(new AgentEvent.Text("ok"),
						new AgentEvent.Completed(AcpSchema.StopReason.END_TURN));
			}
		}

		private static final class FakeSessions implements AgentSessions {

			@Override
			public List<StoredSession> list() {
				throw new UnsupportedAgentOperationException("fake", Operation.LIST);
			}

			@Override
			public List<StoredSession> list(java.nio.file.Path cwd) {
				return list();
			}

			@Override
			public AgentSession load(String name, String sessionId) {
				throw new UnsupportedAgentOperationException("fake", Operation.LOAD);
			}

			@Override
			public AgentSession resume(String name, String sessionId) {
				throw new UnsupportedAgentOperationException("fake", Operation.RESUME);
			}

			@Override
			public void delete(String sessionId) {
				throw new UnsupportedAgentOperationException("fake", Operation.DELETE);
			}

			@Override
			public void close(String name) {
			}

			@Override
			public List<AgentSession> open() {
				return List.of();
			}

			@Override
			public Optional<AgentSession> find(String name) {
				return Optional.empty();
			}

			@Override
			public boolean supports(Operation operation) {
				return false;
			}
		}
	}
}
