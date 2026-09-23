package org.springaicommunity.acp.console;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.springaicommunity.acp.event.AgentEvent;
import org.springaicommunity.acp.permission.PermissionQuestion;
import org.springaicommunity.acp.runtime.AgentNotice;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Where every character the user sees is written.
 *
 * <p>Separate from the loop so that styling and the line discipline live in one place: assistant
 * text arrives as deltas and must not be printed line by line, while tool activity is whole lines
 * that have to interrupt it cleanly. Styles go through JLine rather than raw ANSI escapes, so a dumb
 * terminal gets plain text instead of escape codes.
 *
 * <p>Public and not final: an application with its own idea of how a turn should look registers a
 * subclass as a bean. Every method is synchronized because a permission question is asked from
 * another thread, part-way through a streamed answer.
 */
public class ConsoleRenderer {

	protected static final AttributedStyle DIM = AttributedStyle.DEFAULT.faint();

	protected static final AttributedStyle PROMPT = AttributedStyle.BOLD.foreground(AttributedStyle.CYAN);

	protected static final AttributedStyle ERROR = AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);

	protected static final AttributedStyle WARNING = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);

	protected static final AttributedStyle QUESTION = AttributedStyle.BOLD.foreground(AttributedStyle.YELLOW);

	private final Terminal terminal;

	private final boolean showThoughts;

	/** Titles of this turn's tool calls, because an update names its call only by id. */
	private final Map<String, String> toolTitles = new HashMap<>();

	/** Whether the cursor is part-way through a line of streamed assistant text. */
	private boolean midLine;

	/** The latest accounting of the turn, shown once when it ends rather than every time it moves. */
	private AgentEvent.UsageUpdated usage;

	public ConsoleRenderer(Terminal terminal, boolean showThoughts) {
		this.terminal = terminal;
		this.showThoughts = showThoughts;
	}

	/** The blank line that separates one exchange from the next, written before each prompt. */
	public synchronized void beforePrompt() {
		newlineIfNeeded();
		writer().println();
		writer().flush();
	}

	/** The prompt string handed to the line reader. */
	public synchronized String prompt() {
		return styled("you › ", PROMPT);
	}

	public synchronized void banner(String title, String runtime) {
		writer().println();
		writer().println(styled(title, AttributedStyle.BOLD));
		line(styled("runtime: " + runtime + "   ·   /help for commands, /exit to quit", DIM));
	}

	public synchronized void resolution(String option, String requested, String applied, String mechanism) {
		line(styled("  " + option + ": " + requested + " → " + applied + " (" + mechanism + ")", DIM));
	}

	public synchronized void greeting(String text) {
		writer().println();
		line(styled(text, DIM));
	}

	/** One event of a turn, as it arrives. */
	public synchronized void render(AgentEvent event) {
		switch (event) {
			case AgentEvent.Text text -> text(text.text());
			case AgentEvent.Thought thought -> {
				if (showThoughts) {
					line(styled("  · " + firstLine(thought.text()), DIM));
				}
			}
			case AgentEvent.ToolCallStarted call -> {
				String title = call.title() == null || call.title().isBlank() ? "tool call" : call.title().strip();
				toolTitles.put(call.id(), title);
				line(styled("  → " + title, DIM));
			}
			case AgentEvent.ToolCallUpdated update -> {
				if (update.status() == AcpSchema.ToolCallStatus.FAILED) {
					line(styled("  ✗ " + toolTitles.getOrDefault(update.id(), "tool call") + " failed", WARNING));
				}
			}
			case AgentEvent.PlanUpdated plan -> plan(plan.entries());
			case AgentEvent.UsageUpdated update -> usage = update;
			case AgentEvent.Completed completed -> {
				if (completed.reason() != null && completed.reason() != AcpSchema.StopReason.END_TURN) {
					line(styled("  [turn ended: " + completed.reason() + "]", DIM));
				}
			}
			case AgentEvent.Failed failed -> error(messageOf(failed.cause()));
			case AgentEvent.ConfigChanged changed -> {
			}
			case AgentEvent.ModeChanged mode -> line(styled("  ⋯ mode: " + mode.modeId(), DIM));
		}
	}

	/** The turn is over, however it ended. */
	public synchronized void endOfTurn() {
		newlineIfNeeded();
		if (usage != null) {
			line(styled("  " + describe(usage), DIM));
		}
		usage = null;
		toolTitles.clear();
	}

	public synchronized void cancelled() {
		line(styled("  [cancelled]", DIM));
	}

	public synchronized void notice(AgentNotice notice) {
		AttributedStyle style = notice.severity() == AgentNotice.Severity.ERROR ? ERROR : WARNING;
		line(styled("  ! agent: " + notice, style));
	}

	public synchronized void error(String message) {
		line(styled("  ! " + message, ERROR));
	}

	/** Output of a console command, as opposed to anything the agent said. */
	public synchronized void info(String text) {
		line(styled(text, DIM));
	}

	/** The question part of a permission request; the answer is read by the caller. */
	public synchronized void permission(PermissionQuestion question) {
		String detail = String.join(" · ",
				java.util.stream.Stream.of(question.kind() == null ? null : question.kind().name().toLowerCase(Locale.ROOT),
						question.toolName().filter(name -> !name.equals(question.describe())).orElse(null))
					.filter(java.util.Objects::nonNull)
					.toList());
		line(styled("  ? The agent wants to: " + question.describe(), QUESTION)
				+ (detail.isEmpty() ? "" : styled("  (" + detail + ")", DIM)));
		List<AcpSchema.PermissionOption> options = question.options();
		for (int i = 0; i < options.size(); i++) {
			line("    " + (i + 1) + ") " + labelOf(options.get(i)));
		}
	}

	public synchronized String choicePrompt(int options) {
		return styled("  choose 1-" + options + " (Ctrl-C to cancel) › ", QUESTION);
	}

	public synchronized void bye() {
		newlineIfNeeded();
		writer().println(styled("\nbye", DIM));
		writer().flush();
	}

	protected void text(String delta) {
		writer().print(delta);
		writer().flush();
		if (!delta.isEmpty()) {
			midLine = !delta.endsWith("\n");
		}
	}

	protected void plan(List<AcpSchema.PlanEntry> entries) {
		long done = entries.stream().filter(e -> e.status() == AcpSchema.PlanEntryStatus.COMPLETED).count();
		String current = entries.stream()
			.filter(e -> e.status() == AcpSchema.PlanEntryStatus.IN_PROGRESS)
			.map(AcpSchema.PlanEntry::content)
			.findFirst()
			.map(content -> " — " + firstLine(content))
			.orElse("");
		line(styled("  ⋯ plan: " + done + "/" + entries.size() + " done" + current, DIM));
	}

	/** Writes a whole line, breaking out of a partially streamed one first. */
	protected void line(String text) {
		newlineIfNeeded();
		writer().println(text);
		writer().flush();
	}

	protected String styled(String text, AttributedStyle style) {
		return new AttributedString(text, style).toAnsi(terminal);
	}

	protected PrintWriter writer() {
		return terminal.writer();
	}

	private void newlineIfNeeded() {
		if (midLine) {
			writer().println();
			midLine = false;
		}
	}

	static String labelOf(AcpSchema.PermissionOption option) {
		if (option.name() != null && !option.name().isBlank()) {
			return option.name().strip();
		}
		if (option.kind() == null) {
			return option.optionId();
		}
		return switch (option.kind()) {
			case ALLOW_ONCE -> "Allow once";
			case ALLOW_ALWAYS -> "Always allow";
			case REJECT_ONCE -> "Reject";
			case REJECT_ALWAYS -> "Always reject";
		};
	}

	static String describe(AgentEvent.UsageUpdated usage) {
		StringBuilder text = new StringBuilder("context ").append(tokens(usage.contextUsed()));
		if (usage.contextSize() > 0) {
			text.append(" / ").append(tokens(usage.contextSize()))
				.append(String.format(Locale.ROOT, " (%.0f%%)", usage.contextFraction().orElse(0) * 100));
		}
		text.append(" tokens");
		if (usage.costAmount() != null) {
			text.append(String.format(Locale.ROOT, "  ·  %.4f %s", usage.costAmount(),
					usage.costCurrency() == null ? "" : usage.costCurrency()).stripTrailing());
		}
		return text.toString();
	}

	private static String tokens(long count) {
		return count < 1000 ? Long.toString(count) : String.format(Locale.ROOT, "%.1fk", count / 1000.0);
	}

	static String messageOf(Throwable error) {
		if (error == null) {
			return "the turn failed";
		}
		return error.getMessage() == null ? error.toString() : error.getMessage();
	}

	private static String firstLine(String text) {
		String stripped = text == null ? "" : text.strip();
		int newline = stripped.indexOf('\n');
		String head = newline < 0 ? stripped : stripped.substring(0, newline);
		return head.length() > 100 ? head.substring(0, 100) + "…" : head;
	}
}
