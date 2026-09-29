package org.springaicommunity.acp.goose;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.PlanEntryStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Goose's todo writes read as ACP plans. The wire shape is pinned by a {@code tool_call}
 * captured from goose 1.52.0, so a goose that moves the tool name or the content is
 * caught here rather than by an empty task list.
 */
class TodoPlanTests {

	/** Verbatim from goose 1.52.0, apart from the ids. */
	private static final String CAPTURED = """
			{"sessionUpdate":"tool_call","toolCallId":"call_1","title":"todo: todo write",
			 "rawInput":{"content":"- Gather data \\u2014 done\\n- Draft report \\u2014 in progress\\n- Send report \\u2014 not started"},
			 "_meta":{"goose":{"toolCall":{"toolName":"todo__todo_write","extensionName":"todo"},
			          "created":1790700522,"messageId":"resp_1"}}}
			""";

	@Test
	void readsTheCapturedWriteAsAPlan() throws Exception {
		AcpSchema.ToolCall call = (AcpSchema.ToolCall) new ObjectMapper().readValue(CAPTURED,
				AcpSchema.SessionUpdate.class);

		assertThat(new GooseRuntime().planOf(call)).hasValueSatisfying(plan -> assertThat(plan)
			.extracting(AcpSchema.PlanEntry::content, AcpSchema.PlanEntry::status)
			.containsExactly(tuple("Gather data", PlanEntryStatus.COMPLETED),
					tuple("Draft report", PlanEntryStatus.IN_PROGRESS), tuple("Send report", PlanEntryStatus.PENDING)));
	}

	@Test
	void readsGoosesCheckboxTemplateWithSubItemsFlattened() {
		Optional<List<AcpSchema.PlanEntry>> plan = TodoPlan.of(todoWrite("""
				## Tasks
				- [x] Requirement 1
				- [ ] Task
				  - [ ] Sub-task
				- [-] Requirement 2
				- [~] Another task
				"""));

		assertThat(plan).hasValueSatisfying(entries -> {
			assertThat(entries).extracting(AcpSchema.PlanEntry::content)
				.containsExactly("Requirement 1", "Task", "Sub-task", "Requirement 2", "Another task");
			assertThat(entries).extracting(AcpSchema.PlanEntry::status)
				.containsExactly(PlanEntryStatus.COMPLETED, PlanEntryStatus.PENDING, PlanEntryStatus.PENDING,
						PlanEntryStatus.IN_PROGRESS, PlanEntryStatus.IN_PROGRESS);
			assertThat(entries).extracting(AcpSchema.PlanEntry::priority)
				.containsOnly(AcpSchema.PlanEntryPriority.MEDIUM);
		});
	}

	@Test
	void readsNumberedStruckThroughAndParenthesisedItems() {
		assertThat(TodoPlan.of(todoWrite("""
				1. ~~Look up the VMs~~
				2) Get the schema (in-progress)
				3. File the requests: pending
				4. Report back
				"""))).hasValueSatisfying(
				entries -> assertThat(entries).extracting(AcpSchema.PlanEntry::content, AcpSchema.PlanEntry::status)
					.containsExactly(tuple("Look up the VMs", PlanEntryStatus.COMPLETED),
							tuple("Get the schema", PlanEntryStatus.IN_PROGRESS),
							tuple("File the requests", PlanEntryStatus.PENDING),
							tuple("Report back", PlanEntryStatus.PENDING)));
	}

	/** As a model wrote it against the Meridian agent, in a chat on goose 1.52.0. */
	@Test
	void readsABracketedStatusAndDropsItFromTheText() {
		assertThat(TodoPlan.of(todoWrite("""
				- Get business unit summary [DONE]
				- List open findings [done]
				- Draft one request per VM [IN_PROGRESS]
				- Summarise the drafts
				"""))).hasValueSatisfying(
				entries -> assertThat(entries).extracting(AcpSchema.PlanEntry::content, AcpSchema.PlanEntry::status)
					.containsExactly(tuple("Get business unit summary", PlanEntryStatus.COMPLETED),
							tuple("List open findings", PlanEntryStatus.COMPLETED),
							tuple("Draft one request per VM", PlanEntryStatus.IN_PROGRESS),
							tuple("Summarise the drafts", PlanEntryStatus.PENDING)));
	}

	@Test
	void readsALeadingStatusATickAndAStatusWordAfterAnEmptyBox() {
		assertThat(TodoPlan.of(todoWrite("""
				- [DONE] Get the schema
				[In Progress] Draft the requests
				- \u2705 Look up the VMs
				- [ ] File nothing [done]
				- [x] Report back (not started)
				"""))).hasValueSatisfying(entries -> assertThat(entries)
			.extracting(AcpSchema.PlanEntry::content, AcpSchema.PlanEntry::status)
			.containsExactly(tuple("Get the schema", PlanEntryStatus.COMPLETED),
					tuple("Draft the requests", PlanEntryStatus.IN_PROGRESS),
					tuple("Look up the VMs", PlanEntryStatus.COMPLETED),
					tuple("File nothing", PlanEntryStatus.COMPLETED), tuple("Report back", PlanEntryStatus.COMPLETED)));
	}

	@Test
	void keepsAHyphenatedWordThatIsNotAStatus() {
		assertThat(TodoPlan.of(todoWrite("- [ ] Follow-up with the owner")))
			.hasValueSatisfying(entries -> assertThat(entries).extracting(AcpSchema.PlanEntry::content)
				.containsExactly("Follow-up with the owner"));
	}

	@Test
	void anEmptyListClearsThePlan() {
		assertThat(TodoPlan.of(todoWrite("  \n"))).contains(List.of());
	}

	@Test
	void proseWithNoEntriesIsLeftAsAToolCall() {
		assertThat(TodoPlan.of(todoWrite("Nothing to do yet."))).isEmpty();
	}

	@Test
	void recognisesTheToolByItsNameInMetaNotByItsTitle() {
		Map<String, Object> content = Map.of("content", "- [ ] Task");
		AcpSchema.ToolCall otherTool = toolCall("todo: todo write", content,
				Map.of("goose", Map.of("toolCall", Map.of("toolName", "developer__shell"))));
		AcpSchema.ToolCall noMeta = toolCall("todo: todo write", content, null);

		assertThat(TodoPlan.of(otherTool)).isEmpty();
		assertThat(TodoPlan.of(noMeta)).isEmpty();
		assertThat(TodoPlan.of(null)).isEmpty();
	}

	@Test
	void aWriteWithoutContentIsNotAPlan() {
		assertThat(TodoPlan.of(toolCall("todo: todo write", Map.of(), todoMeta()))).isEmpty();
		assertThat(TodoPlan.of(toolCall("todo: todo write", "- [ ] Task", todoMeta()))).isEmpty();
	}

	private static AcpSchema.ToolCall todoWrite(String content) {
		return toolCall("todo: todo write", Map.of("content", content), todoMeta());
	}

	private static Map<String, Object> todoMeta() {
		return Map.of("goose", Map.of("toolCall", Map.of("toolName", TodoPlan.TOOL_NAME, "extensionName", "todo")));
	}

	private static AcpSchema.ToolCall toolCall(String title, Object rawInput, Map<String, Object> meta) {
		return new AcpSchema.ToolCall("tool_call", "call_1", title, null, null, null, null, rawInput, null, meta);
	}

}
