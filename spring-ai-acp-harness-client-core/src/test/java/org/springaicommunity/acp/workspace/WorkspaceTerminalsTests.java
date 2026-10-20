package org.springaicommunity.acp.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceTerminalsTests {

	private static final String SESSION = "sess-1";

	@TempDir
	Path workspace;

	@TempDir
	Path elsewhere;

	private WorkspaceTerminals terminals;

	@AfterEach
	void closeTerminals() {
		if (terminals != null) {
			terminals.close();
		}
	}

	private WorkspaceTerminals terminals(TerminalAccess access) {
		terminals = new WorkspaceTerminals(WorkspaceJail.around(workspace), access);
		return terminals;
	}

	@Test
	@DisplayName("a terminal is refused outright when it was not lent")
	void refusedWhenDisabled() {
		StepVerifier.create(terminals(TerminalAccess.disabled()).create(request("echo", List.of("hi"), null)))
			.expectError(WorkspaceAccessException.class)
			.verify();
	}

	@Test
	@DisplayName("a command outside the allowlist is refused")
	void refusedWhenNotAllowed() {
		StepVerifier.create(terminals(TerminalAccess.allowing(Set.of("git"))).create(request("echo", List.of(), null)))
			.expectError(WorkspaceAccessException.class)
			.verify();
	}

	/** Spelling out the path must not get past an allowlist that names the command. */
	@Test
	@DisplayName("the allowlist matches the command name, not the way it was spelled")
	void allowlistMatchesTheName() {
		assertThat(TerminalAccess.allowing(Set.of("echo")).permits("/bin/echo")).isTrue();
		assertThat(TerminalAccess.allowing(Set.of("echo")).permits("/bin/rm")).isFalse();
	}

	@Test
	@DisplayName("a command runs and its output comes back with an exit status")
	void runsACommand() {
		String id = start(terminals(TerminalAccess.unrestricted()), request("echo", List.of("hello"), null));

		eventually(() -> {
			AcpSchema.TerminalOutputResponse output = terminals.output(new AcpSchema.TerminalOutputRequest(SESSION, id))
				.block();
			assertThat(output.output()).contains("hello");
			assertThat(output.exitStatus()).isNotNull();
			assertThat(output.exitStatus().exitCode()).isZero();
		});
	}

	@Test
	@DisplayName("a command runs in the workspace unless it names somewhere else inside it")
	void runsInTheWorkspace() throws IOException {
		Files.createDirectory(workspace.resolve("sub"));
		WorkspaceTerminals open = terminals(TerminalAccess.unrestricted());

		String id = start(open, request("pwd", List.of(), "sub"));

		eventually(() -> assertThat(open.output(new AcpSchema.TerminalOutputRequest(SESSION, id)).block().output())
			.contains(workspace.toRealPath().resolve("sub").toString()));
	}

	@Test
	@DisplayName("a working directory outside the workspace is refused")
	void cwdCannotEscape() {
		StepVerifier
			.create(terminals(TerminalAccess.unrestricted()).create(request("pwd", List.of(), elsewhere.toString())))
			.expectError(WorkspaceAccessException.class)
			.verify();
	}

	@Test
	@DisplayName("a working directory reached through a symlink out of the workspace is refused")
	void cwdCannotEscapeThroughASymlink() throws IOException {
		Files.createSymbolicLink(workspace.resolve("out"), elsewhere);

		StepVerifier.create(terminals(TerminalAccess.unrestricted()).create(request("pwd", List.of(), "out")))
			.expectError(WorkspaceAccessException.class)
			.verify();
	}

	@Test
	@DisplayName("output past the byte limit is truncated and says so")
	void truncatesOutput() {
		WorkspaceTerminals open = terminals(new TerminalAccess(true, Set.of(), 16, null, 4));

		String id = start(open, request("echo", List.of("x".repeat(500)), null));

		eventually(() -> {
			AcpSchema.TerminalOutputResponse output = open.output(new AcpSchema.TerminalOutputRequest(SESSION, id))
				.block();
			assertThat(output.truncated()).isTrue();
			assertThat(output.output()).hasSize(16);
		});
	}

	@Test
	@DisplayName("releasing a terminal kills it and forgets it")
	void releaseKillsAndForgets() {
		WorkspaceTerminals open = terminals(TerminalAccess.unrestricted());
		String id = start(open, request("sleep", List.of("30"), null));
		assertThat(open.openCount()).isEqualTo(1);

		open.release(new AcpSchema.ReleaseTerminalRequest(SESSION, id)).block();

		assertThat(open.openCount()).isZero();
		StepVerifier.create(open.output(new AcpSchema.TerminalOutputRequest(SESSION, id)))
			.expectError(WorkspaceAccessException.class)
			.verify();
	}

	@Test
	@DisplayName("more terminals than the limit are refused")
	void boundsConcurrency() {
		WorkspaceTerminals open = terminals(new TerminalAccess(true, Set.of(), 0, null, 1));
		start(open, request("sleep", List.of("30"), null));

		StepVerifier.create(open.create(request("sleep", List.of("30"), null)))
			.expectError(WorkspaceAccessException.class)
			.verify();
	}

	@Test
	@DisplayName("closing the handler kills everything it started")
	void closeKillsEverything() {
		WorkspaceTerminals open = terminals(TerminalAccess.unrestricted());
		start(open, request("sleep", List.of("30"), null));

		open.close();

		assertThat(open.openCount()).isZero();
	}

	/**
	 * Polls an assertion about a running command.
	 *
	 * <p>
	 * A terminal's output is drained by a thread of its own, so a command that has exited
	 * has not necessarily had every byte it wrote collected yet. That is the behaviour
	 * under test — the alternative is blocking the handler until the process ends — so
	 * the test waits rather than pretending the read is synchronous.
	 */
	private static void eventually(ThrowingAssertion assertion) {
		AssertionError last = null;
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (System.nanoTime() < deadline) {
			try {
				assertion.run();
				return;
			}
			catch (AssertionError error) {
				last = error;
				try {
					Thread.sleep(50);
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					break;
				}
			}
			catch (IOException ex) {
				throw new AssertionError(ex);
			}
		}
		throw last == null ? new AssertionError("never asserted") : last;
	}

	@FunctionalInterface
	private interface ThrowingAssertion {

		void run() throws IOException;

	}

	private static String start(WorkspaceTerminals open, AcpSchema.CreateTerminalRequest request) {
		return open.create(request).block(Duration.ofSeconds(10)).terminalId();
	}

	private static AcpSchema.CreateTerminalRequest request(String command, List<String> args, String cwd) {
		return new AcpSchema.CreateTerminalRequest(SESSION, command, args, cwd, null, null);
	}

}
