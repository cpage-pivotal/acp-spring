package org.thought.acp.session;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thought.acp.config.Validation;

/**
 * Maps caller-chosen session names onto the ids an agent issues.
 *
 * <p>One rule matters more than the rest: <strong>registry membership is authoritative over the
 * caller's resume flag.</strong> A caller that retries its first message sends {@code resume=false}
 * again; honoring that literally would discard the context the first attempt established. So a
 * known name resumes whatever the caller asked for, and only an unknown name creates.
 *
 * <p>The mapping is in memory. A restarted process cannot reattach to sessions it issued before,
 * and callers should expect a fresh conversation rather than a resumed one.
 */
public final class SessionRegistry {

	private static final Logger logger = LoggerFactory.getLogger(SessionRegistry.class);

	private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

	/**
	 * Returns the session for {@code name}, creating it through {@code factory} only if this
	 * registry has never seen the name.
	 *
	 * @param factory called at most once per name, under the map's per-key lock
	 */
	public AgentSession resolve(String name, Function<String, String> factory) {
		Validation.requireName(name, "session name");
		return sessions.computeIfAbsent(name, n -> {
			String sessionId = factory.apply(n);
			logger.debug("Created session '{}' as {}", n, sessionId);
			return new AgentSession(n, sessionId);
		});
	}

	/**
	 * Registers a name for a session the agent already has.
	 *
	 * <p>Deliberately not {@link #resolve}: that one is idempotent because a caller retrying its
	 * first prompt must not get a second conversation, whereas binding a name that is already in use
	 * to a <em>different</em> agent-side session can only be a mistake, and doing it silently would
	 * strand whichever session lost.
	 *
	 * @throws IllegalStateException if the name is already registered
	 */
	public AgentSession adopt(String name, String sessionId) {
		Validation.requireName(name, "session name");
		AgentSession adopted = new AgentSession(name, sessionId);
		AgentSession existing = sessions.putIfAbsent(name, adopted);
		if (existing != null) {
			throw new IllegalStateException("Session '" + name + "' is already open as " + existing.sessionId()
					+ "; close it before binding the name to " + sessionId);
		}
		logger.debug("Adopted session '{}' as {}", name, sessionId);
		return adopted;
	}

	public Optional<AgentSession> find(String name) {
		return Optional.ofNullable(sessions.get(name));
	}

	public boolean knows(String name) {
		return sessions.containsKey(name);
	}

	/** Forgets a session. Does not close it on the agent — the caller owns that. */
	public Optional<AgentSession> remove(String name) {
		return Optional.ofNullable(sessions.remove(name));
	}

	/**
	 * Removes sessions idle longer than {@code ttl} that are not mid-turn.
	 *
	 * @return the sessions evicted, so the caller can close them on the agent
	 */
	public List<AgentSession> evictIdle(Duration ttl) {
		Instant cutoff = Instant.now().minus(ttl);
		List<AgentSession> evicted = sessions.values().stream().filter(s -> s.idleSince(cutoff)).toList();
		evicted.forEach(s -> sessions.remove(s.name(), s));
		if (!evicted.isEmpty()) {
			logger.debug("Evicted {} idle session(s)", evicted.size());
		}
		return evicted;
	}

	public List<AgentSession> all() {
		return List.copyOf(sessions.values());
	}

	public void clear() {
		sessions.clear();
	}

	public int size() {
		return sessions.size();
	}
}
