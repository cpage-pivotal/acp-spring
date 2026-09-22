package org.springaicommunity.acp.config;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The log line that stands in for a failure report the agent never sends: an MCP server the agent
 * could not reach is invisible over ACP, so naming what was requested is the whole mechanism. It has
 * to be safe to print.
 */
class McpServerSpecTests {

	@Test
	void anHttpServerIsDescribedByWhereItPointsAndNotByItsHeaders() {
		McpServerSpec server = new McpServerSpec.Http("finops-mcp", URI.create("https://gateway.example.com:8443/mcp"),
				Map.of("Authorization", "Bearer super-secret"));

		assertThat(server.describe()).isEqualTo("finops-mcp (http: https://gateway.example.com:8443/mcp)")
				.doesNotContain("super-secret");
	}

	@Test
	void aTokenInTheQueryStringIsAsSensitiveAsAHeaderAndIsNotPrinted() {
		McpServerSpec server = new McpServerSpec.Http("tools",
				URI.create("https://tools.example.com/mcp?access_token=super-secret"), Map.of());

		assertThat(server.describe()).isEqualTo("tools (http: https://tools.example.com/mcp)")
				.doesNotContain("super-secret");
	}

	@Test
	void aStdioServerIsDescribedByItsCommandAndNotByItsEnvironment() {
		McpServerSpec server = new McpServerSpec.Stdio("local-tools", "/usr/bin/tools-mcp", List.of("--stdio"),
				Map.of("TOOLS_TOKEN", "super-secret"));

		assertThat(server.describe()).isEqualTo("local-tools (stdio: /usr/bin/tools-mcp)")
				.doesNotContain("super-secret");
	}
}
