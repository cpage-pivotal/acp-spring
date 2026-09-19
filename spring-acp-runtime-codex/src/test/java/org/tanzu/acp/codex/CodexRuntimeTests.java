package org.tanzu.acp.codex;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tanzu.acp.config.AgentSettings;
import org.tanzu.acp.config.ProviderSpec;
import org.tanzu.acp.runtime.AgentLaunchSpec;
import org.tanzu.acp.runtime.AgentRuntime.PortableOption;

import static org.assertj.core.api.Assertions.assertThat;

class CodexRuntimeTests {

	@TempDir
	Path workspace;

	@TempDir
	Path home;

	private final CodexRuntime runtime = new CodexRuntime();

	private AgentSettings.Builder settings() {
		return AgentSettings.builder(CodexRuntime.ID, workspace).runtimeHome(home);
	}

	@Test
	void launchesThePinnedAdapterThroughNpxByDefault() {
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime.launch(settings().build());

		assertThat(spec.command()).isEqualTo("npx");
		assertThat(spec.args()).containsExactly("-y", CodexRuntime.DEFAULT_PACKAGE);
	}

	@Test
	void aLocallyInstalledAdapterReplacesNpxEntirely() {
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime
				.launch(settings().runtimeOptions(Map.of("command", "/usr/local/bin/codex-acp")).build());

		assertThat(spec.command()).isEqualTo("/usr/local/bin/codex-acp");
		assertThat(spec.args()).isEmpty();
	}

	@Test
	void anAlternatePackageVersionCanBePinnedWithoutTouchingTheAdapter() {
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime
				.launch(settings().runtimeOptions(Map.of("package", "@agentclientprotocol/codex-acp@1.11.0")).build());

		assertThat(spec.args()).containsExactly("-y", "@agentclientprotocol/codex-acp@1.11.0");
	}

	@Test
	void theAmbientCodexHomeIsLeftAloneWhenNothingAskedForCodexConfig() {
		// Codex keeps auth.json beside config.toml, so relocating the home uninvited would cost a
		// developer machine its `codex login`.
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime.launch(settings().build());

		assertThat(spec.env()).doesNotContainKey(CodexRuntime.HOME_ENV);
	}

	@Test
	void askingForCodexConfigMovesTheHomeAndWritesTheFile() throws Exception {
		AgentSettings settings = settings()
				.runtimeOptions(Map.of("config-toml", Map.of("model_reasoning_effort", "high"))).build();

		runtime.provision(settings);
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime.launch(settings);

		assertThat(spec.env()).containsEntry(CodexRuntime.HOME_ENV, home.toString());
		assertThat(Files.readString(home.resolve("config.toml")))
				.isEqualTo("model_reasoning_effort = \"high\"\n");
	}

	@Test
	void anExplicitHomeIsHonoredWithoutAnyCodexConfig() {
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime
				.launch(settings().runtimeOptions(Map.of("home", home.toString())).build());

		assertThat(spec.env()).containsEntry(CodexRuntime.HOME_ENV, home.toString());
	}

	@Test
	void nothingIsWrittenWhenNoCodexConfigWasAskedFor() {
		runtime.provision(settings().build());

		assertThat(home.resolve("config.toml")).doesNotExist();
	}

	@Test
	void providerCredentialsReachTheProcessAsEnvironment() {
		ProviderSpec provider = new ProviderSpec("openai", "openai", URI.create("https://ai.example.com/v1"), "sk-x",
				Map.of());
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime.launch(settings().provider(provider).build());

		assertThat(spec.env()).containsEntry("OPENAI_API_KEY", "sk-x").containsEntry("OPENAI_BASE_URL",
				"https://ai.example.com/v1");
	}

	@Test
	void theTierThreeEnvBlockWinsOverEverythingTheAdapterChose() {
		ProviderSpec provider = new ProviderSpec("openai", "openai", null, "sk-x", Map.of());
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime
				.launch(settings().provider(provider).runtimeOptions(Map.of("env.OPENAI_API_KEY", "override")).build());

		assertThat(spec.env()).containsEntry("OPENAI_API_KEY", "override");
	}

	@Test
	void offersBothOfCodexsModeOptionsToThePortableModeProperty() {
		assertThat(runtime.configIdsFor(PortableOption.MODE)).containsExactly("mode", "collaboration_mode");
		assertThat(runtime.configIdsFor(PortableOption.PROVIDER)).isEmpty();
	}

	@Test
	void aConfiguredProviderIsNeverSimplyUnsupportedBecauseTheEnvironmentCarriesIt() {
		ProviderSpec provider = new ProviderSpec("openai", "openai", null, "sk-x", Map.of());

		assertThat(runtime.appliedOutOfBand(PortableOption.PROVIDER, settings().provider(provider).build())).isTrue();
		assertThat(runtime.appliedOutOfBand(PortableOption.PROVIDER, settings().provider("openai").build())).isFalse();
		assertThat(runtime.appliedOutOfBand(PortableOption.MODEL, settings().provider(provider).build())).isFalse();
	}

	@Test
	void extraArgumentsAreAppendedRatherThanReplacingTheAdapter() {
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime
				.launch(settings().runtimeOptions(Map.of("args", List.of("--verbose"))).build());

		assertThat(spec.args()).containsExactly("-y", CodexRuntime.DEFAULT_PACKAGE, "--verbose");
	}
}
