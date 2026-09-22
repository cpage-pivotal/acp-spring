package org.thought.acp.mcp.oauth;

import java.util.Optional;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.thought.acp.session.SessionPrincipal;
import org.thought.acp.session.SessionPrincipalResolver;

/**
 * The signed-in user of the current request, as a session principal.
 *
 * <p>Correct because a resolver is asked on the caller's thread when a turn is described, and in a
 * servlet application that is the request's thread, where {@link SecurityContextHolder} holds its
 * user. On a reactive stack there is no such thread; pass the principal to the prompt instead.
 *
 * <p>An anonymous user is nobody. Spring Security keeps an anonymous user's OAuth tokens in the
 * HTTP session rather than in the {@code OAuth2AuthorizedClientService} the proxy reads, so treating
 * one as a principal would send them to sign in to an MCP server and then never find the token.
 */
public final class SecurityContextPrincipalResolver implements SessionPrincipalResolver {

	@Override
	public Optional<SessionPrincipal> current() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| authentication instanceof AnonymousAuthenticationToken) {
			return Optional.empty();
		}
		return Optional.of(SessionPrincipal.of(authentication.getName()));
	}
}
