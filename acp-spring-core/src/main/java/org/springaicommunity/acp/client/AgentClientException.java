package org.springaicommunity.acp.client;

/** Something went wrong talking to the agent. */
public class AgentClientException extends RuntimeException {

	public AgentClientException(String message) {
		super(message);
	}

	public AgentClientException(String message, Throwable cause) {
		super(message, cause);
	}
}
