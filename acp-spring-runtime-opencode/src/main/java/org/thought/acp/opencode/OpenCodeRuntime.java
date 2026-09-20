package org.thought.acp.opencode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thought.acp.config.AgentSettings;
import org.thought.acp.config.ProviderEnvironment;
import org.thought.acp.config.RuntimeOptions;
import org.thought.acp.runtime.AgentLaunchSpec;
import org.thought.acp.runtime.AgentRuntime;
import org.thought.acp.runtime.ToolNames;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Runs OpenCode as an ACP agent over stdio.
 *
 * <p>The leanest of the three adapters, because OpenCode advertises the least: {@code session/new}
 * returns exactly two config options, {@code model} and {@code mode}, and neither the legacy
 * {@code modes}/{@code models} states nor the providers capability. Everything the negotiated tier
 * can do here, it does through {@code session/set_config_option}.
 *
 * <p>One thing it does differently matters to the configuration model. OpenCode has no separate
 * provider option: the provider is the first segment of the model id, so its models are named
 * {@code openai/gpt-5.4-mini}. A portable {@code model: gpt-5.4-mini} would therefore match nothing
 * on a literal comparison. The core handles this — {@code SelectMatcher} tries
 * {@code <provider>/<model>} and then a unique suffix match — so {@code spring.acp.model} means the
 * same thing here as everywhere else, with {@code spring.acp.provider.id} disambiguating when two
 * providers offer a model of the same name.
 *
 * <p>Tier-3 options, under {@code spring.acp.runtimes.opencode}:
 *
 * <pre>{@code
 * command: /opt/opencode/bin/opencode
 * args: [ --print-logs ]
 * config: { theme: system }    # written as opencode.json, pointed at by OPENCODE_CONFIG
 * env: { OPENCODE_DISABLE_AUTOUPDATE: "1" }
 * }</pre>
 */
public class OpenCodeRuntime implements AgentRuntime {

	public static final String ID = "opencode";

	/** The buildpack, or an operator, can name the binary without touching application config. */
	public static final String CLI_PATH_ENV = "OPENCODE_CLI_PATH";

	/** OpenCode reads its configuration from the file this names, in place of the ambient one. */
	public static final String CONFIG_ENV = "OPENCODE_CONFIG";

	private static final String CONFIG_FILE = "opencode.json";

	private static final Logger logger = LoggerFactory.getLogger(OpenCodeRuntime.class);

	private final String executable;

	public OpenCodeRuntime() {
		this(defaultExecutable());
	}

	public OpenCodeRuntime(String executable) {
		this.executable = executable;
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public AgentLaunchSpec launch(AgentSettings settings) {
		RuntimeOptions options = settings.runtimeOptions();
		List<String> args = new ArrayList<>(List.of("acp"));
		args.addAll(options.textList("args"));
		return new AgentLaunchSpec.Stdio(options.text("command").orElse(executable), args, environment(settings));
	}

	/**
	 * Writes {@code opencode.json} into the runtime home when tier 3 asked for one.
	 *
	 * <p>Safe to relocate in a way Codex's home is not: {@code OPENCODE_CONFIG} names the config file
	 * alone, and OpenCode keeps its credentials elsewhere, so pointing it at a file this library owns
	 * does not cost the ambient installation its login.
	 */
	@Override
	public void provision(AgentSettings settings) {
		Map<String, Object> config = settings.runtimeOptions().section("config");
		if (config.isEmpty()) {
			return;
		}
		Path file = configFile(settings);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, Json.write(config), StandardCharsets.UTF_8);
			logger.debug("Wrote {} key(s) to {}", config.size(), file);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not write " + CONFIG_FILE + " to " + file, ex);
		}
	}

	@Override
	public List<String> configIdsFor(PortableOption option) {
		// Verified against opencode 1.18.31.
		return switch (option) {
			case MODEL -> List.of("model");
			case MODE -> List.of("mode");
			// Not a separate option: the provider is the model id's first segment.
			case PROVIDER -> List.of();
		};
	}

	/**
	 * A requested provider is not unsupported here, it is subsumed: it took effect as the prefix the
	 * model was matched with. Reporting it unsupported would fire {@code on-unsupported: fail} on a
	 * configuration that is working exactly as asked.
	 */
	@Override
	public boolean appliedOutOfBand(PortableOption option, AgentSettings settings) {
		return option == PortableOption.PROVIDER
				&& (settings.model() != null && !settings.model().isBlank() || settings.provider().hasCredentials());
	}

	@Override
	public Optional<String> toolNameOf(AcpSchema.ToolCallUpdate toolCall) {
		return ToolNames.fromRawInput(toolCall);
	}

	private Map<String, String> environment(AgentSettings settings) {
		Map<String, String> env = new LinkedHashMap<>();
		RuntimeOptions options = settings.runtimeOptions();

		if (!options.section("config").isEmpty()) {
			env.put(CONFIG_ENV, configFile(settings).toString());
		}
		env.putAll(ProviderEnvironment.of(settings.provider()));
		env.putAll(options.textSection("env"));
		return env;
	}

	private Path configFile(AgentSettings settings) {
		return settings.runtimeOptions().text("config-file").map(Path::of).map(Path::toAbsolutePath)
				.orElseGet(() -> settings.runtimeHome().resolve(CONFIG_FILE));
	}

	private static String defaultExecutable() {
		String configured = System.getenv(CLI_PATH_ENV);
		if (configured != null && !configured.isBlank()) {
			Path path = Path.of(configured);
			if (!Files.isExecutable(path)) {
				throw new IllegalStateException(
						CLI_PATH_ENV + " points at '" + configured + "', which is not an executable file");
			}
			return path.toString();
		}
		return "opencode";
	}
}
