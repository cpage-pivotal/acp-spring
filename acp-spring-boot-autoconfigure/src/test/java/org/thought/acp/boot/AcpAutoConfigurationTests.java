package org.thought.acp.boot;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thought.acp.client.AgentClient;
import org.thought.acp.codex.CodexRuntime;
import org.thought.acp.goose.GooseRuntime;
import org.thought.acp.opencode.OpenCodeRuntime;
import org.thought.acp.config.AgentSettings;
import org.thought.acp.config.PoolSettings;
import org.thought.acp.executor.AgentExecutor;
import org.thought.acp.workspace.FileSystemAccess;
import org.thought.acp.config.McpServerSpec;
import org.thought.acp.config.OnUnsupported;
import org.thought.acp.config.ProviderSpec;
import org.thought.acp.config.RuntimeOptions;
import org.thought.acp.permission.PermissionPolicy;
import org.thought.acp.runtime.AgentRuntime;

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
	void defaultsToCarryingOnWhenTheAgentCannotLoadAnMcpServer() {
		runner.run(context -> assertThat(context.getBean(AgentSettings.class).mcp())
				.isEqualTo(org.thought.acp.config.McpSettings.defaults()));
	}

	@Test
	void anMcpCredentialsProviderAndPrincipalResolverAreTakenFromTheContext() {
		org.thought.acp.mcp.McpCredentialsProvider provider = (server, principal) -> Optional.empty();
		org.thought.acp.session.SessionPrincipalResolver resolver = () -> Optional
			.of(org.thought.acp.session.SessionPrincipal.of("alice"));
		runner.withBean(org.thought.acp.mcp.McpCredentialsProvider.class, () -> provider)
			.withBean(org.thought.acp.session.SessionPrincipalResolver.class, () -> resolver)
			.withPropertyValues("spring.acp.mcp.on-server-failure=fail")
			.run(context -> {
				org.thought.acp.config.McpSettings mcp = context.getBean(AgentSettings.class).mcp();
				assertThat(mcp.credentials()).isSameAs(provider);
				assertThat(mcp.principals()).isSameAs(resolver);
				assertThat(mcp.onServerFailure())
					.isEqualTo(org.thought.acp.config.McpSettings.OnServerFailure.FAIL);
			});
	}

	@Test
	void anApplicationThatWouldRatherNotStartWithoutItsToolsSaysSo() {
		runner.withPropertyValues("spring.acp.mcp.on-server-failure=fail",
				"spring.acp.mcp.detect-timeout=750ms").run(context -> {
					org.thought.acp.config.McpSettings mcp = context.getBean(AgentSettings.class).mcp();
					assertThat(mcp.onServerFailure())
							.isEqualTo(org.thought.acp.config.McpSettings.OnServerFailure.FAIL);
					assertThat(mcp.detectTimeout()).isEqualTo(java.time.Duration.ofMillis(750));
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
		// 'gemini' used to belong here and no longer does: the registry provider vouches for it.
		// What must still fail is an id nothing can supply, which is the typo this check exists for.
		runner.withPropertyValues("spring.acp.runtimes.gooze.model=gpt-5.4-mini").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
					.hasMessageContaining("unregistered runtime(s) [gooze]")
					.hasMessageContaining("[codex, goose, opencode]");
		});
	}

	@Test
	void failsFastWhenTheSelectedRuntimeIsNotRegistered() {
		runner.withPropertyValues("spring.acp.runtime=not-an-agent").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("No AgentRuntime registered")
					.hasMessageContaining("available from the ACP registry");
		});
	}

	@Test
	void resolvesARuntimeNoAdapterClaimsFromTheRegistry() {
		// The M4 claim, at its smallest: an agent nobody wrote an adapter for, selected by property.
		// Nothing is downloaded here — the entry is read from the bundled snapshot, and the agent
		// itself would be fetched on the first turn.
		runner.withPropertyValues("spring.acp.runtime=gemini", "spring.acp.registry.offline=true")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(SelectedRuntime.class).runtime().id()).isEqualTo("gemini");
					assertThat(context.getBean(SelectedRuntime.class).runtime())
							.isInstanceOf(org.thought.acp.registry.RegistryAgentRuntime.class);
				});
	}

	@Test
	void prefersACompiledAdapterOverTheRegistryForTheSameAgent() {
		// Both can supply 'goose'. The adapter wins, because it knows things the catalogue does not:
		// where goose hides a tool name, that its provider option has no category, and how to serve.
		runner.withPropertyValues("spring.acp.runtime=goose", "spring.acp.registry.offline=true")
				.run(context -> assertThat(context.getBean(SelectedRuntime.class).runtime())
						.isInstanceOf(GooseRuntime.class));
	}

	@Test
	void doesNotConsultTheRegistryWhenItIsTurnedOff() {
		runner.withPropertyValues("spring.acp.runtime=gemini", "spring.acp.registry.enabled=false")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).rootCause()
							.hasMessageContaining("No AgentRuntime registered");
				});
	}

	@Test
	void worksWithoutTheRegistryModuleOnTheClasspath() {
		// The same condition trap as the adapters: a class-level @ConditionalOnClass can hold when
		// the class is genuinely absent, and a @Bean-level one cannot.
		runner.withClassLoader(new FilteredClassLoader(org.thought.acp.registry.AgentRegistry.class))
				.withPropertyValues("spring.acp.runtime=goose").run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(AgentSettings.class);
					assertThat(context).doesNotHaveBean(org.thought.acp.runtime.AgentRuntimeProvider.class);
				});
	}

	@Test
	void offersProtocolVersionOneUnlessTheFlagSaysOtherwise() {
		runner.run(context -> assertThat(context.getBean(AgentSettings.class).protocol().maxVersion())
				.isEqualTo(org.thought.acp.protocol.AcpProtocol.V1));
	}

	@Test
	void bindsTheProtocolFeatureFlag() {
		runner.withPropertyValues("spring.acp.protocol.max-version=2", "spring.acp.protocol.strict=true")
				.run(context -> {
					assertThat(context.getBean(AgentSettings.class).protocol().maxVersion()).isEqualTo(2);
					assertThat(context.getBean(AgentSettings.class).protocol().strict()).isTrue();
					assertThat(context.getBean(AgentSettings.class).protocol().offersDraft()).isTrue();
				});
	}

	@Test
	void registersMicrometerObservationsWhenMicrometerIsPresent() {
		runner.run(context -> assertThat(context.getBean(org.thought.acp.observation.AgentObservations.class))
				.isInstanceOf(org.thought.acp.observation.MicrometerAgentObservations.class));
	}

	@Test
	void doesNotObserveWhenTheApplicationTurnsItOff() {
		runner.withPropertyValues("spring.acp.observations.enabled=false").run(context -> assertThat(context)
				.doesNotHaveBean(org.thought.acp.observation.AgentObservations.class));
	}

	@Test
	void worksWithoutMicrometerOnTheClasspath() {
		runner.withClassLoader(new FilteredClassLoader(io.micrometer.observation.ObservationRegistry.class))
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(AgentSettings.class);
				});
	}

	@Test
	void bindsTheProviderBlockAndKeepsTheKeyOutOfItsOwnToString() {
		runner.withPropertyValues("spring.acp.provider.id=acme-ai", "spring.acp.provider.api-type=openai",
				"spring.acp.provider.base-url=https://ai.example.com/v1", "spring.acp.provider.api-key=sk-secret",
				"spring.acp.provider.headers.X-Tenant=acme").run(context -> {
					ProviderSpec provider = context.getBean(AgentSettings.class).provider();
					assertThat(provider.id()).isEqualTo("acme-ai");
					assertThat(provider.apiType()).isEqualTo("openai");
					assertThat(provider.findApiKey()).contains("sk-secret");
					assertThat(provider.headers()).containsEntry("X-Tenant", "acme");
					assertThat(provider).hasToString(
							"ProviderSpec[id=acme-ai, apiType=openai, baseUrl=https://ai.example.com/v1, "
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
		runner.withPropertyValues("spring.acp.runtime-home=/var/lib/acp-spring")
				.run(context -> assertThat(context.getBean(AgentSettings.class).runtimeHome())
						.isEqualTo(java.nio.file.Path.of("/var/lib/acp-spring")));
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
	void lendsTheAgentNothingByDefault() {
		runner.run(context -> {
			AgentSettings settings = context.getBean(AgentSettings.class);
			assertThat(settings.filesystem().read()).isFalse();
			assertThat(settings.filesystem().write()).isFalse();
			assertThat(settings.terminal().enabled()).isFalse();
		});
	}

	@Test
	void bindsFilesystemAndTerminalAccess() {
		runner.withPropertyValues("spring.acp.filesystem.enabled=true", "spring.acp.filesystem.write=true",
				"spring.acp.terminal.enabled=true", "spring.acp.terminal.allowed-commands=mvn,git",
				"spring.acp.terminal.output-limit=64KB", "spring.acp.terminal.command-timeout=30s").run(context -> {
					AgentSettings settings = context.getBean(AgentSettings.class);
					assertThat(settings.filesystem()).isEqualTo(FileSystemAccess.readWrite());
					assertThat(settings.terminal().permits("mvn")).isTrue();
					assertThat(settings.terminal().permits("rm")).isFalse();
					assertThat(settings.terminal().outputByteLimit()).isEqualTo(64 * 1024);
					assertThat(settings.terminal().commandTimeout()).isEqualTo(Duration.ofSeconds(30));
				});
	}

	/** {@code write} without {@code enabled} still lends reads: a writer that cannot read is a trap. */
	@Test
	void writeImpliesRead() {
		runner.withPropertyValues("spring.acp.filesystem.write=true").run(context -> assertThat(
				context.getBean(AgentSettings.class).filesystem()).isEqualTo(FileSystemAccess.readWrite()));
	}

	@Test
	void bindsThePool() {
		runner.withPropertyValues("spring.acp.pool.max-processes=4",
				"spring.acp.pool.max-sessions-per-process=8", "spring.acp.pool.max-restarts=2",
				"spring.acp.pool.session-ttl=15m").run(context -> {
					AgentSettings settings = context.getBean(AgentSettings.class);
					assertThat(settings.pool()).isEqualTo(new PoolSettings(4, 8, 2));
					assertThat(settings.pool().capacity()).isEqualTo(32);
					assertThat(settings.sessionTtl()).isEqualTo(Duration.ofMinutes(15));
				});
	}

	@Test
	void registersTheMigrationFacade() {
		runner.run(context -> assertThat(context).hasSingleBean(AgentExecutor.class));
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
