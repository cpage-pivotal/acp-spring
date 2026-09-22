package org.springaicommunity.acp.session;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.acp.config.Validation;

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
		return resolve(name, null, n -> new Opened(factory.apply(n), null));
	}

	/**
	 * Same, for a session that belongs to someone and may hold resources beyond the agent.
	 *
	 * <p>A name already open for a different principal is refused, not shared. Session names are
	 * chosen by the application, and one that derives them from something two users can both
	 * produce — a ticket number, a document id — would otherwise hand the second user the first
	 * user's conversation, and with it MCP tools calling out under the first user's credentials.
	 *
	 * @param principal who the caller is acting for, or null for nobody
	 * @param factory called at most once per name, under the map's per-key lock; must release
	 * whatever it acquired if it throws
	 * @throws SessionOwnershipException if the name is open for someone else
	 */
	public AgentSession resolve(String name, SessionPrincipal principal, Function<String, Opened> factory) {
		Validation.requireName(name, "session name");
		AgentSession session = sessions.computeIfAbsent(name, n -> {
			Opened opened = factory.apply(n);
			logger.debug("Created session '{}' as {}", n, opened.sessionId());
			return new AgentSession(n, opened.sessionId(), principal, opened.release());
		});
		if (!session.belongsTo(principal)) {
			throw new SessionOwnershipException(name);
		}
		return session;
	}

	/**
	 * What a factory reports back about a session it opened.
	 *
	 * @param release run once when the registry forgets the session; null for nothing
	 */
	public record Opened(String sessionId, Runnable release) {
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
		return adopt(name, sessionId, null, null);
	}

	/**
	 * Same, for a session that belongs to someone and may hold resources beyond the agent.
	 *
	 * @param release run once when the registry forgets the session — including when the name
	 * turns out to be taken, so the caller never has to clean up after a refusal
	 */
	public AgentSession adopt(String name, String sessionId, SessionPrincipal principal, Runnable release) {
		Validation.requireName(name, "session name");
		AgentSession adopted = new AgentSession(name, sessionId, principal, release);
		AgentSession existing = sessions.putIfAbsent(name, adopted);
		if (existing != null) {
			adopted.release();
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

	/**
	 * Forgets a session and releases what it held here. Does not close it on the agent — the
	 * caller owns that.
	 */
	public Optional<AgentSession> remove(String name) {
		AgentSession removed = sessions.remove(name);
		if (removed != null) {
			removed.release();
		}
		return Optional.ofNullable(removed);
	}

	/**
	 * Removes sessions idle longer than {@code ttl} that are not mid-turn.
	 *
	 * @return the sessions evicted, so the caller can close them on the agent
	 */
	public List<AgentSession> evictIdle(Duration ttl) {
		Instant cutoff = Instant.now().minus(ttl);
		List<AgentSession> evicted = sessions.values().stream().filter(s -> s.idleSince(cutoff)).toList();
		evicted.forEach(s -> {
			if (sessions.remove(s.name(), s)) {
				s.release();
			}
		});
		if (!evicted.isEmpty()) {
			logger.debug("Evicted {} idle session(s)", evicted.size());
		}
		return evicted;
	}

	public List<AgentSession> all() {
		return List.copyOf(sessions.values());
	}

	public void clear() {
		List<AgentSession> all = List.copyOf(sessions.values());
		sessions.clear();
		all.forEach(AgentSession::release);
	}

	public int size() {
		return sessions.size();
	}
}
