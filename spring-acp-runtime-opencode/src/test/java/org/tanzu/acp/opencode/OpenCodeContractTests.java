package org.tanzu.acp.opencode;

import org.junit.jupiter.api.condition.EnabledIf;
import org.tanzu.acp.runtime.AgentRuntime;
import org.tanzu.acp.test.AgentProbe;
import org.tanzu.acp.test.AgentRuntimeContract;

/**
 * The portable contract, against a real {@code opencode acp} subprocess.
 *
 * <p>The interesting one of the three: OpenCode names its models {@code provider/model}, advertises
 * neither the legacy {@code modes}/{@code models} states nor the providers capability, and so passes
 * this suite through a different set of mechanisms than the other two. That it passes the same
 * assertions anyway is the claim the milestone is making.
 */
@EnabledIf("usable")
class OpenCodeContractTests extends AgentRuntimeContract {

	static boolean usable() {
		return AgentProbe.isUsable(new OpenCodeRuntime());
	}

	@Override
	protected AgentRuntime runtime() {
		return new OpenCodeRuntime();
	}

	@Override
	protected java.util.Optional<String> reviewingMode() {
		return java.util.Optional.of("plan");
	}
}
