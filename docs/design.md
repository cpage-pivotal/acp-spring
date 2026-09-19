# spring-acp — a Spring Data-style abstraction over ACP coding agents

Status: proposed design. Successor to the `java-wrapper` module of
[`goose-buildpack`](https://github.com/cpage-pivotal/goose-buildpack).

## Context

`goose-buildpack` couples three things to one vendor: a supply buildpack that installs the Goose
binary, a `java-wrapper` library that drives it, and `.goose-config.yml` that configures it. The
wrapper's public contract (`GooseExecutor`) is already vendor-neutral in its signatures, but
everything below it names Goose.

The goal is a successor library where the *runtime* is swappable — Goose, Codex, OpenCode, or any
ACP-compliant agent — behind one Spring programming model and one configuration surface, exactly as
Spring Data swaps the store behind one repository model. The buildpack is explicitly out of scope
for now; this plan covers the library and the configuration model only.

### Feasibility: yes, and the starting position is better than it looks

Three findings, verified this session:

1. **`java-wrapper` is already an ACP client.** `java-wrapper/src/main/java/org/tanzu/goose/cf/acp/`
   speaks JSON-RPC ACP v1 — `initialize`, `session/new`, `session/prompt`, `session/cancel`,
   `session/set_config_option`, `session/close`, inbound `session/request_permission`. Its own
   javadoc calls `AcpTransport` a seam "so everything above it can be tested without a socket," and
   `AcpEventTranslator` a declared compatibility boundary. The only Goose-specific class in that
   package is `GooseServerSupervisor`. This is a generalization, not a rewrite.
2. **An official Java SDK exists**: `com.agentclientprotocol:acp-core:0.17.0` (Java 17+, Reactor,
   Jackson). It ships `AcpClient.sync()/async()` builders with handler slots for exactly the
   client-side methods we must implement (`readTextFileHandler`, `writeTextFileHandler`,
   `requestPermissionHandler`, `createTerminalHandler`, `createElicitationHandler`,
   `sessionUpdateConsumer`), plus `StdioAcpClientTransport` **and** `WebSocketAcpClientTransport`.
   Its design deliberately mirrors the MCP Java SDK, so Spring Boot autoconfiguration on top is
   idiomatic. This deletes most of the ~3k lines of protocol code the wrapper currently owns. It
   does *not* delete the turn demultiplexer — see "What to port" below for why.
3. **Runtime discovery is already a solved, data-driven problem.** The ACP registry
   (`https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json`, 41 agents) publishes per-agent
   launch metadata: `goose` → binary + `["acp"]`, `opencode` → binary + `["acp"]`, `codex-acp` →
   `npx @agentclientprotocol/codex-acp`, `gemini` → `npx @google/gemini-cli --acp`, each with
   per-arch URLs and SHA-256. A generic registry-driven runtime can launch agents we never wrote an
   adapter for.

### Verified preconditions

Measured on the development machine, not assumed:

| Check | Result |
| --- | --- |
| Toolchain | Java 21.0.11 LTS, Maven 3.9.16 |
| `com.agentclientprotocol:acp-core:0.17.0` | Published on Maven Central, resolves, sources read |
| `com.agentclientprotocol:acp-test:0.17.0` | Published — the TCK's in-memory transport is available |
| `goose acp` | goose 1.50.0, "Run goose as an ACP agent server on stdio" |
| ACP handshake | `initialize` over stdio returns `protocolVersion: 1` and full `agentCapabilities` |
| `session/new` | Returns a live session id, `modes`, and four `configOptions` |
| Reactor skeleton | `mvn validate` passes across all seven modules |

Because a provider is already configured locally, M1 can be verified with real prompts end to end
rather than against fakes alone.

### The one hard problem, and the honest answer

ACP standardizes the *conversation*, not the *provisioning*. Portable across every runtime, at the
wire level: `cwd`, `mcpServers[]`, `additionalDirectories`, permission handling, filesystem and
terminal handling, cancellation, and the streamed event vocabulary. **Not portable**: which env var
carries the API key, where the system prompt lives (`AGENTS.md` vs `~/.config/goose/config.yaml` vs
`opencode.json`), Goose `extensions:`, skills.

In between sits a *negotiated* tier. `session/set_config_option` and `providers/set` are standard
methods, but their content is agent-declared — a client can only set option IDs the agent
advertised, so `model: claude-sonnet-5` cannot be pushed blindly. This is the same shape of problem
Spring Data has with store-specific query features, and it gets the same three-tier answer below.

**Risks to accept up front:** `acp-core` is pre-1.0 and states "we don't promise strict semver."
ACP v2 is a published *draft* whose own announcement says not to ship it in production — so target
v1 and let `initialize()` negotiate. Goose's WebSocket ACP server (`goose serve` + `X-Secret-Key`)
is Goose-specific; every other runtime is stdio-only, so both transports must be first-class.

---

## Architecture

```
  Application code
        │  AgentClient  (fluent; sessions, turns, events)
        ▼
  spring-acp-core ──────────────────────────────────────────────┐
     AgentClient impl · SessionRegistry · AgentProcessPool       │
     PermissionPolicy · WorkspaceFileSystem · TerminalPolicy     │
     ConfigResolver (3-tier) · AgentEvent model                  │
        │                    ▲                                   │
        │ AcpSyncClient/     │ AgentRuntime SPI                   │
        │ AcpAsyncClient     │ (launch · provision · configure)   │
        ▼                    │                                   │
  com.agentclientprotocol:acp-core                               │
     StdioAcpClientTransport │ WebSocketAcpClientTransport        │
        │                    │                                   │
        ▼                    ├── runtime-goose  (stdio + ws)     │
  agent subprocess           ├── runtime-codex  (npx, stdio)     │
                             ├── runtime-opencode (binary, stdio)│
                             └── RegistryAgentRuntime (generic) ──┘
```

Decisions confirmed with the user: build on `acp-core`; native `AgentClient` API with an optional
Spring AI `ChatModel` adapter; config bindable from both `application.yaml` and a standalone
`agents.yaml`; first milestone proves Goose, Codex, and OpenCode.

---

## Module layout

Multi-module Maven, Java 21, Spring Boot 4 (matching `java-wrapper`). Group `org.tanzu.acp` — do
**not** squat `org.springframework`. Packages organized by feature, not layer.

| Module | Contents |
| --- | --- |
| `spring-acp-core` | No Spring types on the classpath-required path. `org.tanzu.acp.client`, `.session`, `.process`, `.permission`, `.workspace`, `.config`, `.runtime`, `.event` |
| `spring-acp-runtime-goose` | `GooseRuntime` — stdio `goose acp` **and** `goose serve`/WebSocket; writes `config.yaml`; `extensions`/`skills` support |
| `spring-acp-runtime-codex` | `CodexRuntime` — `npx @agentclientprotocol/codex-acp`; `~/.codex/config.toml` provisioning |
| `spring-acp-runtime-opencode` | `OpenCodeRuntime` — binary + `acp`; `opencode.json` provisioning |
| `spring-acp-spring-boot-autoconfigure` | `AcpProperties`, `AcpAutoConfiguration`, `AcpWebFluxAutoConfiguration`, `AgentsYamlConfigDataLoader` |
| `spring-acp-spring-boot-starter` | Pom-only aggregator (`+ autoconfigure + core + runtime-goose`) |
| `spring-acp-spring-ai` | Optional `AcpChatModel implements ChatModel` |
| `spring-acp-test` | Runtime conformance TCK + in-memory transport (`acp-test`) |

Follow the wrapper's proven packaging trick: declare `spring-boot-*` dependencies `<optional>true</optional>`
in core so the library works without Spring on the classpath, and keep a Spring-free settings mirror
(today's `AcpServerSettings`) between properties and the process layer.

---

## Configuration model

Three tiers. This is the heart of the Spring Data analogy.

### Tier 1 — portable (`spring.acp.*`)

Declared once, honored by every runtime. Either via `application.yaml`, or via a standalone
`agents.yaml` pulled in with `spring.config.import: optional:agents.yaml` — one binding model, two
entry points, so Spring devs get relaxed binding, profiles and `${}` resolution while a
buildpack or operator can still drop a file next to the app.

```yaml
spring:
  acp:
    runtime: goose              # or codex, opencode, or a registry id
    workspace: /home/vcap/app/workspace   # → session/new cwd
    model: claude-sonnet-5      # negotiated; see tier 2
    timeout: 5m
    on-unsupported: warn        # fail | warn | ignore

    provider:                   # → config option, else providers/set, else env
      api-type: openai
      base-url: ${TANZU_AI_ENDPOINT}
      api-key: ${TANZU_AI_API_KEY}

    mcp-servers:                # → session/new mcpServers[] verbatim
      - name: internal-tools
        type: http
        url: https://tools.example.com/mcp
        headers: { Authorization: "Bearer ${MCP_TOKEN}" }

    permissions:
      policy: deny              # deny | allowlist | auto-approve
      allowed-tools: [ developer__text_editor ]

    filesystem:
      enabled: false            # clientCapabilities.fs
      write: false
    terminal:
      enabled: false

    pool:
      max-processes: 1
      max-sessions-per-process: 32
      session-ttl: 60m
      max-restarts: 5
```

Carry over the wrapper's `GooseOptions` validation wholesale — `base-url` must be HTTPS or
loopback/`.apps.internal` with no userinfo/query/fragment, env keys `^[A-Za-z_][A-Za-z0-9_]*$`,
API key ≤16 KiB with no CR/LF, session names `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`. It is already
written and already right.

### Tier 2 — negotiated

`model`, `mode`, and `provider` are *requests*, not assignments. After `session/new`, `ConfigResolver`
inspects the returned `configOptions` and the agent's advertised capabilities and resolves:

| Request | Resolution order |
| --- | --- |
| `model` | a `configOptions` entry with `category: model` whose option `value` or `name` matches → `session/set_config_option`; else the runtime adapter's native mapping (e.g. `GOOSE_MODEL`); else unsupported |
| `provider` | a `configOptions` entry with id/category `provider` → `session/set_config_option`; else `providers/list` + `providers/set` when `ProvidersCapabilities` is advertised; else the adapter's env mapping |
| `mode` | `configOptions` with `category: mode`; else `session/set_mode`; else unsupported |

Measured against a live **goose 1.50.0** `session/new`, this tier is in better shape than assumed:

- Goose returns `configOptions` for `provider` (a select of 80+ values, `tanzu_ai` among them),
  `model` (`category: model`), `mode` (`category: mode`) and `thinking_effort`
  (`category: thought_level`). Provider and model selection therefore go through
  `session/set_config_option` — no env-var mapping needed, and far more portable than one.
- It advertises **no** `providers/*` capability, so `providers/set` is not the primary path; it is a
  fallback for agents that offer it instead.
- It advertises **no** `sessionCapabilities.resume` — session resume is capability-gated, not assumed.
- It returns **both** `modes` and `configOptions`, as the spec's transition guidance prescribes.

Two consequences for `ConfigResolver`: read `configOptions` straight off the `session/new` response
rather than gating on a client capability, and prefer `configOptions` over `modes` when both appear.

`on-unsupported` decides what happens when nothing resolves — `fail` throws
`UnsupportedAgentOptionException` (the Spring Data `UnsupportedOperationException` analogue), `warn`
logs once per option per runtime, `ignore` is silent. Resolution results are exposed on
`AgentSession.configuration()` so an app can inspect what actually took effect.

### Tier 3 — runtime-specific escape hatch (`spring.acp.runtimes.<id>.*`)

Passed to that adapter untouched; ignored by every other adapter. The `@Query`-with-native-SQL
analogue. Precedence: tier 3 overrides tier 1 for the selected runtime.

```yaml
spring:
  acp:
    runtimes:
      goose:
        extensions: { developer: { enabled: true } }
        skills: [ { name: release-checks, path: .goose/skills/release-checks } ]
        env: { GOOSE_DISABLE_KEYRING: "1" }
        serve: { transport: websocket, host: 127.0.0.1, port: 0 }
      codex:
        config-toml: { model_reasoning_effort: high }
      opencode:
        config: { theme: system }
```

Validation: tier-3 keys under a runtime with no registered adapter are a startup failure, not a
silent no-op — that was the most common `.goose-config.yml` mistake class.

---

## Programming interface

```java
// one-shot
String out = agentClient.prompt()
        .user("Summarize the pending changes")
        .call().content();

// named session, structured stream
Flux<AgentEvent> events = agentClient.prompt()
        .session("review-123")
        .user("Now focus on security")
        .options(o -> o.model("claude-opus-5").timeout(Duration.ofMinutes(10)))
        .stream().events();

// escape hatch to raw ACP
AcpSyncClient raw = agentClient.unwrap(AcpSyncClient.class);
```

`AgentEvent` is a sealed interface over records — the structured successor to
`AcpEventTranslator`'s three-shape NDJSON vocabulary, reusing its mapping logic:

```java
public sealed interface AgentEvent {
    record Text(String text)                                  implements AgentEvent {}
    record Thought(String text)                               implements AgentEvent {}
    record ToolCallStarted(String id, String name, ToolKind kind)      implements AgentEvent {}
    record ToolCallUpdated(String id, ToolCallStatus status, List<ContentBlock> content)
                                                              implements AgentEvent {}
    record PlanUpdated(List<PlanEntry> entries)               implements AgentEvent {}
    record ConfigChanged(List<SessionConfigOption> options)   implements AgentEvent {}
    record Usage(long inputTokens, long outputTokens)         implements AgentEvent {}
    record Completed(StopReason reason)                       implements AgentEvent {}
    record Failed(AgentException cause)                       implements AgentEvent {}
}
```

**Preserve the wrapper's load-bearing invariant**: every turn emits exactly one terminal event
(`Completed` or `Failed`) — normal end, RPC error, timeout, dropped connection, or consumer
cancellation. Closing an unfinished stream sends `session/cancel` *before* completing. That
invariant is documented in `AcpTurn.java` and is why UI spinners don't hang; carry the test for it
across.

Also ship `AgentSessions` (list/load/resume/delete/close, capability-gated) and, for migration, an
`AgentExecutor` facade with the exact `GooseExecutor` signatures so existing goose-buildpack apps
can move with a rename.

---

## Runtime SPI

```java
public interface AgentRuntime {
    String id();

    /** How to start it: command+args+env for stdio, or a WebSocket endpoint. */
    AgentLaunchSpec launch(ResolvedConfig config, Path runtimeHome);

    /** Write agent-native config files before launch (config.yaml, config.toml, AGENTS.md…). */
    default void provision(ResolvedConfig config, Path runtimeHome) {}

    /** Apply negotiated options after session/new. */
    default void configureSession(AcpSyncClient client, String sessionId, ResolvedConfig config) {}

    /** Extract a tool name for the permission policy (Goose hides it in _meta). */
    default Optional<String> toolNameOf(AcpSchema.ToolCall call) { return Optional.empty(); }
}
```

`AgentLaunchSpec` is a sealed type: `Stdio(command, args, env)` or `WebSocket(uri, headers, ProcessSpec)`.
Discovery via `spring.factories`-style `META-INF/spring/…AutoConfiguration.imports` plus
`@ConditionalOnMissingBean`, so an app can override any adapter.

`RegistryAgentRuntime` is the generic fallback: given `spring.acp.runtime: gemini` with no compiled
adapter, resolve the entry from a cached ACP registry snapshot, honor `distribution.binary`
(per-arch archive + SHA-256) or `distribution.npx`, and launch it with tier-1 config only. This is
what makes "any ACP agent" a real claim rather than a roadmap item.

---

## What to port from `java-wrapper`, and what to drop

Port (these are proven and non-obvious):

| From | To | Why |
| --- | --- | --- |
| `acp/AcpSessionRegistry.java` | `session/SessionRegistry` | name→id map authoritative over the caller's `resume` flag; per-entry `Semaphore(1)` turn permit (not a lock — the releasing thread differs); idle TTL sweep |
| `acp/GooseServerSupervisor.java` | `process/AgentProcessSupervisor` | virtual-thread stdout drain (an undrained pipe blocks the child), secret redaction, exponential-backoff restart capped in a 5-min window, health polling, shutdown hook |
| `acp/AcpPermissionPolicy` + `AcpClientRequestHandler` | `permission/PermissionPolicy` | deny-by-default, allowlist, `allow_once`/`reject_once` option selection |
| `acp/AcpEventTranslator.java` | `event/AgentEventMapper` | `session/update` → event mapping, drops `_meta.replay` history, synthesizes results for tool calls left open |
| `GooseOptions` validation | `config/` records | see tier 1 above |
| `GooseAutoConfiguration` shape | `AcpAutoConfiguration` | `SmartLifecycle` at `Integer.MAX_VALUE - 1000`; **startup failure logged, not thrown**, so an app healthy apart from its agent stays up |

Drop: the hand-rolled `AcpConnection`, `WebSocketAcpTransport` and JSON-RPC request/response
correlation — `acp-core` covers all of it, and its 0.15.0 release specifically fixed notification
ordering and loss-on-graceful-close, the same class of bug that code exists to avoid.

**Keep the turn demultiplexer.** Verified against the 0.17.0 sources: `prompt()` returns
`Mono<PromptResponse>` carrying only a `stopReason`. Streamed content does *not* come back on that
Mono — it arrives out of band through a single `sessionUpdateConsumer` registered once at client
build time, for every session. So `NdJsonStreamBridge`'s hand-rolled deque is replaced by
`Sinks.many().unicast().onBackpressureBuffer()`, but the logic around it is not free:

- route each `SessionNotification` to the right per-session, per-turn sink by `sessionId`;
- join that sink with the `prompt()` Mono so the turn emits exactly one terminal event;
- on consumer cancellation, send `session/cancel` before completing the sink.

This is the single hardest piece of M1 and the one place `AcpTurn.java`'s existing semantics should
be ported closely rather than reinvented.

---

## Known gaps in acp-core 0.17.0

Found by running the SDK against a live goose 1.50.0. Neither blocks M1; both are worth tracking,
and both argue for keeping the SDK behind our own types rather than exposing it in the public API.

**`NewSessionResponse` does not model `configOptions`.** The record carries `sessionId`, `modes` and
`models`, and is annotated `@JsonIgnoreProperties(ignoreUnknown = true)` — so the config options a
live agent returns on `session/new` are dropped before a client can read them. `ForkSessionResponse`
and `SetSessionConfigOptionResponse` both model the field, which suggests an oversight rather than a
deliberate omission. `ConfigResolver` works around it by setting optimistically and reading the
agent's real configuration out of the set response.

**`session_info_update` is an unknown subtype.** Goose emits it several times per turn; acp-core's
`SessionUpdate` hierarchy has no variant for it, so Jackson fails to resolve the type id and the SDK
logs an ERROR per occurrence. Functionally harmless — the notification carries session metadata a
turn does not need, and the turn completes normally — but the log noise is alarming and would train
operators to ignore a genuine error at that logger.

We deliberately do *not* suppress that logger: silencing real notification-handling failures to hide
one known-benign case is the wrong trade. The fix is to stop relying on `sessionUpdateConsumer` and
register a raw `notificationHandler` for `session/update` instead, deserializing leniently so an
unknown discriminator is skipped rather than thrown. `AcpClient.build()` only installs its own
handler when a `sessionUpdateConsumer` is registered, so a custom handler survives. That belongs in
M2, alongside the SPI extraction.

## Security posture

This library is an ACP **client** running server-side, which is materially different from an IDE.
Defaults must be restrictive and match the wrapper's 4.1.0 hardening:

- `permissions.policy: deny`; `filesystem.enabled: false`; `terminal.enabled: false` — declare all
  three in `clientCapabilities` so agents don't attempt them.
- When filesystem *is* enabled, `WorkspaceFileSystem` resolves every `fs/read_text_file` and
  `fs/write_text_file` path against the session `cwd` via `Path.toRealPath()` and rejects escapes,
  including via symlink. Same jail for `terminal/create` `cwd`.
- Any WebFlux controller is opt-in (`spring.acp.controller.enabled: false` default), requires a
  `Principal`, and rejects request-level provider/model/credential overrides unless explicitly
  enabled. Credentials and endpoints are fixed at process start; only negotiated per-session
  options vary.
- Never log the process env, the WebSocket secret, or MCP headers — keep the supervisor's redaction.

---

## Milestones

**M1 — core + Goose over stdio.** Build order:

1. `spring-acp-core` dependencies and the `AgentEvent` sealed model.
2. `AgentSession` + `SessionRegistry` — port the name→id authority over the caller's `resume` flag
   and the per-entry `Semaphore(1)` turn permit.
3. **`AgentTurn`** — the notification demultiplexer described under "What to port". This is the
   hard part of M1; the exactly-one-terminal-event test lands with it.
4. `AgentClient` fluent API over `AcpAsyncClient`.
5. `PermissionPolicy` (deny by default) and a minimal `ConfigResolver` that applies `model`,
   `provider` and `mode` from the `configOptions` Goose returns on `session/new`.
6. `GooseRuntime` (stdio), `AcpProperties`, `AcpAutoConfiguration`, starter.
7. Smoke app: a Boot application that prompts `goose acp` and streams structured events.

Done when step 7 runs against the real binary and the TCK's turn-semantics tests pass.

Deferred out of M1 deliberately: `AgentProcessSupervisor` restart/health logic (one process, fail
fast, until pooling arrives in M3) and the WebSocket transport.

**M2 — the abstraction earns its keep.** `AgentRuntime` SPI extracted, `CodexRuntime` and
`OpenCodeRuntime` added, `ConfigResolver` generalized across runtimes with the `providers/*` and
env-mapping fallbacks and the `on-unsupported` policy, tier-3 escape hatches. Done when one unchanged app runs against all three by changing `spring.acp.runtime`.

**M3 — parity and ergonomics.** `agents.yaml` `ConfigDataLoader`, `AgentExecutor` migration facade,
process pooling, session list/load/resume/delete, `WorkspaceFileSystem` + terminal handlers,
optional WebFlux controller, Goose WebSocket transport (`goose serve`) for goose-buildpack parity.

**M4 — reach.** `RegistryAgentRuntime` with a cached registry snapshot and SHA-256-verified
downloads; Spring AI `AcpChatModel`; Micrometer observations per turn/tool call; ACP v2 behind
negotiation and a feature flag, off by default.

---

## Verification

1. **Runtime conformance TCK** in `spring-acp-test` — one abstract `AgentRuntimeContractTest` run
   as a parameterized suite against every registered runtime. Asserts the portable contract only:
   session create/prompt/cancel/close, exactly-one-terminal-event, tool-call events observed,
   deny-by-default blocks a write, MCP server from config appears in the agent's tool list,
   unsupported `model` honors `on-unsupported`. This suite *is* the definition of the abstraction —
   an adapter that passes it is swappable.
2. **Fast tests** against `acp-test`'s in-memory transport plus a scripted fake agent, mirroring
   today's `FakeAcpTransport` — no subprocess, runs in CI.
3. **Integration tests** gated on the binaries being present (`goose`, `npx`, `opencode`), skipped
   otherwise; a CI job installs all three from the registry manifest and runs them.
4. **Smoke app** under `spring-acp/samples/` with a single `application.yaml`; flip
   `spring.acp.runtime` across goose/codex/opencode and confirm identical observable behavior.
   Analogue of today's `validation/smoke-app/`.
5. **Security tests** ported from the wrapper: symlink escape from the workspace jail, non-loopback
   host rejection, `base-url` scheme validation, secret redaction in logs, permission-deny path.

## Repository layout

```
spring-acp/
├── pom.xml                                  # reactor
├── README.md
├── docs/design.md                           # this document
├── spring-acp-core/
├── spring-acp-runtime-goose/
├── spring-acp-runtime-codex/
├── spring-acp-runtime-opencode/
├── spring-acp-spring-boot-autoconfigure/
├── spring-acp-spring-boot-starter/
├── spring-acp-spring-ai/
├── spring-acp-test/
└── samples/smoke-app/
```
