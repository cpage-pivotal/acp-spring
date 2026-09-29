package org.springaicommunity.acp.goose;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Reads goose's todo list out of the tool call that writes it.
 *
 * <p>
 * Goose's {@code todo} extension keeps the agent's task list as one markdown document,
 * rewritten whole by {@code todo__todo_write}, and goose reports each write as an
 * ordinary ACP {@code tool_call}: the document in {@code rawInput.content}, the tool in
 * {@code _meta.goose.toolCall.toolName}. It sends no ACP {@code plan} update. Measured
 * against goose 1.52.0:
 *
 * <pre>{@code
 * {"sessionUpdate":"tool_call","title":"todo: todo write",
 *  "rawInput":{"content":"- [x] Gather data\n- [ ] Draft report"},
 *  "_meta":{"goose":{"toolCall":{"toolName":"todo__todo_write","extensionName":"todo"}}}}
 * }</pre>
 *
 * <p>
 * The document's shape is the model's choice. Goose's instructions show a checkbox
 * template, and that is the reading this class relies on; a model that writes "Draft
 * report — in progress", "Draft report [DONE]" or "[DONE] Draft report" instead is read
 * by its status word, which is not kept in the entry's text. A line that is neither a
 * list item nor a checkbox — a heading, a sentence — is not an entry. A document with no
 * entries at all is not read as a plan, so the call is reported as the tool call it is,
 * except an empty one, which is the agent clearing its list.
 *
 * <p>
 * Sub-items become entries of their own: ACP's plan is flat. Every entry is
 * {@code medium} priority, because the document carries none.
 */
final class TodoPlan {

	/** The tool, as goose names it in {@code _meta}: extension, two underscores, tool. */
	static final String TOOL_NAME = "todo__todo_write";

	/**
	 * {@code - item}, {@code * item}, {@code + item}, {@code 1. item}, {@code 1) item}.
	 */
	private static final Pattern LIST_ITEM = Pattern.compile("^(?:[-*+]|\\d+[.)])\\s+(.*)$");

	/**
	 * {@code [ ]}, {@code [x]}; {@code [-]}, {@code [~]}, {@code [/]} and {@code [>]} for
	 * in progress.
	 */
	private static final Pattern CHECKBOX = Pattern.compile("^\\[([ xX\\-~/>])\\]\\s*(.*)$");

	/** The words a model marks an item's status with, whatever it wraps them in. */
	private static final String STATUS = "(done|complete|completed|finished|in[ _-]progress|ongoing|started|pending"
			+ "|not[ _-]started|to ?do)";

	/**
	 * "Draft report — done", "Draft report: in progress", "Draft report (not started)",
	 * "Draft report [DONE]".
	 */
	private static final Pattern TRAILING_STATUS = Pattern.compile(
			"^(.*?)\\s*(?:[—–:-]\\s*[(\\[]?|[(\\[])\\s*" + STATUS + "\\s*[)\\]]?\\s*\\.?$", Pattern.CASE_INSENSITIVE);

	/** "[DONE] Draft report", "(in progress) Draft report". */
	private static final Pattern LEADING_STATUS = Pattern.compile("^[(\\[]\\s*" + STATUS + "\\s*[)\\]]\\s*(.+)$",
			Pattern.CASE_INSENSITIVE);

	/** "✅ Draft report", "✔ Draft report", "☑ Draft report". */
	private static final Pattern CHECKED = Pattern.compile("^(?:\u2705|\u2714\uFE0F?|\u2611\uFE0F?)\\s*(.+)$");

	private static final Pattern STRUCK_THROUGH = Pattern.compile("^~~(.+)~~$");

	private TodoPlan() {
	}

	/**
	 * @return the whole plan, when {@code toolCall} is goose's todo write and its content
	 * reads as one
	 */
	static Optional<List<AcpSchema.PlanEntry>> of(AcpSchema.ToolCall toolCall) {
		if (toolCall == null || !TOOL_NAME.equals(toolName(toolCall.meta()))
				|| !(toolCall.rawInput() instanceof Map<?, ?> input)
				|| !(input.get("content") instanceof String content)) {
			return Optional.empty();
		}
		if (content.isBlank()) {
			return Optional.of(List.of());
		}
		List<AcpSchema.PlanEntry> entries = new ArrayList<>();
		for (String line : content.split("\\R")) {
			entry(line.strip()).ifPresent(entries::add);
		}
		return entries.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(entries));
	}

	/** {@code _meta.goose.toolCall.toolName}, or null. */
	private static String toolName(Map<String, Object> meta) {
		if (meta != null && meta.get("goose") instanceof Map<?, ?> goose
				&& goose.get("toolCall") instanceof Map<?, ?> toolCall
				&& toolCall.get("toolName") instanceof String name) {
			return name;
		}
		return null;
	}

	private static Optional<AcpSchema.PlanEntry> entry(String line) {
		Matcher item = LIST_ITEM.matcher(line);
		boolean listed = item.matches();
		String text = listed ? item.group(1).strip() : line;

		Matcher checkbox = CHECKBOX.matcher(text);
		if (checkbox.matches()) {
			AcpSchema.PlanEntryStatus ticked = switch (checkbox.group(1)) {
				case "x", "X" -> AcpSchema.PlanEntryStatus.COMPLETED;
				case " " -> AcpSchema.PlanEntryStatus.PENDING;
				default -> AcpSchema.PlanEntryStatus.IN_PROGRESS;
			};
			// "- [ ] Draft report [DONE]": the word is the later edit, so it wins over an
			// empty box, and is dropped from the text either way.
			Matcher trailing = TRAILING_STATUS.matcher(checkbox.group(2));
			if (trailing.matches()) {
				return entry(trailing.group(1),
						ticked == AcpSchema.PlanEntryStatus.PENDING ? status(trailing.group(2)) : ticked);
			}
			return entry(checkbox.group(2), ticked);
		}
		Matcher leading = LEADING_STATUS.matcher(text);
		if (leading.matches()) {
			return entry(leading.group(2), status(leading.group(1)));
		}
		if (!listed) {
			return Optional.empty();
		}
		Matcher checked = CHECKED.matcher(text);
		if (checked.matches()) {
			return entry(checked.group(1), AcpSchema.PlanEntryStatus.COMPLETED);
		}
		Matcher struck = STRUCK_THROUGH.matcher(text);
		if (struck.matches()) {
			return entry(struck.group(1), AcpSchema.PlanEntryStatus.COMPLETED);
		}
		Matcher trailing = TRAILING_STATUS.matcher(text);
		if (trailing.matches()) {
			return entry(trailing.group(1), status(trailing.group(2)));
		}
		return entry(text, AcpSchema.PlanEntryStatus.PENDING);
	}

	private static Optional<AcpSchema.PlanEntry> entry(String content, AcpSchema.PlanEntryStatus status) {
		String text = content.strip();
		return text.isEmpty() ? Optional.empty()
				: Optional.of(new AcpSchema.PlanEntry(text, AcpSchema.PlanEntryPriority.MEDIUM, status));
	}

	private static AcpSchema.PlanEntryStatus status(String word) {
		return switch (word.toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ')) {
			case "done", "complete", "completed", "finished" -> AcpSchema.PlanEntryStatus.COMPLETED;
			case "in progress", "ongoing", "started" -> AcpSchema.PlanEntryStatus.IN_PROGRESS;
			default -> AcpSchema.PlanEntryStatus.PENDING;
		};
	}

}
