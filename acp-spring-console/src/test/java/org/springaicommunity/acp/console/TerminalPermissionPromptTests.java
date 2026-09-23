package org.springaicommunity.acp.console;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springaicommunity.acp.permission.PermissionQuestion;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalPermissionPromptTests {

	private static final AcpSchema.PermissionOption ALLOW = new AcpSchema.PermissionOption("allow", "Allow once",
			AcpSchema.PermissionOptionKind.ALLOW_ONCE);

	private static final AcpSchema.PermissionOption ALWAYS = new AcpSchema.PermissionOption("always", null,
			AcpSchema.PermissionOptionKind.ALLOW_ALWAYS);

	private static final AcpSchema.PermissionOption REJECT = new AcpSchema.PermissionOption("reject", "Reject",
			AcpSchema.PermissionOptionKind.REJECT_ONCE);

	private static final PermissionQuestion QUESTION = new PermissionQuestion(Optional.of("developer__shell"),
			"Run `./send-report.sh`", AcpSchema.ToolKind.EXECUTE, List.of(ALLOW, ALWAYS, REJECT));

	@Test
	void showsWhatTheAgentWantsAndTakesTheNumberedChoice() {
		TestTerminals.Scripted terminal = TestTerminals.typing("3\n");

		Optional<AcpSchema.PermissionOption> chosen = prompt(terminal).ask(QUESTION);

		assertThat(chosen).contains(REJECT);
		assertThat(terminal.written()).contains("The agent wants to: Run `./send-report.sh`",
				"(execute · developer__shell)", "1) Allow once", "2) Always allow", "3) Reject");
	}

	@Test
	void asksAgainUntilTheAnswerIsOneOfTheOptions() {
		TestTerminals.Scripted terminal = TestTerminals.typing("yes\n9\n1\n");

		assertThat(prompt(terminal).ask(QUESTION)).contains(ALLOW);
		assertThat(terminal.written()).contains("answer with a number from 1 to 3");
	}

	@Test
	void endOfInputCancelsTheRequest() {
		TestTerminals.Scripted terminal = TestTerminals.typing("");

		assertThat(prompt(terminal).ask(QUESTION)).isEmpty();
		assertThat(terminal.written()).contains("request cancelled");
	}

	@Test
	void aRequestWithNoOptionsIsCancelledWithoutAsking() {
		TestTerminals.Scripted terminal = TestTerminals.typing("1\n");
		PermissionQuestion empty = new PermissionQuestion(Optional.empty(), "x", null, List.of());

		assertThat(prompt(terminal).ask(empty)).isEmpty();
		assertThat(terminal.written()).doesNotContain("wants to");
	}

	private static TerminalPermissionPrompt prompt(TestTerminals.Scripted terminal) {
		return new TerminalPermissionPrompt(terminal.terminal(), new ConsoleRenderer(terminal.terminal(), true));
	}
}
