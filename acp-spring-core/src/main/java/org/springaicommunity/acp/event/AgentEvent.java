package org.springaicommunity.acp.event;

import java.util.List;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * A single observable moment in an agent turn, runtime-neutral.
 *
 * <p>Every turn emits exactly one terminal event — {@link Completed} or {@link Failed} — whether it
 * ends normally, errors, times out, loses its transport, or is cancelled by the consumer. Consumers
 * switch over this type without a default branch, so a turn that emitted no terminal event would
 * hang them forever.
 */
public sealed interface AgentEvent {

	/** Assistant-visible text. */
	record Text(String text) implements AgentEvent {
	}

	/** Reasoning the agent chose to surface; not part of the answer. */
	record Thought(String text) implements AgentEvent {
	}

	/** A tool invocation has started. */
	record ToolCallStarted(String id, String title, AcpSchema.ToolKind kind) implements AgentEvent {
	}

	/** Progress or completion of a previously started tool call. */
	record ToolCallUpdated(String id, AcpSchema.ToolCallStatus status, List<AcpSchema.ToolCallContent> content)
			implements AgentEvent {
	}

	/** The agent's current plan. Replaces any plan emitted earlier in the turn. */
	record PlanUpdated(List<AcpSchema.PlanEntry> entries) implements AgentEvent {
	}

	/** The agent changed its own session configuration mid-turn. */
	record ConfigChanged(List<AcpSchema.SessionConfigOption> options) implements AgentEvent {
	}

	/** The agent switched session mode mid-turn. */
	record ModeChanged(String modeId) implements AgentEvent {
	}

	/**
	 * The agent's own accounting for the session: context window consumed, and cost when it says.
	 *
	 * <p>Deferred from M1 for a reason that turned out to be half right. goose returns token counts
	 * on the {@code session/prompt} response, so there was nowhere for an event to come from — but
	 * that field is goose's own and not in the schema, while {@code usage_update} <em>is</em>, and
	 * carries the numbers an application actually wants to watch. So this comes from the
	 * notification rather than the response, and means the same thing on every agent that sends it.
	 *
	 * <p>Cumulative for the session, not incremental for the turn. {@code used} counts against
	 * {@code size}, the context window; {@code cost} is optional and absent from most agents.
	 */
	record UsageUpdated(long contextUsed, long contextSize, Double costAmount, String costCurrency)
			implements AgentEvent {

		/** How much of the context window is gone, when the agent said how big it is. */
		public java.util.OptionalDouble contextFraction() {
			return contextSize <= 0 ? java.util.OptionalDouble.empty()
					: java.util.OptionalDouble.of((double) contextUsed / contextSize);
		}
	}

	/** Terminal: the turn ended and the agent said why. */
	record Completed(AcpSchema.StopReason reason) implements AgentEvent {
	}

	/** Terminal: the turn did not reach the agent's own stop condition. */
	record Failed(Throwable cause) implements AgentEvent {
	}

	/** Whether this is the one terminal event of its turn. */
	default boolean terminal() {
		return this instanceof Completed || this instanceof Failed;
	}
}
