package org.springaicommunity.acp.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.acp.config.OptionResolution.Mechanism;
import org.springaicommunity.acp.runtime.AgentRuntime;
import org.springaicommunity.acp.runtime.AgentRuntime.PortableOption;
import org.springaicommunity.acp.session.AgentSession;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.publisher.Mono;

/**
 * Applies the negotiated tier — {@code model}, {@code provider}, {@code mode} — to a new session.
 *
 * <p>These are requests, not assignments. ACP standardizes the operations that set them but not the
 * option ids or the values, both of which each agent declares for itself, so a portable
 * {@code model: gpt-5.4-mini} cannot simply be pushed. What happens when an agent cannot honor one
 * is {@link OnUnsupported}'s decision, not this class's.
 *
 * <p><strong>Why it reads before it writes.</strong> The tempting design is to set optimistically
 * and treat an error as "unsupported". Measured against three live agents, that is wrong: goose 1.51
 * <em>accepts</em> a model id it has never heard of, stores it, and fails several seconds later
 * inside the turn, where the provider's 404 arrives as agent prose and reads like a bug in this
 * library. An option that cannot be honored has to be detected before the session is used, which
 * means reading what the agent advertised — see {@link AdvertisedSessionConfig} for how that is
 * recovered, since the SDK's {@code NewSessionResponse} drops it.
 *
 * <p>Four mechanisms are tried in order of how much the protocol guarantees about them:
 *
 * <p><strong>Where the advertised list is not the authority.</strong> Reading before writing assumes
 * the agent knows what exists, which stops being true the moment an application points it at an
 * endpoint of its own: the list Goose 1.51 advertises is its own built-in catalogue of vendor models,
 * and a private gateway's models are not in it. Worse, it is not even stable — Goose repopulates that
 * list from the endpoint <em>after</em> the provider is set, so of three sessions opened seconds
 * apart against one process, the third accepted a model the first two had refused. So when
 * {@link ProviderSpec#isByo()}, the model is checked against {@link ModelCatalog} — the endpoint's own
 * listing — and sent whether or not the agent advertised it. What the agent then does with it is
 * reported honestly; what is no longer done is refusing a model that demonstrably exists.
 *
 * <ol>
 * <li>{@code session/set_config_option} against an advertised option — the modern path, and the only
 * one all three first-party runtimes support;</li>
 * <li>{@code session/set_mode} and {@code session/set_model}, for an agent still returning the
 * older {@code modes} and {@code models} states;</li>
 * <li>{@code providers/set}, for the provider alone, when the agent advertises that capability;</li>
 * <li>the adapter's own out-of-band mapping, which already happened at launch.</li>
 * </ol>
 */
public final class ConfigResolver {

	private static final Logger logger = LoggerFactory.getLogger(ConfigResolver.class);

	private final AgentRuntime runtime;

	private final OnUnsupported onUnsupported;

	private final ModelCatalog catalog;

	/** One warning per option per runtime, not one per session. */
	private final Set<String> warned = ConcurrentHashMap.newKeySet();

	public ConfigResolver(AgentRuntime runtime, OnUnsupported onUnsupported) {
		this(runtime, onUnsupported, ModelCatalog.endpoint());
	}

	public ConfigResolver(AgentRuntime runtime, OnUnsupported onUnsupported, ModelCatalog catalog) {
		this.runtime = runtime;
		this.onUnsupported = onUnsupported == null ? OnUnsupported.WARN : onUnsupported;
		this.catalog = catalog == null ? ModelCatalog.none() : catalog;
	}

	/**
	 * Applies every requested option to {@code session} and records what happened.
	 *
	 * <p>Provider first: on an agent that scopes its model list by provider, asking for a model
	 * before the provider is set would be matched against the wrong list.
	 */
	public Mono<SessionConfiguration> apply(AcpAsyncClient client, AgentSession session, AgentSettings settings) {
		List<OptionResolution> resolutions = new ArrayList<>();
		return resolve(client, session, settings, PortableOption.PROVIDER, settings.provider().id())
				.doOnNext(resolutions::add)
				.then(Mono.defer(() -> resolve(client, session, settings, PortableOption.MODEL, settings.model())))
				.doOnNext(resolutions::add)
				.then(Mono.defer(() -> resolve(client, session, settings, PortableOption.MODE, settings.mode())))
				.doOnNext(resolutions::add).then(Mono.fromCallable(() -> {
					SessionConfiguration configuration = SessionConfiguration.from(resolutions);
					session.configuration(configuration);
					return configuration;
				}));
	}

	private Mono<OptionResolution> resolve(AcpAsyncClient client, AgentSession session, AgentSettings settings,
			PortableOption option, String value) {
		if (value == null || value.isBlank()) {
			return Mono.just(OptionResolution.notRequested(option));
		}
		Optional<String> refusal = endpointRefusal(settings, option, value);
		if (refusal.isPresent()) {
			return unsupported(option, value, refusal.get(), null);
		}
		return viaConfigOption(client, session, settings, option, value)
				.switchIfEmpty(Mono.defer(() -> viaLegacyState(client, session, option, value)))
				.switchIfEmpty(Mono.defer(() -> viaProviders(client, settings, option, value)))
				.switchIfEmpty(Mono.defer(() -> viaRuntime(settings, option, value)))
				.switchIfEmpty(Mono.defer(() -> unsupported(option, value, noMechanism(session), null)))
				// A mechanism that failed must not hide one that already succeeded: Codex rejects
				// providers/set over an SDK field-name mismatch, while its config.toml has been
				// carrying that same provider since launch.
				.onErrorResume(error -> error instanceof UnsupportedAgentOptionException ? Mono.error(error)
						: viaRuntime(settings, option, value).switchIfEmpty(Mono.defer(() -> unsupported(option,
								value, "the agent rejected it: " + rootMessage(error), error))));
	}

	/**
	 * The good path. Walks the adapter's config ids, then the portable categories, and takes the
	 * first advertised option that actually offers the requested value.
	 *
	 * <p>An option that exists but does not offer the value is the interesting case: it means the
	 * agent understands the question and the answer is no. That ends resolution here rather than
	 * falling through to a weaker mechanism, and the message names what it does offer.
	 */
	private Mono<OptionResolution> viaConfigOption(AcpAsyncClient client, AgentSession session,
			AgentSettings settings, PortableOption option, String value) {
		AdvertisedSessionConfig advertised = session.advertised();
		String qualifier = option == PortableOption.MODEL ? settings.provider().id() : null;

		List<String> ids = runtime.configIdsFor(option);
		List<String> categories = runtime.configCategoriesFor(option);
		AcpSchema.SessionConfigSelect present = null;

		for (String key : concat(ids, categories)) {
			Optional<AcpSchema.SessionConfigSelect> candidate = advertised.select(key, key);
			if (candidate.isEmpty()) {
				continue;
			}
			present = candidate.get();
			Optional<String> matched = SelectMatcher.match(present, value, qualifier);
			if (matched.isPresent()) {
				return set(client, session, option, value, present.id(), matched.get(), Mechanism.CONFIG_OPTION);
			}
		}

		if (present == null) {
			return Mono.empty();
		}
		if (sendsUnadvertised(settings, option)) {
			// The option exists, the value is not in its list, and the list is not the authority here.
			return set(client, session, option, value, present.id(), value, Mechanism.ENDPOINT);
		}
		return unsupported(option, value, "the agent's '" + present.id() + "' option offers "
				+ SelectMatcher.examples(present), null);
	}

	/**
	 * Whether a value the agent did not advertise should be sent regardless.
	 *
	 * <p>Only the model, and only for an endpoint the application named. Mode and provider are the
	 * agent's own vocabulary, which it does know the whole of; inventing values for those would be
	 * guessing rather than deferring to something better informed.
	 */
	private static boolean sendsUnadvertised(AgentSettings settings, PortableOption option) {
		return option == PortableOption.MODEL && settings.provider().isByo();
	}

	/**
	 * Why the endpoint says this model is wrong, when it is in a position to say so.
	 *
	 * <p>This is the check that replaces the advertised list for a bring-your-own endpoint, and it is
	 * a better one: it names the models actually being served rather than the ones the agent shipped
	 * knowing about, and it fires before a turn is spent instead of arriving as agent prose inside
	 * one. An endpoint that publishes no listing refuses nothing.
	 */
	private Optional<String> endpointRefusal(AgentSettings settings, PortableOption option, String value) {
		if (option != PortableOption.MODEL || !settings.provider().isByo()) {
			return Optional.empty();
		}
		return catalog.modelsOf(settings.provider())
				.filter(models -> models.stream().noneMatch(model -> model.equalsIgnoreCase(value)))
				.map(models -> "the endpoint at " + settings.provider().findApiBase().orElseThrow() + " serves "
						+ String.join(", ", models));
	}

	private Mono<OptionResolution> set(AcpAsyncClient client, AgentSession session, PortableOption option,
			String requested, String configId, String value, Mechanism mechanism) {
		return client
				.setSessionConfigOption(
						new AcpSchema.SetSessionConfigOptionRequest(session.sessionId(), configId, value, null, null))
				.map(response -> {
					session.advertised(session.advertised().withConfigOptions(response.configOptions()));
					logger.debug("Set {}='{}' on session {} via config option '{}'{}", option.propertyName(), value,
							session.sessionId(), configId,
							mechanism == Mechanism.ENDPOINT ? " (not advertised; the endpoint serves it)" : "");
					return OptionResolution.applied(option, requested, value, mechanism,
							"session/set_config_option " + configId
									+ (mechanism == Mechanism.ENDPOINT ? " (not advertised by the agent)" : ""));
				});
	}

	/**
	 * {@code session/set_mode} and {@code session/set_model}, for an agent that returns the older
	 * {@code modes} or {@code models} state and no config option for it. Codex returns both shapes;
	 * an agent written before {@code configOptions} existed returns only this one.
	 */
	private Mono<OptionResolution> viaLegacyState(AcpAsyncClient client, AgentSession session, PortableOption option,
			String value) {
		AdvertisedSessionConfig advertised = session.advertised();
		return switch (option) {
			case MODE -> advertised.findModes()
					.flatMap(state -> state.availableModes().stream()
							.filter(m -> matches(value, m.id(), m.name())).findFirst())
					.map(mode -> client.setSessionMode(new AcpSchema.SetSessionModeRequest(session.sessionId(),
							mode.id()))
							.thenReturn(OptionResolution.applied(option, value, mode.id(), Mechanism.SESSION_MODE,
									"session/set_mode")))
					.orElseGet(Mono::empty);
			case MODEL -> advertised.findModels()
					.flatMap(state -> state.availableModels().stream()
							.filter(m -> matches(value, m.modelId(), m.name())).findFirst())
					.map(model -> client
							.setSessionModel(new AcpSchema.SetSessionModelRequest(session.sessionId(), model.modelId()))
							.thenReturn(OptionResolution.applied(option, value, model.modelId(),
									Mechanism.SESSION_MODEL, "session/set_model")))
					.orElseGet(Mono::empty);
			case PROVIDER -> Mono.empty();
		};
	}

	/**
	 * {@code providers/set}, gated on the agent advertising the capability.
	 *
	 * <p>{@code providers/list} is deliberately not consulted first. It would only confirm that the id
	 * exists, and acp-core 0.17.0 cannot read the id it comes back as — Codex, the one runtime that
	 * implements the method, returns {@code providerId} where the SDK's record expects {@code id}, so
	 * every entry parses with a null name. Setting and reading the error is both simpler and, here,
	 * the only thing that works.
	 */
	private Mono<OptionResolution> viaProviders(AcpAsyncClient client, AgentSettings settings, PortableOption option,
			String value) {
		if (option != PortableOption.PROVIDER || !supportsProviders(client)) {
			return Mono.empty();
		}
		ProviderSpec provider = settings.provider();
		return client
				.setProvider(new AcpSchema.SetProviderRequest(value, provider.apiType(),
						provider.findBaseUrl().map(java.net.URI::toString).orElse(null), provider.headers()))
				.thenReturn(OptionResolution.applied(option, value, value, Mechanism.PROVIDERS_SET, "providers/set"));
	}

	private boolean supportsProviders(AcpAsyncClient client) {
		try {
			return client.getAgentCapabilities() != null && client.getAgentCapabilities().supportsProviders();
		}
		catch (RuntimeException ex) {
			// Capabilities are only known after initialize(); a client that is not there yet has none.
			return false;
		}
	}

	private Mono<OptionResolution> viaRuntime(AgentSettings settings, PortableOption option, String value) {
		if (!runtime.appliedOutOfBand(option, settings)) {
			return Mono.empty();
		}
		logger.debug("Runtime '{}' carried {}='{}' outside the protocol", runtime.id(), option.propertyName(), value);
		return Mono.just(OptionResolution.applied(option, value, value, Mechanism.OUT_OF_BAND,
				"applied by the " + runtime.id() + " adapter at launch"));
	}

	private static String noMechanism(AgentSession session) {
		return session.advertised().isEmpty() ? "the agent advertised no configurable options"
				: "the agent has no option for it";
	}

	private Mono<OptionResolution> unsupported(PortableOption option, String value, String detail, Throwable cause) {
		return switch (onUnsupported) {
			case FAIL -> Mono
					.error(new UnsupportedAgentOptionException(option.propertyName(), value, runtime.id(), detail,
							cause));
			case WARN -> Mono.fromCallable(() -> {
				warnOnce(option, value, detail);
				return OptionResolution.unsupported(option, value, detail);
			});
			case IGNORE -> Mono.just(OptionResolution.unsupported(option, value, detail));
		};
	}

	private void warnOnce(PortableOption option, String value, String detail) {
		if (warned.add(runtime.id() + '/' + option)) {
			logger.warn("Runtime '{}' cannot honor {}='{}' ({}); continuing with the agent's own default. "
					+ "Set spring.acp.on-unsupported=fail to make this an error.", runtime.id(),
					option.propertyName(), value, detail);
		}
	}

	private static boolean matches(String request, String id, String name) {
		return request.equalsIgnoreCase(id) || request.equalsIgnoreCase(name);
	}

	private static List<String> concat(List<String> first, List<String> second) {
		List<String> all = new ArrayList<>(first);
		second.stream().filter(s -> !all.contains(s)).forEach(all::add);
		return all;
	}

	private static String rootMessage(Throwable error) {
		Throwable cause = error;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
	}
}
