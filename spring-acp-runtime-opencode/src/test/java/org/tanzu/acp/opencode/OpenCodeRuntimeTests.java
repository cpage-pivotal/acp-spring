package org.tanzu.acp.opencode;

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

class OpenCodeRuntimeTests {

	@TempDir
	Path workspace;

	@TempDir
	Path home;

	private final OpenCodeRuntime runtime = new OpenCodeRuntime("opencode");

	private AgentSettings.Builder settings() {
		return AgentSettings.builder(OpenCodeRuntime.ID, workspace).runtimeHome(home);
	}

	@Test
	void launchesTheBinaryInAcpMode() {
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime.launch(settings().build());

		assertThat(spec.command()).isEqualTo("opencode");
		assertThat(spec.args()).containsExactly("acp");
	}

	@Test
	void aTierThreeCommandOverridesTheBinaryButNotTheSubcommand() {
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime
				.launch(settings().runtimeOptions(Map.of("command", "/opt/opencode/bin/opencode")).build());

		assertThat(spec.command()).isEqualTo("/opt/opencode/bin/opencode");
		assertThat(spec.args()).containsExactly("acp");
	}

	@Test
	void writesOpencodeJsonAndPointsTheProcessAtIt() throws Exception {
		AgentSettings settings = settings().runtimeOptions(Map.of("config", Map.of("theme", "system"))).build();

		runtime.provision(settings);
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime.launch(settings);

		Path file = home.resolve("opencode.json");
		assertThat(spec.env()).containsEntry(OpenCodeRuntime.CONFIG_ENV, file.toString());
		assertThat(Files.readString(file)).isEqualTo("{\n  \"theme\": \"system\"\n}\n");
	}

	@Test
	void nothingIsWrittenAndNothingIsRedirectedWithoutTierThreeConfig() {
		AgentSettings settings = settings().build();

		runtime.provision(settings);
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime.launch(settings);

		assertThat(home.resolve("opencode.json")).doesNotExist();
		assertThat(spec.env()).doesNotContainKey(OpenCodeRuntime.CONFIG_ENV);
	}

	@Test
	void hasNoProviderOptionBecauseTheProviderIsPartOfTheModelId() {
		assertThat(runtime.configIdsFor(PortableOption.PROVIDER)).isEmpty();
		assertThat(runtime.configIdsFor(PortableOption.MODEL)).containsExactly("model");
	}

	@Test
	void aProviderRequestIsSubsumedByTheModelRatherThanUnsupported() {
		// The provider took effect as the prefix the model was matched with, so reporting it
		// unsupported would fire on-unsupported=fail on a configuration that is working as asked.
		assertThat(runtime.appliedOutOfBand(PortableOption.PROVIDER,
				settings().provider("openai").model("gpt-5.4-mini").build())).isTrue();
		assertThat(runtime.appliedOutOfBand(PortableOption.PROVIDER, settings().provider("openai").build()))
				.isFalse();
	}

	@Test
	void providerCredentialsReachTheProcessAsEnvironment() {
		ProviderSpec provider = new ProviderSpec("openai", "openai", null, "sk-x", Map.of());
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime.launch(settings().provider(provider).build());

		assertThat(spec.env()).containsEntry("OPENAI_API_KEY", "sk-x");
	}

	@Test
	void extraArgumentsAreAppended() {
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime
				.launch(settings().runtimeOptions(Map.of("args", List.of("--print-logs"))).build());

		assertThat(spec.args()).containsExactly("acp", "--print-logs");
	}
}
