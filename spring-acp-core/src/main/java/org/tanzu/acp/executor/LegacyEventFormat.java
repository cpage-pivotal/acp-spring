package org.tanzu.acp.executor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.tanzu.acp.event.AgentEvent;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Renders {@link AgentEvent} as the newline-delimited JSON {@code goose run --output-format
 * stream-json} emitted, which the buildpack's wrapper then reproduced and its consumers parse.
 *
 * <p>This is a compatibility boundary, not an internal detail: applications switch on {@code type}
 * without a default branch, so the three shapes are fixed — {@code message} carries content,
 * {@code notification} carries anything a human should see but the answer should not contain, and
 * exactly one {@code complete} ends the stream. A consumer that never receives {@code complete}
 * waits forever, which is why the terminal event is rendered even for a failure.
 *
 * <p>One field is rendered as a lie by omission and it is worth saying so: {@code total_tokens} is
 * always zero. Goose reports token counts on the {@code session/prompt} response rather than as an
 * update, and this library does not currently surface that; Micrometer observations in M4 are
 * where the real number will come from.
 */
final class LegacyEventFormat {

	private final AcpJsonMapper json = AcpJsonMapper.createDefault();

	private final String extensionId;

	LegacyEventFormat(String extensionId) {
		this.extensionId = extensionId;
	}

	/** Zero or more lines for one event. Most events produce exactly one; some produce none. */
	List<String> render(AgentEvent event) {
		return switch (event) {
			case AgentEvent.Text text -> List.of(message("assistant", textContent(text.text())));
			case AgentEvent.Thought thought -> List.of(notification(thought.text()));
			case AgentEvent.ToolCallStarted started -> List.of(message("assistant", toolRequest(started)));
			case AgentEvent.ToolCallUpdated updated -> toolResponse(updated);
			case AgentEvent.Completed ignored -> List.of(complete());
			case AgentEvent.Failed failed -> List.of(notification(describe(failed.cause())), complete());
			default -> List.of();
		};
	}

	private List<String> toolResponse(AgentEvent.ToolCallUpdated updated) {
		boolean finished = updated.status() == AcpSchema.ToolCallStatus.COMPLETED
				|| updated.status() == AcpSchema.ToolCallStatus.FAILED;
		if (!finished) {
			return List.of();
		}
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("type", "toolResponse");
		item.put("id", updated.id());
		item.put("is_error", updated.status() == AcpSchema.ToolCallStatus.FAILED);
		return List.of(message("user", item));
	}

	private Map<String, Object> toolRequest(AgentEvent.ToolCallStarted started) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("name", started.title());
		value.put("arguments", Map.of());

		Map<String, Object> toolCall = new LinkedHashMap<>();
		toolCall.put("status", "success");
		toolCall.put("value", value);

		Map<String, Object> item = new LinkedHashMap<>();
		item.put("type", "toolRequest");
		item.put("id", started.id());
		item.put("toolCall", toolCall);
		return item;
	}

	private Map<String, Object> textContent(String text) {
		Map<String, Object> content = new LinkedHashMap<>();
		content.put("type", "text");
		content.put("text", text);
		return content;
	}

	private String message(String role, Map<String, Object> contentItem) {
		List<Object> content = new ArrayList<>();
		content.add(contentItem);

		Map<String, Object> message = new LinkedHashMap<>();
		message.put("role", role);
		message.put("content", content);

		Map<String, Object> event = new LinkedHashMap<>();
		event.put("type", "message");
		event.put("message", message);
		return write(event);
	}

	String notification(String text) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("type", "notification");
		event.put("extension_id", extensionId);
		event.put("data", Map.of("message", text == null ? "" : text));
		return write(event);
	}

	String complete() {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("type", "complete");
		event.put("total_tokens", 0);
		return write(event);
	}

	private static String describe(Throwable cause) {
		if (cause == null) {
			return "The turn failed";
		}
		return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
	}

	private String write(Map<String, Object> event) {
		try {
			return json.writeValueAsString(event);
		}
		catch (Exception ex) {
			// Every value here was put in by this class, so this cannot happen for data reasons.
			throw new IllegalStateException("Could not render a legacy event", ex);
		}
	}
}
