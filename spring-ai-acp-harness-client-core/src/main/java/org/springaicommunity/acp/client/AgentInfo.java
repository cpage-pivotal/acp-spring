package org.springaicommunity.acp.client;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import java.util.Optional;

/**
 * What the agent said about itself during {@code initialize}.
 *
 * <p>
 * Both fields are optional in ACP and at least one agent leaves them out, so this exists
 * rather than exposing {@code AcpSchema.Implementation} directly: a caller asking "which
 * agent am I talking to" gets a stable two-field answer instead of a schema record that
 * may grow fields, and the absent case is an empty {@link Optional} rather than a null
 * inside a record.
 */
public record AgentInfo(String name, String version) {

	static Optional<AgentInfo> from(AcpSchema.Implementation implementation) {
		if (implementation == null || implementation.name() == null) {
			return Optional.empty();
		}
		return Optional.of(new AgentInfo(implementation.name(), implementation.version()));
	}

	@Override
	public String toString() {
		return version == null || version.isBlank() ? name : name + " " + version;
	}
}
