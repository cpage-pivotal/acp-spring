package org.springaicommunity.acp.mcp.oauth;

import java.util.Optional;

import org.springaicommunity.acp.session.SessionPrincipal;
import org.springaicommunity.acp.session.SessionPrincipalResolver;

/**
 * The person running this terminal application: the operating-system user.
 *
 * <p>
 * There is exactly one, and every session is theirs, so their tokens are kept under their
 * name and a second user on the same machine — with their own config directory — keeps
 * their own.
 */
public final class LocalPrincipalResolver implements SessionPrincipalResolver {

	private final Optional<SessionPrincipal> user = Optional.of(SessionPrincipal.of(System.getProperty("user.name")));

	@Override
	public Optional<SessionPrincipal> current() {
		return user;
	}

}
