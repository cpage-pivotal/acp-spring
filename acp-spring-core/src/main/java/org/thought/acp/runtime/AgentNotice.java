package org.thought.acp.runtime;

/**
 * Something the agent reported about itself that the protocol has no way to carry.
 *
 * <p>The motivating case is an MCP server the agent could not connect to. ACP says nothing about
 * it — {@code session/new} succeeds, no {@code session/update} arrives, and the application gets a
 * session whose model quietly has no tools — while the agent writes a perfectly clear sentence
 * about it to a log file of its own. A notice is that sentence, recovered.
 *
 * <p>Deliberately three fields. Anything richer would be this library modelling one agent's
 * diagnostics, and an adapter that needed more would be a sign the protocol should carry it.
 *
 * @param severity how the agent rated it
 * @param subject what it is about, spelled the way the application spelled it — an MCP server name,
 * so a notice can be matched against what was requested
 * @param detail the agent's own words, already redacted
 */
public record AgentNotice(Severity severity, String subject, String detail) {

	public AgentNotice {
		if (severity == null) {
			throw new IllegalArgumentException("notice severity must not be null");
		}
		subject = subject == null ? "" : subject.strip();
		detail = detail == null ? "" : detail.strip();
	}

	public static AgentNotice warning(String subject, String detail) {
		return new AgentNotice(Severity.WARNING, subject, detail);
	}

	public static AgentNotice error(String subject, String detail) {
		return new AgentNotice(Severity.ERROR, subject, detail);
	}

	/** Whether this notice is about {@code name}, matched the way an application would spell it. */
	public boolean concerns(String name) {
		return name != null && !subject.isEmpty() && subject.equalsIgnoreCase(name.strip());
	}

	@Override
	public String toString() {
		return subject.isEmpty() ? detail : subject + ": " + detail;
	}

	public enum Severity {

		WARNING, ERROR

	}
}
