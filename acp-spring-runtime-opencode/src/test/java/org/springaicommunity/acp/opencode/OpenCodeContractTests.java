package org.springaicommunity.acp.opencode;

import org.junit.jupiter.api.condition.EnabledIf;
import org.springaicommunity.acp.runtime.AgentRuntime;
import org.springaicommunity.acp.test.AgentProbe;
import org.springaicommunity.acp.test.AgentRuntimeContract;

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

	/**
	 * OpenCode qualifies its model ids with the provider, so these are not Goose's spellings.
	 *
	 * <p>Same reasoning as the other two runtimes: the cheap end of the agentic coding line, with a
	 * cheaper fallback behind it. OpenCode also advertises free {@code opencode/*} models, which are
	 * not used here — a rate-limited free tier turns a contract failure into a coin toss, and a flaky
	 * suite costs more than the tokens it saves.
	 */
	@Override
	protected java.util.List<String> preferredModels() {
		return java.util.List.of("openai/gpt-5.6-luna", "openai/gpt-5.4-mini");
	}

	@Override
	protected java.util.Optional<String> reviewingMode() {
		return java.util.Optional.of("plan");
	}
}
