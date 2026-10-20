package org.springaicommunity.acp.config;

/**
 * How many agent processes to run, and how much to ask of each.
 *
 * <p>
 * An agent handles many sessions but runs one turn at a time per session and, in
 * practice, is bounded by its own model calls rather than by anything this library
 * controls. So the reason to run more than one process is concurrency across
 * <em>unrelated</em> conversations, and the reason not to is that each one is a full
 * agent with its own memory footprint and its own provider credentials on its
 * environment. One is the right default for an application that serves requests one at a
 * time; anything more is a decision an operator makes with a load figure in hand.
 *
 * @param maxProcesses agent connections to keep, each backed by its own process
 * @param maxSessionsPerProcess named sessions one process may hold before another is
 * preferred
 * @param maxRestarts restarts allowed inside a five-minute window before the runtime is
 * given up on
 */
public record PoolSettings(int maxProcesses, int maxSessionsPerProcess, int maxRestarts) {

	public static final int DEFAULT_MAX_PROCESSES = 1;

	public static final int DEFAULT_MAX_SESSIONS_PER_PROCESS = 32;

	public static final int DEFAULT_MAX_RESTARTS = 5;

	public PoolSettings {
		if (maxProcesses <= 0) {
			maxProcesses = DEFAULT_MAX_PROCESSES;
		}
		if (maxSessionsPerProcess <= 0) {
			maxSessionsPerProcess = DEFAULT_MAX_SESSIONS_PER_PROCESS;
		}
		if (maxRestarts < 0) {
			maxRestarts = DEFAULT_MAX_RESTARTS;
		}
	}

	public static PoolSettings defaults() {
		return new PoolSettings(DEFAULT_MAX_PROCESSES, DEFAULT_MAX_SESSIONS_PER_PROCESS, DEFAULT_MAX_RESTARTS);
	}

	/**
	 * The total number of named sessions this pool will hold before it refuses a new one.
	 */
	public int capacity() {
		return maxProcesses * maxSessionsPerProcess;
	}
}
