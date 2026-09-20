package org.tanzu.acp.boot;

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
import org.tanzu.acp.config.McpServerSpec;
import org.tanzu.acp.config.OnUnsupported;
import org.tanzu.acp.config.PoolSettings;
import org.tanzu.acp.config.ProviderSpec;
import org.tanzu.acp.config.RuntimeOptions;
import org.tanzu.acp.permission.PermissionPolicy;
import org.tanzu.acp.workspace.FileSystemAccess;
import org.tanzu.acp.workspace.TerminalAccess;

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

	private final Permissions permissions = new Permissions();

	private final FileSystem filesystem = new FileSystem();

	private final Terminal terminal = new Terminal();

	private final Pool pool = new Pool();

	private final Controller controller = new Controller();

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
	 * Which model provider to use, and how to reach it.
	 *
	 * <p>{@code id} is a negotiated request the agent may decline. The rest is provisioning: it goes
	 * over the wire when the agent advertises the providers capability, and into the agent process's
	 * environment when it does not. Fixed when the process starts, never per request.
	 */
	public static class Provider {

		/** The provider's name as the agent knows it, e.g. openai. */
		private String id;

		/** The API dialect, which also names the environment variables the credentials travel in. */
		private String apiType;

		/** HTTPS, or plain HTTP only for loopback and .apps.internal. */
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

	/** One MCP server. {@code url} selects HTTP transport; {@code command} selects stdio. */
	public static class McpServer {

		private String name;

		private URI url;

		private Map<String, String> headers = Map.of();

		private String command;

		private List<String> args = List.of();

		private Map<String, String> env = Map.of();

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
