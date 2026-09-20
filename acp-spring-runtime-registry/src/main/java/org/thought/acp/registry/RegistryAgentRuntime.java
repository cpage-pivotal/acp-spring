package org.thought.acp.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.thought.acp.config.AgentSettings;
import org.thought.acp.config.ProviderEnvironment;
import org.thought.acp.config.RuntimeOptions;
import org.thought.acp.runtime.AgentLaunchSpec;
import org.thought.acp.runtime.AgentRuntime;
import org.thought.acp.runtime.ToolNames;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * An {@link AgentRuntime} for an agent nobody wrote an adapter for.
 *
 * <p>This is what makes "any ACP agent" a claim rather than a roadmap item. Set
 * {@code spring.acp.runtime: gemini} with no Gemini adapter on the classpath and this resolves the
 * entry from the registry snapshot, obtains the agent the way the registry says to — an npm package
 * through {@code npx}, a Python one through {@code uvx}, or a verified archive for this platform —
 * and launches it with the arguments the registry says make it speak ACP.
 *
 * <p><strong>What it cannot do is the more useful half of the documentation.</strong> An adapter
 * exists to carry the things ACP does not standardize: what an agent calls its options, where it
 * reads its config file, which environment variable holds a key, how it buries a tool name. None of
 * that is in the registry, and none of it can be guessed. So this runtime provisions nothing,
 * writes no files, and reaches the negotiated tier only through the portable half of the option
 * vocabulary — the ACP {@code category} on an advertised config option, plus the obvious ids
 * {@code model}, {@code mode} and {@code provider}. An agent that names its options anything else
 * reports them unsupported, honestly, through the same {@code on-unsupported} an adapter would.
 *
 * <p>Tier 3 still works, because tier 3 is the escape hatch for exactly this:
 *
 * <pre>{@code
 * spring:
 *   acp:
 *     runtime: gemini
 *     runtimes:
 *       gemini:
 *         args: [--yolo]                    # appended to what the registry supplies
 *         env: { GEMINI_API_KEY: "${KEY}" }
 * }</pre>
 */
public class RegistryAgentRuntime implements AgentRuntime {

	private final RegistryEntry entry;

	private final AgentInstaller installer;

	private final Platform platform;

	public RegistryAgentRuntime(RegistryEntry entry, AgentInstaller installer) {
		this(entry, installer, Platform.current());
	}

	RegistryAgentRuntime(RegistryEntry entry, AgentInstaller installer, Platform platform) {
		this.entry = entry;
		this.installer = installer;
		this.platform = platform;
	}

	@Override
	public String id() {
		return entry.id();
	}

	/** What the registry says this agent is, for a log line that names a version. */
	public RegistryEntry entry() {
		return entry;
	}

	@Override
	public AgentLaunchSpec launch(AgentSettings settings) {
		return switch (entry.distribution()) {
			case RegistryEntry.Distribution.Binary binary -> launchBinary(binary, settings);
			case RegistryEntry.Distribution.Npx npx ->
				stdio("npx", prepend(List.of("-y", npx.packageSpec()), npx.args(), settings), npx.env(), settings);
			case RegistryEntry.Distribution.Uvx uvx ->
				stdio("uvx", prepend(List.of(uvx.packageSpec()), uvx.args(), settings), uvx.env(), settings);
		};
	}

	private AgentLaunchSpec launchBinary(RegistryEntry.Distribution.Binary binary, AgentSettings settings) {
		RegistryEntry.Artifact artifact = binary.forPlatform(platform)
				.orElseThrow(() -> new AgentInstaller.AgentInstallException("The ACP registry publishes no build of '"
						+ entry.id() + "' for " + platform.id() + "; it has "
						+ binary.artifacts().keySet().stream().sorted().toList()));
		java.nio.file.Path command = installer.install(entry, artifact);
		return stdio(command.toString(), prepend(List.of(), artifact.args(), settings), artifact.env(), settings);
	}

	/**
	 * The registry's own arguments first, then the application's.
	 *
	 * <p>The registry's are what make the agent speak ACP at all — {@code acp}, {@code --acp} — so
	 * they are not the application's to reorder, and tier 3 appends rather than replaces.
	 */
	private static List<String> prepend(List<String> leading, List<String> registryArgs, AgentSettings settings) {
		List<String> args = new ArrayList<>(leading);
		args.addAll(registryArgs);
		args.addAll(settings.runtimeOptions().textList("args"));
		return args;
	}

	private AgentLaunchSpec stdio(String command, List<String> args, Map<String, String> registryEnv,
			AgentSettings settings) {
		return new AgentLaunchSpec.Stdio(command, args, environment(registryEnv, settings));
	}

	/**
	 * The environment the agent starts with.
	 *
	 * <p>Same ordering rule as every adapter: the registry's own defaults first, then the provider's
	 * credentials under the derived variable names, then the tier-3 {@code env} block, which
	 * therefore wins over both. That last one is how an application reaches an agent whose key lives
	 * under a name nothing here could have guessed.
	 */
	private static Map<String, String> environment(Map<String, String> registryEnv, AgentSettings settings) {
		Map<String, String> env = new LinkedHashMap<>(registryEnv);
		env.putAll(ProviderEnvironment.of(settings.provider()));
		RuntimeOptions options = settings.runtimeOptions();
		env.putAll(options.textSection("env"));
		return env;
	}

	/**
	 * Only an identifier the agent actually published, never the title.
	 *
	 * <p>An adapter may fall back to the title because its author knows that agent's titles are
	 * stable tool names. Nothing here knows that about an agent it has never seen, and a wrong
	 * answer would turn an allowlist into an approval.
	 */
	@Override
	public Optional<String> toolNameOf(AcpSchema.ToolCallUpdate toolCall) {
		return ToolNames.fromRawInput(toolCall);
	}

	/**
	 * Whether the provider was carried outside the protocol after all.
	 *
	 * <p>An agent with no {@code provider} config option and no {@code providers/set} still gets the
	 * key, because it went onto the process environment at launch. Saying so is what stops
	 * {@code on-unsupported: fail} firing on a configuration that is working.
	 */
	@Override
	public boolean appliedOutOfBand(PortableOption option, AgentSettings settings) {
		return option == PortableOption.PROVIDER && !ProviderEnvironment.of(settings.provider()).isEmpty();
	}
}
