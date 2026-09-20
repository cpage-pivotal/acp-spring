package org.thought.acp.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thought.acp.config.OptionResolution.Mechanism;
import org.thought.acp.runtime.AgentLaunchSpec;
import org.thought.acp.runtime.AgentRuntime;
import org.thought.acp.runtime.AgentRuntime.PortableOption;
import org.thought.acp.session.AgentSession;
import org.thought.acp.session.SessionRegistry;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The negotiated tier: four mechanisms, three policies, and one agent that lies.
 */
class ConfigResolverTests {

	@TempDir
	Path workspace;

	private final AcpAsyncClient client = mock(AcpAsyncClient.class);

	private final AgentSession session = new SessionRegistry().resolve("s", name -> "sid-1");

	private AgentSettings settings() {
		return AgentSettings.builder("fake", workspace).build();
	}

	private AgentSettings settings(java.util.function.Consumer<AgentSettings.Builder> customizer) {
		AgentSettings.Builder builder = AgentSettings.builder("fake", workspace);
		customizer.accept(builder);
		return builder.build();
	}

	private void advertise(AcpSchema.SessionConfigOption... options) {
		session.advertised(new AdvertisedSessionConfig(List.of(options), null, null));
	}

	private void acceptsConfigOption() {
		when(client.setSessionConfigOption(any()))
				.thenReturn(Mono.just(new AcpSchema.SetSessionConfigOptionResponse(List.of())));
	}

	private SessionConfiguration resolve(AgentRuntime runtime, AgentSettings settings) {
		return resolve(runtime, settings, ModelCatalog.none());
	}

	private SessionConfiguration resolve(AgentRuntime runtime, AgentSettings settings, ModelCatalog catalog) {
		return new ConfigResolver(runtime, settings.onUnsupported(), catalog).apply(client, session, settings)
				.block(Duration.ofSeconds(5));
	}

	/** A provider pointed at an endpoint of the application's own. */
	private static ProviderSpec byo() {
		return new ProviderSpec("acme", "openai", URI.create("https://gateway.example.com/team-x/openai"), "k",
				Map.of());
	}

	private static ModelCatalog serving(String... models) {
		return provider -> Optional.of(List.of(models));
	}

	// --- the good path ----------------------------------------------------------------------

	@Test
	void setsAModelTheAgentAdvertises() {
		advertise(select("model", "model", "gpt-5.5", "gpt-5.5", "gpt-5.6"));
		acceptsConfigOption();

		SessionConfiguration resolved = resolve(new FakeRuntime(), settings(b -> b.model("gpt-5.6")));

		assertThat(resolved.of(PortableOption.MODEL).mechanism()).isEqualTo(Mechanism.CONFIG_OPTION);
		assertThat(resolved.applied(PortableOption.MODEL)).contains("gpt-5.6");
	}

	@Test
	void findsAnOptionByCategoryWhenTheIdIsVendorSpecific() {
		advertise(select("vendor_model_picker", "model", "a", "a", "b"));
		acceptsConfigOption();

		SessionConfiguration resolved = resolve(new FakeRuntime(), settings(b -> b.model("b")));

		assertThat(resolved.of(PortableOption.MODEL).detail())
				.isEqualTo("session/set_config_option vendor_model_picker");
	}

	@Test
	void walksSeveralCandidateIdsToFindTheOneHoldingTheValue() {
		// Codex's shape: two options in the mode family, and only the second has "plan".
		advertise(select("mode", "mode", "agent", "read-only", "agent"),
				select("collaboration_mode", "collaboration_mode", "default", "default", "plan"));
		acceptsConfigOption();

		AgentRuntime codexLike = new FakeRuntime() {
			@Override
			public List<String> configIdsFor(PortableOption option) {
				return option == PortableOption.MODE ? List.of("mode", "collaboration_mode")
						: super.configIdsFor(option);
			}
		};

		SessionConfiguration resolved = resolve(codexLike, settings(b -> b.mode("plan")));

		assertThat(resolved.of(PortableOption.MODE).detail())
				.isEqualTo("session/set_config_option collaboration_mode");
	}

	// --- the agent that lies ----------------------------------------------------------------

	@Test
	void refusesAValueTheAgentDoesNotOfferEvenThoughTheCallWouldSucceed() {
		// goose 1.51 stores an unknown model id without complaint and fails several seconds later,
		// inside the turn, where the provider's 404 arrives as agent prose. The request is never sent.
		advertise(select("model", "model", "gpt-5.5", "gpt-5.5"));
		acceptsConfigOption();

		SessionConfiguration resolved = resolve(new FakeRuntime(),
				settings(b -> b.model("no-such-model").onUnsupported(OnUnsupported.WARN)));

		assertThat(resolved.of(PortableOption.MODEL).mechanism()).isEqualTo(Mechanism.UNSUPPORTED);
		verify(client, never()).setSessionConfigOption(any());
	}

	@Test
	void theRefusalNamesValuesTheAgentDoesOffer() {
		advertise(select("model", "model", "gpt-5.5", "gpt-5.5", "gpt-5.6"));

		SessionConfiguration resolved = resolve(new FakeRuntime(), settings(b -> b.model("nope")));

		assertThat(resolved.of(PortableOption.MODEL).detail()).contains("gpt-5.5").contains("gpt-5.6");
	}

	// --- fallbacks --------------------------------------------------------------------------

	@Test
	void fallsBackToSetModeForAnAgentThatOnlyReturnsModes() {
		session.advertised(new AdvertisedSessionConfig(List.of(),
				new AcpSchema.SessionModeState("auto", List.of(new AcpSchema.SessionMode("plan", "Plan", null))),
				null));
		when(client.setSessionMode(any())).thenReturn(Mono.just(new AcpSchema.SetSessionModeResponse()));

		SessionConfiguration resolved = resolve(new FakeRuntime(), settings(b -> b.mode("plan")));

		assertThat(resolved.of(PortableOption.MODE).mechanism()).isEqualTo(Mechanism.SESSION_MODE);
		verify(client, never()).setSessionConfigOption(any());
	}

	@Test
	void fallsBackToSetModelForAnAgentThatOnlyReturnsModels() {
		session.advertised(new AdvertisedSessionConfig(List.of(), null,
				new AcpSchema.SessionModelState("a", List.of(new AcpSchema.ModelInfo("b", "Model B", null)))));
		when(client.setSessionModel(any())).thenReturn(Mono.just(new AcpSchema.SetSessionModelResponse()));

		SessionConfiguration resolved = resolve(new FakeRuntime(), settings(b -> b.model("Model B")));

		assertThat(resolved.of(PortableOption.MODEL).mechanism()).isEqualTo(Mechanism.SESSION_MODEL);
		assertThat(resolved.applied(PortableOption.MODEL)).contains("b");
	}

	@Test
	void usesProvidersSetWhenTheAgentAdvertisesTheCapability() {
		when(client.getAgentCapabilities()).thenReturn(NegotiatedCapabilities.fromAgent(
				new AcpSchema.AgentCapabilities(false, null, null, null, new AcpSchema.ProvidersCapabilities(), null)));
		when(client.setProvider(any())).thenReturn(Mono.just(new AcpSchema.SetProviderResponse()));

		ProviderSpec provider = new ProviderSpec("openai", "openai", URI.create("https://api.example.com/v1"), "k",
				Map.of());
		SessionConfiguration resolved = resolve(new FakeRuntime(), settings(b -> b.provider(provider)));

		assertThat(resolved.of(PortableOption.PROVIDER).mechanism()).isEqualTo(Mechanism.PROVIDERS_SET);
	}

	@Test
	void anAdapterThatCarriedTheOptionAtLaunchIsNotReportedUnsupported() {
		AgentRuntime envMapping = new FakeRuntime() {
			@Override
			public boolean appliedOutOfBand(PortableOption option, AgentSettings settings) {
				return option == PortableOption.PROVIDER;
			}
		};

		SessionConfiguration resolved = resolve(envMapping,
				settings(b -> b.provider("openai").onUnsupported(OnUnsupported.FAIL)));

		assertThat(resolved.of(PortableOption.PROVIDER).mechanism()).isEqualTo(Mechanism.OUT_OF_BAND);
	}

	// --- policy -----------------------------------------------------------------------------

	@Test
	void onUnsupportedFailThrows() {
		AgentSettings strict = settings(b -> b.model("nope").onUnsupported(OnUnsupported.FAIL));

		assertThatThrownBy(() -> resolve(new FakeRuntime(), strict))
				.isInstanceOf(UnsupportedAgentOptionException.class).hasMessageContaining("model='nope'");
	}

	@Test
	void onUnsupportedIgnoreStillRecordsWhatHappened() {
		SessionConfiguration resolved = resolve(new FakeRuntime(),
				settings(b -> b.model("nope").onUnsupported(OnUnsupported.IGNORE)));

		assertThat(resolved.of(PortableOption.MODEL).mechanism()).isEqualTo(Mechanism.UNSUPPORTED);
		assertThat(resolved.unsupported()).hasSize(1);
	}

	@Test
	void anOptionNobodyAskedForIsNeitherAppliedNorUnsupported() {
		SessionConfiguration resolved = resolve(new FakeRuntime(), settings());

		assertThat(resolved.of(PortableOption.MODEL).mechanism()).isEqualTo(Mechanism.NOT_REQUESTED);
		assertThat(resolved.unsupported()).isEmpty();
	}

	@Test
	void anRpcErrorFromTheAgentIsAnUnsupportedOptionRatherThanAFailedTurn() {
		advertise(select("model", "model", "a", "a", "b"));
		when(client.setSessionConfigOption(any())).thenReturn(Mono.error(new IllegalStateException("Invalid params")));

		SessionConfiguration resolved = resolve(new FakeRuntime(), settings(b -> b.model("b")));

		assertThat(resolved.of(PortableOption.MODEL).mechanism()).isEqualTo(Mechanism.UNSUPPORTED);
		assertThat(resolved.of(PortableOption.MODEL).detail()).contains("Invalid params");
	}

	@Test
	void aMechanismThatFailedDoesNotHideOneThatAlreadySucceeded() {
		// Measured against codex-acp 1.12: providers/set fails on an SDK field-name mismatch while
		// the adapter's own config file has been carrying that provider since launch. Reporting it
		// unsupported would fire on-unsupported=fail on a session that is talking to the right
		// provider.
		when(client.getAgentCapabilities()).thenReturn(NegotiatedCapabilities.fromAgent(
				new AcpSchema.AgentCapabilities(false, null, null, null, new AcpSchema.ProvidersCapabilities(), null)));
		when(client.setProvider(any())).thenReturn(Mono.error(new IllegalStateException("Invalid params")));
		AgentRuntime carriedAtLaunch = new FakeRuntime() {
			@Override
			public boolean appliedOutOfBand(PortableOption option, AgentSettings settings) {
				return option == PortableOption.PROVIDER;
			}
		};

		SessionConfiguration resolved = resolve(carriedAtLaunch, settings(b -> b.provider(byo())));

		assertThat(resolved.of(PortableOption.PROVIDER).mechanism()).isEqualTo(Mechanism.OUT_OF_BAND);
	}

	@Test
	void anAgentThatRejectsAnOptionNobodyCarriedIsStillUnsupported() {
		advertise(select("model", "model", "a", "a", "b"));
		when(client.setSessionConfigOption(any())).thenReturn(Mono.error(new IllegalStateException("Invalid params")));

		SessionConfiguration resolved = resolve(new FakeRuntime(), settings(b -> b.model("b")));

		assertThat(resolved.of(PortableOption.MODEL).mechanism()).isEqualTo(Mechanism.UNSUPPORTED);
	}

	@Test
	void theResolutionIsRecordedOnTheSessionForTheApplicationToRead() {
		advertise(select("model", "model", "a", "a", "b"));
		acceptsConfigOption();

		resolve(new FakeRuntime(), settings(b -> b.model("b")));

		assertThat(session.configuration().applied(PortableOption.MODEL)).contains("b");
	}

	// --- an endpoint of the application's own ------------------------------------------------

	@Test
	void sendsAModelTheAgentNeverAdvertisedWhenTheApplicationNamedTheEndpoint() {
		// The agent's list is its own built-in catalogue of vendor models. It cannot contain what a
		// private gateway serves, so it does not get a vote.
		advertise(select("model", "model", "gpt-4o", "gpt-4o", "gpt-4o-mini"));
		acceptsConfigOption();

		SessionConfiguration resolved = resolve(new FakeRuntime(),
				settings(b -> b.provider(byo()).model("acme/llm-1")), serving("acme/llm-1"));

		assertThat(resolved.of(PortableOption.MODEL).mechanism()).isEqualTo(Mechanism.ENDPOINT);
		assertThat(resolved.applied(PortableOption.MODEL)).contains("acme/llm-1");
	}

	@Test
	void sendsItEvenWhenTheEndpointPublishesNoListing() {
		// Plenty of gateways serve completions and nothing else. Trusting the application beats
		// refusing a model that is probably there.
		advertise(select("model", "model", "gpt-4o", "gpt-4o"));
		acceptsConfigOption();

		SessionConfiguration resolved = resolve(new FakeRuntime(),
				settings(b -> b.provider(byo()).model("acme/llm-1")), ModelCatalog.none());

		assertThat(resolved.of(PortableOption.MODEL).mechanism()).isEqualTo(Mechanism.ENDPOINT);
	}

	@Test
	void theEndpointsOwnListingIsWhatRefusesAModelInstead() {
		advertise(select("model", "model", "gpt-4o", "gpt-4o"));

		SessionConfiguration resolved = resolve(new FakeRuntime(),
				settings(b -> b.provider(byo()).model("acme/llm-2")), serving("acme/llm-1"));

		assertThat(resolved.of(PortableOption.MODEL).mechanism()).isEqualTo(Mechanism.UNSUPPORTED);
		// The message names what is really being served, not what the agent shipped knowing about.
		assertThat(resolved.of(PortableOption.MODEL).detail()).contains("acme/llm-1")
				.contains("https://gateway.example.com/team-x/openai/v1").doesNotContain("gpt-4o");
		verify(client, never()).setSessionConfigOption(any());
	}

	@Test
	void anEndpointThatRefusesAModelStillFailsUnderOnUnsupportedFail() {
		advertise(select("provider", "provider", "acme", "acme"), select("model", "model", "gpt-4o", "gpt-4o"));
		acceptsConfigOption();
		AgentSettings strict = settings(
				b -> b.provider(byo()).model("acme/llm-2").onUnsupported(OnUnsupported.FAIL));

		assertThatThrownBy(() -> resolve(new FakeRuntime(), strict, serving("acme/llm-1")))
				.isInstanceOf(UnsupportedAgentOptionException.class).hasMessageContaining("acme/llm-1");
	}

	@Test
	void theAdvertisedListStillDecidesWhenNoEndpointWasNamed() {
		// The guard the whole design rests on: against a vendor the agent knows, an unadvertised
		// model is still a typo, and still caught before a turn is spent on it.
		advertise(select("model", "model", "gpt-4o", "gpt-4o"));

		SessionConfiguration resolved = resolve(new FakeRuntime(),
				settings(b -> b.provider("openai").model("gpt-4-o")), serving("gpt-4-o"));

		assertThat(resolved.of(PortableOption.MODEL).mechanism()).isEqualTo(Mechanism.UNSUPPORTED);
	}

	@Test
	void anEndpointDoesNotLicenseInventingModesOrProviders() {
		advertise(select("mode", "mode", "build", "build"));

		SessionConfiguration resolved = resolve(new FakeRuntime(), settings(b -> b.provider(byo()).mode("plan")),
				ModelCatalog.none());

		assertThat(resolved.of(PortableOption.MODE).mechanism()).isEqualTo(Mechanism.UNSUPPORTED);
	}

	private static AcpSchema.SessionConfigSelect select(String id, String category, String current, String... values) {
		return new AcpSchema.SessionConfigSelect("select", id, id, null, category, current,
				java.util.Arrays.stream(values).map(v -> new AcpSchema.SessionConfigSelectOption(v, v)).toList(),
				null);
	}

	/** A runtime with no vendor behavior at all, so each test adds only the one thing it is about. */
	private static class FakeRuntime implements AgentRuntime {

		@Override
		public String id() {
			return "fake";
		}

		@Override
		public AgentLaunchSpec launch(AgentSettings settings) {
			return new AgentLaunchSpec.Stdio("true", List.of(), Map.of());
		}
	}
}
