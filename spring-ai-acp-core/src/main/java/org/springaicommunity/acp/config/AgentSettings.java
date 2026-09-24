package org.springaicommunity.acp.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springaicommunity.acp.permission.PermissionPolicy;
import org.springaicommunity.acp.protocol.ProtocolSettings;
import org.springaicommunity.acp.workspace.FileSystemAccess;
import org.springaicommunity.acp.workspace.TerminalAccess;

/**
 * The resolved, runtime-neutral configuration an {@code AgentClient} runs with.
 *
 * <p>
 * Deliberately free of Spring types. The Boot module binds {@code spring.acp.*} into
 * this, but the core library stays usable — and testable — with Spring absent from the
 * classpath.
 *
 * <p>
 * All three configuration tiers appear here, and the type of each field says which tier
 * it is in. {@code workspace}, {@code mcpServers}, {@code permissions} and
 * {@code timeout} are portable: every runtime honors them, because ACP does.
 * {@code skills} is portable for a different reason: ACP says nothing about skills, but
 * every runtime reads them from the same place in the workspace. {@code model},
 * {@code mode} and {@code provider} are negotiated requests that {@code ConfigResolver}
 * tries to place and {@code onUnsupported} prices. {@link RuntimeOptions} is tier three,
 * opaque to everything but the one adapter it names.
 *
 * <p>
 * {@link FileSystemAccess} and {@link TerminalAccess} are portable in the same sense but
 * point the other way: they say what the agent may ask <em>this</em> client to do, not
 * what this client asks of the agent. Both are off by default.
 *
 * <p>
 * {@link ProtocolSettings} sits under all three: it decides which ACP version the
 * conversation the other tiers are configuring is held in.
 */
public record AgentSettings(String runtime, Path workspace, Path runtimeHome, Duration timeout, String model,
		ProviderSpec provider, String mode, List<McpServerSpec> mcpServers, McpSettings mcp, List<SkillSpec> skills,
		PermissionPolicy permissions, FileSystemAccess filesystem, TerminalAccess terminal, OnUnsupported onUnsupported,
		Duration sessionTtl, PoolSettings pool, RuntimeOptions runtimeOptions, ProtocolSettings protocol) {

	public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

	public static final Duration DEFAULT_SESSION_TTL = Duration.ofMinutes(60);

	public AgentSettings {
		Validation.requireText(runtime, "runtime");
		if (workspace == null) {
			throw new IllegalArgumentException("workspace must not be null");
		}
		if (!workspace.isAbsolute()) {
			throw new IllegalArgumentException("workspace must be an absolute path but was '" + workspace + "'");
		}
		if (!Files.isDirectory(workspace)) {
			throw new IllegalArgumentException("workspace '" + workspace + "' is not an existing directory");
		}
		runtimeHome = runtimeHome == null ? defaultRuntimeHome(runtime) : runtimeHome.toAbsolutePath();
		timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
		if (timeout.isNegative() || timeout.isZero()) {
			throw new IllegalArgumentException("timeout must be positive but was " + timeout);
		}
		sessionTtl = sessionTtl == null ? DEFAULT_SESSION_TTL : sessionTtl;
		pool = pool == null ? PoolSettings.defaults() : pool;
		mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
		mcp = mcp == null ? McpSettings.defaults() : mcp;
		skills = skills == null ? List.of() : List.copyOf(skills);
		permissions = permissions == null ? PermissionPolicy.deny() : permissions;
		filesystem = filesystem == null ? FileSystemAccess.none() : filesystem;
		terminal = terminal == null ? TerminalAccess.disabled() : terminal;
		onUnsupported = onUnsupported == null ? OnUnsupported.WARN : onUnsupported;
		provider = provider == null ? ProviderSpec.none() : provider;
		runtimeOptions = runtimeOptions == null ? RuntimeOptions.empty() : runtimeOptions;
		protocol = protocol == null ? ProtocolSettings.defaults() : protocol;

		long distinct = mcpServers.stream().map(McpServerSpec::name).distinct().count();
		if (distinct != mcpServers.size()) {
			throw new IllegalArgumentException("mcp server names must be unique");
		}
		if (skills.stream().map(SkillSpec::name).distinct().count() != skills.size()) {
			throw new IllegalArgumentException("skill names must be unique");
		}
	}

	/**
	 * Where an adapter may write the files its agent reads at startup.
	 *
	 * <p>
	 * Not the workspace: the workspace is the application's own code, and dropping a
	 * {@code config.toml} into it would be visible to the agent as content and to a
	 * reviewer as a change. Keyed by runtime id so two adapters on one machine cannot
	 * overwrite each other.
	 */
	private static Path defaultRuntimeHome(String runtime) {
		return Paths.get(System.getProperty("java.io.tmpdir"), "spring-ai-acp", runtime).toAbsolutePath();
	}

	public static Builder builder(String runtime, Path workspace) {
		return new Builder(runtime, workspace);
	}

	/** Applies per-request overrides on top of these settings. */
	public AgentSettings merge(AgentOptions options) {
		if (options == null) {
			return this;
		}
		return new AgentSettings(runtime, workspace, runtimeHome, options.findTimeout().orElse(timeout),
				options.findModel().orElse(model), options.findProvider().map(provider::withId).orElse(provider),
				options.findMode().orElse(mode), mcpServers, mcp, skills, permissions, filesystem, terminal,
				onUnsupported, sessionTtl, pool, runtimeOptions, protocol);
	}

	public static final class Builder {

		private final String runtime;

		private final Path workspace;

		private Path runtimeHome;

		private Duration timeout;

		private String model;

		private ProviderSpec provider = ProviderSpec.none();

		private String mode;

		private List<McpServerSpec> mcpServers = List.of();

		private McpSettings mcp = McpSettings.defaults();

		private List<SkillSpec> skills = List.of();

		private PermissionPolicy permissions = PermissionPolicy.deny();

		private FileSystemAccess filesystem = FileSystemAccess.none();

		private TerminalAccess terminal = TerminalAccess.disabled();

		private OnUnsupported onUnsupported = OnUnsupported.WARN;

		private Duration sessionTtl;

		private PoolSettings pool = PoolSettings.defaults();

		private RuntimeOptions runtimeOptions = RuntimeOptions.empty();

		private ProtocolSettings protocol = ProtocolSettings.defaults();

		private Builder(String runtime, Path workspace) {
			this.runtime = runtime;
			this.workspace = workspace;
		}

		public Builder runtimeHome(Path runtimeHome) {
			this.runtimeHome = runtimeHome;
			return this;
		}

		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		public Builder model(String model) {
			this.model = model;
			return this;
		}

		public Builder provider(ProviderSpec provider) {
			this.provider = provider;
			return this;
		}

		public Builder provider(String id) {
			return provider(ProviderSpec.of(id));
		}

		public Builder mode(String mode) {
			this.mode = mode;
			return this;
		}

		public Builder mcpServers(List<McpServerSpec> mcpServers) {
			this.mcpServers = mcpServers;
			return this;
		}

		public Builder mcp(McpSettings mcp) {
			this.mcp = mcp;
			return this;
		}

		public Builder skills(List<SkillSpec> skills) {
			this.skills = skills;
			return this;
		}

		public Builder permissions(PermissionPolicy permissions) {
			this.permissions = permissions;
			return this;
		}

		public Builder filesystem(FileSystemAccess filesystem) {
			this.filesystem = filesystem;
			return this;
		}

		public Builder terminal(TerminalAccess terminal) {
			this.terminal = terminal;
			return this;
		}

		public Builder onUnsupported(OnUnsupported onUnsupported) {
			this.onUnsupported = onUnsupported;
			return this;
		}

		public Builder sessionTtl(Duration sessionTtl) {
			this.sessionTtl = sessionTtl;
			return this;
		}

		public Builder pool(PoolSettings pool) {
			this.pool = pool;
			return this;
		}

		public Builder runtimeOptions(RuntimeOptions runtimeOptions) {
			this.runtimeOptions = runtimeOptions;
			return this;
		}

		public Builder runtimeOptions(Map<String, ?> runtimeOptions) {
			return runtimeOptions(RuntimeOptions.of(runtimeOptions));
		}

		public Builder protocol(ProtocolSettings protocol) {
			this.protocol = protocol;
			return this;
		}

		public AgentSettings build() {
			return new AgentSettings(runtime, workspace, runtimeHome, timeout, model, provider, mode, mcpServers, mcp,
					skills, permissions, filesystem, terminal, onUnsupported, sessionTtl, pool, runtimeOptions,
					protocol);
		}

	}
}
