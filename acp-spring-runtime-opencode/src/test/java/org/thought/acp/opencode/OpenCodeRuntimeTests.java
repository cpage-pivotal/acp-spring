package org.thought.acp.opencode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thought.acp.config.AgentSettings;
import org.thought.acp.config.ProviderSpec;
import org.thought.acp.runtime.AgentLaunchSpec;
import org.thought.acp.runtime.AgentRuntime.PortableOption;

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
	void anEndpointOfTheApplicationsOwnBecomesAProviderOpenCodeCanLoad() throws Exception {
		ProviderSpec provider = new ProviderSpec("acme", "openai",
				java.net.URI.create("https://gateway.example.com/team-x/openai"), "sk-x", Map.of());
		AgentSettings settings = settings().provider(provider).model("llm-1").build();

		runtime.provision(settings);
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime.launch(settings);

		Path file = home.resolve("opencode.json");
		assertThat(spec.env()).containsEntry(OpenCodeRuntime.CONFIG_ENV, file.toString());
		assertThat(Files.readString(file)).contains("\"@ai-sdk/openai-compatible\"")
				.contains("\"baseURL\": \"https://gateway.example.com/team-x/openai/v1\"")
				// OpenCode's own indirection, so the key stays in the environment.
				.contains("\"apiKey\": \"{env:OPENAI_API_KEY}\"")
				// Its model ids are provider/model, so the endpoint and its model are one entry.
				.contains("\"model\": \"acme/llm-1\"")
				// The endpoint's key is exported as OPENAI_API_KEY, which would switch the vendor on too.
				.contains("\"disabled_providers\": [\"openai\"]");
		assertThat(runtime.appliedOutOfBand(PortableOption.MODEL, settings)).isTrue();
	}

	@Test
	void anEndpointNamedAfterItsApiTypeIsNotMergedIntoTheBuiltInProvider() throws Exception {
		// OpenCode merges an entry named "openai" into its own openai provider, whose loader calls
		// sdk.responses() — which the compatible package lacks, so every turn failed.
		ProviderSpec provider = new ProviderSpec("openai", "openai",
				java.net.URI.create("https://gateway.example.com/team-x/openai"), "sk-x", Map.of());
		AgentSettings settings = settings().provider(provider).model("vendor/llm-1").build();

		runtime.provision(settings);

		String config = Files.readString(home.resolve("opencode.json"));
		assertThat(config).contains("\"acp\": {").doesNotContain("\"openai\": {")
				.contains("\"name\": \"openai\"")
				.contains("\"model\": \"acp/vendor/llm-1\"")
				.contains("\"disabled_providers\": [\"openai\"]");
		assertThat(runtime.appliedOutOfBand(PortableOption.MODEL, settings)).isTrue();
	}

	@Test
	void aVendorOpenCodeAlreadyKnowsIsLeftToTheWire() {
		AgentSettings settings = settings().provider(new ProviderSpec("openai", "openai", null, "sk-x", Map.of()))
				.model("gpt-5.4-mini").build();

		runtime.provision(settings);

		assertThat(home.resolve("opencode.json")).doesNotExist();
		assertThat(runtime.appliedOutOfBand(PortableOption.MODEL, settings)).isFalse();
	}

	@Test
	void extraArgumentsAreAppended() {
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime
				.launch(settings().runtimeOptions(Map.of("args", List.of("--print-logs"))).build());

		assertThat(spec.args()).containsExactly("acp", "--print-logs");
	}
}
