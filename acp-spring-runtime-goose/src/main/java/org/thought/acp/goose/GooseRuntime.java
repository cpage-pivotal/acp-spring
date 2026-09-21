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
import java.util.regex.Matcher;

import org.thought.acp.runtime.AgentLaunchSpec;
import org.thought.acp.runtime.AgentNotice;
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
	 * Goose names an OpenAI-compatible endpoint {@code OPENAI_HOST}, not the
	 * {@code OPENAI_BASE_URL} the derivation rule would produce, and it means scheme, authority
	 * <em>and</em> any path prefix the endpoint is published under — everything up to the API version.
	 * Goose appends the version and the route itself, which is why the version segment is stripped
	 * from the canonical base URL rather than passed on.
	 *
	 * <p>The route is deliberately left to Goose. It chooses per model — measured against one
	 * endpoint and one process, {@code gpt-5.6-terra} went to {@code /v1/responses} and
	 * {@code deepseek-…} to {@code /v1/chat/completions} — and that choice is better informed than
	 * anything this adapter could make: the Responses API keeps reasoning items across a turn, which
	 * is worth having wherever it exists. Setting {@code OPENAI_BASE_PATH} here would pin every model
	 * to one dialect, so an application that genuinely needs that pins it itself through the tier-3
	 * {@code env} block.
	 */
	private static final String OPENAI_HOST = "OPENAI_HOST";

	/** Goose's own names for the provider and model it starts with. */
	private static final String GOOSE_PROVIDER = "GOOSE_PROVIDER";

	private static final String GOOSE_MODEL = "GOOSE_MODEL";

	/** {@code goose serve} refuses to start without this, which is the behaviour we want. */
	private static final String SECRET_KEY_ENV = "GOOSE_SERVER__SECRET_KEY";

	private static final String SECRET_KEY_HEADER = "X-Secret-Key";

	private static final String SERVE_WEBSOCKET = "websocket";

	/**
	 * goose's own wording for an extension that did not start, with the name and the reason.
	 *
	 * <p>The one string in this class that depends on goose's internals rather than its interface,
	 * and it carries no compatibility promise. {@code GooseRuntimeTests} pins it against captured
	 * lines and the live contract test catches the day it changes; between releases a goose that
	 * reworded this goes quiet again, which is worth knowing and is why the upstream fix — the
	 * warning arriving over ACP — is the one that ends this.
	 */
	private static final java.util.regex.Pattern EXTENSION_FAILURE = java.util.regex.Pattern
			.compile("Failed to load extension ([^:]+): (.*)");

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

	/**
	 * Where goose writes its own log.
	 *
	 * <p>Measured against 1.51.0 on both transports: {@code $XDG_STATE_HOME/goose/logs/cli/<date>/}
	 * — {@code goose serve} uses the same {@code cli} subdirectory as {@code goose acp} — with
	 * {@code ~/.local/state} standing in when {@code XDG_STATE_HOME} is unset.
	 *
	 * <p>This deliberately <em>reads</em> the location rather than choosing one. Pointing
	 * {@code XDG_STATE_HOME} at {@link AgentSettings#runtimeHome()} would make the path certain and
	 * would also move goose's session storage, which lives under the same root — an ephemeral
	 * per-client directory would quietly break {@code session/load} and {@code session/resume}
	 * across restarts. Trading one silent failure for another is not a fix. A deployment that puts
	 * the log somewhere else says so with the tier-3 {@code log-dir}.
	 *
	 * @see #noticeOf(String)
	 */
	@Override
	public Optional<Path> logDirectory(AgentSettings settings) {
		Optional<Path> configured = settings.runtimeOptions().text("log-dir").map(Paths::get);
		if (configured.isPresent()) {
			return configured.map(Path::toAbsolutePath);
		}
		String state = System.getenv("XDG_STATE_HOME");
		Path root = state == null || state.isBlank() ? Paths.get(System.getProperty("user.home"), ".local", "state")
				: Paths.get(state);
		return Optional.of(root.resolve("goose").resolve("logs").toAbsolutePath());
	}

	/**
	 * Reads the one thing in that log a client cannot learn any other way.
	 *
	 * <p>goose logs JSON lines. An extension it could not start produces, at WARN from
	 * {@code goose::agents::agent}:
	 *
	 * <pre>{@code
	 * {"timestamp":"…","level":"WARN","fields":{"message":
	 *     "Failed to load extension finops-mcp: failed to initialize MCP client: …"}}
	 * }</pre>
	 *
	 * <p>and nothing at all on stdout, on stderr, or over ACP — the session opens normally and the
	 * model simply has no tools. Matched on the message prefix rather than parsed as JSON, because
	 * the interesting part is one sentence and a brittle dependency on one field's path buys
	 * nothing over a brittle dependency on one sentence's wording.
	 *
	 * <p>Only this line is reported. goose's log carries plenty else that is none of a client's
	 * business, and an adapter that forwarded it all would bury the warning it exists to surface.
	 */
	@Override
	public Optional<AgentNotice> noticeOf(String logLine) {
		if (logLine == null) {
			return Optional.empty();
		}
		Matcher matcher = EXTENSION_FAILURE.matcher(logLine);
		if (!matcher.find()) {
			return Optional.empty();
		}
		String name = matcher.group(1).strip();
		String detail = unescape(untilEndOfMessage(matcher.group(2)));
		return Optional.of(AgentNotice.warning(name,
				"could not load MCP server '" + name + "': " + detail));
	}

	/**
	 * The message without the rest of the JSON record around it.
	 *
	 * <p>The message is a JSON string, so it ends at the first quote the agent did not escape —
	 * whether what follows is another field or the end of the line.
	 */
	private static String untilEndOfMessage(String rest) {
		for (int i = 0; i < rest.length(); i++) {
			char character = rest.charAt(i);
			if (character == '\\') {
				i++;
			}
			else if (character == '"') {
				return rest.substring(0, i);
			}
		}
		return rest;
	}

	private static String unescape(String text) {
		return text.replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\").strip();
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

		env.putAll(providerEnvironment(settings));

		RuntimeOptions options = settings.runtimeOptions();
		env.putAll(options.textSection("env"));
		return env;
	}

	/**
	 * The provider's credentials in Goose's spelling, and — for an endpoint of the application's own —
	 * the provider and model to start with.
	 *
	 * <p>The second half is only done for a bring-your-own endpoint, and that restriction is the whole
	 * design. Against a vendor the agent knows, the model belongs on the wire, where the protocol can
	 * report what was applied and {@code ConfigResolver} can tell a typo from a model; forcing
	 * {@code GOOSE_MODEL} there would replace a checked answer with an unchecked one. Against a
	 * private endpoint there is nothing to check it against but the endpoint, whose catalogue Goose
	 * populates asynchronously after the provider is set — so a session opened in the first moments of
	 * a process can be told a model it serves does not exist. Naming it at launch is what makes the
	 * first turn behave like the tenth.
	 */
	private Map<String, String> providerEnvironment(AgentSettings settings) {
		ProviderSpec provider = settings.provider();
		Map<String, String> env = new LinkedHashMap<>(ProviderEnvironment.of(provider));

		provider.findApiBase().filter(base -> isOpenAiCompatible(provider)).ifPresent(base -> {
			env.remove(ProviderEnvironment.baseUrlVariable(provider.apiType()));
			env.put(OPENAI_HOST, endpoint(base));
		});

		if (provider.isByo()) {
			provider.findApiType().or(provider::findId).ifPresent(id -> env.put(GOOSE_PROVIDER, id));
			startingModel(settings).ifPresent(model -> env.put(GOOSE_MODEL, model));
		}
		return env;
	}

	/**
	 * The canonical base URL without its version segment: scheme, authority, and whatever path prefix
	 * the endpoint is published under, which is the whole of what Goose wants to be told.
	 */
	private static String endpoint(URI base) {
		String text = base.toString();
		String path = base.getRawPath() == null ? "" : base.getRawPath();
		String prefix = path.lastIndexOf('/') <= 0 ? "" : path.substring(0, path.lastIndexOf('/'));
		return text.substring(0, text.length() - path.length()) + prefix;
	}

	private static boolean isOpenAiCompatible(ProviderSpec provider) {
		return provider.findApiType().filter("openai"::equalsIgnoreCase).isPresent();
	}

	private static Optional<String> startingModel(AgentSettings settings) {
		return Optional.ofNullable(settings.model()).filter(model -> !model.isBlank());
	}

	/**
	 * What {@link #providerEnvironment} already carried, declared so the resolver can price it.
	 *
	 * <p>Kept in step with that method rather than restating its conditions: a runtime that claims
	 * more here than it sets there turns {@code on-unsupported: fail} into a lie, which is what
	 * {@code AgentRuntimeContract} checks.
	 */
	@Override
	public boolean appliedOutOfBand(PortableOption option, AgentSettings settings) {
		ProviderSpec provider = settings.provider();
		return switch (option) {
			case MODEL -> provider.isByo() && startingModel(settings).isPresent();
			case PROVIDER -> provider.isByo() && provider.findApiType().or(provider::findId).isPresent();
			case MODE -> false;
		};
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
