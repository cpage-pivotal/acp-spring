package org.springaicommunity.acp.workspace;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Whether the agent may ask this client to run commands, and which ones.
 *
 * <p>The most dangerous capability in the protocol, and the one whose defaults matter most: a
 * terminal is arbitrary code execution as the JVM's user, inside a process that has the
 * application's credentials on its environment. Off by default, and when it is on, the commands run
 * inside the workspace jail with their output bounded.
 *
 * <p>{@code allowedCommands} is empty by default and empty means <em>all</em>, which reads
 * backwards until you notice that the capability itself is the gate: an application that turned
 * terminals on and then allowed nothing would have built something that cannot work. The list is
 * there for the case worth having — an application that wants the agent to be able to run its test
 * suite and nothing else. Matching is on the command name alone, since the arguments are the
 * agent's to choose.
 */
public record TerminalAccess(boolean enabled, Set<String> allowedCommands, long outputByteLimit,
		Duration commandTimeout, int maxConcurrent) {

	public static final long DEFAULT_OUTPUT_BYTE_LIMIT = 1024L * 1024;

	public static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofMinutes(5);

	public static final int DEFAULT_MAX_CONCURRENT = 8;

	public TerminalAccess {
		allowedCommands = allowedCommands == null ? Set.of()
				: allowedCommands.stream().map(c -> c.toLowerCase(Locale.ROOT))
						.collect(Collectors.toUnmodifiableSet());
		outputByteLimit = outputByteLimit <= 0 ? DEFAULT_OUTPUT_BYTE_LIMIT : outputByteLimit;
		commandTimeout = commandTimeout == null ? DEFAULT_COMMAND_TIMEOUT : commandTimeout;
		maxConcurrent = maxConcurrent <= 0 ? DEFAULT_MAX_CONCURRENT : maxConcurrent;
	}

	public static TerminalAccess disabled() {
		return new TerminalAccess(false, Set.of(), 0, null, 0);
	}

	/** Terminals on, every command allowed, with the default limits. */
	public static TerminalAccess unrestricted() {
		return new TerminalAccess(true, Set.of(), 0, null, 0);
	}

	/** Terminals on, limited to the named commands. */
	public static TerminalAccess allowing(Set<String> commands) {
		return new TerminalAccess(true, commands, 0, null, 0);
	}

	/**
	 * Whether {@code command} may run. The comparison is on the file name, so
	 * {@code /usr/local/bin/mvn} and {@code mvn} are the same command — an allowlist that could be
	 * evaded by spelling out the path would not be one.
	 */
	public boolean permits(String command) {
		if (!enabled) {
			return false;
		}
		if (allowedCommands.isEmpty()) {
			return true;
		}
		if (command == null || command.isBlank()) {
			return false;
		}
		String name = command.substring(Math.max(command.lastIndexOf('/'), command.lastIndexOf('\\')) + 1);
		return allowedCommands.contains(name.toLowerCase(Locale.ROOT));
	}
}
