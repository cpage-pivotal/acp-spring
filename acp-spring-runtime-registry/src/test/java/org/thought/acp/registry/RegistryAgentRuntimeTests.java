package org.thought.acp.registry;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thought.acp.config.AgentSettings;
import org.thought.acp.config.ProviderSpec;
import org.thought.acp.runtime.AgentLaunchSpec;
import org.thought.acp.runtime.AgentRuntime;
import org.thought.acp.runtime.AgentRuntime.PortableOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the generic runtime launches, and what it honestly cannot do.
 *
 * <p>The negative assertions are as much the point as the positive ones. This runtime reaches the
 * negotiated tier through the portable half of the option vocabulary and nothing else, because
 * nothing here has ever seen the agent it is launching.
 */
class RegistryAgentRuntimeTests {

	@TempDir
	Path workspace;

	@Test
	void launchesAnNpxAgentWithTheArgumentsThatMakeItSpeakAcp() {
		AgentLaunchSpec spec = runtime("gemini").launch(settings(Map.of()));

		assertThat(spec).isInstanceOfSatisfying(AgentLaunchSpec.Stdio.class, stdio -> {
			assertThat(stdio.command()).isEqualTo("npx");
			assertThat(stdio.args()).startsWith("-y").contains("--acp");
			assertThat(stdio.args().get(1)).startsWith("@google/gemini-cli@");
		});
	}

	@Test
	void launchesAUvxAgent() {
		AgentLaunchSpec spec = runtime("fast-agent").launch(settings(Map.of()));

		assertThat(spec).isInstanceOfSatisfying(AgentLaunchSpec.Stdio.class, stdio -> {
			assertThat(stdio.command()).isEqualTo("uvx");
			assertThat(stdio.args().getFirst()).startsWith("fast-agent-acp==");
			// The registry's own env for this agent comes across.
			assertThat(stdio.env()).containsKey("FAST_AGENT_MODEL");
		});
	}

	@Test
	@DisplayName("tier 3 appends arguments rather than replacing the registry's")
	void appendsTheApplicationsOwnArguments() {
		// The registry's arguments are what make the agent speak ACP at all, so they are not the
		// application's to reorder.
		AgentLaunchSpec spec = runtime("gemini").launch(settings(Map.of("args", List.of("--yolo"))));

		assertThat(((AgentLaunchSpec.Stdio) spec).args()).containsSubsequence("--acp", "--yolo");
	}

	@Test
	void putsTheProvidersCredentialsOnTheEnvironmentUnderTheDerivedNames() {
		AgentSettings settings = AgentSettings.builder("gemini", workspace)
				.provider(new ProviderSpec("openai", "openai", URI.create("https://ai.example.com"), "sk-secret",
						Map.of()))
				.build();

		AgentLaunchSpec.Stdio stdio = (AgentLaunchSpec.Stdio) runtime("gemini").launch(settings);

		// Canonical rather than verbatim: an agent nobody wrote an adapter for reads OPENAI_BASE_URL
		// with its SDK's assumptions, and those include the version segment.
		assertThat(stdio.env()).containsEntry("OPENAI_API_KEY", "sk-secret").containsEntry("OPENAI_BASE_URL",
				"https://ai.example.com/v1");
	}

	@Test
	@DisplayName("the tier-3 env block wins over both the registry and the derived names")
	void letsTheApplicationOverrideAVariableNothingCouldHaveGuessed() {
		AgentSettings settings = AgentSettings.builder("gemini", workspace).provider(
				new ProviderSpec("openai", "openai", null, "sk-derived", Map.of()))
				.runtimeOptions(Map.of("env", Map.of("OPENAI_API_KEY", "sk-explicit", "GEMINI_API_KEY", "g-key")))
				.build();

		AgentLaunchSpec.Stdio stdio = (AgentLaunchSpec.Stdio) runtime("gemini").launch(settings);

		assertThat(stdio.env()).containsEntry("OPENAI_API_KEY", "sk-explicit").containsEntry("GEMINI_API_KEY",
				"g-key");
	}

	@Test
	void reportsTheProviderAsAppliedWhenItReachedTheAgentThroughTheEnvironment() {
		// Otherwise on-unsupported: fail would fire on a configuration that is working exactly as
		// asked, because no advertised config option carried it.
		AgentSettings withKey = AgentSettings.builder("gemini", workspace)
				.provider(new ProviderSpec("openai", "openai", null, "sk-secret", Map.of())).build();

		assertThat(runtime("gemini").appliedOutOfBand(PortableOption.PROVIDER, withKey)).isTrue();
		assertThat(runtime("gemini").appliedOutOfBand(PortableOption.MODEL, withKey)).isFalse();
		assertThat(runtime("gemini").appliedOutOfBand(PortableOption.PROVIDER, settings(Map.of()))).isFalse();
	}

	@Test
	@DisplayName("the portable option vocabulary, and nothing an adapter would have known")
	void reachesTheNegotiatedTierOnlyThroughWhatTheProtocolStandardizes() {
		AgentRuntime runtime = runtime("gemini");

		assertThat(runtime.configIdsFor(PortableOption.MODE)).containsExactly("mode");
		assertThat(runtime.configCategoriesFor(PortableOption.MODE)).containsExactly("mode");
		// No standard category for a provider, so there is no fallback rather than a wrong one.
		assertThat(runtime.configCategoriesFor(PortableOption.PROVIDER)).isEmpty();
	}

	@Test
	@DisplayName("a tool name only if the agent published one; never the human-readable title")
	void willNotGuessAToolName() {
		// An adapter may fall back to the title because its author knows that agent. A wrong answer
		// here would turn an allowlist into an approval.
		var titled = new com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallUpdate("t1", "Read the build file", null,
				null, null, null, null, null);

		assertThat(runtime("gemini").toolNameOf(titled)).isEmpty();
	}

	@Test
	void saysWhichPlatformsAnAgentHasWhenItHasNoneForThisOne() {
		RegistryEntry entry = registry().find("goose").orElseThrow();
		RegistryAgentRuntime runtime = new RegistryAgentRuntime(entry, new AgentInstaller(offlineSettings()),
				new Platform("plan9", "sparc"));

		assertThatThrownBy(() -> runtime.launch(settings(Map.of())))
				.isInstanceOf(AgentInstaller.AgentInstallException.class)
				.hasMessageContaining("no build of 'goose' for plan9-sparc").hasMessageContaining("linux-x86_64");
	}

	@Test
	void offersAnIntelBuildToAnAppleSiliconMachineAndNeverTheReverse() {
		// Rosetta runs the first; nothing runs the second, and failing at the download is better
		// than failing at exec.
		assertThat(new Platform("darwin", "aarch64").candidateKeys()).containsExactly("darwin-aarch64",
				"darwin-x86_64");
		assertThat(new Platform("darwin", "x86_64").candidateKeys()).containsExactly("darwin-x86_64");
		assertThat(new Platform("linux", "aarch64").candidateKeys()).containsExactly("linux-aarch64");
	}

	@Test
	void namesThePlatformTheWayTheRegistryDoes() {
		Platform current = Platform.current();

		assertThat(current.os()).isIn("darwin", "linux", "windows");
		assertThat(current.arch()).isNotBlank();
		assertThat(current.id()).isEqualTo(current.os() + "-" + current.arch());
	}

	private AgentRuntime runtime(String id) {
		return new RegistryAgentRuntimeProvider(registry(), new AgentInstaller(offlineSettings())).forId(id)
				.orElseThrow();
	}

	private AgentRegistry registry() {
		return new AgentRegistry(offlineSettings());
	}

	private RegistrySettings offlineSettings() {
		return new RegistrySettings(null, workspace.resolve("cache"), null, true, true, null);
	}

	private AgentSettings settings(Map<String, Object> runtimeOptions) {
		return AgentSettings.builder("gemini", workspace).runtimeOptions(runtimeOptions).build();
	}
}
