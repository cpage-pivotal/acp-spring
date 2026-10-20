package org.springaicommunity.acp.permission;

import java.util.Optional;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Asks a person how to answer an agent's permission request.
 *
 * <p>
 * The human half of {@link PermissionPolicy#ask(PermissionPrompt)}, and a bean for the
 * same reason {@code AuthorizationPrompt} is one: only the application knows where its
 * person is — a terminal, a web page, a chat thread. Implementations may block for as
 * long as the person takes; the client calls them off the transport's threads so the rest
 * of the turn keeps flowing meanwhile.
 */
@FunctionalInterface
public interface PermissionPrompt {

	/**
	 * @param question what the agent wants to do, and the choices it offered
	 * @return the option the person chose, or empty to cancel the request
	 */
	Optional<AcpSchema.PermissionOption> ask(PermissionQuestion question);

}
