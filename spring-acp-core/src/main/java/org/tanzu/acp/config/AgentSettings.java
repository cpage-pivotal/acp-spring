package org.tanzu.acp.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.tanzu.acp.permission.PermissionPolicy;

/**
 * The resolved, runtime-neutral configuration an {@code AgentClient} runs with.
 *
 * <p>Deliberately free of Spring types. The Boot module binds {@code spring.acp.*} into this, but
 * the core library stays usable — and testable — with Spring absent from the classpath.
 */
public record AgentSettings(String runtime, Path workspace, Duration timeout, String model, String provider,
		String mode, List<McpServerSpec> mcpServers, PermissionPolicy permissions, OnUnsupported onUnsupported,
		Duration sessionTtl, Map<String, String> runtimeOptions) {

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
		timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
		if (timeout.isNegative() || timeout.isZero()) {
			throw new IllegalArgumentException("timeout must be positive but was " + timeout);
		}
		sessionTtl = sessionTtl == null ? DEFAULT_SESSION_TTL : sessionTtl;
		mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
		permissions = permissions == null ? PermissionPolicy.deny() : permissions;
		onUnsupported = onUnsupported == null ? OnUnsupported.WARN : onUnsupported;
		runtimeOptions = runtimeOptions == null ? Map.of() : Map.copyOf(runtimeOptions);

		long distinct = mcpServers.stream().map(McpServerSpec::name).distinct().count();
		if (distinct != mcpServers.size()) {
			throw new IllegalArgumentException("mcp server names must be unique");
		}
	}

	public static Builder builder(String runtime, Path workspace) {
		return new Builder(runtime, workspace);
	}

	/** Applies per-request overrides on top of these settings. */
	public AgentSettings merge(AgentOptions options) {
		if (options == null) {
			return this;
		}
		return new AgentSettings(runtime, workspace, options.findTimeout().orElse(timeout),
				options.findModel().orElse(model), options.findProvider().orElse(provider),
				options.findMode().orElse(mode), mcpServers, permissions, onUnsupported, sessionTtl, runtimeOptions);
	}

	public static final class Builder {

		private final String runtime;

		private final Path workspace;

		private Duration timeout;

		private String model;

		private String provider;

		private String mode;

		private List<McpServerSpec> mcpServers = List.of();

		private PermissionPolicy permissions = PermissionPolicy.deny();

		private OnUnsupported onUnsupported = OnUnsupported.WARN;

		private Duration sessionTtl;

		private Map<String, String> runtimeOptions = Map.of();

		private Builder(String runtime, Path workspace) {
			this.runtime = runtime;
			this.workspace = workspace;
		}

		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		public Builder model(String model) {
			this.model = model;
			return this;
		}

		public Builder provider(String provider) {
			this.provider = provider;
			return this;
		}

		public Builder mode(String mode) {
			this.mode = mode;
			return this;
		}

		public Builder mcpServers(List<McpServerSpec> mcpServers) {
			this.mcpServers = mcpServers;
			return this;
		}

		public Builder permissions(PermissionPolicy permissions) {
			this.permissions = permissions;
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

		public Builder runtimeOptions(Map<String, String> runtimeOptions) {
			this.runtimeOptions = runtimeOptions;
			return this;
		}

		public AgentSettings build() {
			return new AgentSettings(runtime, workspace, timeout, model, provider, mode, mcpServers, permissions,
					onUnsupported, sessionTtl, runtimeOptions);
		}
	}
}
