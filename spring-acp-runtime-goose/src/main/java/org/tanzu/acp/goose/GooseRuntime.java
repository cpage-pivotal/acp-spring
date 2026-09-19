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
import org.tanzu.acp.runtime.AgentLaunchSpec;
import org.tanzu.acp.runtime.AgentRuntime;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Runs Goose as an ACP agent over stdio.
 *
 * <p>Goose is the reference runtime for this library, and it is well behaved: {@code goose acp}
 * speaks ACP v1 on stdin and stdout, and a live {@code session/new} advertises config options for
 * provider, model, mode and thinking effort. That means provider and model selection go over the
 * wire through {@code session/set_config_option} rather than through environment variables — more
 * portable, and it survives Goose changing its own configuration format.
 */
public class GooseRuntime implements AgentRuntime {

	/** The buildpack sets this; a developer machine usually just has {@code goose} on the PATH. */
	public static final String CLI_PATH_ENV = "GOOSE_CLI_PATH";

	public static final String ID = "goose";

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
		builtins(settings).forEach(name -> {
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
		return Optional.ofNullable(toolCall).map(AcpSchema.ToolCallUpdate::rawInput)
				.filter(Map.class::isInstance).map(Map.class::cast).map(m -> m.get("toolName"))
				.filter(String.class::isInstance).map(String.class::cast)
				.or(() -> Optional.ofNullable(toolCall).map(AcpSchema.ToolCallUpdate::title));
	}

	@Override
	public List<String> configIdsFor(PortableOption option) {
		// Verified against goose 1.50.0: session/new advertises exactly these ids.
		return switch (option) {
			case MODEL -> List.of("model");
			case PROVIDER -> List.of("provider");
			case MODE -> List.of("mode");
		};
	}

	private List<String> builtins(AgentSettings settings) {
		String value = settings.runtimeOptions().get("builtins");
		if (value == null || value.isBlank()) {
			return List.of();
		}
		return List.of(value.split("\\s*,\\s*"));
	}

	private Map<String, String> environment(AgentSettings settings) {
		Map<String, String> env = new LinkedHashMap<>();

		// TAS containers have no durable desktop keyring, and telemetry from a server-side agent is
		// not the operator's to send.
		env.put("GOOSE_DISABLE_KEYRING", "1");
		env.put("GOOSE_TELEMETRY_ENABLED", "false");

		settings.runtimeOptions().forEach((key, value) -> {
			if (key.startsWith("env.")) {
				env.put(key.substring("env.".length()), value);
			}
		});
		return env;
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
