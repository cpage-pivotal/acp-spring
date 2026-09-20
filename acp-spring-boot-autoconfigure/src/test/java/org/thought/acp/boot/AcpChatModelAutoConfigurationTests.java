package org.thought.acp.boot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thought.acp.ai.AcpChatModel;
import org.thought.acp.ai.AcpChatOptions;
import org.thought.acp.client.AgentClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Whether the agent shows up where a Spring AI application already looks for a model.
 */
class AcpChatModelAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(AcpAutoConfiguration.class, AcpChatModelAutoConfiguration.class))
			.withUserConfiguration(StubClient.class);

	@Test
	void registersTheAgentAsAChatModel() {
		runner.run(context -> assertThat(context).hasSingleBean(ChatModel.class).hasSingleBean(AcpChatModel.class));
	}

	@Test
	@DisplayName("the model and mode from spring.acp.* become the chat model's defaults")
	void carriesThePortableOptionsIntoTheDefaults() {
		runner.withPropertyValues("spring.acp.model=gpt-5.4-mini", "spring.acp.mode=plan").run(context -> {
			AcpChatOptions options = (AcpChatOptions) context.getBean(AcpChatModel.class).getOptions();
			assertThat(options.getModel()).isEqualTo("gpt-5.4-mini");
			assertThat(options.getMode()).isEqualTo("plan");
			// Deliberately not a session: one bean naming one session would make the whole
			// application share a conversation, invisibly, until two callers interfered.
			assertThat(options.getSession()).isNull();
		});
	}

	@Test
	void backsOffFromAnApplicationsOwnChatModel() {
		runner.withUserConfiguration(OwnChatModel.class)
				.run(context -> assertThat(context.getBean(AcpChatModel.class).getOptions())
						.extracting(options -> ((AcpChatOptions) options).getSession()).isEqualTo("house"));
	}

	@Test
	void canBeTurnedOffWithoutRemovingTheDependency() {
		runner.withPropertyValues("spring.acp.chat-model.enabled=false")
				.run(context -> assertThat(context).doesNotHaveBean(AcpChatModel.class));
	}

	@Test
	void doesNothingWithoutSpringAiOnTheClasspath() {
		runner.withClassLoader(new FilteredClassLoader(ChatModel.class)).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(AgentClient.class);
		});
	}

	@Configuration(proxyBeanMethods = false)
	static class StubClient {

		@Bean
		AgentClient acpAgentClient() {
			return mock(AgentClient.class);
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class OwnChatModel {

		@Bean
		AcpChatModel acpChatModel(AgentClient client) {
			return new AcpChatModel(client, AcpChatOptions.builder().session("house").build());
		}
	}
}
