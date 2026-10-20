package org.springaicommunity.acp.protocol;

import org.springaicommunity.acp.client.AcpAgentClientException;

/**
 * The agent and this library did not end up on a version both can speak.
 *
 * <p>
 * Thrown during the handshake rather than during a turn, which is the point: the
 * alternative is a conversation that decodes into the wrong shapes and fails somewhere
 * further on, where nothing names the cause.
 */
public class UnsupportedProtocolVersionException extends AcpAgentClientException {

	public UnsupportedProtocolVersionException(String message) {
		super(message);
	}

}
