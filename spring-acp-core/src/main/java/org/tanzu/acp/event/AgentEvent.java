package org.tanzu.acp.event;

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
