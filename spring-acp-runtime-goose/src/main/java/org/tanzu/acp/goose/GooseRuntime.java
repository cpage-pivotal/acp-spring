package org.tanzu.acp.goose;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.tanzu.acp.config.AgentSettings;
import org.tanzu.acp.config.ProviderEnvironment;
import org.tanzu.acp.config.ProviderSpec;
import org.tanzu.acp.config.RuntimeOptions;
import org.tanzu.acp.runtime.AgentLaunchSpec;
import org.tanzu.acp.runtime.AgentRuntime;
import org.tanzu.acp.runtime.ToolNames;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Runs Goose as an ACP agent over stdio.
 *
 * <p>Goose is the reference runtime for this library and the best behaved of the three: a live
 * {@code session/new} advertises config options for provider, model, mode and thinking effort, so
 * provider and model selection go over the wire rather than through environment variables — more
 * portable, and it survives Goose changing its own configuration format.
 *
 * <p>Two things about it the core has to be told. Its {@code provider} option arrives with no
 * {@code category}, so it is only findable by id; and it will <em>accept</em> a model id it has
 * never heard of, which is why {@code ConfigResolver} validates against the advertised values
 * instead of trusting the call to fail.
 *
 * <p>Tier-3 options, under {@code spring.acp.runtimes.goose}:
 *
 * <pre>{@code
 * builtins: developer,todo     # --with-builtin, comma-separated or a YAML list
 * env: { GOOSE_DISABLE_KEYRING: "1" }
 * }</pre>
 */
public class GooseRuntime implements AgentRuntime {

	/** The buildpack sets this; a developer machine usually just has {@code goose} on the PATH. */
	public static final String CLI_PATH_ENV = "GOOSE_CLI_PATH";

	public static final String ID = "goose";

	/**
	 * Goose names the endpoint of an OpenAI-compatible provider {@code OPENAI_HOST}, not the
	 * {@code OPENAI_BASE_URL} the derivation rule would produce.
	 */
	private static final String OPENAI_HOST = "OPENAI_HOST";

	private final String executable;

	public GooseRuntime() {
		this(defaultExecutable());
	}

	public GooseRuntime(String executable) {
		this.executable = executable;
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public AgentLaunchSpec launch(AgentSettings settings) {
		List<String> args = new ArrayList<>(List.of("acp"));

		// Goose's builtin extensions are a runtime-specific concern: tier 3, not tier 1.
		settings.runtimeOptions().textList("builtins").forEach(name -> {
			args.add("--with-builtin");
			args.add(name);
		});

		return new AgentLaunchSpec.Stdio(executable, args, environment(settings));
	}

	/**
	 * Goose reports the tool behind a permission request in its own {@code _meta}, because ACP has
	 * no required field for it — {@code title} is prose for a human to read. Without this, an
	 * allowlist policy could only reject.
	 */
	@Override
	public Optional<String> toolNameOf(AcpSchema.ToolCallUpdate toolCall) {
		return ToolNames.fromRawInputOrTitle(toolCall);
	}

	@Override
	public List<String> configIdsFor(PortableOption option) {
		// Verified against goose 1.51.0: session/new advertises exactly these ids.
		return switch (option) {
			case MODEL -> List.of("model");
			case PROVIDER -> List.of("provider");
			case MODE -> List.of("mode");
		};
	}

	/**
	 * Goose's provider option carries no category, so the portable {@code provider} category would
	 * never match it and could only match something else. Better to have no fallback than a wrong one.
	 */
	@Override
	public List<String> configCategoriesFor(PortableOption option) {
		return option == PortableOption.PROVIDER ? List.of() : AgentRuntime.super.configCategoriesFor(option);
	}

	/**
	 * The environment Goose starts with.
	 *
	 * <p>Ordering is the contract: the hardening defaults first, then the provider's credentials, then
	 * the application's own tier-3 {@code env} block, which therefore wins over both.
	 */
	private Map<String, String> environment(AgentSettings settings) {
		Map<String, String> env = new LinkedHashMap<>();

		// TAS containers have no durable desktop keyring, and telemetry from a server-side agent is
		// not the operator's to send.
		env.put("GOOSE_DISABLE_KEYRING", "1");
		env.put("GOOSE_TELEMETRY_ENABLED", "false");

		ProviderSpec provider = settings.provider();
		env.putAll(ProviderEnvironment.of(provider, baseUrlVariable(provider)));

		RuntimeOptions options = settings.runtimeOptions();
		env.putAll(options.textSection("env"));
		return env;
	}

	private static String baseUrlVariable(ProviderSpec provider) {
		return provider.findApiType().filter("openai"::equalsIgnoreCase).map(t -> OPENAI_HOST).orElse(null);
	}

	/**
	 * Prefers the explicit path the buildpack exports, then falls back to the PATH. Resolving an
	 * absolute path here means the failure, when there is one, names the file rather than surfacing
	 * as a bare {@code IOException} from process start.
	 */
	private static String defaultExecutable() {
		String configured = System.getenv(CLI_PATH_ENV);
		if (configured != null && !configured.isBlank()) {
			Path path = Paths.get(configured);
			if (!Files.isExecutable(path)) {
				throw new IllegalStateException(
						CLI_PATH_ENV + " points at '" + configured + "', which is not an executable file");
			}
			return path.toString();
		}
		return "goose";
	}
}
