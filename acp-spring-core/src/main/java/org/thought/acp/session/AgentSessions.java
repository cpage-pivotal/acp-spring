package org.thought.acp.session;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The conversations an agent has, as opposed to the turns run in them.
 *
 * <p>Everything here is capability-gated, and that is the whole difficulty. {@code session/new} and
 * {@code session/prompt} are mandatory in ACP; listing, loading, resuming and deleting are not, and
 * the three runtimes this library ships adapters for do not agree on which of them exist. So the
 * portable contract is not "these five operations work" but "these five operations are askable, and
 * {@link #supports} answers before you spend a round trip finding out". An operation the agent did
 * not advertise throws {@link UnsupportedAgentOperationException} rather than failing on the wire
 * with an error code the caller would have to interpret.
 *
 * <p>The distinction between {@link #load} and {@link #resume} is the protocol's, not this
 * library's: load replays the stored conversation as {@code session/update} notifications, resume
 * reattaches without replaying. Replay updates carry {@code _meta.replay} and are dropped by
 * {@code AgentEventMapper}, so neither one leaks history into the next turn's event stream — but
 * load pays for the replay on the wire and resume does not.
 */
public interface AgentSessions {

	/** Every session the agent has stored, following pagination to the end. */
	List<StoredSession> list();

	/** Sessions stored against one working directory. */
	List<StoredSession> list(Path cwd);

	/**
	 * Binds {@code name} in this client to a session the agent already has, replaying its history.
	 *
	 * <p>The registry is the authority on names, so a name already in use is a programming error
	 * rather than a silent rebinding: a caller that meant to continue an open conversation should
	 * prompt it, and one that meant to replace it should {@link #close} it first.
	 */
	AgentSession load(String name, String sessionId);

	/** Binds {@code name} to an existing session without replaying its history. */
	AgentSession resume(String name, String sessionId);

	/**
	 * {@link #load(String, String)} on behalf of {@code principal}, whose MCP credentials the
	 * re-declared servers are called with. The two-argument form asks
	 * {@code McpSettings.principals()}.
	 */
	default AgentSession load(String name, String sessionId, SessionPrincipal principal) {
		if (principal == null) {
			return load(name, sessionId);
		}
		throw new UnsupportedOperationException(getClass().getName() + " does not support session principals");
	}

	/** {@link #resume(String, String)} on behalf of {@code principal}. */
	default AgentSession resume(String name, String sessionId, SessionPrincipal principal) {
		if (principal == null) {
			return resume(name, sessionId);
		}
		throw new UnsupportedOperationException(getClass().getName() + " does not support session principals");
	}

	/** Permanently removes a stored session from the agent. It will not appear in {@link #list}. */
	void delete(String sessionId);

	/**
	 * Ends the named session on the agent and forgets it here.
	 *
	 * <p>Always succeeds locally. {@code session/close} is attempted only when the agent advertised
	 * it, because an agent without it has no way to be told and the session simply ages out there.
	 */
	void close(String name);

	/** The named sessions this client currently holds open. */
	List<AgentSession> open();

	/** One of them, by name. */
	Optional<AgentSession> find(String name);

	/** Whether the agent advertised {@code operation}. */
	boolean supports(Operation operation);

	/** The optional session methods, and the ACP capability that gates each. */
	enum Operation {

		LIST("session/list"), LOAD("session/load"), RESUME("session/resume"), DELETE("session/delete"),
		CLOSE("session/close");

		private final String method;

		Operation(String method) {
			this.method = method;
		}

		/** The ACP method name, used in the exception message so an operator can grep the spec. */
		public String method() {
			return method;
		}
	}
}
