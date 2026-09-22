package org.springaicommunity.acp.opencode;

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
import org.springaicommunity.acp.config.AgentSettings;
import org.springaicommunity.acp.config.ProviderEnvironment;
import org.springaicommunity.acp.config.ProviderSpec;
import org.springaicommunity.acp.config.RuntimeOptions;
import org.springaicommunity.acp.runtime.AgentLaunchSpec;
import org.springaicommunity.acp.runtime.AgentRuntime;
import org.springaicommunity.acp.runtime.ToolNames;

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

	/** What OpenCode loads to talk to an OpenAI-compatible endpoint it has no built-in entry for. */
	private static final String COMPATIBLE_PACKAGE = "@ai-sdk/openai-compatible";

	/** The name a bring-your-own endpoint gets in the config when the application named none. */
	private static final String DEFAULT_PROVIDER_KEY = "acp";

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
	 * Writes {@code opencode.json} into the runtime home when tier 3 asked for one, or when the
	 * application named an endpoint of its own.
	 *
	 * <p>Safe to relocate in a way Codex's home is not: {@code OPENCODE_CONFIG} names the config file
	 * alone, and OpenCode keeps its credentials elsewhere, so pointing it at a file this library owns
	 * does not cost the ambient installation its login.
	 */
	@Override
	public void provision(AgentSettings settings) {
		Map<String, Object> config = configFor(settings);
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

	/**
	 * The application's own {@code config} block, over a description of its endpoint when it
	 * configured one.
	 *
	 * <p>OpenCode reaches a provider it was not built knowing about through the Vercel AI SDK's
	 * OpenAI-compatible package, declared in its config rather than through the environment, and its
	 * model ids are {@code provider/model} — so the endpoint and the model it serves are one entry.
	 * The key itself stays out of the file: {@code {env:...}} is OpenCode's own indirection, pointed
	 * at the variable {@link #environment} already exports.
	 *
	 * <p>Only for a bring-your-own endpoint. Against a provider OpenCode ships with, the model is a
	 * value it advertises and belongs on the wire, where the protocol reports what was applied.
	 *
	 * <p>The vendor the api type names is disabled alongside. The key this process holds for it is
	 * the endpoint's, exported under that vendor's variable, and OpenCode switches its built-in entry
	 * on for any key it finds there — measured against 1.18.31, 49 {@code openai/*} models beside the
	 * endpoint's one. A model both offer would then match the vendor's entry by its
	 * {@code <provider>/<model>} spelling, and the endpoint's key would go to the vendor.
	 */
	private static Map<String, Object> configFor(AgentSettings settings) {
		Map<String, Object> config = new LinkedHashMap<>(endpointConfig(settings));
		config.putAll(settings.runtimeOptions().section("config"));
		return config;
	}

	private static Map<String, Object> endpointConfig(AgentSettings settings) {
		ProviderSpec provider = settings.provider();
		if (!provider.isByo() || provider.findApiType().isEmpty()) {
			return Map.of();
		}
		String id = endpointKey(provider);
		Map<String, Object> options = new LinkedHashMap<>();
		options.put("baseURL", provider.findApiBase().orElseThrow().toString());
		provider.findApiKey().ifPresent(
				key -> options.put("apiKey", "{env:" + ProviderEnvironment.apiKeyVariable(provider.apiType()) + "}"));

		Map<String, Object> endpoint = new LinkedHashMap<>();
		endpoint.put("npm", COMPATIBLE_PACKAGE);
		endpoint.put("name", provider.findId().orElse(id));
		endpoint.put("options", options);
		startingModel(settings)
				.ifPresent(model -> endpoint.put("models", Map.of(model, Map.of("name", model))));

		Map<String, Object> config = new LinkedHashMap<>();
		config.put("provider", Map.of(id, endpoint));
		config.put("disabled_providers", List.of(provider.apiType()));
		startingModel(settings).ifPresent(model -> config.put("model", id + "/" + model));
		return config;
	}

	/**
	 * The name the endpoint is entered under: the application's provider id, unless that id is just
	 * the api type.
	 *
	 * <p>{@code provider.id: openai} beside {@code api-type: openai} is the natural way to describe
	 * an OpenAI-compatible gateway, and goose reads it that way. OpenCode does not: an entry under the
	 * name of a provider it ships with is merged into that provider, and the built-in's own loader
	 * then runs on it. For {@code openai} that loader calls {@code sdk.responses(...)}, which the
	 * compatible package does not have, so every turn fails with
	 * {@code Z.responses is not a function} (measured against 1.18.31). The endpoint therefore goes
	 * under a name of its own, and the model is still found: the resolver's suffix match takes
	 * {@code acp/<model>} for a request of {@code <model>}.
	 */
	private static String endpointKey(ProviderSpec provider) {
		return provider.findId().filter(id -> !id.equalsIgnoreCase(provider.apiType())).orElse(DEFAULT_PROVIDER_KEY);
	}

	private static Optional<String> startingModel(AgentSettings settings) {
		return Optional.ofNullable(settings.model()).filter(model -> !model.isBlank());
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
		return switch (option) {
			case PROVIDER -> settings.model() != null && !settings.model().isBlank()
					|| settings.provider().hasCredentials();
			case MODEL -> !endpointConfig(settings).isEmpty() && startingModel(settings).isPresent();
			case MODE -> false;
		};
	}

	@Override
	public Optional<String> toolNameOf(AcpSchema.ToolCallUpdate toolCall) {
		return ToolNames.fromRawInput(toolCall);
	}

	private Map<String, String> environment(AgentSettings settings) {
		Map<String, String> env = new LinkedHashMap<>();
		RuntimeOptions options = settings.runtimeOptions();

		if (!configFor(settings).isEmpty()) {
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
