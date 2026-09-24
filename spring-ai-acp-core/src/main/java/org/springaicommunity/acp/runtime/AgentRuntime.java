package org.springaicommunity.acp.runtime;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springaicommunity.acp.config.AgentSettings;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * The seam between this library and one particular agent.
 *
 * <p>
 * Everything ACP standardizes is handled in the core and never reaches an implementation
 * of this interface. What remains is the provisioning ACP does not cover: how the agent
 * is started, what files it reads before it starts, the names it happens to use for the
 * options the protocol lets a client set, and the handful of places its wire output
 * carries vendor detail.
 *
 * <p>
 * Note what is <em>not</em> here. There is no hook for "apply the model", because model
 * selection is a protocol operation and the core performs it identically for every agent;
 * an adapter only says what the option is <em>called</em>. Every method that could tempt
 * an adapter into doing the core's job has been left out on purpose — an adapter that
 * grows one is a sign the core is missing something.
 *
 * <p>
 * An implementation that passes {@code AgentRuntimeContract} is swappable. That suite,
 * not this interface, is the real definition of the abstraction.
 */
public interface AgentRuntime {

	/** The value {@code spring.acp.runtime} selects this runtime by. */
	String id();

	/** Builds the command line, arguments and environment to start the agent with. */
	AgentLaunchSpec launch(AgentSettings settings);

	/**
	 * Writes whatever the agent reads from disk at startup — its own config file, an
	 * instructions file — before {@link #launch} is honored. Runs once per client, not
	 * once per session.
	 *
	 * <p>
	 * Anything written belongs under {@link AgentSettings#runtimeHome()}, never in the
	 * workspace.
	 */
	default void provision(AgentSettings settings) {
	}

	/**
	 * The {@code authMethods} id to send {@code authenticate} with before the first
	 * session, or empty to send nothing.
	 *
	 * <p>
	 * ACP lets an agent refuse {@code session/new} until the client has picked one of the
	 * auth methods it offered on {@code initialize}, and a credential on the process
	 * environment does not count as picking one: codex-acp 1.12 and 1.13 answer
	 * "Authentication required" with {@code OPENAI_API_KEY} set, and accept the same key
	 * once {@code authenticate} names {@code api-key}. Which method a configuration means
	 * is vendor knowledge, so the adapter names it and the core sends it — the same
	 * division as {@link #configIdsFor}. Return one only when the settings carry what
	 * that method needs; an agent already signed in some other way needs nothing.
	 */
	default Optional<String> authMethod(AgentSettings settings) {
		return Optional.empty();
	}

	/**
	 * Names the tool behind a permission request, when the agent makes that knowable.
	 *
	 * <p>
	 * ACP has no required field for this: {@code toolCall.title} is prose meant for
	 * humans, and agents that expose a stable identifier do so in their own
	 * {@code _meta}. A runtime that cannot answer returns empty, and an allowlist policy
	 * then rejects the request rather than guessing.
	 */
	default Optional<String> toolNameOf(AcpSchema.ToolCallUpdate toolCall) {
		return Optional.empty();
	}

	/**
	 * Where this agent writes its own log, if it writes one this client can find.
	 *
	 * <p>
	 * Exists because of a failure the protocol does not report at all. An agent that
	 * cannot connect to an MCP server answers {@code session/new} normally, sends no
	 * {@code session/update} and — measured against goose 1.51.0 on both of its
	 * transports — writes nothing to stdout or stderr either. It does write
	 * {@code Failed to load extension <name>} to a log file. Watching that file is the
	 * only way a client learns what the agent already knows.
	 *
	 * <p>
	 * A declaration, not an action: the core does the watching, exactly as it does the
	 * option setting that {@link #configIdsFor} only names. Return empty unless
	 * <em>this</em> client can know the path — an adapter that attaches to an agent
	 * somebody else started does not know what environment that process has, and guessing
	 * would watch the wrong file.
	 *
	 * @see #noticeOf(String)
	 */
	default Optional<java.nio.file.Path> logDirectory(AgentSettings settings) {
		return Optional.empty();
	}

	/**
	 * What one line of that log means to a client, if it means anything.
	 *
	 * <p>
	 * The file-based twin of {@link #toolNameOf}: a pure function over the agent's own
	 * output, reading vendor detail the protocol never standardized. Most lines mean
	 * nothing to a client and return empty; a client is not a log aggregator, and an
	 * adapter that reported everything would make the warnings that matter unfindable.
	 *
	 * <p>
	 * The line arrives already redacted, and what is returned is logged, so an
	 * implementation must not put anything back that a secret could hide in.
	 */
	default Optional<AgentNotice> noticeOf(String logLine) {
		return Optional.empty();
	}

	/**
	 * Workarounds this agent needs on its MCP traffic, applied by the loopback proxy.
	 *
	 * <p>
	 * For the interoperability bugs between one agent and some MCP servers that make the
	 * agent drop a server without a word. The same division of labour as
	 * {@link #noticeOf}: the adapter knows its agent's quirks, the core does the
	 * plumbing. While this returns anything, every HTTP MCP server is routed through the
	 * proxy — credentialed or not, since a filter cannot touch traffic the agent sends
	 * straight to the server — so an adapter should return filters only when the
	 * application has asked for them.
	 */
	default List<org.springaicommunity.acp.mcp.McpRequestFilter> mcpRequestFilters(AgentSettings settings) {
		return List.of();
	}

	/**
	 * The {@code session/set_config_option} ids this runtime uses for a portable option,
	 * most specific first, or empty if it has none.
	 *
	 * <p>
	 * Order is meaningful. Codex exposes two options in the mode family — {@code mode}
	 * for its approval policy and {@code collaboration_mode} for plan-versus-build — and
	 * the resolver walks the list looking for the one whose advertised values contain
	 * what was asked for.
	 */
	default List<String> configIdsFor(PortableOption option) {
		return List.of(option.defaultConfigId());
	}

	/**
	 * The {@code category} values to fall back on when no id matches, most specific
	 * first.
	 *
	 * <p>
	 * Categories are the portable half of the option vocabulary and ids are the vendor
	 * half, so an agent nobody wrote an adapter for is still reachable through this. It
	 * is a fallback rather than the primary key because agents do not always set it:
	 * goose returns its {@code provider} option with no category at all.
	 */
	default List<String> configCategoriesFor(PortableOption option) {
		return option.defaultCategory().map(List::of).orElseGet(List::of);
	}

	/**
	 * Whether this runtime already carried {@code option} to the agent outside the
	 * protocol — an environment variable set in {@link #launch}, a line written by
	 * {@link #provision}.
	 *
	 * <p>
	 * Consulted only after every protocol path has failed, and only to decide whether the
	 * request counts as unsupported. It is a declaration, not an action: the work
	 * happened at launch, because that is the last moment an environment variable can
	 * still be set.
	 */
	default boolean appliedOutOfBand(PortableOption option, AgentSettings settings) {
		return false;
	}

	/**
	 * The options that {@code spring.acp.*} can express and ACP may or may not be able to
	 * honor.
	 */
	enum PortableOption {

		MODEL("model", "model"), PROVIDER("provider", null), MODE("mode", "mode");

		private final String defaultConfigId;

		private final String defaultCategory;

		PortableOption(String defaultConfigId, String defaultCategory) {
			this.defaultConfigId = defaultConfigId;
			this.defaultCategory = defaultCategory;
		}

		public String defaultConfigId() {
			return defaultConfigId;
		}

		/**
		 * The ACP {@code category} this option corresponds to, where the protocol names
		 * one. There is no standard category for a provider, which is why that one is
		 * empty.
		 */
		public Optional<String> defaultCategory() {
			return Optional.ofNullable(defaultCategory);
		}

		/**
		 * The lower-case name used in property names, log lines and exception messages.
		 */
		public String propertyName() {
			return name().toLowerCase(Locale.ROOT);
		}

	}

}
