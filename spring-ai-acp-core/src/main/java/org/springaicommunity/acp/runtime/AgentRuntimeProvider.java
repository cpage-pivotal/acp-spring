package org.springaicommunity.acp.runtime;

import java.util.List;
import java.util.Optional;

/**
 * Makes an {@link AgentRuntime} for an id nobody registered a bean for.
 *
 * <p>
 * The difference from contributing an {@code AgentRuntime} directly is that this is asked
 * a question rather than announcing an answer. An adapter knows which agent it is and
 * registers itself; a provider is handed the id an application asked for and decides
 * whether it can build something for it. That is the shape the registry needs — 41 agents
 * in a catalogue, of which an application wants exactly one, and starting 41 beans to
 * find out would be absurd.
 *
 * <p>
 * Consulted only after the registered adapters have been searched, so a compiled adapter
 * always wins over a generic one for the same agent. An application that has both
 * {@code spring-ai-acp-runtime-goose} and {@code spring-ai-acp-runtime-registry} on its
 * classpath and asks for {@code goose} gets the adapter, with its {@code _meta} tool
 * names and its served transport, rather than a registry download of the same binary.
 */
public interface AgentRuntimeProvider {

	/**
	 * A runtime for {@code runtimeId}, or empty if this provider does not know that
	 * agent.
	 */
	Optional<AgentRuntime> forId(String runtimeId);

	/**
	 * Ids this provider could answer for, for error messages and for validating a tier-3
	 * block against a runtime that has no bean.
	 *
	 * <p>
	 * May be expensive, may be incomplete, and is never used to <em>choose</em> a runtime
	 * — {@link #forId} is. A provider that cannot enumerate returns an empty list and
	 * still works.
	 */
	default List<String> knownIds() {
		return List.of();
	}

}
