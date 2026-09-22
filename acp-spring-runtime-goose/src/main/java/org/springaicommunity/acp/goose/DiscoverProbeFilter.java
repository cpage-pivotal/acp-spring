package org.springaicommunity.acp.goose;

import java.io.IOException;
import java.util.List;

import org.springaicommunity.acp.mcp.McpRequestFilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Answers goose's {@code server/discover} probe for an MCP server that mishandles it.
 *
 * <p>goose 1.51 opens an MCP connection by sending {@code server/discover} with
 * {@code MCP-Protocol-Version: 2026-07-28}, and falls back to plain {@code initialize} when the
 * server says it has never heard of the method. A server that predates the version is supposed to
 * say exactly that. The Tanzu MCP gateway instead answers with a 400 whose JSON-RPC {@code id} is
 * {@code "server-error"} rather than the request's, so goose cannot correlate the error, never falls
 * back, and the extension fails to start — measured, with no message over ACP, stdout or stderr,
 * whether the server is declared in {@code session/new} or in goose's own config.
 *
 * <p>So this says what the older server should have: a well-formed "method not found" carrying the
 * request's own id. goose then falls back and connects. And the version header the gateway refuses
 * outright is not passed upstream on anything else either.
 *
 * <p>Opt-in ({@code spring.acp.runtimes.goose.mcp.answer-discover}) because it costs a server that
 * <em>does</em> implement discovery its newer protocol. Delete it the day either side is fixed.
 */
final class DiscoverProbeFilter implements McpRequestFilter {

	static final String DISCOVER = "server/discover";

	static final String PROBE_VERSION = "2026-07-28";

	private static final String VERSION_HEADER = "MCP-Protocol-Version";

	private static final ObjectMapper JSON = new ObjectMapper();

	@Override
	public Outcome filter(McpRequest request) {
		JsonNode message = parse(request.body());
		if (message != null && DISCOVER.equals(message.path("method").asText(null))) {
			if (!message.has("id")) {
				// A notification, which JSON-RPC answers with nothing at all.
				return new Outcome.Answer(202, null, new byte[0]);
			}
			ObjectNode error = JSON.createObjectNode().put("jsonrpc", "2.0");
			error.set("id", message.get("id"));
			error.putObject("error").put("code", -32601).put("message", "Method not found");
			return Outcome.Answer.json(200, error.toString());
		}
		List<String> versions = request.header(VERSION_HEADER);
		return versions.contains(PROBE_VERSION) ? request.withoutHeader(VERSION_HEADER).forward() : request.forward();
	}

	private static JsonNode parse(byte[] body) {
		if (body.length == 0) {
			return null;
		}
		try {
			JsonNode node = JSON.readTree(body);
			return node != null && node.isObject() ? node : null;
		}
		catch (IOException ex) {
			return null;
		}
	}
}
