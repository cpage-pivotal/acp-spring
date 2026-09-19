# spring-acp

A Spring library that talks to **any** [Agent Client Protocol](https://agentclientprotocol.com)
coding agent — Goose, Codex, OpenCode, and others — behind one programming model and one
configuration surface.

The relationship to ACP runtimes is the one Spring Data has to databases:

- configuration carries options that work across **all** runtimes, plus a per-runtime escape hatch;
- application code always uses the same abstracted interface;
- the agent on the backend is swapped by changing one property.

```yaml
spring:
  acp:
    runtime: goose      # ← change to codex or opencode; nothing else moves
    model: claude-sonnet-5
```

```java
String out = agentClient.prompt()
        .session("review-123")
        .user("Inspect this change")
        .call().content();

Flux<AgentEvent> events = agentClient.prompt()
        .user("Now focus on security")
        .stream().events();     // Text, Thought, ToolCallStarted, PlanUpdated, Completed…
```

## Status

**M1 is done: core plus Goose over stdio.** Verified against a live `goose acp` subprocess —
connect, blocking call, streamed turn, named sessions keeping context across turns, and stream
cancellation that actually cancels the turn on the agent. 49 tests green, of which 5 drive the real
binary and skip when it is absent.

Not yet built: the Codex and OpenCode adapters (M2), the `agents.yaml` loader, process pooling and
the WebSocket transport (M3), the registry-driven runtime and the Spring AI adapter (M4).

The design is in **[docs/design.md](docs/design.md)** — the feasibility analysis, the configuration
model, the runtime SPI, known gaps in the ACP Java SDK, and the milestone plan.

## Try it

```bash
mvn install
cd samples/smoke-app
mvn dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt -q
java -cp "target/classes:$(cat /tmp/cp.txt)" org.tanzu.acp.sample.SmokeApplication
```

Needs `goose` on the PATH with a provider configured. Change `spring.acp.model` in
`samples/smoke-app/src/main/resources/application.yaml` if your key cannot reach the default.

## Why this is feasible

- The `java-wrapper` in `goose-buildpack` is **already an ACP v1 client**; only its process
  supervisor is Goose-specific. This is a generalization, not a rewrite.
- An official Java SDK exists — `com.agentclientprotocol:acp-core` (Java 17+, Reactor, stdio and
  WebSocket transports) — whose design mirrors the MCP Java SDK, so Spring Boot autoconfiguration
  on top is idiomatic.
- The [ACP registry](https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json) publishes
  per-agent launch metadata, so runtimes with no hand-written adapter can still be launched from
  data.

## The interesting problem

ACP standardizes the *conversation*, not the *provisioning*. So configuration comes in three tiers:

| Tier | Where | Example |
| --- | --- | --- |
| **Portable** | `spring.acp.*` | `workspace`, `mcp-servers`, `permissions`, `timeout` |
| **Negotiated** | resolved against what the agent advertises | `model`, `mode`, `provider` |
| **Runtime-specific** | `spring.acp.runtimes.<id>.*` | Goose `extensions`, Codex `config-toml` |

The negotiated tier is the crux: `session/set_config_option` is a standard method, but its option
IDs are agent-declared, so `model:` is a request rather than an assignment. `on-unsupported`
(`fail` / `warn` / `ignore`) decides what happens when a runtime cannot honor one.

## Scope

Library and configuration only. Packaging the agent binary — buildpack, container image, or
sidecar — is deliberately out of scope; the runtime may live anywhere the JVM can launch or reach
it.

## License

MIT.
