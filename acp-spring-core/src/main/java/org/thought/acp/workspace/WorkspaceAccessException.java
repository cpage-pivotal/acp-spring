package org.thought.acp.workspace;

import org.thought.acp.client.AgentClientException;

/**
 * An agent asked this client to touch something outside the workspace, or something it was not
 * lent access to at all.
 *
 * <p>Thrown rather than answered quietly because the agent is the one that has to change course:
 * ACP turns a handler error into a JSON-RPC error on the request the agent made, which is exactly
 * the signal a well-behaved agent needs to try somewhere else. The message names the path it asked
 * for and never the path it resolved to — a jail that reports where the boundary is has told an
 * agent how to probe it.
 */
public class WorkspaceAccessException extends AgentClientException {

	public WorkspaceAccessException(String message) {
		super(message);
	}

	public WorkspaceAccessException(String message, Throwable cause) {
		super(message, cause);
	}
}
