# Changelog

All notable changes to Spring AI ACP are documented here. The project follows
[Semantic Versioning](https://semver.org/) (MAJOR.MINOR.PATCH).

## [0.3.1] - Unreleased

## [0.3.0] - 2026-09-24

Initial release as Spring AI ACP, under the `org.springaicommunity` groupId and the Apache
License 2.0.

### Agents and runtimes

- `AgentClient`, a fluent client that drives any Agent Client Protocol v1 coding agent and
  streams each turn as `AgentEvent`s, with exactly one terminal event per turn.
- Compiled runtime adapters for Goose, Codex (`codex-acp`) and OpenCode, selected by
  `spring.acp.runtime`, and `spring-ai-acp-boot-starter-registry`, which launches any agent the
  ACP registry publishes from a cached catalogue, with SHA-256-verified downloads.
- A supervised `goose serve` over WebSocket, as an alternative to stdio.
- ACP version negotiation; v2 is behind a feature flag and refused.

### Configuration

- Three configuration tiers: portable (`spring.acp.*`), negotiated (`model`, `mode` and
  `provider`, resolved against the options the agent advertises, with `on-unsupported` =
  fail/warn/ignore), and runtime-specific (`spring.acp.runtimes.<id>.*`).
- Bring-your-own OpenAI-compatible endpoints through `spring.acp.provider.base-url`, with the
  endpoint's own model listing deciding which models are valid.
- A standalone `agents.yaml`, loaded as Spring config data.

### Sessions, MCP and skills

- Capability-gated session list, load, resume and delete, with connection pooling and idle
  session sweeping.
- MCP servers declared per session, with credentials kept out of the agent: HTTP servers that
  need them are reached through a loopback proxy, one unguessable route per session.
- `spring-ai-acp-mcp-oauth`: per-user MCP-spec OAuth, redirecting through Spring Security in a
  web application or signing in through a loopback browser flow in a terminal one.
- Detection of MCP servers that goose fails to load, which ACP never reports on the wire,
  surfaced as `AgentClient.notices()` and acted on through `spring.acp.mcp.on-server-failure`.
- Skills installed from Git (optionally pinned to a commit) into the workspace.

### Permissions and the workspace

- Permission policies (`deny`, `allowlist`, `auto-approve` and `ask`), with `ask` answered by
  a person through a `PermissionPrompt`.
- A workspace jail that confines the agent's filesystem and terminal requests by real path.

### Integrations

- `AcpChatModel`, which publishes the agent as a Spring AI `ChatModel`.
- An opt-in HTTP endpoint, and the `AgentExecutor` facade.
- `spring-ai-acp-console`, a terminal chat over the application's agent for prototyping it.
- A Micrometer observation per turn and per tool call, and `AgentEvent.UsageUpdated`.

### Testing

- `spring-ai-acp-test`: `AgentRuntimeContract`, the cross-adapter contract test;
  `AgentProbe`, which gates live suites on a real session; and `ScriptedAgent`, a fake agent
  speaking raw JSON-RPC for wire-format tests. Live-agent suites are opt-in with
  `-Dspring-ai-acp.test.live=true`.
