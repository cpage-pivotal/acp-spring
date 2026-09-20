package org.thought.acp.registry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.thought.acp.client.AgentClient;
import org.thought.acp.client.AgentClientFactory;
import org.thought.acp.config.AgentSettings;
import org.thought.acp.runtime.AgentRuntime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The M4 claim, end to end and offline: a catalogue entry becomes a running agent this library is
 * talking to.
 *
 * <p>Every earlier test in this module proves one link — the catalogue parses, the digest is
 * checked, the archive unpacks, the launch spec is right. None of them proves the chain holds, and
 * the chain is what "any ACP agent" means. So this builds an archive, publishes it through a
 * {@code file:} registry, and then goes through the ordinary
 * {@link AgentClientFactory#create(AgentRuntime, AgentSettings)} path: resolve, verify, unpack,
 * chmod, spawn, handshake, prompt.
 *
 * <p>The agent is fifteen lines of shell rather than a real vendor's binary, and deliberately so.
 * A test that downloaded goose would be testing goose, a CDN and a network; what is under test here
 * is this module, and a shell script that speaks JSON-RPC over stdio is an ACP agent as far as the
 * protocol is concerned.
 */
@DisabledOnOs(OS.WINDOWS)
class RegistryInstallAndConnectTests {

	@TempDir
	Path temp;

	/**
	 * An ACP agent in POSIX shell: answers the handshake, opens a session, streams one chunk and
	 * ends the turn.
	 *
	 * <p>Two things about it were learned the hard way, and both are about being a fake rather than
	 * about this library. Every reply goes through {@code /usr/bin/printf} rather than the shell's
	 * builtin, because a builtin writes to the shell's own stdout, which libc block-buffers when it
	 * is a pipe — a script that keeps reading never flushes, and the handshake sits in a buffer until
	 * the test times out. And the id is echoed back verbatim rather than parsed as a number, because
	 * {@code acp-core} issues string request ids ({@code "1eb2628c-0"}), which JSON-RPC allows and a
	 * digit-matching {@code sed} silently does not.
	 */
	private static final String SHELL_AGENT = """
			#!/bin/sh
			while IFS= read -r line; do
			  id=`printf '%s' "$line" | sed -e 's/,"method".*//' -e 's/.*"id"://'`
			  case "$line" in
			    *'"method":"initialize"'*)
			      /usr/bin/printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":1,"agentInfo":{"name":"house-agent","version":"9.9.9"},"agentCapabilities":{}}}\\n' "$id" ;;
			    *'"method":"session/new"'*)
			      /usr/bin/printf '{"jsonrpc":"2.0","id":%s,"result":{"sessionId":"s-1"}}\\n' "$id" ;;
			    *'"method":"session/prompt"'*)
			      /usr/bin/printf '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s-1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"installed from the registry"}}}}\\n'
			      /usr/bin/printf '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s-1","update":{"sessionUpdate":"usage_update","used":12,"size":1000}}}\\n'
			      /usr/bin/printf '{"jsonrpc":"2.0","id":%s,"result":{"stopReason":"end_turn"}}\\n' "$id" ;;
			    *'"method":"session/close"'*)
			      /usr/bin/printf '{"jsonrpc":"2.0","id":%s,"result":{}}\\n' "$id" ;;
			  esac
			done
			""";

	@Test
	@DisplayName("an agent nobody wrote an adapter for: catalogue entry to finished turn")
	void installsAnAgentFromTheRegistryAndTalksToIt() throws Exception {
		Path archive = AgentInstallerTests.tarGz(temp.resolve("house-agent.tar.gz"),
				Map.of("house-agent", SHELL_AGENT));
		AgentRegistry registry = registryPublishing(archive, AgentInstallerTests.sha256(archive));

		AgentRuntime runtime = new RegistryAgentRuntimeProvider(registry,
				new AgentInstaller(registry.settings())).forId("house-agent").orElseThrow();
		Path workspace = Files.createDirectories(temp.resolve("workspace"));

		try (AgentClient client = AgentClientFactory.create(runtime,
				AgentSettings.builder("house-agent", workspace).timeout(Duration.ofSeconds(30)).build())) {

			assertThat(client.agentInfo()).get().extracting(info -> info.name()).isEqualTo("house-agent");
			assertThat(client.protocolVersion()).isEqualTo(1);
			assertThat(client.prompt("say something").call().content()).isEqualTo("installed from the registry");
		}
	}

	@Test
	@DisplayName("the digest is checked before the agent is ever run")
	void refusesToRunAnAgentWhoseBytesAreNotTheOnesPublished() throws Exception {
		// The scenario this whole class exists to make safe: the catalogue is trusted, the release
		// host is not, and the difference is one comparison made before exec.
		Path archive = AgentInstallerTests.tarGz(temp.resolve("house-agent.tar.gz"),
				Map.of("house-agent", SHELL_AGENT.replace("house-agent", "not-what-was-published")));
		AgentRegistry registry = registryPublishing(archive,
				"0000000000000000000000000000000000000000000000000000000000000000");

		AgentRuntime runtime = new RegistryAgentRuntimeProvider(registry,
				new AgentInstaller(registry.settings())).forId("house-agent").orElseThrow();
		Path workspace = Files.createDirectories(temp.resolve("workspace"));

		org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> AgentClientFactory.create(runtime,
						AgentSettings.builder("house-agent", workspace).build()))
				.isInstanceOf(AgentInstaller.AgentInstallException.class)
				.hasMessageContaining("refusing to run it");
	}

	/** A one-agent catalogue on disk, pinned with a {@code file:} URL. */
	private AgentRegistry registryPublishing(Path archive, String sha256) throws Exception {
		Path catalogue = temp.resolve("registry.json");
		Files.writeString(catalogue, """
				{"agents": [{
				  "id": "house-agent", "name": "House Agent", "version": "9.9.9",
				  "distribution": {"binary": {"%s": {
				     "archive": "%s", "cmd": "./house-agent", "args": ["acp"], "sha256": "%s"}}}
				}]}
				""".formatted(Platform.current().id(), archive.toUri(), sha256));
		return new AgentRegistry(new RegistrySettings(catalogue.toUri(), temp.resolve("cache"), Duration.ZERO, false,
				true, null));
	}
}
