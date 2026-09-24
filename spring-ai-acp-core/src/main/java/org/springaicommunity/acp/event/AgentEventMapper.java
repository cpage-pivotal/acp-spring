package org.springaicommunity.acp.event;

import java.util.List;
import java.util.Optional;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Maps ACP {@code session/update} payloads onto {@link AgentEvent}.
 *
 * <p>
 * Two deliberate omissions. {@code user_message_chunk} is dropped: it echoes the prompt
 * we just sent, and replaying it to the caller as agent output is wrong. Updates carrying
 * {@code _meta.replay} are dropped for the same reason — {@code session/load} replays
 * history through the same channel a live turn uses, and history is not this turn's
 * output.
 */
public final class AgentEventMapper {

	private AgentEventMapper() {
	}

	/**
	 * @return the event this update represents, or empty when it carries nothing a caller
	 * needs
	 */
	public static Optional<AgentEvent> map(AcpSchema.SessionUpdate update) {
		if (update == null) {
			return Optional.empty();
		}
		return switch (update) {
			case AcpSchema.AgentMessageChunk c ->
				isReplay(c.meta()) ? Optional.empty() : text(c.content()).map(AgentEvent.Text::new);
			case AcpSchema.AgentThoughtChunk c ->
				isReplay(c.meta()) ? Optional.empty() : text(c.content()).map(AgentEvent.Thought::new);
			case AcpSchema.ToolCall c -> isReplay(c.meta()) ? Optional.empty()
					: Optional.of(new AgentEvent.ToolCallStarted(c.toolCallId(), c.title(), c.kind()));
			case AcpSchema.ToolCallUpdateNotification c -> isReplay(c.meta()) ? Optional.empty()
					: Optional.of(new AgentEvent.ToolCallUpdated(c.toolCallId(), c.status(), c.content()));
			case AcpSchema.Plan c -> Optional.of(new AgentEvent.PlanUpdated(entries(c.entries())));
			case AcpSchema.ConfigOptionUpdate c ->
				Optional.of(new AgentEvent.ConfigChanged(options(c.configOptions())));
			case AcpSchema.CurrentModeUpdate c -> Optional.of(new AgentEvent.ModeChanged(c.currentModeId()));
			case AcpSchema.UsageUpdate c -> usage(c);
			// user_message_chunk, available_commands_update: nothing a caller of a turn
			// needs.
			default -> Optional.empty();
		};
	}

	/**
	 * Extracts readable text from a content block. Images, audio and resource links carry
	 * no text and are skipped rather than rendered as a placeholder.
	 */
	private static Optional<String> text(AcpSchema.ContentBlock content) {
		return content instanceof AcpSchema.TextContent t && t.text() != null && !t.text().isEmpty()
				? Optional.of(t.text()) : Optional.empty();
	}

	private static boolean isReplay(java.util.Map<String, Object> meta) {
		return meta != null && Boolean.TRUE.equals(meta.get("replay"));
	}

	private static List<AcpSchema.PlanEntry> entries(List<AcpSchema.PlanEntry> e) {
		return e == null ? List.of() : List.copyOf(e);
	}

	private static List<AcpSchema.SessionConfigOption> options(List<AcpSchema.SessionConfigOption> o) {
		return o == null ? List.of() : List.copyOf(o);
	}

	/**
	 * Usage is dropped when the agent sent no numbers at all.
	 *
	 * <p>
	 * {@code used} and {@code size} are required by the schema and boxed by the SDK, so
	 * an update with neither is an agent sending the notification for the sake of its
	 * {@code cost} field or for nothing. Emitting {@code 0 of 0} would read as an empty
	 * context window rather than as no measurement, which is the wrong claim to put in
	 * front of a gauge.
	 */
	private static Optional<AgentEvent> usage(AcpSchema.UsageUpdate update) {
		if (update.used() == null && update.size() == null) {
			return Optional.empty();
		}
		AcpSchema.Cost cost = update.cost();
		return Optional.of(new AgentEvent.UsageUpdated(update.used() == null ? 0L : update.used(),
				update.size() == null ? 0L : update.size(), cost == null ? null : cost.amount(),
				cost == null ? null : cost.currency()));
	}

}
