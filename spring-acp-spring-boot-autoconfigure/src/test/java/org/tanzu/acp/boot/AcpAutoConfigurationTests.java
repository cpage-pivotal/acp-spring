package org.tanzu.acp.boot;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.tanzu.acp.client.AgentClient;
import org.tanzu.acp.config.AgentSettings;
import org.tanzu.acp.config.McpServerSpec;
import org.tanzu.acp.config.OnUnsupported;
import org.tanzu.acp.permission.PermissionPolicy;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binding and wiring only — no agent is started. {@code AgentClient} is stubbed out so these stay
 * fast and run anywhere.
 */
class AcpAutoConfigurationTests {

	/** Auto-configuration alone, with nothing stubbed. */
	private final ApplicationContextRunner plain = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(AcpAutoConfiguration.class));

	/** The same, with the client stubbed so no agent subprocess is started. */
	private final ApplicationContextRunner runner = plain.withUserConfiguration(StubClient.class);

	@Test
	void bindsPortableOptions() {
		runner.withPropertyValues("spring.acp.runtime=goose", "spring.acp.model=claude-sonnet-5",
				"spring.acp.timeout=90s", "spring.acp.on-unsupported=fail").run(context -> {
					AgentSettings settings = context.getBean(AgentSettings.class);
					assertThat(settings.runtime()).isEqualTo("goose");
					assertThat(settings.model()).isEqualTo("claude-sonnet-5");
					assertThat(settings.timeout()).isEqualTo(Duration.ofSeconds(90));
					assertThat(settings.onUnsupported()).isEqualTo(OnUnsupported.FAIL);
				});
	}

	@Test
	void defaultsToDenyingToolPermissions() {
		runner.run(context -> {
			PermissionPolicy policy = context.getBean(AgentSettings.class).permissions();
			AcpSchema.PermissionOption allow = new AcpSchema.PermissionOption("a", "Allow",
					AcpSchema.PermissionOptionKind.ALLOW_ONCE);
			AcpSchema.PermissionOption reject = new AcpSchema.PermissionOption("r", "Reject",
					AcpSchema.PermissionOptionKind.REJECT_ONCE);

			assertThat(policy.decide(Optional.of("developer__shell"), List.of(allow, reject))).contains(reject);
		});
	}

	@Test
	void bindsHttpAndStdioMcpServers() {
		runner.withPropertyValues("spring.acp.mcp-servers[0].name=remote",
				"spring.acp.mcp-servers[0].url=https://tools.example.com/mcp",
				"spring.acp.mcp-servers[0].headers.Authorization=Bearer token",
				"spring.acp.mcp-servers[1].name=local", "spring.acp.mcp-servers[1].command=/usr/bin/helper",
				"spring.acp.mcp-servers[1].args[0]=serve").run(context -> {
					List<McpServerSpec> servers = context.getBean(AgentSettings.class).mcpServers();
					assertThat(servers).hasSize(2);
					assertThat(servers.get(0)).isInstanceOf(McpServerSpec.Http.class);
					assertThat(servers.get(1)).isInstanceOf(McpServerSpec.Stdio.class);
				});
	}

	@Test
	void rejectsAPlainHttpMcpServerOnTheOpenInternet() {
		runner.withPropertyValues("spring.acp.mcp-servers[0].name=remote",
				"spring.acp.mcp-servers[0].url=http://tools.example.com/mcp")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void passesRuntimeSpecificOptionsToTheSelectedRuntimeOnly() {
		runner.withPropertyValues("spring.acp.runtime=goose", "spring.acp.runtimes.goose.builtins=developer,todo")
				.run(context -> assertThat(context.getBean(AgentSettings.class).runtimeOptions())
						.containsEntry("builtins", "developer,todo"));
	}

	@Test
	void failsFastWhenRuntimeSpecificOptionsNameARuntimeNobodyRegistered() {
		runner.withPropertyValues("spring.acp.runtimes.codex.model=gpt-5").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasRootCauseMessage(
					"spring.acp.runtimes has options for unregistered runtime(s) [codex]; registered runtimes are [goose]");
		});
	}

	@Test
	void failsFastWhenTheSelectedRuntimeIsNotRegistered() {
		runner.withPropertyValues("spring.acp.runtime=opencode").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("No AgentRuntime registered");
		});
	}

	@Test
	void backsOffEntirelyWhenDisabled() {
		plain.withPropertyValues("spring.acp.enabled=false").run(context -> assertThat(context)
				.hasNotFailed().doesNotHaveBean(AgentSettings.class).doesNotHaveBean(AgentClient.class));
	}

	/** Replaces the real client so no subprocess is started; everything else stays real. */
	@Configuration(proxyBeanMethods = false)
	static class StubClient {

		@Bean
		AgentClient acpAgentClient(SelectedRuntime selected, AgentSettings settings) {
			return org.mockito.Mockito.mock(AgentClient.class);
		}
	}
}
