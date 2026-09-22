package org.springaicommunity.acp.goose;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.acp.config.AgentSettings;
import org.springaicommunity.acp.config.ProviderSpec;
import org.springaicommunity.acp.runtime.AgentLaunchSpec;
import org.springaicommunity.acp.runtime.AgentRuntime.PortableOption;

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
		// Goose reads OPENAI_HOST, not the OPENAI_BASE_URL the derivation rule would produce, and it
		// appends the version segment itself.
		ProviderSpec provider = new ProviderSpec("acme-ai", "openai", URI.create("https://ai.example.com/v1"), "sk-x",
				Map.of());
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime.launch(settings().provider(provider).build());

		assertThat(spec.env()).containsEntry("OPENAI_HOST", "https://ai.example.com")
				.containsEntry("OPENAI_API_KEY", "sk-x").doesNotContainKey("OPENAI_BASE_URL");
	}

	@Test
	void anEndpointPublishedUnderAPathKeepsThatPath() {
		// A gateway that gives every endpoint its own prefix, which is the shape that made the
		// single-variable mapping wrong: the prefix has to survive into OPENAI_HOST.
		ProviderSpec provider = new ProviderSpec("acme-ai", "openai",
				URI.create("https://gateway.example.com/team-x/openai"), "sk-x", Map.of());
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime.launch(settings().provider(provider).build());

		assertThat(spec.env()).containsEntry("OPENAI_HOST", "https://gateway.example.com/team-x/openai");
	}

	@Test
	void theRouteIsLeftToGooseSoAModelThatWantsTheResponsesApiGetsIt() {
		// Measured: from one prefixed OPENAI_HOST, goose sent gpt-5.6-terra to /v1/responses and
		// deepseek to /v1/chat/completions. Pinning OPENAI_BASE_PATH would take that choice away and
		// cost every reasoning model its reasoning items.
		ProviderSpec provider = new ProviderSpec("acme-ai", "openai",
				URI.create("https://gateway.example.com/team-x/openai"), "sk-x", Map.of());

		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime.launch(settings().provider(provider).build());

		assertThat(spec.env()).doesNotContainKey("OPENAI_BASE_PATH");
	}

	@Test
	void anApplicationThatMustPinTheDialectStillCan() {
		ProviderSpec provider = new ProviderSpec("acme-ai", "openai", URI.create("https://ai.example.com/v1"), "sk-x",
				Map.of());
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime.launch(settings().provider(provider)
				.runtimeOptions(Map.of("env.OPENAI_BASE_PATH", "v1/chat/completions")).build());

		assertThat(spec.env()).containsEntry("OPENAI_BASE_PATH", "v1/chat/completions");
	}

	@Test
	void anEndpointOfTheApplicationsOwnNamesItsProviderAndModelAtLaunch() {
		ProviderSpec provider = new ProviderSpec("acme-ai", "openai", URI.create("https://ai.example.com/v1"), "sk-x",
				Map.of());
		AgentSettings settings = settings().provider(provider).model("acme/llm-1").build();
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime.launch(settings);

		assertThat(spec.env()).containsEntry("GOOSE_PROVIDER", "openai").containsEntry("GOOSE_MODEL", "acme/llm-1");
		assertThat(runtime.appliedOutOfBand(PortableOption.MODEL, settings)).isTrue();
	}

	@Test
	void aVendorTheAgentAlreadyKnowsLeavesTheModelToTheWire() {
		// Nothing carried out of band, because the protocol can do it and report what happened.
		AgentSettings settings = settings().provider(new ProviderSpec("openai", "openai", null, "sk-x", Map.of()))
				.model("gpt-5.6").build();
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime.launch(settings);

		assertThat(spec.env()).doesNotContainKey("GOOSE_MODEL").doesNotContainKey("GOOSE_PROVIDER");
		assertThat(runtime.appliedOutOfBand(PortableOption.MODEL, settings)).isFalse();
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

	// --- the served transport ---------------------------------------------------------------

	private AgentLaunchSpec.WebSocket served(Map<String, Object> serve) {
		AgentSettings settings = AgentSettings.builder("goose", workspace)
				.runtimeOptions(Map.of("serve", serve)).build();
		return (AgentLaunchSpec.WebSocket) runtime.launch(settings);
	}

	@Test
	void stdioIsTheDefaultTransport() {
		assertThat(runtime.launch(AgentSettings.builder("goose", workspace).build()))
				.isInstanceOf(AgentLaunchSpec.Stdio.class);
	}

	@Test
	void theServedTransportStartsGooseServeAndPointsAtItsAcpEndpoint() {
		AgentLaunchSpec.WebSocket spec = served(Map.of("transport", "websocket", "port", "45231"));

		assertThat(spec.uri()).isEqualTo(URI.create("ws://127.0.0.1:45231/acp"));
		assertThat(spec.process().healthUri()).isEqualTo(URI.create("http://127.0.0.1:45231/health"));
		assertThat(spec.process().args()).containsExactly("serve", "--host", "127.0.0.1", "--port", "45231");
	}

	@Test
	void portZeroAsksTheOperatingSystemForOne() {
		AgentLaunchSpec.WebSocket spec = served(Map.of("transport", "websocket", "port", "0"));

		assertThat(spec.uri().getPort()).isGreaterThan(0);
	}

	/**
	 * The generated secret has to reach two places at once — the server's environment and this
	 * client's upgrade header — and the server refuses every connection if they disagree.
	 */
	@Test
	void theGeneratedSecretReachesBothTheServerAndTheHeader() {
		AgentLaunchSpec.WebSocket spec = served(Map.of("transport", "websocket"));

		String fromEnvironment = spec.process().env().get("GOOSE_SERVER__SECRET_KEY");
		assertThat(fromEnvironment).isNotBlank().hasSize(64);
		assertThat(spec.headers()).containsEntry("X-Secret-Key", fromEnvironment);
	}

	@Test
	void eachLaunchGetsItsOwnSecret() {
		assertThat(served(Map.of("transport", "websocket")).headers().get("X-Secret-Key"))
				.isNotEqualTo(served(Map.of("transport", "websocket")).headers().get("X-Secret-Key"));
	}

	@Test
	void builtinsAndTheHardeningEnvironmentSurviveTheSwitchOfTransport() {
		AgentSettings settings = AgentSettings.builder("goose", workspace)
				.runtimeOptions(Map.of("serve", Map.of("transport", "websocket"), "builtins", "developer")).build();

		AgentLaunchSpec.WebSocket spec = (AgentLaunchSpec.WebSocket) runtime.launch(settings);

		assertThat(spec.process().args()).contains("--with-builtin", "developer");
		assertThat(spec.process().env()).containsEntry("GOOSE_DISABLE_KEYRING", "1");
	}
}
