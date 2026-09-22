package org.springaicommunity.acp.session;

/**
 * A session name is already open on behalf of someone else.
 *
 * <p>The message names the session but neither principal: whoever receives it is, by definition,
 * not the person the session belongs to.
 */
public final class SessionOwnershipException extends IllegalStateException {

	public SessionOwnershipException(String sessionName) {
		super("Session '" + sessionName + "' is open on behalf of a different principal; "
				+ "session names must be unique per user");
	}
}
