package org.springaicommunity.acp.registry;

import java.util.List;
import java.util.Optional;

import org.springaicommunity.acp.runtime.AcpAgentRuntime;
import org.springaicommunity.acp.runtime.AcpAgentRuntimeProvider;

/**
 * Answers for any agent in the ACP registry that has no compiled adapter.
 *
 * <p>
 * Nothing is downloaded here. Resolving an id reads the snapshot and builds a runtime;
 * the agent itself is fetched the first time that runtime is launched, which is the first
 * time a prompt runs. That keeps a misconfigured agent out of context refresh — the rule
 * the whole library follows: an application healthy apart from its agent stays up.
 */
public class RegistryAcpAgentRuntimeProvider implements AcpAgentRuntimeProvider {

	private final AcpAgentRegistry registry;

	private final AcpAgentInstaller installer;

	public RegistryAcpAgentRuntimeProvider(AcpAgentRegistry registry) {
		this(registry, new AcpAgentInstaller(registry.settings()));
	}

	public RegistryAcpAgentRuntimeProvider(AcpAgentRegistry registry, AcpAgentInstaller installer) {
		this.registry = registry;
		this.installer = installer;
	}

	@Override
	public Optional<AcpAgentRuntime> forId(String runtimeId) {
		return registry.find(runtimeId).map(entry -> new RegistryAcpAgentRuntime(entry, installer));
	}

	@Override
	public List<String> knownIds() {
		return registry.ids();
	}

}
