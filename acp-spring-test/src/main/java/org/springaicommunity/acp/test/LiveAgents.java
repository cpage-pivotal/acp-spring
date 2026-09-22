package org.springaicommunity.acp.test;

/**
 * The one switch that decides whether a suite is allowed to talk to a real agent.
 *
 * <p>Every live test here is charged to whoever runs it, in tokens, against their own key. The
 * conformance suite spends around ten turns per runtime, so a machine with all three agents logged
 * in was paying for thirty turns on every {@code mvn install} — including two agentic tool loops per
 * runtime, which cost more than the other eight turns together. That is the wrong trade for a build
 * run dozens of times a day: what changes between two builds is this library, and what the live
 * suite uniquely catches is the agents moving underneath it.
 *
 * <p>So the live suites are opt-in, and the always-on gate against protocol regressions is
 * {@link ScriptedAgent} — a fake agent speaking raw JSON-RPC, which is where the wire-format bugs
 * this library works around were caught in the first place. Run the live suites when an adapter
 * changes, before a release, and in CI:
 *
 * <pre>{@code
 * mvn test -Dacp-spring.test.live=true
 * }</pre>
 *
 * <p>Suites gated this way {@linkplain org.junit.jupiter.api.condition.EnabledIf skip} rather than
 * fail, exactly as they already do on a machine with no usable agent.
 */
public final class LiveAgents {

	/** Opt in with {@code -Dacp-spring.test.live=true}. */
	public static final String PROPERTY = "acp-spring.test.live";

	private LiveAgents() {
	}

	/** Whether this build has opted in to spending real turns against real agents. */
	public static boolean enabled() {
		return Boolean.parseBoolean(System.getProperty(PROPERTY));
	}
}
