package org.springaicommunity.acp.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.acp.runtime.AgentNotice;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The file-tailing half of reporting what an agent only ever writes to its own log.
 *
 * <p>
 * Nothing here knows any agent: the interpretation is a function the adapter supplies, so
 * these tests use a trivial one and concentrate on the reading — which lines are picked
 * up, which are deliberately not, and what happens when a log file is appended to,
 * rotated or truncated.
 */
class AgentLogWatcherTests {

	@TempDir
	Path logs;

	/**
	 * Stands in for an adapter: the interesting lines name a subject, the rest mean
	 * nothing.
	 */
	private static Optional<AgentNotice> interpret(String line) {
		return line.startsWith("FAILED ") ? Optional.of(AgentNotice.warning(line.substring(7).split(" ")[0], line))
				: Optional.empty();
	}

	private AgentLogWatcher watcher() {
		return new AgentLogWatcher(logs, AgentLogWatcherTests::interpret);
	}

	@Test
	void aLineTheAdapterRecognizesBecomesANotice() throws IOException {
		write("agent.log", "INFO starting", "FAILED finops-mcp could not connect");

		try (AgentLogWatcher watcher = watcher()) {
			watcher.poll();

			assertThat(watcher.notices()).singleElement()
				.satisfies(notice -> assertThat(notice.subject()).isEqualTo("finops-mcp"));
		}
	}

	@Test
	void whatIsAppendedLaterIsReadOnceAndOnlyOnce() throws IOException {
		write("agent.log", "INFO starting");

		try (AgentLogWatcher watcher = watcher()) {
			watcher.poll();
			append("agent.log", "FAILED finops-mcp could not connect");
			watcher.poll();
			watcher.poll();

			assertThat(watcher.notices()).hasSize(1);
		}
	}

	@Test
	void aLogFileFromAnEarlierRunIsNotReplayedAsThisRunsNews() throws IOException {
		Path old = write("yesterday.log", "FAILED finops-mcp could not connect");
		Files.setLastModifiedTime(old, FileTime.fromMillis(System.currentTimeMillis() - Duration.ofDays(1).toMillis()));

		try (AgentLogWatcher watcher = watcher()) {
			watcher.poll();

			assertThat(watcher.notices()).isEmpty();
		}
	}

	@Test
	void aLogFileCreatedAfterTheAgentStartedIsPickedUp() throws IOException {
		try (AgentLogWatcher watcher = watcher()) {
			watcher.poll();
			write("later/today.log", "FAILED finops-mcp could not connect");
			watcher.poll();

			assertThat(watcher.notices()).hasSize(1);
		}
	}

	@Test
	void aTruncatedFileIsReadFromTheTopRatherThanFromAStaleOffset() throws IOException {
		write("agent.log", "INFO a long line that makes the file longer than what replaces it");

		try (AgentLogWatcher watcher = watcher()) {
			watcher.poll();
			write("agent.log", "FAILED finops-mcp restarted");
			watcher.poll();

			assertThat(watcher.notices()).hasSize(1);
		}
	}

	@Test
	void theSameFailureReportedTwiceIsStillOneNotice() throws IOException {
		write("agent.log", "FAILED finops-mcp could not connect", "FAILED finops-mcp could not connect");

		try (AgentLogWatcher watcher = watcher()) {
			watcher.poll();

			assertThat(watcher.notices()).hasSize(1);
		}
	}

	@Test
	void aMissingDirectoryIsNotAFailure() {
		try (AgentLogWatcher watcher = new AgentLogWatcher(logs.resolve("never-created"),
				AgentLogWatcherTests::interpret)) {
			watcher.start();
			watcher.poll();

			assertThat(watcher.notices()).isEmpty();
		}
	}

	@Test
	void awaitingANoticeReturnsAsSoonAsTheAgentWritesOne() throws IOException {
		try (AgentLogWatcher watcher = watcher()) {
			watcher.start();
			write("agent.log", "FAILED finops-mcp could not connect");

			assertThat(watcher.awaitNotice("finops-mcp", Duration.ofSeconds(5))).isPresent();
		}
	}

	@Test
	void awaitingANoticeThatNeverComesCostsTheTimeoutAndNothingElse() {
		try (AgentLogWatcher watcher = watcher()) {
			watcher.start();

			assertThat(watcher.awaitNotice("finops-mcp", Duration.ofMillis(200))).isEmpty();
		}
	}

	@Test
	void aListenerSeesEachNoticeAsItArrives() throws IOException {
		List<AgentNotice> seen = new CopyOnWriteArrayList<>();
		write("agent.log", "FAILED finops-mcp could not connect");

		try (AgentLogWatcher watcher = new AgentLogWatcher(logs, AgentLogWatcherTests::interpret, seen::add)) {
			watcher.poll();

			assertThat(seen).hasSize(1);
		}
	}

	@Test
	void aSecretInTheAgentsOwnLogIsRedactedBeforeTheAdapterEverSeesIt() throws IOException {
		List<String> seen = new CopyOnWriteArrayList<>();
		write("agent.log", "FAILED finops-mcp authorization: Bearer super-secret-token");

		try (AgentLogWatcher watcher = new AgentLogWatcher(logs, line -> {
			seen.add(line);
			return interpret(line);
		})) {
			watcher.poll();

			assertThat(seen).singleElement().satisfies(line -> assertThat(line).doesNotContain("super-secret-token"));
			assertThat(watcher.notices()).singleElement()
				.satisfies(notice -> assertThat(notice.detail()).doesNotContain("super-secret-token"));
		}
	}

	private Path write(String name, String... lines) throws IOException {
		Path file = logs.resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, String.join("\n", lines) + "\n");
		return file;
	}

	private void append(String name, String... lines) throws IOException {
		Files.writeString(logs.resolve(name), String.join("\n", lines) + "\n", java.nio.file.StandardOpenOption.APPEND);
	}

}
