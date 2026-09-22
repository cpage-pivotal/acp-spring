package org.springaicommunity.acp.config;

import java.time.Duration;
import java.util.Optional;

/**
 * Per-request overrides. Every field is optional; an empty field defers to {@link AgentSettings}.
 *
 * <p>Only options that ACP can genuinely vary per session belong here. Credentials, the workspace
 * root and the MCP server set are fixed when the agent process starts, so they are deliberately
 * absent — offering them per call would be a lie.
 */
public record AgentOptions(Duration timeout, String model, String provider, String mode) {

	private static final AgentOptions NONE = new AgentOptions(null, null, null, null);

	public AgentOptions {
		if (timeout != null && (timeout.isNegative() || timeout.isZero())) {
			throw new IllegalArgumentException("timeout must be positive but was " + timeout);
		}
		if (timeout != null && timeout.toHours() > 24) {
			throw new IllegalArgumentException("timeout must not exceed 24h but was " + timeout);
		}
	}

	public static AgentOptions none() {
		return NONE;
	}

	public static Builder builder() {
		return new Builder();
	}

	public Optional<Duration> findTimeout() {
		return Optional.ofNullable(timeout);
	}

	public Optional<String> findModel() {
		return Optional.ofNullable(model);
	}

	public Optional<String> findProvider() {
		return Optional.ofNullable(provider);
	}

	public Optional<String> findMode() {
		return Optional.ofNullable(mode);
	}

	public static final class Builder {

		private Duration timeout;

		private String model;

		private String provider;

		private String mode;

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

		public AgentOptions build() {
			return new AgentOptions(timeout, model, provider, mode);
		}
	}
}
