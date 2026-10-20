package org.springaicommunity.acp.client;

/** Something went wrong talking to the agent. */
public class AcpAgentClientException extends RuntimeException {

	public AcpAgentClientException(String message) {
		super(message);
	}

	public AcpAgentClientException(String message, Throwable cause) {
		super(message, cause);
	}

}
