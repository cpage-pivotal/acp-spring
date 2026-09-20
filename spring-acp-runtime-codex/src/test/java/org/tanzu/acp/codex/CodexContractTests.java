package org.tanzu.acp.codex;

import org.junit.jupiter.api.condition.EnabledIf;
import org.tanzu.acp.runtime.AgentRuntime;
import org.tanzu.acp.test.AgentProbe;
import org.tanzu.acp.test.AgentRuntimeContract;

/**
 * The portable contract, against the real Codex ACP adapter.
 *
 * <p>Skipped unless {@code npx} can fetch the adapter and Codex is authenticated here — the first
 * run downloads the package, which is why the probe's timeout is generous.
 */
@EnabledIf("usable")
class CodexContractTests extends AgentRuntimeContract {

	static boolean usable() {
		return AgentProbe.isUsable(new CodexRuntime());
	}

	@Override
	protected AgentRuntime runtime() {
		return new CodexRuntime();
	}

	/**
	 * The cheap end of the catalog Codex advertised when this was written.
	 *
	 * <p>Nothing the contract asserts needs a frontier model — it is measuring turn termination,
	 * cancellation and permission denial, not answer quality — and the suite runs several live turns
	 * per build. If the account's Codex stops offering this name the base class falls back to the
	 * model Codex is already configured with, so the suite keeps running and only the saving lapses.
	 */
	@Override
	protected java.util.List<String> preferredModels() {
		return java.util.List.of("gpt-5.6-luna");
	}

	/**
	 * {@code plan}, not {@code read-only}.
	 *
	 * <p>Measured: {@code mode: read-only} — "always ask to edit external files" — lets Codex write
	 * inside the session's own cwd without asking at all, so a client that trusted the name would
	 * believe it had a review gate it did not have. {@code collaboration_mode: plan} asks, is refused,
	 * and writes nothing.
	 *
	 * <p>Worth noticing what carries it: {@code plan} is a value of {@code mode} on OpenCode and of
	 * {@code collaboration_mode} here, and {@code spring.acp.mode: plan} reaches both, because
	 * {@link CodexRuntime#configIdsFor} offers the resolver two candidates and it takes whichever one
	 * actually has the value.
	 */
	@Override
	protected java.util.Optional<String> reviewingMode() {
		return java.util.Optional.of("plan");
	}
}
