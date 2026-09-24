# AGENTS.md

Guidance for AI coding agents (Goose, Codex, OpenCode, and others) that work on this
repository through the Agent Client Protocol.

## Build and test

Java 21 is required. Use the Maven wrapper:

```bash
./mvnw install          # build + all tests except the live-agent ones
./mvnw test             # same suite; live-agent suites are opt-in and skip by default
./mvnw test -Dspring-ai-acp.test.live=true    # also run the live-agent suites (spends real tokens)
./mvnw -pl spring-ai-acp-core test -Dtest=ConfigResolverTests   # one class
./mvnw spring-javaformat:apply                # apply the Spring formatter before a PR
```

Live-agent suites cost real tokens and are opt-in: `AgentProbe.isUsable` gates every
live suite, so without `-Dspring-ai-acp.test.live=true` nothing is probed and no agent is
started. `ScriptedAgent`'s wire tests are the always-on gate against protocol regressions.
Registry live tests are opt-in too: `-Dspring-ai-acp.test.registry.live=true`.

## Conventions

- Java uses tabs, not spaces.
- Package-by-feature under `org.springaicommunity.acp`: `client`, `session`, `turn`,
  `event`, `config`, `permission`, `mcp`, `workspace`, `process`, `protocol`,
  `observation`, `executor`, `runtime`, `skill`.
- Core and application code must not name a specific agent; vendor knowledge lives only
  in its runtime adapter under `spring-ai-acp-runtime-*`.
- `docs/user-guide.html` is the rendered user guide; `README.md` links to it.
- This repository is built on the official `com.agentclientprotocol:acp-core`
  (pre-1.0, pinned exactly). Read the "Known gaps" section of the user guide before
  changing protocol handling.

## Testing conventions

- Contract tests (`*ContractTests` per runtime module) assert only what ACP
  standardizes; never assert a model name, tool name or agent phrasing. Skip (don't
  fail) where an agent legitimately lacks an optional capability.
- Tests that create git commits must disable signing (`-c commit.gpgsign=false`), so a
  developer's own signing setup cannot hang the suite.
- Keep the always-on suite deterministic and free of network access to real agents.

## Pull requests

- Sign every commit and keep history linear.
- Direct changes to `main` are not accepted; submit a pull request.
- Confirm your contribution is compatible with the Apache License 2.0.
