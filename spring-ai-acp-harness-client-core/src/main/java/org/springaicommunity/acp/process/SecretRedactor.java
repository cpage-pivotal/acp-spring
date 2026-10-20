package org.springaicommunity.acp.process;

import java.util.regex.Pattern;

/**
 * Blanks anything on an agent's output that looks like a credential.
 *
 * <p>
 * Needed because a supervised agent's stdout goes into the application's log, and agents
 * print their own configuration when they start, when they fail to start, and whenever
 * their log level is raised to find out why. The pattern is deliberately shaped around
 * how these appear in practice — {@code key=value}, {@code "key": "value"},
 * {@code key: value} — rather than trying to recognise secrets by their content, which
 * cannot be done.
 *
 * <p>
 * It is a second line of defence, not the first: this library never logs the process
 * environment, and the first line is not putting the secret where it can be printed. A
 * redactor that catches most cases is still worth having for the log line nobody
 * predicted.
 */
public final class SecretRedactor {

	/**
	 * The optional scheme group is not decoration. Without it
	 * {@code Authorization: Bearer eyJ...} redacts the word "Bearer" and prints the
	 * token, because a single pass consumes the first thing after the separator and
	 * carries on from there.
	 */
	private static final Pattern SENSITIVE = Pattern
		.compile("(?i)(authorization|x-api-key|x-secret-key|api[_-]?key|secret[_-]?key|access[_-]?token"
				+ "|delegation[_-]?token|password|bearer)([\"'=:\\s]+)(?:bearer\\s+|basic\\s+|token\\s+)?"
				+ "([^\\s,}\"']+)");

	private SecretRedactor() {
	}

	public static String redact(String line) {
		return line == null ? null : SENSITIVE.matcher(line).replaceAll("$1$2[REDACTED]");
	}

}
