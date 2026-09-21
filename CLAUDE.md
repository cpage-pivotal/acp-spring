# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`acp-spring`: a Spring Boot 4 / Java 21 library that drives any Agent Client Protocol (ACP) coding agent (Goose, Codex, OpenCode, or any of the ~41 in the ACP registry) behind one API, selected by `spring.acp.runtime`. Successor to the `java-wrapper` module of `../goose-buildpack`. Built on the official `com.agentclientprotocol:acp-core` (pre-1.0, pinned exactly). Packaging the agent binary is out of scope. `docs/design.md` is the authoritative design and lists the known gaps in acp-core 0.17.0; read the relevant section before changing protocol handling.

## Commands

```bash
mvn install                                   # build + all tests except the live-agent ones (also needed before running the sample)
mvn test                                      # all tests; live-agent suites are opt-in and skip by default
mvn test -Dacp-spring.test.live=true          # also run the live-agent suites (spends real tokens)
mvn -pl acp-spring-core test                  # one module
mvn -pl acp-spring-core test -Dtest=ConfigResolverTests            # one class
mvn -pl acp-spring-core test -Dtest=ConfigResolverTests#someMethod # one method
mvn -pl samples/smoke-app spring-boot:run -Dspring-boot.run.arguments=--spring.acp.runtime=codex   # goose|codex|opencode|gemini
```

- **Live-agent suites cost real tokens, so they are opt-in: `-Dacp-spring.test.live=true`** (`LiveAgents`, honoured by `AgentProbe.isUsable`, which every live suite gates on). Without it nothing is probed and no agent is started. Run them when an adapter changes and before a release; `ScriptedAgent`'s wire tests are the always-on gate against protocol regressions. They still skip per runtime when the agent is absent or has no credentials.
- Live contract tests pick the model via `-Dacp-spring.test.<runtimeId>.model=...` (otherwise `AgentProbe` chooses).
- Opt-in tests that download ~24 MB from the real registry: `-Dacp-spring.test.registry.live=true`.
- No linter/formatter is configured. Java uses tabs.

## Architecture

Module dependency shape: `AgentClient` API → `AgentClientPool` → `acp-spring-core` → `acp-core` SDK → agent subprocess (stdio) or supervised `goose serve` (our own `WebSocketAgentTransport`).

- **acp-spring-core** (package-by-feature under `org.thought.acp`): `client` (fluent `AgentClient`, pool), `session` (optional session ops, gated by capability), `turn` + `event` (demultiplexing session updates into `AgentEvent`; exactly one terminal event per turn), `config` (`ConfigResolver`), `permission`, `workspace` (jail for fs/terminal), `process`, `protocol` (version negotiation), `observation`, `executor` (legacy `GooseExecutor`-shaped facade).
- **Runtime adapters** (`runtime-goose|codex|opencode`) implement the `AgentRuntime` SPI (launch spec, provisioning, candidate option ids). `runtime-registry` implements `AgentRuntimeProvider`: launches any registry agent from catalogue data with SHA-256-verified downloads. A compiled adapter always wins over the registry for the same id. Core and application code must not name a specific agent; vendor knowledge lives only in its adapter.
- **spring-boot-autoconfigure / starter**: `AcpProperties` binding, standalone `agents.yaml` via a `ConfigData` loader, optional HTTP controller, registry/observation/ChatModel configs.
- **acp-spring-ai**: `AcpChatModel` adapter (a named session sends only unheard messages; unnamed sends full history).
- **acp-spring-test**: `AgentRuntimeContract` (the real definition of the abstraction, extended once per adapter), `AgentProbe` (gates suites on a real session, not `--version`), `ScriptedAgent` (fake agent speaking raw JSON-RPC over the SDK's in-memory transport).

### Non-obvious design points

- **Three config tiers**: portable (`spring.acp.*`), negotiated (`model`/`mode`/`provider`, resolved against options the agent advertised; `on-unsupported` = fail/warn/ignore), runtime-specific (`spring.acp.runtimes.<id>.*`). The resolver reads before writing and never sends a value the agent didn't advertise, because e.g. goose accepts unknown model ids and fails later inside the turn. Adapters supply candidate option-id spellings; core matches values across them.
- **Except for a BYO endpoint.** With `spring.acp.provider.base-url` set, the agent's advertised model list is neither authoritative nor even stable (goose refreshes it from the endpoint *after* the provider is set), so the endpoint's own `/models` listing decides and an unadvertised model is sent anyway (`Mechanism.ENDPOINT`). `base-url` is canonicalised to end at the version segment; each adapter derives its vendor spelling from that. Model only — not mode or provider. See "Bring-your-own endpoints" in `docs/design.md`.
- **Don't pin the OpenAI wire API.** goose picks per model (gpt-5.6-terra → `/v1/responses`, deepseek → `/v1/chat/completions`) from `OPENAI_HOST` alone, so the adapter sets no `OPENAI_BASE_PATH`: Responses keeps reasoning items across a turn and is worth preferring wherever it exists. An application that must pin one dialect uses the tier-3 `env` block.
- **Not every runtime can reach every endpoint.** Verified live against a Tanzu GenAI (OpenAI-compatible) endpoint: goose and opencode answer; codex-acp 1.12 speaks only the Responses API (`wire_api = "chat"` now makes it refuse to start), so a gateway serving only `/chat/completions` 404s inside its first turn. The adapter is wired correctly; the dialect is the constraint.
- **SDK gaps are worked around, not suppressed** (e.g. `configOptions` dropped from `session/new`/`session/load` responses, `sessionUpdate` discriminator with no record). These are wire-format gaps invisible to mocks, hence `ScriptedAgent` wire tests; see "Known gaps" in `docs/design.md`.
- **An MCP server the agent cannot load is reported nowhere on the wire.** `session/new` succeeds,
  no `session/update` arrives, and goose writes nothing to stdout or stderr — only to its own log
  file. Hence `AgentRuntime.logDirectory`/`noticeOf` (declarations; `AgentLogWatcher` does the
  reading), `AgentClient.notices()`, and `spring.acp.mcp.on-server-failure`. Goose-only, and only
  for an agent this client started. `tools/mcp-silence-check.py` re-measures it after a goose
  upgrade.
- **`permissions.policy: deny` does not stop an agent writing files.** Combine with an agent mode that asks first (`mode: plan`). Workspace jail confines fs/terminal requests by real path (symlinks followed), not just `normalize()`.
- Optional session operations throw `UnsupportedAgentOperationException` naming the ACP method rather than failing on the wire.
- ACP v2 is gated behind a flag and refused; target v1.

## Testing conventions

- Contract tests (`*ContractTests` per runtime module) assert only what ACP standardizes; never assert a model name, tool name or agent phrasing. Skip (don't fail) where an agent legitimately lacks an optional capability.
- Registry tests use fake agents (tiny shell scripts) via a `file:` registry, not real downloads.
