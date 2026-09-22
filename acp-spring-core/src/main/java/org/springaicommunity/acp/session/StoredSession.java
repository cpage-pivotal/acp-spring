package org.springaicommunity.acp.session;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Optional;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * A conversation the agent has kept, as reported by {@code session/list}.
 *
 * <p>Not an {@link AgentSession}: this one is not open, has no turn permit and belongs to the agent
 * rather than to this client. It becomes an {@code AgentSession} only by being loaded or resumed,
 * which is the distinction {@link AgentSessions} exists to make explicit.
 */
public record StoredSession(String sessionId, Path cwd, String title, Instant updatedAt) {

	static StoredSession from(AcpSchema.SessionInfo info) {
		return new StoredSession(info.sessionId(), info.cwd() == null ? null : Paths.get(info.cwd()), info.title(),
				parseInstant(info.updatedAt()));
	}

	/**
	 * ACP says {@code updatedAt} is an RFC 3339 timestamp, but it is an agent-supplied string and a
	 * listing is not worth failing over one unparseable field.
	 */
	private static Instant parseInstant(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(value);
		}
		catch (java.time.format.DateTimeParseException ex) {
			return null;
		}
	}

	public Optional<Path> findCwd() {
		return Optional.ofNullable(cwd);
	}

	public Optional<String> findTitle() {
		return Optional.ofNullable(title);
	}

	public Optional<Instant> findUpdatedAt() {
		return Optional.ofNullable(updatedAt);
	}
}
