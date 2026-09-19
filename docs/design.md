# spring-acp — a Spring Data-style abstraction over ACP coding agents

Status: **M1 and M2 built and verified**; M3–M4 proposed. Successor to the `java-wrapper` module of
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
        │ AcpAsyncClient     │ AgentRuntime SPI                  │
        │                    │ (launch · provision · option ids)  │
        ▼                    │                                   │
  com.agentclientprotocol:acp-core                               │
     StdioAcpClientTransport │ WebSocketAcpClientTransport        │
        │                    │                                   │
        ▼                    ├── runtime-goose  (stdio; ws M3)   │
  agent subprocess           ├── runtime-codex  (npx, stdio)     │
                             ├── runtime-opencode (binary, stdio)│
                             └── RegistryAgentRuntime (M4) ──────┘
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
| `spring-acp-runtime-codex` | `CodexRuntime` — `npx @agentclientprotocol/codex-acp`; `CODEX_HOME` + `config.toml` provisioning |
| `spring-acp-runtime-opencode` | `OpenCodeRuntime` — binary + `acp`; `opencode.json` via `OPENCODE_CONFIG` |
| `spring-acp-spring-boot-autoconfigure` | `AcpProperties`, `AcpAutoConfiguration`, `AcpWebFluxAutoConfiguration`, `AgentsYamlConfigDataLoader` |
| `spring-acp-spring-boot-starter` | Pom-only aggregator (`+ autoconfigure + core + runtime-goose`) |
| `spring-acp-spring-ai` | Optional `AcpChatModel implements ChatModel` |
| `spring-acp-test` | `AgentRuntimeContract` (the conformance TCK), `ScriptedAgent`, `AgentProbe`. JUnit and AssertJ are compile-scope here: it publishes an abstract test class other modules extend |

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
      id: tanzu_ai
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

`model`, `mode`, and `provider` are *requests*, not assignments. `ConfigResolver` reads what the
agent advertised on `session/new` and then tries four mechanisms, in order of how much the protocol
guarantees about each:

| Order | Mechanism | Used by |
| --- | --- | --- |
| 1 | `session/set_config_option` against an advertised option, matched by the adapter's config ids then by portable `category` | all three runtimes |
| 2 | `session/set_mode` / `session/set_model`, for an agent returning the older `modes`/`models` states and no config option | agents predating `configOptions` |
| 3 | `providers/set`, gated on the agent advertising `providers` | Codex only |
| 4 | the adapter's own out-of-band mapping, already applied at launch | Codex, OpenCode (see below) |

Measured against **goose 1.51.0**, **codex-acp 1.12.0** and **opencode 1.18.31**:

| | `configOptions` ids | `modes`/`models` | `providers/*` |
| --- | --- | --- | --- |
| goose | `provider` (**no** `category`), `mode`, `model`, `thinking_effort` | `modes` only | not implemented |
| codex-acp | `mode`, `collaboration_mode`, `model`, `reasoning_effort`, `fast-mode` | both | **advertised and working** |
| opencode | `model` (values are `provider/model`), `mode` | neither | not implemented |

Four findings changed the design:

**The resolver must read before it writes.** The obvious implementation sets optimistically and treats
an error as "unsupported". That is wrong, and not subtly: **goose 1.51 accepts a model id it has never
heard of**, stores it, and fails seconds later inside the turn, where the provider's 404 arrives as
agent *prose*. `on-unsupported` cannot be implemented on top of that, so resolution matches the
request against the advertised values and never sends a call it expects to be refused. OpenCode and
Codex both reject properly; one agent out of three is enough.

**Which means recovering a field the SDK drops.** `NewSessionResponse` in acp-core 0.17.0 models
`sessionId`, `modes` and `models` and ignores the rest, so `configOptions` never reaches a client.
`SessionConfigRecorder` decorates the transport and reads it out of `unmarshalFrom` — the one place
the raw payload and its target type meet — decoding options one at a time so an option type this SDK
version cannot model costs that option rather than all of them. Delete it when the SDK models the
field.

**Ids and categories are both needed, and neither alone.** Goose returns its `provider` option with a
**null** category, so category matching would only ever match something else; Codex exposes two
options in the mode family (`mode` for approval policy, `collaboration_mode` for plan-versus-build),
so an adapter offers an ordered list of candidate ids and the resolver takes whichever one actually
holds the requested value. That is what makes `spring.acp.mode: plan` mean the same thing on
OpenCode, where `plan` is a value of `mode`, and on Codex, where it is a value of
`collaboration_mode`.

**A portable model name has to survive three spellings.** OpenCode names models `openai/gpt-5.4-mini`,
Codex names the same family `gpt-5.6-terra`, goose offers 94 bare ids. `SelectMatcher` widens in four
steps — exact value, human-readable name, `<provider>/<value>`, then a *unique* suffix match — and
stops at the first unambiguous hit. The uniqueness condition is the point: two providers offering a
model of the same name is precisely when a client must not guess.

`on-unsupported` decides what happens when nothing resolves — `fail` throws
`UnsupportedAgentOptionException` (the Spring Data `UnsupportedOperationException` analogue), `warn`
logs once per option per runtime, `ignore` is silent. All three name what the agent *does* offer:

```
WARN  Runtime 'goose' cannot honor model='gpt-4o-from-2024' (the agent's 'model' option offers
      gpt-6-astra, gpt-5.6-luna, gpt-5.6-sol, gpt-5.6-terra, gpt-realtime-2.1, … (94 in all));
      continuing with the agent's own default.
```

Resolution results are exposed on `AgentSession.configuration()` as one `OptionResolution` per
option, carrying what was requested, what the agent took, and which mechanism carried it —
so `warn` does not hide the difference between the model an application asked for and the model it is
talking to. `AgentClient.openSession(name)` makes that readable without spending a turn.

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

Bound as `Map<String, Map<String, Object>>` and normalized by `RuntimeOptions`, which exists because
the shape the values arrive in is not the adapter's business: a nested block in `application.yaml` and
`spring.acp.runtimes.codex.config-toml.model_reasoning_effort=high` in a properties file or an
environment variable mean the same thing, and an adapter that read the raw map would have to handle
both. Lookups are relaxed the way Spring's own binding is (`configToml`, `config-toml`, `config_toml`
are one path) while the keys handed back keep their original spelling, because they end up verbatim in
a file the agent parses.

Validation: tier-3 keys under a runtime with no registered adapter are a startup failure, not a
silent no-op — that was the most common `.goose-config.yml` mistake class. Two adapters claiming the
same runtime id is also a startup failure rather than a tie broken by bean ordering; the error says to
reuse the bundled bean's name to replace it.

**Where tier 3 writes.** Never the workspace: that is the application's own code, and a `config.toml`
dropped into it is visible to the agent as content and to a reviewer as a change. `spring.acp.runtime-home`
names somewhere else, defaulting to a directory under the JVM's temp directory keyed by runtime id.

One trap found by walking into it. Codex keeps `auth.json` beside `config.toml`, so pointing
`CODEX_HOME` at a managed directory relocates the *credential store* too — setting one innocuous key
(`config-toml.model_reasoning_effort: low`) made a working application fail with "Authentication
required". `CodexRuntime` therefore inherits the ambient home untouched unless the application asked
for codex-specific config, never writes to it, and symlinks the existing `auth.json` across when the
home does move. A link rather than a copy: the secret stays in one place and stays current if the user
logs in again. On a platform there is no ambient home and the key arrives through the environment, so
nothing happens.

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

// what the negotiated tier actually achieved, without spending a turn on finding out
SessionConfiguration config = agentClient.openSession("review-123").configuration();
config.applied(PortableOption.MODEL);            // the model this session is really using
config.unsupported();                            // what was asked for and could not be honored
```

`openSession` exists because with `on-unsupported: warn` the requested model and the effective model
can differ, and an application that cares should not have to run a turn to discover which it got.
Negotiation happens once per session rather than once per turn — re-sending three unchanged options
before every prompt is wire traffic that cannot change anything — and again only when a turn carries
per-request overrides that genuinely differ from the session's current state.

`AgentEvent` is a sealed interface over records — the structured successor to
`AcpEventTranslator`'s three-shape NDJSON vocabulary, reusing its mapping logic:

```java
public sealed interface AgentEvent {
    record Text(String text)                                  implements AgentEvent {}
    record Thought(String text)                               implements AgentEvent {}
    record ToolCallStarted(String id, String title, ToolKind kind)     implements AgentEvent {}
    record ToolCallUpdated(String id, ToolCallStatus status, List<ToolCallContent> content)
                                                              implements AgentEvent {}
    record PlanUpdated(List<PlanEntry> entries)               implements AgentEvent {}
    record ConfigChanged(List<SessionConfigOption> options)   implements AgentEvent {}
    record ModeChanged(String modeId)                         implements AgentEvent {}
    record Completed(StopReason reason)                       implements AgentEvent {}
    record Failed(Throwable cause)                            implements AgentEvent {}
}
```

Two changes from the sketch, both made when the model met real agents. `ToolCallStarted` carries
`title` rather than `name`, because that is honestly what ACP gives — prose written for a human — and
the stable identifier an allowlist needs is vendor-specific and lives behind
`AgentRuntime.toolNameOf`. `Usage` is not in the model: goose returns token counts on the
`session/prompt` response rather than as an update, so an event would have had nowhere to come from;
it returns with Micrometer observations in M4.

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

    /** How to start it: command, args and environment. */
    AgentLaunchSpec launch(AgentSettings settings);

    /** Write agent-native config files before launch (config.toml, opencode.json, AGENTS.md…). */
    default void provision(AgentSettings settings) {}

    /** What this agent calls the options a client may set, most specific first. */
    default List<String> configIdsFor(PortableOption option) { … }

    /** Portable ACP categories to fall back on when no id matches. */
    default List<String> configCategoriesFor(PortableOption option) { … }

    /** Declares that launch or provision already carried an option outside the protocol. */
    default boolean appliedOutOfBand(PortableOption option, AgentSettings settings) { return false; }

    /** Extract a tool name for the permission policy (Goose hides it in _meta). */
    default Optional<String> toolNameOf(AcpSchema.ToolCallUpdate call) { return Optional.empty(); }
}
```

Note what is **not** here, and why the M1 sketch's `configureSession` was dropped: model selection is
a protocol operation the core performs identically for every agent, so an adapter only says what the
option is *called*. Every method that would tempt an adapter into doing the core's work has been left
out on purpose — an adapter that grows one is a sign the core is missing something.

`appliedOutOfBand` is the env-mapping fallback, and it is a *declaration* rather than an action,
because the work has to happen in `launch` — the last moment an environment variable can still be set.
Two adapters need it for opposite reasons: Codex because the provider's key is on the process
environment whether or not `providers/set` worked, OpenCode because a requested provider is not
unsupported there but *subsumed*, having taken effect as the prefix the model was matched with.
Without that, `on-unsupported: fail` would fire on a configuration working exactly as asked.

`AgentLaunchSpec` is sealed, with `Stdio(command, args, env)` today; `WebSocket` arrives with the
process pool in M3. `AgentClientFactory.connect(runtime, settings, transport)` is the seam between
starting an agent and talking to one — it is how the scripted agent is driven, and where a runtime
that attaches to something it did not spawn will come in.

Discovery is a nested `@Configuration` per adapter, each carrying `@ConditionalOnClass`, not a
`@ConditionalOnClass` bean method. Boot reads the condition on a configuration class from the
bytecode; on a bean method it must reflect over the annotation, which throws `TypeNotPresentException`
for an absent adapter — and then logs it and registers the bean anyway, so the application dies on
`NoClassDefFoundError` at refresh. Since the entire point of these being optional dependencies is that
an application ships only the agents it wants, the condition has to hold when the class is genuinely
missing. There is a test for exactly that, with a `FilteredClassLoader`.

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

Found by running the SDK against three live agents. All four are worked around; none is suppressed;
each argues for keeping the SDK behind our own types rather than exposing it in the public API.

**`NewSessionResponse` does not model `configOptions`.** *(worked around in M2.)* The record carries
`sessionId`, `modes` and `models` and ignores unknown properties, so the config options every live
agent returns are dropped before a client can read them — and without them the negotiated tier cannot
tell a model an agent has from one it does not. `ForkSessionResponse` and
`SetSessionConfigOptionResponse` both model the field, which suggests an oversight.
`SessionConfigRecorder` recovers it at `AcpTransport.unmarshalFrom`, the one point where the raw
payload and its target type meet, without parsing frames or correlating ids. Delete it when the SDK
models the field; nothing outside it needs to know.

**`session_info_update` is an unknown subtype.** *(fixed in M2.)* Goose emits it several times per
turn; acp-core's `SessionUpdate` hierarchy has no variant for it, so Jackson fails to resolve the type
id and the SDK logs an ERROR per occurrence. Functionally harmless, but an ERROR per turn for a benign
case is worse than useless: it teaches an operator to ignore the logger where a *real* notification
failure would appear.

Suppressing that logger would have hidden the real failures too. Instead the client registers a raw
`notificationHandler` for `session/update` rather than a `sessionUpdateConsumer`, and
`SessionUpdateDecoder` decodes leniently: an unknown discriminator is skipped with a debug line naming
what was skipped, and anything else still surfaces. `AcpClient.build()` installs its own handler only
when a `sessionUpdateConsumer` is registered, so the custom one survives.

**`ProviderInfo` models `id` where Codex sends `providerId`.** Codex is the only runtime that
implements `providers/list`, and every entry it returns parses with a null id. So the providers path
sets directly rather than listing first: the list step could only confirm that an id exists, and it
cannot. Gated on the advertised capability, with an error meaning unsupported.

**Stderr from a process that dies immediately is sometimes lost.** The SDK subscribes to the child's
error stream slightly after starting it, so a complaint written microseconds before the process exits
is dropped — measured at roughly one run in five. No real agent is in that race, since an agent that
rejects its configuration has parsed a file first, and the realistic case was reliable across repeated
runs. Not worked around; noted, and the conformance suite deliberately does not reproduce it.

## Security posture

This library is an ACP **client** running server-side, which is materially different from an IDE.
Defaults must be restrictive and match the wrapper's 4.1.0 hardening.

### Correction, measured in M2: `permissions.policy: deny` does not stop an agent writing files

The M1 posture below implied it did. It does not, and the difference is worth stating plainly.
Declaring `fs.writeTextFile: false` in `clientCapabilities` only declines to lend the agent *the
client's* filesystem methods. An agent in its default mode writes with its own process access and
never asks:

| agent | default mode | permission requests | file written |
| --- | --- | --- | --- |
| goose 1.51 | `auto` | **0** | yes |
| opencode 1.18 | `build` | **0** | yes |
| codex-acp 1.12 | `agent` | **0** | yes |
| goose 1.51 | `approve` | 2, both refused | **no** |
| codex-acp 1.12 | `collaboration_mode: plan` | 1, refused | **no** |
| opencode 1.18 | `plan` | asked and refused | **no** |

So the two halves are: **`mode` decides whether the question is asked, and `permissions.policy`
decides the answer.** Either alone is not a review gate. This is exactly the negotiated tier M2 built,
and `spring.acp.mode` reaches all three — though the value is the agent's own (`approve` for goose,
`plan` for the other two, and for Codex under a different option id).

One trap inside the trap: Codex's `mode: read-only` is described as "always ask to edit external
files" and lets it write inside the session's own `cwd` without asking at all. A client that trusted
the name would believe it had a gate it did not have.

The conformance suite asserts both halves, and the remaining exposure — an agent writing outside the
workspace with its own tools — is what M3's `WorkspaceFileSystem` jail and the process supervisor
address. Until then, an agent's process has whatever access the JVM's user has.

### Baseline

- `permissions.policy: deny`; `filesystem.enabled: false`; `terminal.enabled: false` — declare all
  three in `clientCapabilities` so agents don't attempt them (and see the correction above for what
  that does and does not buy).
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

**M1 — core + Goose over stdio. Done.** All seven steps built and verified against a live
`goose acp` subprocess. 49 tests green: 5 drive the real binary and skip when it is absent, 44 run
anywhere.

| Step | Delivered | Where |
| --- | --- | --- |
| 1 | `AgentEvent` sealed model, `AgentEventMapper` | `core/event` |
| 2 | `AgentSession`, `SessionRegistry` | `core/session` |
| 3 | `SessionUpdateRouter`, `AgentTurn` | `core/turn` |
| 4 | `AgentClient`, `DefaultAgentClient`, `AgentClientFactory` | `core/client` |
| 5 | `PermissionPolicy`, `ConfigResolver`, `Validation` | `core/permission`, `core/config` |
| 6 | `GooseRuntime`, `AcpProperties`, `AcpAutoConfiguration`, `SelectedRuntime`, starter | `runtime-goose`, `spring-boot-autoconfigure` |
| 7 | Smoke app driven entirely by `application.yaml` | `samples/smoke-app` |

Live coverage: connect and negotiate, blocking call, streamed turn, named sessions keeping context
across turns, and stream cancellation that reaches the agent.

Three things the build taught us that the plan had not anticipated:

- **Runtime selection had to be separated from launching.** Validation originally lived inside the
  bean that starts the process, so proving "a bad `spring.acp.runtime` fails fast" meant spawning an
  agent. `SelectedRuntime` now resolves and validates at context refresh and names the registered
  alternatives in the error. A tier-3 block for an unregistered runtime fails there too, rather than
  being silently ignored — the single most expensive mistake class in the format this replaces.
- **`AgentTurn` needed two guards, not one.** A flag to make the first channel to finish the winner
  and the second a no-op, and a second flag distinguishing consumer cancellation from a turn that
  ended on its own — without it, every completed turn would also send a pointless `session/cancel`.
- **A pinned test model was necessary.** The developer's own `~/.config/goose/config.yaml` may name
  a model their key cannot reach, and the resulting 404 arrives as agent *text*, so it reads as a
  library bug in the assertion output. The live tests pin the model via a system property, which
  exercises the negotiated tier as a side effect and proves `session/set_config_option` took effect.

Deferred from M1 as planned: `AgentProcessSupervisor` restart/health logic and the WebSocket
transport.

**M2 — the abstraction earns its keep. Done.** One unchanged application runs against goose 1.51.0,
codex-acp 1.12.0 and opencode 1.18.31 with `spring.acp.runtime` as the only difference. 170 tests
green: 137 run anywhere, 33 drive real agents and skip when one is unusable.

| Step | Delivered | Where |
| --- | --- | --- |
| 1 | `AgentRuntime` SPI extended: candidate config ids, portable categories, `appliedOutOfBand` | `core/runtime` |
| 2 | `SessionConfigRecorder` recovers the `configOptions` the SDK drops | `core/client` |
| 3 | `SessionUpdateDecoder` + raw `session/update` handler; the ERROR-per-turn is gone | `core/event` |
| 4 | `ConfigResolver` rewritten: four mechanisms, read-before-write, `SelectMatcher` | `core/config` |
| 5 | `RuntimeOptions`, `ProviderSpec`, `ProviderEnvironment`, `SessionConfiguration` | `core/config` |
| 6 | `CodexRuntime` (npx, `CODEX_HOME`, TOML) and `OpenCodeRuntime` (`opencode.json`) | `runtime-codex`, `runtime-opencode` |
| 7 | `AgentClient.openSession`, per-session rather than per-turn negotiation | `core/client` |
| 8 | `ScriptedAgent` and `AgentRuntimeContract` — the TCK | `spring-acp-test` |
| 9 | Multi-runtime smoke app, three agents on one classpath | `samples/smoke-app` |

The completion test, run three times with nothing changed but one property:

```
Connected to goose 1.51.0 over ACP v1                     → READY, Completed[reason=END_TURN]
Connected to @agentclientprotocol/codex-acp 1.12.0 …      → READY, Completed[reason=END_TURN]
Connected to OpenCode 1.18.31 over ACP v1                 → READY, Completed[reason=END_TURN]
```

And the negotiated tier doing real work, same application, same properties:

```
opencode  model gpt-5.4-mini → openai/gpt-5.4-mini (CONFIG_OPTION)   mode plan → plan (CONFIG_OPTION)
codex     mode  plan         → plan (CONFIG_OPTION, via collaboration_mode)
goose     model gpt-4o-from-2024 → UNSUPPORTED, with the 94 it does offer named in the warning
```

Six things the build taught us that the plan had not anticipated:

- **"Set it and see" cannot implement `on-unsupported`.** goose accepts an unknown model id and fails
  inside the turn as agent prose. The resolver has to read the advertised options first — which meant
  recovering a field the SDK drops. This inverted the M1 design note, which had the resolver setting
  optimistically on purpose.
- **Advertised is not reachable.** The first version of the TCK picked any advertised model that
  differed from the current one and got OpenCode's `chatgpt-image-latest`, then "you do not have access
  to it" seventy seconds later — the same failure mode as an unknown id, from the same place. An agent
  advertises what its vendor sells, not what the machine's key can reach. So a suite that runs turns
  uses the model the agent is already configured with, and proving a model can be *changed* is a
  separate test that needs no turn.
- **`permissions.policy: deny` is half a review gate.** See the security correction above. Found by a
  contract assertion that failed on OpenCode and then failed on goose too.
- **Two config ids, one portable value.** `plan` is a value of `mode` on OpenCode and of
  `collaboration_mode` on Codex. An adapter offering an ordered candidate list, with the resolver
  taking whichever holds the value, is what makes one property mean one thing.
- **`@ConditionalOnClass` does not work on a `@Bean` method for an absent class.** It throws, logs,
  and registers the bean anyway, so the application dies at refresh on the very dependency that was
  meant to be optional. Each adapter now registers from a nested `@Configuration`.
- **Ephemeral turns were silently costing five seconds each.** A turn's teardown runs on the thread
  that delivers the agent's replies, and it blocked there waiting for a `session/close`
  acknowledgement that only that thread could deliver. The timeout resolved the deadlock, so nothing
  failed and nothing said so; the session was left open on the agent anyway. Found by reading test
  timings, not by a failure.

Deferred from M2 as planned: `AgentProcessSupervisor` restart/health logic, the WebSocket transport,
and `agents.yaml`.

**M3 — parity and ergonomics.** `agents.yaml` `ConfigDataLoader`, `AgentExecutor` migration facade,
process pooling, session list/load/resume/delete, `WorkspaceFileSystem` + terminal handlers,
optional WebFlux controller, Goose WebSocket transport (`goose serve`) for goose-buildpack parity.

**M4 — reach.** `RegistryAgentRuntime` with a cached registry snapshot and SHA-256-verified
downloads; Spring AI `AcpChatModel`; Micrometer observations per turn/tool call; ACP v2 behind
negotiation and a feature flag, off by default.

---

## Verification

170 tests. `mvn test` runs all of them; the live ones skip themselves when an agent is not usable.

**Fast tests (137)** — turn semantics, session registry concurrency and permit accounting, event
mapping, permission policy, URL/header/env/secret validation, tier-3 normalization, model matching,
every branch of the negotiated tier, adapter launch and provisioning for all three runtimes, and Boot
binding and adapter registration via `ApplicationContextRunner`. No subprocess.

**Wire tests (16)**, on `acp-test`'s in-memory transport with `ScriptedAgent` — a fake agent that
speaks raw JSON-RPC rather than the SDK's records, which is the point of it. The core's fast tests mock
`AcpAsyncClient`, and both SDK gaps this library works around are *format* gaps, invisible to a mock:
`configOptions` dropped from a typed response, and a `sessionUpdate` discriminator with no record. The
scripted agent can also be told to misbehave the way real agents do —
`acceptsUnknownValues(true)` reproduces goose 1.51 storing a model it has never heard of — so the
core's defenses are testable without waiting for a vendor to ship the bug again.

**Runtime conformance (33 = 11 × 3)** — `AgentRuntimeContract` in `spring-acp-test`, extended once per
adapter. **This suite, not the `AgentRuntime` interface, is the definition of the abstraction:** an
interface only constrains signatures, and three adapters can satisfy one and still behave differently
enough that an application cannot move between them. It asserts only what ACP genuinely standardizes —
connect and negotiate, exactly-one-terminal-event, blocking call, named sessions keeping context,
cancellation reaching the agent, a tool-using turn still terminating once, deny-by-default blocking a
write in the agent's reviewing mode, the requested model applied by an advertised mechanism, a
different model applied, and an unsupported model honoring both `fail` and `warn`. It never asserts a
model name, a tool name, or how an agent phrases an answer; a test a runtime could only pass by
behaving like Goose would make it a Goose conformance suite.

Two deliberate concessions in it, both documented at the assertion:

- `reviewingMode()` is the one piece of vendor knowledge the suite cannot do without, because there is
  nothing in the protocol to derive "the mode in which this agent asks first" from.
- `AgentProbe` gates each suite on a real session rather than `agent --version`, because all three
  agents are installed long before they are usable, and an agent with no credentials answers the
  handshake and then fails inside a turn. It also supplies the model to ask for, since hardcoding one
  per agent would put three model catalogs into the test source.

**Multi-runtime smoke** — `samples/smoke-app` with all three adapters on one classpath, run three
times. M2's completion test; output above.

Still planned:

1. **CI that installs all three** from the registry manifest, so the live suites run somewhere other
   than a developer machine.
2. **Security tests** for the features they guard, in M3: workspace jail escape via symlink, terminal
   `cwd` confinement, and secret redaction in process logs. URL scheme, embedded-credential,
   header-injection, env-name and secret-length rejection are already covered in `ValidationTests`.

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
