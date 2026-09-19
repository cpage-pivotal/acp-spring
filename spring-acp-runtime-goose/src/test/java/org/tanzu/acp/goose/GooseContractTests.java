package org.tanzu.acp.goose;

import org.junit.jupiter.api.condition.EnabledIf;
import org.tanzu.acp.runtime.AgentRuntime;
import org.tanzu.acp.test.AgentProbe;
import org.tanzu.acp.test.AgentRuntimeContract;

/**
 * The portable contract, against a real {@code goose acp} subprocess.
 *
 * <p>Skipped when Goose is absent or cannot reach a provider from this machine, so the suite stays
 * green on a machine that has never installed it.
 */
@EnabledIf("usable")
class GooseContractTests extends AgentRuntimeContract {

	static boolean usable() {
		return AgentProbe.isUsable(new GooseRuntime());
	}

	@Override
	protected AgentRuntime runtime() {
		return new GooseRuntime();
	}

	@Override
	protected java.util.Optional<String> reviewingMode() {
		return java.util.Optional.of("approve");
	}
}
