# Changelog

All notable changes to Spring AI ACP are documented here. The project follows
[Semantic Versioning](https://semver.org/) (MAJOR.MINOR.PATCH).

## [Unreleased]

### 0.2.0-SNAPSHOT

- Rename the project and its Maven coordinates to `spring-ai-acp` under the
  `org.springaicommunity` groupId (formerly `acp-spring` under `org.springaicommunity.acp`).
- Relicense from MIT to Apache License 2.0.
- Add community hygiene: `LICENSE`, `CONTRIBUTING`, `CODE_OF_CONDUCT`, `SECURITY`,
  `SUPPORT`, `CHANGELOG`, `AGENTS.md`, issue/PR templates, and a Maven wrapper.
- Add `acp-spring-console`, a terminal chat for prototyping an agent.

## [0.1.0] - 2026

Initial release. Four milestones:

- **M1** — Spring Boot auto-configuration, the `acp-spring-boot-starter`, and an
  `AgentClient` over ACP v1 with a Goose stdio runtime and a turn demultiplexer.
- **M2** — Three runtimes (Goose, Codex, OpenCode) behind one `spring.acp.runtime`
  property, and `AgentRuntimeContract`, the cross-adapter contract test.
- **M3** — Capability-gated session list/load/resume/delete, a workspace jail for the
  filesystem and terminal methods, connection pooling with idle session sweeping, a
  supervised `goose serve` over WebSocket, a standalone `agents.yaml`, an opt-in HTTP
  endpoint, and the `AgentExecutor` facade.
- **M4** — The registry-driven runtime with SHA-256-verified downloads,
  `AcpChatModel` for Spring AI, a Micrometer observation per turn and per tool call,
  `AgentEvent.UsageUpdated`, and real ACP version negotiation behind a feature flag.
