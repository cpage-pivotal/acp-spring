package org.springaicommunity.acp.config;

import org.springaicommunity.acp.runtime.AgentRuntime.PortableOption;

/**
 * What became of one requested option: what was asked for, what the agent actually took, and which
 * of the negotiated tier's several mechanisms got it there.
 *
 * <p>Exposed through {@code AgentSession.configuration()} so an application can tell the difference
 * between "the model I asked for" and "the model this agent is really running", which
 * {@code on-unsupported: warn} otherwise hides in a log line.
 */
public record OptionResolution(PortableOption option, String requested, String applied, Mechanism mechanism,
		String detail) {

	public static OptionResolution notRequested(PortableOption option) {
		return new OptionResolution(option, null, null, Mechanism.NOT_REQUESTED, null);
	}

	public static OptionResolution applied(PortableOption option, String requested, String value,
			Mechanism mechanism, String detail) {
		return new OptionResolution(option, requested, value, mechanism, detail);
	}

	public static OptionResolution unsupported(PortableOption option, String requested, String detail) {
		return new OptionResolution(option, requested, null, Mechanism.UNSUPPORTED, detail);
	}

	public boolean isApplied() {
		return mechanism.applied();
	}

	/** How a requested option reached the agent, or why it did not. */
	public enum Mechanism {

		/** Nothing was asked for; the agent keeps its own default. */
		NOT_REQUESTED(false),

		/** {@code session/set_config_option} against an option the agent advertised. The good path. */
		CONFIG_OPTION(true),

		/**
		 * {@code session/set_config_option} with a value the agent never advertised, because the
		 * application named an endpoint of its own and the endpoint — not the agent's built-in
		 * catalogue — is what knows which models exist.
		 */
		ENDPOINT(true),

		/** {@code session/set_mode}, for an agent that returns {@code modes} but no mode config option. */
		SESSION_MODE(true),

		/** {@code session/set_model}, likewise for {@code models}. */
		SESSION_MODEL(true),

		/** {@code providers/set}, for an agent advertising the providers capability. */
		PROVIDERS_SET(true),

		/**
		 * The adapter already carried it outside the protocol — an environment variable set at launch,
		 * a line written into the agent's own config file. Applied, but unverifiable from here.
		 */
		OUT_OF_BAND(true),

		/** No mechanism could carry it. {@link OnUnsupported} decided what that cost. */
		UNSUPPORTED(false);

		private final boolean applied;

		Mechanism(boolean applied) {
			this.applied = applied;
		}

		public boolean applied() {
			return applied;
		}
	}
}
