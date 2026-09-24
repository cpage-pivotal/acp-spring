package org.springaicommunity.acp.codex;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.acp.config.AgentSettings;
import org.springaicommunity.acp.config.ProviderSpec;
import org.springaicommunity.acp.runtime.AgentLaunchSpec;
import org.springaicommunity.acp.runtime.AgentRuntime.PortableOption;

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
		// Codex keeps auth.json beside config.toml, so relocating the home uninvited
		// would cost a
		// developer machine its `codex login`.
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime.launch(settings().build());

		assertThat(spec.env()).doesNotContainKey(CodexRuntime.HOME_ENV);
	}

	@Test
	void askingForCodexConfigMovesTheHomeAndWritesTheFile() throws Exception {
		AgentSettings settings = settings()
			.runtimeOptions(Map.of("config-toml", Map.of("model_reasoning_effort", "high")))
			.build();

		runtime.provision(settings);
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime.launch(settings);

		assertThat(spec.env()).containsEntry(CodexRuntime.HOME_ENV, home.toString());
		assertThat(Files.readString(home.resolve("config.toml"))).isEqualTo("model_reasoning_effort = \"high\"\n");
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

		assertThat(spec.env()).containsEntry("OPENAI_API_KEY", "sk-x")
			.containsEntry("OPENAI_BASE_URL", "https://ai.example.com/v1");
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
	void anEndpointOfTheApplicationsOwnBecomesAModelProviderCodexCanUse() throws Exception {
		// Codex has no environment variable for "talk to this URL instead": a provider it
		// was not
		// built knowing about exists only as a table in config.toml, and so does the
		// model it serves.
		ProviderSpec provider = new ProviderSpec("acme", "openai",
				URI.create("https://gateway.example.com/team-x/openai"), "sk-x", Map.of());
		AgentSettings settings = settings().provider(provider).model("acme/llm-1").build();

		runtime.provision(settings);
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime.launch(settings);

		assertThat(spec.env()).containsEntry(CodexRuntime.HOME_ENV, home.toString())
			.containsEntry("OPENAI_API_KEY", "sk-x");
		assertThat(Files.readString(home.resolve("config.toml"))).contains("model = \"acme/llm-1\"")
			.contains("model_provider = \"acme\"")
			.contains("[model_providers.acme]")
			.contains("base_url = \"https://gateway.example.com/team-x/openai/v1\"")
			// The key stays in the environment; the file only names the variable.
			.contains("env_key = \"OPENAI_API_KEY\"")
			// codex-acp 1.12+ fails every session/new on `wire_api = "chat"`, and
			// `responses` is
			// its default, so naming the dialect at all is a promise to break when it
			// moves again.
			.doesNotContain("wire_api");
		assertThat(runtime.appliedOutOfBand(PortableOption.MODEL, settings)).isTrue();
	}

	@Test
	void aVendorCodexAlreadyKnowsGetsNoModelProviderTable() throws Exception {
		AgentSettings settings = settings().provider(new ProviderSpec("openai", "openai", null, "sk-x", Map.of()))
			.model("gpt-5.6")
			.build();

		runtime.provision(settings);

		assertThat(Files.readString(home.resolve("config.toml"))).doesNotContain("model_provider")
			.doesNotContain("model =");
		assertThat(runtime.appliedOutOfBand(PortableOption.MODEL, settings)).isFalse();
	}

	@Test
	void anOpenAiKeySignsCodexInThroughTheApiKeyMethod() {
		// codex-acp 1.12 ignores OPENAI_API_KEY on its environment until authenticate
		// names api-key.
		AgentSettings settings = settings().provider(new ProviderSpec("openai", "openai", null, "sk-x", Map.of()))
			.build();

		assertThat(runtime.authMethod(settings)).contains(CodexRuntime.API_KEY_METHOD);
		assertThat(((AgentLaunchSpec.Stdio) runtime.launch(settings)).env()).containsEntry("OPENAI_API_KEY", "sk-x");
	}

	@Test
	void signingInWithAKeyKeepsItOffTheDiskAndOutOfTheUsersOwnHome() throws Exception {
		// authenticate would otherwise write auth.json into CODEX_HOME — which, left
		// ambient, is the
		// user's ~/.codex and their own login.
		AgentSettings settings = settings().provider(new ProviderSpec("openai", "openai", null, "sk-x", Map.of()))
			.build();

		runtime.provision(settings);
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime.launch(settings);

		assertThat(spec.env()).containsEntry(CodexRuntime.HOME_ENV, home.toString());
		assertThat(Files.readString(home.resolve("config.toml")))
			.isEqualTo("cli_auth_credentials_store = \"ephemeral\"\n");
		// The configured key is the credential; a developer's codex login must not stand
		// in for it.
		assertThat(home.resolve("auth.json")).doesNotExist();
	}

	@Test
	void aLinkToTheUsersLoginLeftByAnEarlierRunIsRemovedOnceAKeyIsConfigured() throws Exception {
		Path login = Files.writeString(workspace.resolve("somebody-elses-auth.json"), "{}");
		Files.createSymbolicLink(home.resolve("auth.json"), login);

		runtime.provision(settings().provider(new ProviderSpec("openai", "openai", null, "sk-x", Map.of())).build());

		assertThat(home.resolve("auth.json")).doesNotExist();
		assertThat(login).exists();
	}

	@Test
	void noKeyOrAnEndpointOfTheApplicationsOwnNeedsNoSignIn() {
		// A model_providers table carries its own env_key, and codex-acp opens sessions
		// against it
		// without authenticate.
		ProviderSpec byo = new ProviderSpec("acme", "openai", URI.create("https://ai.example.com/v1"), "sk-x",
				Map.of());

		assertThat(runtime.authMethod(settings().build())).isEmpty();
		assertThat(runtime.authMethod(settings().provider("openai").build())).isEmpty();
		assertThat(runtime.authMethod(settings().provider(byo).build())).isEmpty();
	}

	@Test
	void theApplicationsOwnCodexConfigStillWins() throws Exception {
		ProviderSpec provider = new ProviderSpec("acme", "openai", URI.create("https://ai.example.com/v1"), "sk-x",
				Map.of());
		AgentSettings settings = settings().provider(provider)
			.model("acme/llm-1")
			.runtimeOptions(Map.of("config-toml", Map.of("model", "something-else")))
			.build();

		runtime.provision(settings);

		assertThat(Files.readString(home.resolve("config.toml"))).contains("model = \"something-else\"")
			.doesNotContain("model = \"acme/llm-1\"")
			.contains("[model_providers.acme]");
	}

	@Test
	void extraArgumentsAreAppendedRatherThanReplacingTheAdapter() {
		AgentLaunchSpec.Stdio spec = (AgentLaunchSpec.Stdio) runtime
			.launch(settings().runtimeOptions(Map.of("args", List.of("--verbose"))).build());

		assertThat(spec.args()).containsExactly("-y", CodexRuntime.DEFAULT_PACKAGE, "--verbose");
	}

}
