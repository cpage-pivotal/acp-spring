package org.tanzu.acp.goose;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tanzu.acp.config.AgentSettings;
import org.tanzu.acp.config.ProviderSpec;
import org.tanzu.acp.runtime.AgentLaunchSpec;
import org.tanzu.acp.runtime.AgentRuntime.PortableOption;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import static org.assertj.core.api.Assertions.assertThat;

class GooseRuntimeTests {

	@TempDir
	Path workspace;

	private final GooseRuntime runtime = new GooseRuntime("goose");

	private AgentSettings.Builder settings() {
		return AgentSettings.builder(GooseRuntime.ID, workspace);
	}

	@Test
	void launchesInAcpModeWithServerSideDefaults() {
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime.launch(settings().build());

		assertThat(spec.command()).isEqualTo("goose");
		assertThat(spec.args()).containsExactly("acp");
		assertThat(spec.env()).containsEntry("GOOSE_DISABLE_KEYRING", "1").containsEntry("GOOSE_TELEMETRY_ENABLED",
				"false");
	}

	@Test
	void buildinsBecomeRepeatedWithBuiltinArguments() {
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime
				.launch(settings().runtimeOptions(Map.of("builtins", "developer,todo")).build());

		assertThat(spec.args()).containsExactly("acp", "--with-builtin", "developer", "--with-builtin", "todo");
	}

	@Test
	void buildinsCanAlsoBeWrittenAsAList() {
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime
				.launch(settings().runtimeOptions(Map.of("builtins", List.of("developer"))).build());

		assertThat(spec.args()).containsExactly("acp", "--with-builtin", "developer");
	}

	@Test
	void goosesOwnNameForAnOpenAiEndpointIsUsed() {
		// Goose reads OPENAI_HOST, not the OPENAI_BASE_URL the derivation rule would produce.
		ProviderSpec provider = new ProviderSpec("tanzu-ai", "openai", URI.create("https://ai.example.com/v1"), "sk-x",
				Map.of());
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime.launch(settings().provider(provider).build());

		assertThat(spec.env()).containsEntry("OPENAI_HOST", "https://ai.example.com/v1")
				.containsEntry("OPENAI_API_KEY", "sk-x").doesNotContainKey("OPENAI_BASE_URL");
	}

	@Test
	void theTierThreeEnvBlockWinsOverTheAdaptersOwnDefaults() {
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime
				.launch(settings().runtimeOptions(Map.of("env.GOOSE_DISABLE_KEYRING", "0")).build());

		assertThat(spec.env()).containsEntry("GOOSE_DISABLE_KEYRING", "0");
	}

	@Test
	void theProviderOptionIsFoundByIdOnlyBecauseGooseSendsNoCategoryForIt() {
		assertThat(runtime.configIdsFor(PortableOption.PROVIDER)).containsExactly("provider");
		assertThat(runtime.configCategoriesFor(PortableOption.PROVIDER)).isEmpty();
		assertThat(runtime.configCategoriesFor(PortableOption.MODEL)).containsExactly("model");
	}

	@Test
	void theToolNameComesFromGoosesOwnRawInput() {
		AcpSchema.ToolCallUpdate call = new AcpSchema.ToolCallUpdate("id", "Ran a shell command", null, null, null,
				null, Map.of("toolName", "developer__shell"), null);

		assertThat(runtime.toolNameOf(call)).contains("developer__shell");
	}

	@Test
	void theTitleIsTheFallbackWhenGooseSendsNoRawInput() {
		AcpSchema.ToolCallUpdate call = new AcpSchema.ToolCallUpdate("id", "developer__text_editor", null, null, null,
				null, null, null);

		assertThat(runtime.toolNameOf(call)).contains("developer__text_editor");
	}
}
