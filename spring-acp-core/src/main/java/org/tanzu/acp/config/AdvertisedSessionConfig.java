package org.tanzu.acp.config;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * What the agent said about itself when it created the session: the config options it will accept,
 * and the legacy {@code modes} and {@code models} states.
 *
 * <p>Reading this before setting anything is what makes the negotiated tier honest. Measured against
 * three live runtimes, an agent asked to set a value it does not have does one of two things: reject
 * the call (OpenCode, Codex) or <em>accept it and fail later</em> — goose 1.51 stores an unknown
 * model id without complaint and the resulting 404 surfaces mid-turn as agent prose. So "set it and
 * see" cannot implement {@code on-unsupported}: the answer has to come from the advertised options.
 *
 * <p>{@code modes} and {@code models} are the pre-{@code configOptions} way of saying the same
 * thing. The spec's transition guidance has agents return both, and they do, so this is consulted
 * only when no matching config option exists — OpenCode, for instance, returns neither.
 */
public record AdvertisedSessionConfig(List<AcpSchema.SessionConfigOption> configOptions,
		AcpSchema.SessionModeState modes, AcpSchema.SessionModelState models) {

	private static final AdvertisedSessionConfig EMPTY = new AdvertisedSessionConfig(List.of(), null, null);

	public AdvertisedSessionConfig {
		configOptions = configOptions == null ? List.of() : List.copyOf(configOptions);
	}

	/** Nothing advertised: an agent that returned no options and no mode or model state. */
	public static AdvertisedSessionConfig empty() {
		return EMPTY;
	}

	/**
	 * The select option matching {@code id}, or failing that {@code category}.
	 *
	 * <p>Both are needed. Goose returns its {@code provider} option with a null category, and Codex
	 * returns two options in the {@code mode} family under different ids, so neither key alone finds
	 * everything.
	 */
	public Optional<AcpSchema.SessionConfigSelect> select(String id, String category) {
		Optional<AcpSchema.SessionConfigSelect> byId = selects().filter(o -> equalsIgnoreCase(o.id(), id)).findFirst();
		return byId.isPresent() ? byId
				: selects().filter(o -> equalsIgnoreCase(o.category(), category)).findFirst();
	}

	/** Every select option the agent advertised, in the order it advertised them. */
	public java.util.stream.Stream<AcpSchema.SessionConfigSelect> selects() {
		return configOptions.stream().filter(AcpSchema.SessionConfigSelect.class::isInstance)
				.map(AcpSchema.SessionConfigSelect.class::cast);
	}

	public Optional<AcpSchema.SessionModeState> findModes() {
		return Optional.ofNullable(modes).filter(m -> m.availableModes() != null && !m.availableModes().isEmpty());
	}

	public Optional<AcpSchema.SessionModelState> findModels() {
		return Optional.ofNullable(models).filter(m -> m.availableModels() != null && !m.availableModels().isEmpty());
	}

	public boolean isEmpty() {
		return configOptions.isEmpty() && findModes().isEmpty() && findModels().isEmpty();
	}

	/**
	 * This config with the option set an agent returned from a {@code session/set_config_option}.
	 *
	 * <p>Not cosmetic: on an agent that scopes its model list by provider, setting the provider
	 * changes which models are legal, so a model resolved against the pre-provider list would be
	 * resolved against the wrong one.
	 */
	public AdvertisedSessionConfig withConfigOptions(List<AcpSchema.SessionConfigOption> options) {
		return options == null || options.isEmpty() ? this : new AdvertisedSessionConfig(options, modes, models);
	}

	private static boolean equalsIgnoreCase(String a, String b) {
		return a != null && b != null && a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
	}
}
