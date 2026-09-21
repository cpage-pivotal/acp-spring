package org.thought.acp.config;

import java.time.Duration;

/**
 * What to do when the agent reports that it could not load one of the configured MCP servers.
 *
 * <p>Needed because the default — carry on — is the behaviour that costs an afternoon. The agent
 * connects, the model answers, and the only symptom of a server that never loaded is the model
 * saying in prose that it lacks tools the application believes it has. An application that would
 * rather not start at all than serve a half-equipped agent says so here.
 *
 * @param onServerFailure what a reported failure does to the session that requested the server
 * @param detectTimeout how long {@code session/new} waits for such a report before carrying on
 */
public record McpSettings(OnServerFailure onServerFailure, Duration detectTimeout) {

	/**
	 * Long enough for the agent to have written the line, short enough to pay on every session open.
	 *
	 * <p>Measured against goose 1.51.0, the warning and the {@code session/new} reply land within the
	 * same tenth of a second, in an order that is not guaranteed either way — so reading once,
	 * synchronously, would be a race. Two seconds is the margin over the observed gap, and it is only
	 * ever waited out in full when {@code on-server-failure} is {@code fail}.
	 */
	public static final Duration DEFAULT_DETECT_TIMEOUT = Duration.ofSeconds(2);

	public McpSettings {
		onServerFailure = onServerFailure == null ? OnServerFailure.WARN : onServerFailure;
		detectTimeout = detectTimeout == null ? DEFAULT_DETECT_TIMEOUT : detectTimeout;
		if (detectTimeout.isNegative()) {
			throw new IllegalArgumentException("mcp detect-timeout must not be negative but was " + detectTimeout);
		}
	}

	public static McpSettings defaults() {
		return new McpSettings(OnServerFailure.WARN, DEFAULT_DETECT_TIMEOUT);
	}

	public enum OnServerFailure {

		/**
		 * Log it at WARN and open the session anyway. The default, because a missing MCP server is
		 * not always fatal and because no runtime but Goose can report one at all today.
		 */
		WARN,

		/**
		 * Throw from {@code openSession}, naming the server and quoting the agent.
		 *
		 * <p>Only ever triggered by a report the agent actually made. Nothing here probes a server or
		 * infers a failure from silence: an agent that says nothing opens the session, whatever this
		 * is set to. See "MCP servers fail silently" in {@code docs/design.md}.
		 */
		FAIL

	}
}
