package org.tanzu.acp.runtime;

import java.util.List;
import java.util.Optional;

import org.tanzu.acp.config.AgentSettings;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * The seam between this library and one particular agent.
 *
 * <p>Everything ACP standardizes is handled in the core and never reaches an implementation of this
 * interface. What remains is the provisioning ACP does not cover: how the agent is started, what
 * files it reads before it starts, and the handful of places its wire output carries vendor detail.
 *
 * <p>An implementation that passes the runtime conformance suite is swappable. That suite, not this
 * interface, is the real definition of the abstraction.
 */
public interface AgentRuntime {

	/** The value {@code spring.acp.runtime} selects this runtime by. */
	String id();

	/** Builds the command line, arguments and environment to start the agent with. */
	AgentLaunchSpec launch(AgentSettings settings);

	/**
	 * Writes whatever the agent reads from disk at startup — its own config file, an instructions
	 * file — before {@link #launch} is honored. Runs once per client, not once per session.
	 */
	default void provision(AgentSettings settings) {
	}

	/**
	 * Names the tool behind a permission request, when the agent makes that knowable.
	 *
	 * <p>ACP has no required field for this: {@code toolCall.title} is prose meant for humans, and
	 * agents that expose a stable identifier do so in their own {@code _meta}. A runtime that cannot
	 * answer returns empty, and an allowlist policy then rejects the request rather than guessing.
	 */
	default Optional<String> toolNameOf(AcpSchema.ToolCallUpdate toolCall) {
		return Optional.empty();
	}

	/**
	 * The {@code session/set_config_option} ids this runtime uses for the portable options, most
	 * specific first. The resolver tries them in order.
	 */
	default List<String> configIdsFor(PortableOption option) {
		return List.of(option.defaultConfigId());
	}

	/** The options that {@code spring.acp.*} can express and ACP may or may not be able to honor. */
	enum PortableOption {

		MODEL("model"), PROVIDER("provider"), MODE("mode");

		private final String defaultConfigId;

		PortableOption(String defaultConfigId) {
			this.defaultConfigId = defaultConfigId;
		}

		public String defaultConfigId() {
			return defaultConfigId;
		}
	}
}
