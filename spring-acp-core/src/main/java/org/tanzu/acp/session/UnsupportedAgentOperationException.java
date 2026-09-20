package org.tanzu.acp.session;

import org.tanzu.acp.client.AgentClientException;

/**
 * An operation this library offers that the agent in front of it does not implement.
 *
 * <p>The counterpart to {@code UnsupportedAgentOptionException}, one level up: that one is about a
 * <em>value</em> an agent will not take, this one about a <em>method</em> it does not have. ACP
 * makes both possible and neither avoidable — {@code session/list}, {@code session/delete} and
 * {@code session/resume} are each gated on a capability the agent advertises — so an abstraction
 * over several agents has to have a way of saying "not here", and saying it by name.
 */
public class UnsupportedAgentOperationException extends AgentClientException {

	private final transient AgentSessions.Operation operation;

	public UnsupportedAgentOperationException(String runtimeId, AgentSessions.Operation operation) {
		super("Runtime '" + runtimeId + "' does not support " + operation.method()
				+ "; the agent did not advertise it in its capabilities");
		this.operation = operation;
	}

	public AgentSessions.Operation operation() {
		return operation;
	}
}
