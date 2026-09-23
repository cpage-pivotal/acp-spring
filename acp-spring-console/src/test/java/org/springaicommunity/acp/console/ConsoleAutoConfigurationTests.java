package org.springaicommunity.acp.console;

import java.util.List;
import java.util.Optional;

import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springaicommunity.acp.boot.AcpAutoConfiguration;
import org.springaicommunity.acp.client.AgentClient;
import org.springaicommunity.acp.config.AgentSettings;
import org.springaicommunity.acp.permission.PermissionPrompt;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Wiring only: the console is never started, since nothing here publishes ApplicationReadyEvent. */
class ConsoleAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AcpAutoConfiguration.class, ConsoleAutoConfiguration.class))
		.withUserConfiguration(StubClient.class)
		.withBean(Terminal.class, () -> TestTerminals.typing("").terminal())
		.withPropertyValues("spring.acp.runtime=goose");

	@Test
	void registersTheConsoleWhenEnabled() {
		runner.withPropertyValues("spring.acp.console.enabled=true", "spring.application.name=meridian")
			.run(context -> {
				assertThat(context).hasSingleBean(ConsoleChat.class)
					.hasSingleBean(ConsoleRenderer.class)
					.hasSingleBean(TerminalPermissionPrompt.class);
				assertThat(context.getBean(ConsoleChat.class).session()).isEqualTo("console");
			});
	}

	@Test
	void bindsTheSessionName() {
		runner.withPropertyValues("spring.acp.console.enabled=true", "spring.acp.console.session=meridian-console")
			.run(context -> assertThat(context.getBean(ConsoleChat.class).session()).isEqualTo("meridian-console"));
	}

	@Test
	void registersNothingWhenDisabled() {
		runner.withPropertyValues("spring.acp.console.enabled=false")
			.run(context -> assertThat(context).doesNotHaveBean(ConsoleChat.class)
				.doesNotHaveBean(ConsoleRenderer.class)
				.doesNotHaveBean(PermissionPrompt.class));
	}

	/** A test JVM has no terminal, which is exactly the case auto mode exists for. */
	@Test
	void staysOutOfTheWayWithoutATerminalUnlessToldOtherwise() {
		assertThat(ConsoleAutoConfiguration.interactive()).isFalse();
		runner.run(context -> assertThat(context).doesNotHaveBean(ConsoleChat.class));
	}

	@Test
	void backsOffWhenTheAgentIsDisabled() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(AcpAutoConfiguration.class, ConsoleAutoConfiguration.class))
			.withPropertyValues("spring.acp.enabled=false", "spring.acp.console.enabled=true")
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ConsoleChat.class));
	}

	@Test
	void theConsoleIsWhoPolicyAskAsks() {
		runner.withPropertyValues("spring.acp.console.enabled=true", "spring.acp.permissions.policy=ask")
			.run(context -> {
				assertThat(context).hasNotFailed();
				TerminalPermissionPrompt prompt = context.getBean(TerminalPermissionPrompt.class);
				assertThat(context.getBean(PermissionPrompt.class)).isSameAs(prompt);
			});
	}

	@Test
	void anApplicationsOwnPermissionPromptWins() {
		AcpSchema.PermissionOption allow = new AcpSchema.PermissionOption("a", "Allow",
				AcpSchema.PermissionOptionKind.ALLOW_ONCE);
		PermissionPrompt own = question -> Optional.of(allow);
		runner.withBean(PermissionPrompt.class, () -> own)
			.withPropertyValues("spring.acp.console.enabled=true", "spring.acp.permissions.policy=ask")
			.run(context -> {
				assertThat(context).doesNotHaveBean(TerminalPermissionPrompt.class);
				assertThat(context.getBean(AgentSettings.class).permissions().decide(Optional.of("t"), List.of(allow)))
					.contains(allow);
			});
	}

	@Configuration(proxyBeanMethods = false)
	static class StubClient {

		@Bean
		AgentClient acpAgentClient() {
			return mock(AgentClient.class);
		}
	}
}
