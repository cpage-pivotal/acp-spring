# Changelog

All notable changes to Spring AI ACP are documented here. The project follows
[Semantic Versioning](https://semver.org/) (MAJOR.MINOR.PATCH).

## [0.3.1] - Unreleased

### Added

- `auth: provided` for an MCP server whose credentials come from the application's own
  `McpCredentialsProvider`. Such a server may omit `url`: the provider supplies the upstream
  with `McpCredentials.routed(url, credentials)` when the session opens, as a credential broker
  that issues a token together with its endpoint does. A URL-less server the provider does not
  route is left out of that session instead of refusing it.
- A Tanzu AI Models (GenAI) service bound on Cloud Foundry becomes the provider with no
  configuration. Its OpenAI-compatible endpoint is used as `spring.acp.provider.base-url` and
  `api-key`, and when no `spring.acp.model` is set, the first `TOOLS`-capable model in its
  catalog is chosen.
  - An explicitly configured provider wins.
  - `spring.acp.tanzu-ai.enabled` and `service-name` control it.
- `CODEX_ACP_CLI_PATH` names a local codex-acp for the Codex runtime to launch in place of
  `npx`, as `GOOSE_CLI_PATH` and `OPENCODE_CLI_PATH` do for theirs. The Codex supply buildpack
  exports it. `spring.acp.runtimes.codex.command` still wins, and so does an explicit
  `package`, which asks for npx.
- Goose's `todo` list arrives as `AgentEvent.PlanUpdated`. Goose keeps its task list in a tool
  and sends no ACP `plan` update, so each `todo__todo_write` call is read as the agent's plan
  instead of being reported as a tool call. `AgentRuntime.planOf` is the hook any adapter can use
  for an agent that does the same.
- Helpers for runtime adapters, so their shared rules live in core:
  - `AgentEnvironment.layered` builds a launch environment in the order every adapter uses.
    Agent defaults come first, then provider credentials, then the tier-3 `env` block, which wins.
  - `Executables.fromEnvironment` reads an executable path from an environment variable such as
    `GOOSE_CLI_PATH`, and checks it at startup.
  - `AgentSettings.findModel()` returns the requested model, or empty when it is blank.

### Changed

- `AcpProperties` and its nested types are records, bound through their constructors. Code that
  reads them uses record accessors (`properties.model()`, `properties.pool().sessionTtl()`) in
  place of getters. Property names, defaults and metadata are unchanged, except that
  `spring.acp.protocol.max-version` now lists its default (`1`) in the metadata. A test that
  declared `new AcpProperties()` as a bean registers it with
  `@EnableConfigurationProperties(AcpProperties.class)` instead.

### Fixed

- OpenCode sessions no longer call MCP servers as another user, or through a closed session's
  route. OpenCode keeps one MCP client per server name for its whole process, replaced by each
  `session/new`, so every session used the route of the one opened last. A runtime now declares
  this with `AgentRuntime.mcpScope()` (`PROCESS` for OpenCode). The pool then keeps each such
  process to one principal at a time, restarting an idle process before it serves someone else,
  and `McpAccess` gives the principal one route shared by all of their sessions, held until the
  last closes. `spring.acp.pool.max-processes` caps how many users hold OpenCode sessions at once,
  and each costs a whole OpenCode process (about 650 MB); see "Processes, users and memory" in the
  user guide.

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
