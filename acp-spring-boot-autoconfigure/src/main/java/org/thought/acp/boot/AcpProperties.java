package org.thought.acp.boot;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.thought.acp.config.McpServerSpec;
import org.thought.acp.config.McpSettings;
import org.thought.acp.config.OnUnsupported;
import org.thought.acp.config.PoolSettings;
import org.thought.acp.config.ProviderSpec;
import org.thought.acp.config.RuntimeOptions;
import org.thought.acp.permission.PermissionPolicy;
import org.thought.acp.protocol.AcpProtocol;
import org.thought.acp.protocol.ProtocolSettings;
import org.thought.acp.workspace.FileSystemAccess;
import org.thought.acp.workspace.TerminalAccess;

/**
 * Binds {@code spring.acp.*}.
 *
 * <p>The three tiers of the configuration model show up here as three shapes. Portable options are
 * plain properties. Negotiated options — {@code model}, {@code provider}, {@code mode} — look
 * identical but are requests the agent may decline, which is what {@code on-unsupported} governs.
 * Runtime-specific options live under {@code runtimes.<id>} and are passed to that adapter
 * untouched.
 */
@ConfigurationProperties(prefix = "spring.acp")
public class AcpProperties {

	/** Which agent to run. Must match a registered AgentRuntime, e.g. goose. */
	private String runtime = "goose";

	/** Absolute path the agent treats as its working directory. Defaults to the JVM's. */
	private Path workspace;

	/**
	 * Where an adapter may write the config files its agent reads at startup. Never the workspace.
	 * Defaults to a directory under the JVM's temp directory, named for the runtime.
	 */
	private Path runtimeHome;

	/** How long a single turn may take. */
	private Duration timeout = Duration.ofMinutes(5);

	/** Requested model. Honored only if the agent exposes one; see on-unsupported. */
	private String model;

	private final Provider provider = new Provider();

	/** Requested session mode, e.g. Goose's auto, approve, chat. */
	private String mode;

	/** What to do when the runtime cannot honor model, provider or mode. */
	private OnUnsupported onUnsupported = OnUnsupported.WARN;

	/** MCP servers offered to every session. Passed through ACP verbatim. */
	private List<McpServer> mcpServers = new ArrayList<>();

	private final Mcp mcp = new Mcp();

	private final Permissions permissions = new Permissions();

	private final FileSystem filesystem = new FileSystem();

	private final Terminal terminal = new Terminal();

	private final Pool pool = new Pool();

	private final Controller controller = new Controller();

	private final Protocol protocol = new Protocol();

	private final Observations observations = new Observations();

	private final Registry registry = new Registry();

	/**
	 * Runtime-specific options, keyed by runtime id. Ignored by every other runtime.
	 *
	 * <p>The value type is {@code Object} rather than {@code String} because tier 3 passes an
	 * agent's own configuration through untouched, and that is not always flat —
	 * {@code codex.config-toml} is a table and {@code opencode.config} is a JSON document.
	 * {@link RuntimeOptions} normalizes whichever shape the binder produces.
	 */
	private Map<String, Map<String, Object>> runtimes = new LinkedHashMap<>();

	public List<McpServerSpec> toMcpServerSpecs() {
		return mcpServers.stream().map(McpServer::toSpec).toList();
	}

	public McpSettings toMcpSettings() {
		return new McpSettings(mcp.getOnServerFailure(), mcp.getDetectTimeout());
	}

	public PermissionPolicy toPermissionPolicy() {
		return permissions.toPolicy();
	}

	public ProviderSpec toProviderSpec() {
		return provider.toSpec();
	}

	public RuntimeOptions optionsFor(String runtimeId) {
		return RuntimeOptions.of(runtimes.getOrDefault(runtimeId, Map.of()));
	}

	public FileSystemAccess toFileSystemAccess() {
		return filesystem.toAccess();
	}

	public TerminalAccess toTerminalAccess() {
		return terminal.toAccess();
	}

	public PoolSettings toPoolSettings() {
		return pool.toSettings();
	}

	public ProtocolSettings toProtocolSettings() {
		return protocol.toSettings();
	}

	public enum PermissionMode {

		DENY, ALLOWLIST, AUTO_APPROVE
	}

	/**
	 * Whether the agent may use this client's filesystem methods.
	 *
	 * <p>Off by default, and read-only when it is on unless {@code write} says otherwise, because
	 * the two are genuinely different decisions: lending an agent the ability to read the
	 * repository it is reasoning about is ordinary, and lending it the ability to change that
	 * repository through this process is not.
	 */
	public static class FileSystem {

		/** Answer fs/read_text_file. */
		private boolean enabled;

		/** Also answer fs/write_text_file. Implies enabled. */
		private boolean write;

		FileSystemAccess toAccess() {
			return new FileSystemAccess(enabled || write, write);
		}

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public boolean isWrite() {
			return write;
		}

		public void setWrite(boolean write) {
			this.write = write;
		}
	}

	/** Whether the agent may ask this client to run commands, and which ones. */
	public static class Terminal {

		/** Answer the terminal/* methods. Arbitrary code execution as this JVM's user. */
		private boolean enabled;

		/** Command names the agent may run. Empty allows any, which is the default. */
		private Set<String> allowedCommands = Set.of();

		/** Output kept per terminal before it is reported truncated. */
		private DataSize outputLimit = DataSize.ofMegabytes(1);

		/** How long one command may run before it is killed. */
		private Duration commandTimeout = Duration.ofMinutes(5);

		/** Terminals one connection may hold at once. */
		private int maxConcurrent = 8;

		TerminalAccess toAccess() {
			return new TerminalAccess(enabled, allowedCommands, outputLimit.toBytes(), commandTimeout, maxConcurrent);
		}

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public Set<String> getAllowedCommands() {
			return allowedCommands;
		}

		public void setAllowedCommands(Set<String> allowedCommands) {
			this.allowedCommands = allowedCommands;
		}

		public DataSize getOutputLimit() {
			return outputLimit;
		}

		public void setOutputLimit(DataSize outputLimit) {
			this.outputLimit = outputLimit;
		}

		public Duration getCommandTimeout() {
			return commandTimeout;
		}

		public void setCommandTimeout(Duration commandTimeout) {
			this.commandTimeout = commandTimeout;
		}

		public int getMaxConcurrent() {
			return maxConcurrent;
		}

		public void setMaxConcurrent(int maxConcurrent) {
			this.maxConcurrent = maxConcurrent;
		}
	}

	/** How many agent processes to run, and how much to ask of each. */
	public static class Pool {

		/** Agent connections to keep. Each one is a separate agent process. */
		private int maxProcesses = 1;

		/** Named sessions one connection may hold before another is preferred. */
		private int maxSessionsPerProcess = 32;

		/** How long an idle named session is kept before it is closed. */
		private Duration sessionTtl = Duration.ofMinutes(60);

		/** Replacements allowed within a five-minute window before a connection is left down. */
		private int maxRestarts = 5;

		PoolSettings toSettings() {
			return new PoolSettings(maxProcesses, maxSessionsPerProcess, maxRestarts);
		}

		public int getMaxProcesses() {
			return maxProcesses;
		}

		public void setMaxProcesses(int maxProcesses) {
			this.maxProcesses = maxProcesses;
		}

		public int getMaxSessionsPerProcess() {
			return maxSessionsPerProcess;
		}

		public void setMaxSessionsPerProcess(int maxSessionsPerProcess) {
			this.maxSessionsPerProcess = maxSessionsPerProcess;
		}

		public Duration getSessionTtl() {
			return sessionTtl;
		}

		public void setSessionTtl(Duration sessionTtl) {
			this.sessionTtl = sessionTtl;
		}

		public int getMaxRestarts() {
			return maxRestarts;
		}

		public void setMaxRestarts(int maxRestarts) {
			this.maxRestarts = maxRestarts;
		}
	}

	/**
	 * The optional HTTP endpoint.
	 *
	 * <p>Off by default and, when on, authenticated by default. An agent endpoint is a way to spend
	 * an application's model budget and to make its agent act on its workspace, so the defaults are
	 * the ones an application would have to deliberately weaken rather than remember to set.
	 */
	public static class Controller {

		/** Register the reactive controller. */
		private boolean enabled;

		/** Serve requests that arrive without a Principal. Development only. */
		private boolean allowUnauthenticated;

		/** Let a request name its own model or provider. Off: credentials are fixed at startup. */
		private boolean allowRequestOverrides;

		/** Base path for the endpoint. */
		private String path = "/api/acp";

		/** Longest prompt a request may carry. */
		private int maxPromptChars = 32_000;

		/** Longest timeout a request may ask for. */
		private Duration maxTimeout = Duration.ofMinutes(10);

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public boolean isAllowUnauthenticated() {
			return allowUnauthenticated;
		}

		public void setAllowUnauthenticated(boolean allowUnauthenticated) {
			this.allowUnauthenticated = allowUnauthenticated;
		}

		public boolean isAllowRequestOverrides() {
			return allowRequestOverrides;
		}

		public void setAllowRequestOverrides(boolean allowRequestOverrides) {
			this.allowRequestOverrides = allowRequestOverrides;
		}

		public String getPath() {
			return path;
		}

		public void setPath(String path) {
			this.path = path;
		}

		public int getMaxPromptChars() {
			return maxPromptChars;
		}

		public void setMaxPromptChars(int maxPromptChars) {
			this.maxPromptChars = maxPromptChars;
		}

		public Duration getMaxTimeout() {
			return maxTimeout;
		}

		public void setMaxTimeout(Duration maxTimeout) {
			this.maxTimeout = maxTimeout;
		}
	}

	/**
	 * Which ACP version to offer, and how much to trust the answer.
	 *
	 * <p>There is one right value for {@code max-version} today and it is the default. The property
	 * exists because the alternative to a flag is a code change, and ACP v2 is a published draft whose
	 * own announcement says to gate it behind version negotiation <em>and</em> a feature flag. Raising
	 * it offers v2; an agent that accepts is then refused, loudly, because {@code acp-core} 0.17.0
	 * decodes the v1 wire format and a v2 turn would report as having ended for no reason. See
	 * {@code AcpProtocol}.
	 */
	public static class Protocol {

		/** The highest ACP version to offer on initialize. */
		private int maxVersion = AcpProtocol.HIGHEST_SPOKEN;

		/**
		 * Fail when an agent answers a version nobody offered, rather than clamping to the offer.
		 *
		 * <p>Off by default because goose 1.51 does exactly that, for every offer, and an application
		 * running goose should not have to choose between a startup failure and no negotiation at all.
		 */
		private boolean strict;

		ProtocolSettings toSettings() {
			return new ProtocolSettings(maxVersion, strict);
		}

		public int getMaxVersion() {
			return maxVersion;
		}

		public void setMaxVersion(int maxVersion) {
			this.maxVersion = maxVersion;
		}

		public boolean isStrict() {
			return strict;
		}

		public void setStrict(boolean strict) {
			this.strict = strict;
		}
	}

	/** Whether turns and tool calls are reported to Micrometer. */
	public static class Observations {

		/**
		 * Record an observation per turn and per tool call. On when Micrometer is present.
		 *
		 * <p>Unlike the controller, this defaults on: an observation publishes nothing and exposes
		 * nothing, and an application that has an {@code ObservationRegistry} has already asked to be
		 * measured.
		 */
		private boolean enabled = true;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}
	}

	/**
	 * The ACP agent registry: where the catalogue comes from, and what may be downloaded.
	 *
	 * <p>Only consulted for a {@code spring.acp.runtime} that no adapter on the classpath claims. An
	 * application running goose, codex or opencode never touches any of this.
	 *
	 * <p>Nothing here names a type from {@code acp-spring-runtime-registry}, and that is load-bearing
	 * rather than tidy. This class is instantiated by a field initializer, which runs whenever
	 * {@code AcpProperties} is bound — so a default of {@code RegistrySettings.DEFAULT_URL} would make
	 * an optional dependency mandatory, and an application without it would die at refresh on the very
	 * jar it chose not to ship. Null here means "the registry module's own default", and
	 * {@code AcpRegistryConfiguration} does the conversion behind a class condition that can hold when
	 * the class is genuinely missing.
	 */
	public static class Registry {

		/** Consult the registry for a runtime no adapter claims. */
		private boolean enabled = true;

		/** The published catalogue. A file: URL pins it to a snapshot you control. */
		private URI url;

		/** Where the snapshot and the downloaded agents are kept between runs. */
		private Path cache;

		/** How long a cached snapshot is used before the catalogue is fetched again. */
		private Duration refresh;

		/** Forbid every network call: use the bundled snapshot and whatever is already installed. */
		private boolean offline;

		/**
		 * Refuse an agent the registry publishes no sha256 for.
		 *
		 * <p>On by default, which makes 9 of the registry's 19 binary agents need one more line of
		 * configuration. That is the intended cost: this downloads an executable and runs it with the
		 * application's credentials in its environment.
		 */
		private boolean requireChecksum = true;

		/** How long one agent download may take. */
		private Duration downloadTimeout;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public URI getUrl() {
			return url;
		}

		public void setUrl(URI url) {
			this.url = url;
		}

		public Path getCache() {
			return cache;
		}

		public void setCache(Path cache) {
			this.cache = cache;
		}

		public Duration getRefresh() {
			return refresh;
		}

		public void setRefresh(Duration refresh) {
			this.refresh = refresh;
		}

		public boolean isOffline() {
			return offline;
		}

		public void setOffline(boolean offline) {
			this.offline = offline;
		}

		public boolean isRequireChecksum() {
			return requireChecksum;
		}

		public void setRequireChecksum(boolean requireChecksum) {
			this.requireChecksum = requireChecksum;
		}

		public Duration getDownloadTimeout() {
			return downloadTimeout;
		}

		public void setDownloadTimeout(Duration downloadTimeout) {
			this.downloadTimeout = downloadTimeout;
		}
	}

	/**
	 * Which model provider to use, and how to reach it.
	 *
	 * <p>{@code id} is a negotiated request the agent may decline. The rest is provisioning: it goes
	 * over the wire when the agent advertises the providers capability, and into the agent process's
	 * environment — or its config file — when it does not. Fixed when the process starts, never per
	 * request.
	 *
	 * <p>Setting {@code base-url} says the application has an endpoint of its own, and that endpoint
	 * becomes the authority on which models exist: {@code spring.acp.model} is checked against its
	 * {@code /models} listing rather than against the catalogue the agent shipped with, and is applied
	 * even though the agent never advertised it. Nothing else has to be configured for that.
	 */
	public static class Provider {

		/** The provider's name as the agent knows it, e.g. openai. */
		private String id;

		/** The API dialect, which also names the environment variables the credentials travel in. */
		private String apiType;

		/**
		 * An OpenAI-style base URL, up to and including the version segment — {@code /v1} is added
		 * when it is not there, so a platform's {@code …/openai} and a README's {@code …/v1} mean the
		 * same endpoint. HTTPS, or plain HTTP only for loopback and .apps.internal.
		 */
		private URI baseUrl;

		/** Sent to the provider, never logged. */
		private String apiKey;

		/** Extra headers for the provider endpoint. Rejected if they contain CR or LF. */
		private Map<String, String> headers = Map.of();

		ProviderSpec toSpec() {
			return new ProviderSpec(id, apiType, baseUrl, apiKey, headers);
		}

		public String getId() {
			return id;
		}

		public void setId(String id) {
			this.id = id;
		}

		public String getApiType() {
			return apiType;
		}

		public void setApiType(String apiType) {
			this.apiType = apiType;
		}

		public URI getBaseUrl() {
			return baseUrl;
		}

		public void setBaseUrl(URI baseUrl) {
			this.baseUrl = baseUrl;
		}

		public String getApiKey() {
			return apiKey;
		}

		public void setApiKey(String apiKey) {
			this.apiKey = apiKey;
		}

		public Map<String, String> getHeaders() {
			return headers;
		}

		public void setHeaders(Map<String, String> headers) {
			this.headers = headers;
		}
	}

	public static class Permissions {

		/** How to answer an agent asking permission to use a tool. */
		private PermissionMode policy = PermissionMode.DENY;

		/** Exact tool names to approve when policy is allowlist. */
		private Set<String> allowedTools = Set.of();

		PermissionPolicy toPolicy() {
			return switch (policy) {
				case DENY -> PermissionPolicy.deny();
				case AUTO_APPROVE -> PermissionPolicy.autoApprove();
				case ALLOWLIST -> PermissionPolicy.allowlist(allowedTools);
			};
		}

		public PermissionMode getPolicy() {
			return policy;
		}

		public void setPolicy(PermissionMode policy) {
			this.policy = policy;
		}

		public Set<String> getAllowedTools() {
			return allowedTools;
		}

		public void setAllowedTools(Set<String> allowedTools) {
			this.allowedTools = allowedTools;
		}
	}

	/**
	 * What to do about an MCP server the agent reports it could not load.
	 *
	 * <p>Only Goose can report one at all today, and only for an agent this application started;
	 * everywhere else these settings are inert, because nothing ever reports a failure. See "MCP
	 * servers fail silently" in {@code docs/design.md}.
	 */
	public static class Mcp {

		/** {@code warn} logs it and opens the session anyway; {@code fail} refuses the session. */
		private McpSettings.OnServerFailure onServerFailure = McpSettings.OnServerFailure.WARN;

		/** How long session opening waits for such a report. Only paid when {@code fail}. */
		private Duration detectTimeout = McpSettings.DEFAULT_DETECT_TIMEOUT;

		public McpSettings.OnServerFailure getOnServerFailure() {
			return onServerFailure;
		}

		public void setOnServerFailure(McpSettings.OnServerFailure onServerFailure) {
			this.onServerFailure = onServerFailure;
		}

		public Duration getDetectTimeout() {
			return detectTimeout;
		}

		public void setDetectTimeout(Duration detectTimeout) {
			this.detectTimeout = detectTimeout;
		}
	}

	/** How an MCP server is authorized. */
	public enum McpAuth {

		/** Whatever the configuration says, headers included, handed to the agent as it stands. */
		NONE,

		/** The MCP authorization spec's OAuth, per user; provided by {@code acp-spring-mcp-oauth}. */
		OAUTH

	}

	/** One MCP server. {@code url} selects HTTP transport; {@code command} selects stdio. */
	public static class McpServer {

		private String name;

		private URI url;

		private Map<String, String> headers = Map.of();

		private String command;

		private List<String> args = List.of();

		private Map<String, String> env = Map.of();

		/**
		 * How this server is authorized. {@code oauth} means the MCP authorization spec's OAuth flow,
		 * with each user's own token, and needs {@code acp-spring-mcp-oauth} on the classpath.
		 */
		private McpAuth auth = McpAuth.NONE;

		McpServerSpec toSpec() {
			if (url != null && command != null) {
				throw new IllegalArgumentException(
						"mcp server '" + name + "' sets both url and command; pick one transport");
			}
			if (url != null) {
				return new McpServerSpec.Http(name, url, headers);
			}
			if (command != null) {
				return new McpServerSpec.Stdio(name, command, args, env);
			}
			throw new IllegalArgumentException("mcp server '" + name + "' must set either url or command");
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public McpAuth getAuth() {
			return auth;
		}

		public void setAuth(McpAuth auth) {
			this.auth = auth == null ? McpAuth.NONE : auth;
		}

		/** The spec this server binds to, for an extension that needs to know which servers it owns. */
		public McpServerSpec spec() {
			return toSpec();
		}

		public URI getUrl() {
			return url;
		}

		public void setUrl(URI url) {
			this.url = url;
		}

		public Map<String, String> getHeaders() {
			return headers;
		}

		public void setHeaders(Map<String, String> headers) {
			this.headers = headers;
		}

		public String getCommand() {
			return command;
		}

		public void setCommand(String command) {
			this.command = command;
		}

		public List<String> getArgs() {
			return args;
		}

		public void setArgs(List<String> args) {
			this.args = args;
		}

		public Map<String, String> getEnv() {
			return env;
		}

		public void setEnv(Map<String, String> env) {
			this.env = env;
		}
	}

	public String getRuntime() {
		return runtime;
	}

	public void setRuntime(String runtime) {
		this.runtime = runtime;
	}

	public Path getWorkspace() {
		return workspace;
	}

	public void setWorkspace(Path workspace) {
		this.workspace = workspace;
	}

	public Duration getTimeout() {
		return timeout;
	}

	public void setTimeout(Duration timeout) {
		this.timeout = timeout;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public Provider getProvider() {
		return provider;
	}

	public String getMode() {
		return mode;
	}

	public void setMode(String mode) {
		this.mode = mode;
	}

	public OnUnsupported getOnUnsupported() {
		return onUnsupported;
	}

	public void setOnUnsupported(OnUnsupported onUnsupported) {
		this.onUnsupported = onUnsupported;
	}

	public Mcp getMcp() {
		return mcp;
	}

	public List<McpServer> getMcpServers() {
		return mcpServers;
	}

	public void setMcpServers(List<McpServer> mcpServers) {
		this.mcpServers = mcpServers;
	}

	public Permissions getPermissions() {
		return permissions;
	}

	public FileSystem getFilesystem() {
		return filesystem;
	}

	public Terminal getTerminal() {
		return terminal;
	}

	public Pool getPool() {
		return pool;
	}

	public Controller getController() {
		return controller;
	}

	public Protocol getProtocol() {
		return protocol;
	}

	public Observations getObservations() {
		return observations;
	}

	public Registry getRegistry() {
		return registry;
	}

	public Map<String, Map<String, Object>> getRuntimes() {
		return runtimes;
	}

	public void setRuntimes(Map<String, Map<String, Object>> runtimes) {
		this.runtimes = runtimes;
	}

	public Path getRuntimeHome() {
		return runtimeHome;
	}

	public void setRuntimeHome(Path runtimeHome) {
		this.runtimeHome = runtimeHome;
	}
}
