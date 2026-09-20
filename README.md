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
    model: gpt-5.4-mini # a request, not an assignment; see on-unsupported
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

**M1, M2 and M3 are done: the core, all three runtimes, and parity with the wrapper this
replaces.** One unchanged application runs against goose 1.51.0, codex-acp 1.12.0 and opencode
1.18.31 with `spring.acp.runtime` as the only difference — the completion test, and the reason the
abstraction is worth having.

289 tests green: 244 run anywhere (including 21 against a scripted agent speaking raw JSON-RPC over
an in-memory transport) and 45 drive real agents, skipping themselves when one is not usable here.
Of those, 42 are the same 14 assertions run against each adapter: `AgentRuntimeContract`, which is
the actual definition of the abstraction, since an interface alone cannot stop three adapters
behaving differently enough that an application cannot move between them.

M3 added session list/load/resume/delete (capability-gated, because no two agents implement the
same set), a workspace jail for the filesystem and terminal methods, connection pooling with idle
session sweeping, a supervised `goose serve` over WebSocket, a standalone `agents.yaml`, an opt-in
HTTP endpoint, and an `AgentExecutor` facade with the old `GooseExecutor` signatures.

Not yet built: the registry-driven runtime, the Spring AI adapter and Micrometer (M4).

The design is in **[docs/design.md](docs/design.md)** — the feasibility analysis, the configuration
model, the runtime SPI, six known gaps in the ACP Java SDK, and what each milestone measured.

## Try it

```bash
mvn install
mvn -pl samples/smoke-app spring-boot:run
mvn -pl samples/smoke-app spring-boot:run -Dspring-boot.run.arguments=--spring.acp.runtime=codex
mvn -pl samples/smoke-app spring-boot:run -Dspring-boot.run.arguments=--spring.acp.runtime=opencode
```

Needs the corresponding agent installed and authenticated: `goose` or `opencode` on the PATH, or
`npx` for the Codex adapter. Nothing in the sample's Java names an agent.

## A caveat worth reading before you rely on it

`permissions.policy: deny` does **not** stop an agent from writing files. Declaring
`fs.writeTextFile: false` only declines to lend the agent *the client's* filesystem; measured against
all three agents, each one creates a file in its default mode with **zero** permission requests.

The mode decides whether the question gets asked; the policy decides the answer. Set both:

```yaml
spring:
  acp:
    mode: plan          # goose calls its equivalent "approve"
    permissions:
      policy: deny
```

With that, goose asks twice, is refused twice, and writes nothing. The measurements and the full
table are in the design doc's security section.

M3 closed the half of this that *is* the client's: when `spring.acp.filesystem` or
`spring.acp.terminal` is turned on, every path the agent asks this client to touch is confined to
the workspace by real path, symlinks followed — because `Path.normalize()` stops `..` and stops
nothing else, and an agent can plant a symlink with one tool call. What remains outside any
client's reach is the agent's *own* file access, which is the operating system's to restrict.

## Why this is feasible

- The `java-wrapper` in `goose-buildpack` is **already an ACP v1 client**; only its process
  supervisor is Goose-specific. This is a generalization, not a rewrite.
- An official Java SDK exists — `com.agentclientprotocol:acp-core` (Java 17+, Reactor, stdio and
  WebSocket transports) — whose design mirrors the MCP Java SDK, so Spring Boot autoconfiguration
  on top is idiomatic. Six gaps in it are worked around and documented rather than suppressed.
- The [ACP registry](https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json) publishes
  per-agent launch metadata, so runtimes with no hand-written adapter can still be launched from
  data.

## The interesting problem

ACP standardizes the *conversation*, not the *provisioning*. So configuration comes in three tiers:

| Tier | Where | Example |
| --- | --- | --- |
| **Portable** | `spring.acp.*` | `workspace`, `mcp-servers`, `permissions`, `timeout` |
| **Negotiated** | resolved against what the agent advertises | `model`, `mode`, `provider` |
| **Runtime-specific** | `spring.acp.runtimes.<id>.*` | Goose `builtins`, Codex `config-toml`, OpenCode `config` |

The negotiated tier is the crux: `session/set_config_option` is a standard method, but its option
IDs and values are agent-declared, so `model:` is a request rather than an assignment.
`on-unsupported` (`fail` / `warn` / `ignore`) decides what happens when a runtime cannot honor one,
and `AgentSession.configuration()` reports what actually took effect.

Two things that tier turned out to require, both measured rather than guessed:

- **Read before you write.** goose 1.51 *accepts* a model id it has never heard of and fails seconds
  later inside the turn, where the provider's 404 arrives as agent prose. So the resolver matches the
  request against the options the agent advertised and never sends a call it expects to be refused —
  which meant recovering a field the ACP Java SDK drops from `session/new`.
- **One value, several vendor spellings.** `plan` is a value of OpenCode's `mode` and of Codex's
  `collaboration_mode`; `gpt-5.4-mini` is `openai/gpt-5.4-mini` on OpenCode. An adapter supplies
  candidate option ids and the core matches values across the spellings, so one property means one
  thing.

## What each agent can actually do with a session

Every optional session method is gated on a capability, and the three runtimes disagree — which is
why `sessions().supports(...)` is part of the API rather than a convenience:

| | `list` | `load` | `resume` | `delete` | `close` |
| --- | --- | --- | --- | --- | --- |
| goose 1.51.0 | yes | yes | **no** | yes | yes |
| codex-acp 1.12.0 | yes | yes | yes | yes | yes |
| opencode 1.18.31 | yes | yes | yes | **no** | yes |

```java
if (agentClient.sessions().supports(Operation.LOAD)) {
    agentClient.sessions().load("review-123", storedId);   // same conversation, not a new one
}
```

An operation the agent never advertised throws `UnsupportedAgentOperationException` naming the ACP
method, rather than failing on the wire with a code the caller has to interpret.

## Scope

Library and configuration only. Packaging the agent binary — buildpack, container image, or
sidecar — is deliberately out of scope; the runtime may live anywhere the JVM can launch or reach
it.

## License

MIT.
