package org.springaicommunity.acp.goose;

import org.junit.jupiter.api.condition.EnabledIf;
import org.springaicommunity.acp.runtime.AgentRuntime;
import org.springaicommunity.acp.test.AgentProbe;
import org.springaicommunity.acp.test.AgentRuntimeContract;

/**
 * The portable contract, against a real {@code goose acp} subprocess.
 *
 * <p>
 * Skipped when Goose is absent or cannot reach a provider from this machine, so the suite
 * stays green on a machine that has never installed it.
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

	/**
	 * Goose's model ids are bare, because the provider is chosen separately.
	 *
	 * <p>
	 * {@code gpt-6-luna} is the cheap end of the agentic coding line and still follows an
	 * instruction and calls a tool, which is all the contract asks of it. Goose
	 * advertises whatever its configured provider sells, so on a machine pointed at a
	 * different provider the name doesn't match and the base class falls back to the
	 * model Goose already has.
	 */
	@Override
	protected java.util.List<String> preferredModels() {
		return java.util.List.of("gpt-6-luna");
	}

	@Override
	protected java.util.Optional<String> reviewingMode() {
		return java.util.Optional.of("approve");
	}

}
