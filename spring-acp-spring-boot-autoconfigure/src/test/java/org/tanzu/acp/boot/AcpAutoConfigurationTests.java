package org.tanzu.acp.boot;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.tanzu.acp.client.AgentClient;
import org.tanzu.acp.codex.CodexRuntime;
import org.tanzu.acp.goose.GooseRuntime;
import org.tanzu.acp.opencode.OpenCodeRuntime;
import org.tanzu.acp.config.AgentSettings;
import org.tanzu.acp.config.McpServerSpec;
import org.tanzu.acp.config.OnUnsupported;
import org.tanzu.acp.config.ProviderSpec;
import org.tanzu.acp.config.RuntimeOptions;
import org.tanzu.acp.permission.PermissionPolicy;
import org.tanzu.acp.runtime.AgentRuntime;

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
	void registersEveryAdapterOnTheClasspath() {
		// The completion test for this milestone in miniature: one application, three agents, and the
		// only thing that chooses between them is spring.acp.runtime.
		runner.run(context -> assertThat(context.getBeansOfType(AgentRuntime.class).values())
				.extracting(AgentRuntime::id).containsExactlyInAnyOrder("goose", "codex", "opencode"));
	}

	@Test
	void selectsWhicheverRuntimeThePropertyNames() {
		for (String id : List.of("goose", "codex", "opencode")) {
			runner.withPropertyValues("spring.acp.runtime=" + id).run(context -> assertThat(context)
					.hasNotFailed().getBean(SelectedRuntime.class)
					.satisfies(selected -> assertThat(selected.runtime().id()).isEqualTo(id)));
		}
	}

	@Test
	void passesRuntimeSpecificOptionsToTheSelectedRuntimeOnly() {
		runner.withPropertyValues("spring.acp.runtime=goose", "spring.acp.runtimes.goose.builtins=developer,todo",
				"spring.acp.runtimes.codex.package=ignored").run(context -> {
					RuntimeOptions options = context.getBean(AgentSettings.class).runtimeOptions();
					assertThat(options.textList("builtins")).containsExactly("developer", "todo");
					assertThat(options.text("package")).isEmpty();
				});
	}

	@Test
	void bindsANestedRuntimeSpecificBlock() {
		// Tier 3 passes an agent's own configuration through untouched, and that is not always flat.
		runner.withPropertyValues("spring.acp.runtime=codex",
				"spring.acp.runtimes.codex.config-toml.model_reasoning_effort=high",
				"spring.acp.runtimes.codex.config-toml.sandbox.mode=read-only").run(context -> {
					RuntimeOptions options = context.getBean(AgentSettings.class).runtimeOptions();
					assertThat(options.section("config-toml")).containsKey("model_reasoning_effort");
					assertThat(options.text("config-toml.sandbox.mode")).contains("read-only");
				});
	}

	@Test
	void failsFastWhenRuntimeSpecificOptionsNameARuntimeNobodyRegistered() {
		runner.withPropertyValues("spring.acp.runtimes.gemini.model=gemini-3").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
					.hasMessageContaining("unregistered runtime(s) [gemini]")
					.hasMessageContaining("[codex, goose, opencode]");
		});
	}

	@Test
	void failsFastWhenTheSelectedRuntimeIsNotRegistered() {
		runner.withPropertyValues("spring.acp.runtime=gemini").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("No AgentRuntime registered");
		});
	}

	@Test
	void bindsTheProviderBlockAndKeepsTheKeyOutOfItsOwnToString() {
		runner.withPropertyValues("spring.acp.provider.id=tanzu-ai", "spring.acp.provider.api-type=openai",
				"spring.acp.provider.base-url=https://ai.example.com/v1", "spring.acp.provider.api-key=sk-secret",
				"spring.acp.provider.headers.X-Tenant=acme").run(context -> {
					ProviderSpec provider = context.getBean(AgentSettings.class).provider();
					assertThat(provider.id()).isEqualTo("tanzu-ai");
					assertThat(provider.apiType()).isEqualTo("openai");
					assertThat(provider.findApiKey()).contains("sk-secret");
					assertThat(provider.headers()).containsEntry("X-Tenant", "acme");
					assertThat(provider).hasToString(
							"ProviderSpec[id=tanzu-ai, apiType=openai, baseUrl=https://ai.example.com/v1, "
									+ "apiKey=***, headers=[X-Tenant]]");
				});
	}

	@Test
	void rejectsAProviderEndpointOnPlainHttp() {
		runner.withPropertyValues("spring.acp.provider.base-url=http://ai.example.com/v1")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void rejectsAProviderHeaderCarryingALineBreak() {
		runner.withPropertyValues("spring.acp.provider.headers.X-Bad=value\ninjected: yes")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void theRuntimeHomeDefaultsOutsideTheWorkspace() {
		runner.run(context -> {
			AgentSettings settings = context.getBean(AgentSettings.class);
			assertThat(settings.runtimeHome()).isAbsolute().doesNotExist();
			assertThat(settings.runtimeHome().startsWith(settings.workspace())).isFalse();
			assertThat(settings.runtimeHome().getFileName()).hasToString("goose");
		});
	}

	@Test
	void theRuntimeHomeCanBeNamed() {
		runner.withPropertyValues("spring.acp.runtime-home=/var/lib/spring-acp")
				.run(context -> assertThat(context.getBean(AgentSettings.class).runtimeHome())
						.isEqualTo(java.nio.file.Path.of("/var/lib/spring-acp")));
	}

	@Test
	void anAdapterWhoseClassIsNotOnTheClasspathIsNotRegistered() {
		// The reason each adapter registers from a nested configuration: the condition has to hold
		// when the class is genuinely absent, which is the normal case for an optional dependency.
		runner.withClassLoader(new FilteredClassLoader(CodexRuntime.class, OpenCodeRuntime.class))
				.run(context -> assertThat(context.getBeansOfType(AgentRuntime.class).values())
						.extracting(AgentRuntime::id).containsExactly("goose"));
	}

	@Test
	void anApplicationReplacesABundledAdapterByReusingItsBeanName() {
		runner.withUserConfiguration(OwnGoose.class).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean("gooseAgentRuntime", AgentRuntime.class))
					.isInstanceOf(OwnGoose.Replacement.class);
		});
	}

	@Test
	void twoAdaptersForTheSameAgentIsAnErrorRatherThanATieToBeBroken() {
		runner.withUserConfiguration(SecondGoose.class).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
					.hasMessageContaining("More than one AgentRuntime is registered for [goose]");
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

	/** An application with its own idea of how to launch Goose, named so the bundled one backs off. */
	@Configuration(proxyBeanMethods = false)
	static class OwnGoose {

		@Bean("gooseAgentRuntime")
		AgentRuntime gooseAgentRuntime() {
			return new Replacement();
		}

		static class Replacement extends GooseRuntime {

			Replacement() {
				super("/opt/goose/bin/goose");
			}
		}
	}

	/** The same, but named something else, so both end up registered. */
	@Configuration(proxyBeanMethods = false)
	static class SecondGoose {

		@Bean
		AgentRuntime myOwnGoose() {
			return new GooseRuntime("/opt/goose/bin/goose");
		}
	}
}
