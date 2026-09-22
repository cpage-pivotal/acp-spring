# acp-spring

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

**All four milestones are done.** One unchanged application runs against goose 1.51.0, codex-acp
1.12.0 and opencode 1.18.31 with `spring.acp.runtime` as the only difference — the completion test,
and the reason the abstraction is worth having. Since M4 it also runs against Gemini CLI, which this
library has never had a line of code about: no adapter, just a catalogue entry.

391 tests green: 344 run anywhere (including 24 against a scripted agent speaking raw JSON-RPC over
an in-memory transport), 45 drive real agents and skip themselves when one is not usable here, and
2 download a real agent from the real registry and are opt-in. Of the live ones, 42 are the same 14
assertions run against each adapter: `AgentRuntimeContract`, which is the actual definition of the
abstraction, since an interface alone cannot stop three adapters behaving differently enough that an
application cannot move between them.

M3 added session list/load/resume/delete (capability-gated, because no two agents implement the
same set), a workspace jail for the filesystem and terminal methods, connection pooling with idle
session sweeping, a supervised `goose serve` over WebSocket, a standalone `agents.yaml`, an opt-in
HTTP endpoint, and an `AgentExecutor` facade with the old `GooseExecutor` signatures.

M4 added the registry-driven runtime with SHA-256-verified downloads, `AcpChatModel` for Spring AI,
a Micrometer observation per turn and per tool call, `AgentEvent.UsageUpdated`, and real ACP version
negotiation behind a feature flag.

The design is in **[docs/design.md](docs/design.md)** — the feasibility analysis, the configuration
model, the runtime SPI, seven known gaps in the ACP Java SDK, and what each milestone measured.

## Try it

```bash
mvn install
mvn -pl samples/smoke-app spring-boot:run
mvn -pl samples/smoke-app spring-boot:run -Dspring-boot.run.arguments=--spring.acp.runtime=codex
mvn -pl samples/smoke-app spring-boot:run -Dspring-boot.run.arguments=--spring.acp.runtime=opencode
```

Needs the corresponding agent installed and authenticated: `goose` or `opencode` on the PATH, or
`npx` for the Codex adapter. Nothing in the sample's Java names an agent.

A fourth run needs nothing installed at all, because the agent is fetched from the ACP registry,
verified against its published SHA-256 and launched:

```bash
mvn -pl samples/smoke-app spring-boot:run -Dspring-boot.run.arguments=--spring.acp.runtime=gemini
```

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
  on top is idiomatic. Seven gaps in it are worked around and documented rather than suppressed.
- The [ACP registry](https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json) publishes
  per-agent launch metadata, so runtimes with no hand-written adapter can be launched from data —
  which M4 turned from a claim into `spring.acp.runtime: gemini`.

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

And one place where reading first is exactly wrong:

- **Your own endpoint, your own models.** Point `spring.acp.provider.base-url` at a gateway or a
  model server you run, and the agent's built-in catalogue stops being evidence — goose advertises
  ~32 OpenAI ids and repopulates them from the endpoint *after* the provider is set, so the same
  model was refused by two sessions and accepted by a third seconds later. With a `base-url` set, the
  endpoint's own `/models` listing decides and the model is applied whether or not the agent
  advertised it. Four properties, no runtime-specific block:

```yaml
spring:
  acp:
    runtime: goose
    model: deepseek-ai/DeepSeek-V4-Flash-0731
    provider:
      id: openai
      api-type: openai
      base-url: https://gateway.example.com/team-x/openai
      api-key: ${GENAI_API_KEY}
```

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

## An agent nobody wrote an adapter for

Set `spring.acp.runtime` to any of the 41 agents the ACP registry publishes, add
`acp-spring-runtime-registry`, and the agent is resolved from a cached catalogue snapshot,
downloaded, checked against its published SHA-256 and launched:

```
No adapter claims runtime 'gemini'; it was resolved from a runtime provider
Connected to gemini-cli 0.60.0 over ACP v1
```

A compiled adapter always wins for the same agent, because an adapter knows things a catalogue does
not: where goose hides a tool name, that its provider option has no category, how to run it as a
served sidecar. The generic runtime has none of that and does not pretend to — it provisions nothing,
never guesses a tool name from a human-readable title, and reaches the negotiated tier only through
what ACP actually standardizes.

`require-checksum` is on by default, which makes 9 of the registry's 19 binary agents need one more
line of configuration. That is deliberate: this downloads an executable and runs it in a process
holding your model credentials.

## As a Spring AI `ChatModel`

```java
ChatResponse response = chatModel.call(new Prompt("Review the pending changes",
        AcpChatOptions.builder().session("review-123").mode("plan").build()));
```

Add `acp-spring-ai` and the agent appears wherever a Spring AI application already looks for
a model. It is an adapter rather than a wrapper, because the two models disagree about who owns the
conversation: a chat completion is stateless and resends the history every call, while an ACP session
holds it — along with a file tree, a plan and tool results no message list can carry. So a named
session sends only what the agent has not heard yet, and an unnamed one sends everything. Use one
memory or the other, not both.

## MCP servers that need each user's own sign-in

Add `acp-spring-mcp-oauth`, mark the server, and add one line to your `SecurityFilterChain`:

```yaml
spring:
  acp:
    mcp-servers:
      - name: finops-mcp
        url: https://gateway.example.com/finops-mcp/mcp
        auth: oauth
```

```java
http.with(McpClientOAuth2Configurer.mcpClientOAuth2(), mcp -> mcp.cimd(false));
```

The first time a signed-in user needs the server, they are redirected to its authorization server;
after that their token is refreshed as needed. A terminal application sets
`spring.acp.mcp.oauth.mode: local` instead and needs no filter chain: the first run opens a browser
for each server, and the sign-ins are kept in `~/.config/<spring.application.name>/`. The agent never sees it: HTTP MCP servers with
credentials are reached through a loopback proxy, one unguessable route per session. See "MCP
credentials" in `docs/design.md`.

## Metrics and traces

With Micrometer on the classpath, every turn and every tool call becomes an observation — a timer,
and a span parented to whatever the caller was already in:

```
acp.turn{acp.runtime=goose, acp.model=gpt-5.6-terra, acp.session.kind=named, acp.outcome=END_TURN}
acp.tool.call{acp.runtime=goose, acp.tool.kind=READ, acp.tool.status=COMPLETED}
```

`acp.model` is the model the session is **really** using, not the one that was requested — which with
`on-unsupported: warn` is not the same thing, and is the whole reason to tag it.

## Scope

Library and configuration only. Packaging the agent binary — buildpack, container image, or
sidecar — is deliberately out of scope; the runtime may live anywhere the JVM can launch or reach
it.

## License

MIT.
