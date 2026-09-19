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
import org.tanzu.acp.config.McpServerSpec;
import org.tanzu.acp.config.OnUnsupported;
import org.tanzu.acp.config.ProviderSpec;
import org.tanzu.acp.config.RuntimeOptions;
import org.tanzu.acp.permission.PermissionPolicy;

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

	/** How long an idle named session is kept before it is closed. */
	private Duration sessionTtl = Duration.ofMinutes(60);

	/** MCP servers offered to every session. Passed through ACP verbatim. */
	private List<McpServer> mcpServers = new ArrayList<>();

	private final Permissions permissions = new Permissions();

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

	public enum PermissionMode {

		DENY, ALLOWLIST, AUTO_APPROVE
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

	public Duration getSessionTtl() {
		return sessionTtl;
	}

	public void setSessionTtl(Duration sessionTtl) {
		this.sessionTtl = sessionTtl;
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
