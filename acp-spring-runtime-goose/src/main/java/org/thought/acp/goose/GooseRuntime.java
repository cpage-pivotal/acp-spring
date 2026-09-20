package org.thought.acp.goose;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.thought.acp.config.AgentSettings;
import org.thought.acp.config.ProviderEnvironment;
import org.thought.acp.config.ProviderSpec;
import org.thought.acp.config.RuntimeOptions;
import org.thought.acp.runtime.AgentLaunchSpec;
import org.thought.acp.runtime.AgentRuntime;
import org.thought.acp.runtime.ToolNames;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Runs Goose as an ACP agent, over stdio or over its own WebSocket server.
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
 * <p>Goose is also the only runtime with a second transport. {@code goose serve} is an ACP server
 * over WebSocket rather than a subprocess on a pipe, which is what the buildpack this library
 * succeeds has always used, and the two differ in more than plumbing: a served agent outlives any
 * one connection, so it is supervised and restarted rather than owned by its transport. Stdio
 * stays the default because every other runtime has only that, and a library whose default
 * behaviour depended on which agent was selected would be the problem it exists to solve.
 *
 * <p>Tier-3 options, under {@code spring.acp.runtimes.goose}:
 *
 * <pre>{@code
 * builtins: developer,todo     # --with-builtin, comma-separated or a YAML list
 * env: { GOOSE_DISABLE_KEYRING: "1" }
 * serve:
 *   transport: websocket       # stdio (default) or websocket
 *   host: 127.0.0.1            # loopback unless you mean otherwise
 *   port: 0                    # 0 asks the OS for a free one
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

	/** {@code goose serve} refuses to start without this, which is the behaviour we want. */
	private static final String SECRET_KEY_ENV = "GOOSE_SERVER__SECRET_KEY";

	private static final String SECRET_KEY_HEADER = "X-Secret-Key";

	private static final String SERVE_WEBSOCKET = "websocket";

	private static final String DEFAULT_SERVE_HOST = "127.0.0.1";

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
		return isServed(settings) ? serve(settings) : stdio(settings);
	}

	private AgentLaunchSpec stdio(AgentSettings settings) {
		List<String> args = new ArrayList<>(List.of("acp"));
		builtins(settings, args);
		return new AgentLaunchSpec.Stdio(executable, args, environment(settings));
	}

	/**
	 * Starts {@code goose serve} on a loopback port and connects to its {@code /acp} WebSocket.
	 *
	 * <p>The secret is generated here rather than configured, and that is deliberate: it is a
	 * credential shared between two processes on one machine for the lifetime of one of them, so
	 * there is nothing for an operator to rotate, leak or forget to set. It reaches the server on
	 * its environment and this client on an upgrade header, and appears in no file and no log —
	 * {@code SecretRedactor} covers the case where the agent prints it back.
	 *
	 * <p>Port 0 means "ask the OS", which is what an application that only needs the agent to be
	 * reachable from inside the same container should say. Binding a socket to find a free port and
	 * closing it again does leave a window in which something else could take it; the alternative
	 * is a fixed port that fails outright when it is busy, which is worse for a sidecar nobody
	 * chose the port of.
	 */
	private AgentLaunchSpec serve(AgentSettings settings) {
		RuntimeOptions options = settings.runtimeOptions();
		String host = options.text("serve.host").orElse(DEFAULT_SERVE_HOST);
		int port = options.text("serve.port").map(Integer::parseInt).filter(p -> p > 0)
				.orElseGet(GooseRuntime::freePort);
		String secret = newSecret();

		List<String> args = new ArrayList<>(List.of("serve", "--host", host, "--port", String.valueOf(port)));
		builtins(settings, args);

		Map<String, String> env = new LinkedHashMap<>(environment(settings));
		env.put(SECRET_KEY_ENV, secret);

		AgentLaunchSpec.ManagedProcess process = new AgentLaunchSpec.ManagedProcess(executable, args, env,
				URI.create("http://" + host + ":" + port + "/health"), startupTimeout(options));

		return new AgentLaunchSpec.WebSocket(URI.create("ws://" + host + ":" + port + "/acp"),
				Map.of(SECRET_KEY_HEADER, secret), process);
	}

	private static Duration startupTimeout(RuntimeOptions options) {
		return options.text("serve.startup-timeout").map(Duration::parse).orElse(null);
	}

	/** Whether tier 3 asked for the served transport. */
	private static boolean isServed(AgentSettings settings) {
		return settings.runtimeOptions().text("serve.transport").filter(SERVE_WEBSOCKET::equalsIgnoreCase).isPresent();
	}

	/** Goose's builtin extensions are a runtime-specific concern: tier 3, not tier 1. */
	private static void builtins(AgentSettings settings, List<String> args) {
		settings.runtimeOptions().textList("builtins").forEach(name -> {
			args.add("--with-builtin");
			args.add(name);
		});
	}

	private static int freePort() {
		try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (java.io.IOException ex) {
			throw new IllegalStateException("Could not allocate a port for 'goose serve'", ex);
		}
	}

	private static String newSecret() {
		byte[] bytes = new byte[32];
		new java.security.SecureRandom().nextBytes(bytes);
		return java.util.HexFormat.of().formatHex(bytes);
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
