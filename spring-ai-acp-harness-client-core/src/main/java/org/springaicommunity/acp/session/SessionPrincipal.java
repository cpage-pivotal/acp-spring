package org.springaicommunity.acp.session;

import org.springaicommunity.acp.config.Validation;

/**
 * Who a session is being opened for, in a multi-user application.
 *
 * <p>
 * Two things hang off it. The MCP credentials a session is given are that person's, not
 * the application's: an {@code McpCredentialsProvider} is asked with this name, and
 * whatever token it returns is only ever sent on behalf of the session it was asked for.
 * And a named session belongs to the principal that opened it, so a second user who
 * happens to prompt the same name is refused rather than handed the first user's
 * conversation — and with it the first user's tools.
 *
 * <p>
 * Just a name, deliberately. It is compared by name, logged by nobody, and carries no
 * credentials of its own: the provider that turns it into a token already knows where
 * tokens live. An application that has no users never needs one, and nothing about its
 * sessions changes.
 *
 * @param name the stable identifier of the user, typically
 * {@code Authentication.getName()}
 */
public record SessionPrincipal(String name) {

	public SessionPrincipal {
		Validation.requireText(name, "session principal name");
	}

	public static SessionPrincipal of(String name) {
		return new SessionPrincipal(name);
	}

	/**
	 * Never the name itself: a log line is the wrong place to learn who is using the
	 * application.
	 */
	@Override
	public String toString() {
		return "SessionPrincipal[redacted]";
	}
}
