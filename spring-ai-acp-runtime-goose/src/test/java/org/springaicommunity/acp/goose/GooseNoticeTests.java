package org.springaicommunity.acp.goose;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springaicommunity.acp.config.AgentSettings;
import org.springaicommunity.acp.runtime.AgentNotice;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What goose says when it cannot load an MCP server, and where it says it.
 *
 * <p>
 * The two log lines below are captured verbatim from goose 1.51.0 by
 * {@code tools/mcp-silence-check.py} — one HTTP server that refused the connection, one
 * stdio server whose process exited. They are fixtures rather than examples: this parser
 * depends on goose's own wording, which carries no compatibility promise, so the test
 * that pins it has to be pinned to something goose actually wrote. The live contract test
 * catches the day the wording changes; this one catches the day the parser does.
 */
class GooseNoticeTests {

	private static final String HTTP_FAILURE = """
			{"timestamp":"2026-09-21T01:27:23.412971Z","level":"WARN","fields":{"message":\
			"Failed to load extension finops-mcp: failed to initialize MCP client: Send message error \
			Transport [rmcp::transport::worker::WorkerTransport<rmcp::transport::streamable_http_client\
			::StreamableHttpClientWorker<reqwest::async_impl::client::Client>>] error: Client error: \
			error sending request for url (http://127.0.0.1:9/mcp), when send discover request"},\
			"target":"goose::agents::agent"}""";

	private static final String STDIO_FAILURE = """
			{"timestamp":"2026-09-21T01:27:37.988044Z","level":"WARN","fields":{"message":\
			"Failed to load extension local-tools: process quit before initialization: stderr = "},\
			"target":"goose::agents::agent"}""";

	private final GooseRuntime runtime = new GooseRuntime();

	@Test
	void anUnreachableHttpServerIsReportedAgainstTheNameTheApplicationGaveIt() {
		AgentNotice notice = runtime.noticeOf(HTTP_FAILURE).orElseThrow();

		assertThat(notice.severity()).isEqualTo(AgentNotice.Severity.WARNING);
		assertThat(notice.subject()).isEqualTo("finops-mcp");
		assertThat(notice.concerns("finops-mcp")).isTrue();
		assertThat(notice.detail()).contains("failed to initialize MCP client").contains("when send discover request");
	}

	@Test
	void theRestOfTheJsonRecordIsNotPartOfWhatTheAgentSaid() {
		AgentNotice notice = runtime.noticeOf(HTTP_FAILURE).orElseThrow();

		assertThat(notice.detail()).doesNotContain("goose::agents::agent").doesNotContain("\"target\"");
	}

	@Test
	void aStdioServerThatExitedIsReportedTheSameWay() {
		AgentNotice notice = runtime.noticeOf(STDIO_FAILURE).orElseThrow();

		assertThat(notice.subject()).isEqualTo("local-tools");
		assertThat(notice.detail()).contains("process quit before initialization");
	}

	@Test
	void everythingElseInTheLogIsNoneOfAClientsBusiness() {
		assertThat(runtime.noticeOf("""
				{"timestamp":"2026-09-21T01:27:23.400000Z","level":"INFO","fields":{"message":\
				"Starting session"},"target":"goose::session"}""")).isEmpty();
		assertThat(runtime.noticeOf("")).isEmpty();
		assertThat(runtime.noticeOf(null)).isEmpty();
	}

	@Test
	void theLogDirectoryFollowsTheStateHomeGooseItselfUses() {
		Path directory = runtime.logDirectory(settings(Map.of())).orElseThrow();

		// Compared as strings: AssertJ's PathAssert.endsWith resolves the real path,
		// which requires the directory to exist — true on a machine with goose installed,
		// false on a fresh build machine where no goose has ever run.
		assertThat(directory).isAbsolute();
		assertThat(directory.normalize().toString()).endsWith(Paths.get("goose", "logs").toString());
	}

	@Test
	void aDeploymentThatPutsTheLogElsewhereSaysSoInTierThree() {
		Path directory = runtime.logDirectory(settings(Map.of("log-dir", "/var/log/goose"))).orElseThrow();

		assertThat(directory).isEqualTo(Paths.get("/var/log/goose"));
	}

	private AgentSettings settings(Map<String, Object> options) {
		return AgentSettings.builder(GooseRuntime.ID, Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath())
			.runtimeOptions(options)
			.build();
	}

}
