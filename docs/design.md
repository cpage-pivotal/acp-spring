# acp-spring — a Spring Data-style abstraction over ACP coding agents

Status: **M1, M2, M3 and M4 built and verified**. Successor to the `java-wrapper` module of
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

1. **`java-wrapper` is already an ACP client.** `java-wrapper/src/main/java/org/thought/goose/cf/acp/`
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
`opencode.json`), Goose `extensions:`. Skills turned out to be portable after all, though not through
ACP: goose, opencode and codex all read `<cwd>/.agents/skills/`, so `spring.acp.skills[]` is
installed there by the core (`SkillInstaller`) with no adapter involvement — the one thing this
library writes into the workspace rather than `runtimeHome`. A skill with no `url` is bundled: copied
out of the application's classpath, so in production out of its own jar. With a `url` it is fetched
by `git`, and a `token` for a private repository reaches that one git process only as an
environment-borne `http.<url>.extraHeader` — never a URL, an argument, a file or a log line.

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
        │  AgentClient (fluent; turns, events) · AgentSessions · AgentExecutor
        ▼
  AgentClientPool ── N connections, sticky sessions, idle sweep, replacement
        ▼
  acp-spring-core ──────────────────────────────────────────────┐
     AgentClient impl · SessionRegistry · ConfigResolver (3-tier) │
     PermissionPolicy · WorkspaceJail · WorkspaceFileSystem       │
     WorkspaceTerminals · AgentProcessSupervisor · AgentEvent     │
     AcpProtocol (version negotiation) · AgentObservations        │
        │                    ▲                                   │
        │ AcpAsyncClient     │ AgentRuntime SPI                  │
        │                    │ (launch · provision · option ids)  │
        ▼                    │ AgentRuntimeProvider SPI           │
  com.agentclientprotocol:acp-core                               │
     StdioAcpClientTransport │ WebSocketAgentTransport (ours)     │
        │                    │                                   │
        ▼                    ├── runtime-goose  (stdio + ws)     │
  agent subprocess           ├── runtime-codex  (npx, stdio)     │
  or supervised server       ├── runtime-opencode (binary, stdio)│
                             └── runtime-registry ───────────────┘
                                 AgentRegistry · AgentInstaller
                                 (any of 41 published agents)

  Also on top of AgentClient: acp-spring-ai (AcpChatModel),
  and Micrometer observations per turn and per tool call.
```

Decisions confirmed with the user: build on `acp-core`; native `AgentClient` API with an optional
Spring AI `ChatModel` adapter; config bindable from both `application.yaml` and a standalone
`agents.yaml`; first milestone proves Goose, Codex, and OpenCode.

---

## Module layout

Multi-module Maven, Java 21, Spring Boot 4 (matching `java-wrapper`). Group `org.springaicommunity.acp` — do
**not** squat `org.springframework`. Packages organized by feature, not layer.

| Module | Contents |
| --- | --- |
| `acp-spring-core` | No Spring types on the classpath-required path. `org.springaicommunity.acp.client`, `.session`, `.turn`, `.process`, `.transport`, `.permission`, `.mcp`, `.workspace`, `.config`, `.runtime`, `.event`, `.executor`, `.protocol`, `.observation` |
| `acp-spring-runtime-goose` | `GooseRuntime` — stdio `goose acp` **and** `goose serve` over WebSocket; `--with-builtin` extensions |
| `acp-spring-runtime-codex` | `CodexRuntime` — `npx @agentclientprotocol/codex-acp`; `CODEX_HOME` + `config.toml` provisioning |
| `acp-spring-runtime-opencode` | `OpenCodeRuntime` — binary + `acp`; `opencode.json` via `OPENCODE_CONFIG` |
| `acp-spring-runtime-registry` | `AgentRegistry`, `AgentInstaller`, `Archives`, `RegistryAgentRuntime` — any agent the ACP registry publishes, with no adapter |
| `acp-spring-boot-autoconfigure` | `AcpProperties`, `AcpAutoConfiguration`, `AcpWebFluxAutoConfiguration` + `AcpController`, `AgentsConfigDataLoader` |
| `acp-spring-boot-starter` | Pom-only aggregator (`+ autoconfigure + core + runtime-goose`) |
| `acp-spring-ai` | `AcpChatModel implements ChatModel`, `AcpChatOptions` — Spring AI 2.0.x |
| `acp-spring-mcp-oauth` | `OAuth2McpCredentialsProvider`, `McpOAuthAutoConfiguration`, `JdbcMcpClientRegistrationRepository` — per-user MCP-spec OAuth via Spring Security and mcp-security; carries its own auto-configuration so Spring Security never reaches an application that did not add it |
| `acp-spring-test` | `AgentRuntimeContract` (the conformance TCK), `ScriptedAgent`, `AgentProbe`. JUnit and AssertJ are compile-scope here: it publishes an abstract test class other modules extend |

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
      id: acme_ai
      api-type: openai
      base-url: ${ACME_AI_ENDPOINT}
      api-key: ${ACME_AI_API_KEY}

    mcp-servers:                # → session/new mcpServers[] verbatim
      - name: internal-tools
        type: http
        url: https://tools.example.com/mcp
        headers: { Authorization: "Bearer ${MCP_TOKEN}" }

    permissions:
      policy: deny              # deny | allowlist | auto-approve
      allowed-tools: [ developer__text_editor ]

    filesystem:                 # → clientCapabilities.fs; see the security section
      enabled: false            # answer fs/read_text_file
      write: false              # also answer fs/write_text_file; implies enabled
    terminal:
      enabled: false            # answer terminal/*; arbitrary execution as this JVM's user
      allowed-commands: []      # empty allows any; the capability itself is the gate
      output-limit: 1MB
      command-timeout: 5m
      max-concurrent: 8

    pool:
      max-processes: 1
      max-sessions-per-process: 32
      session-ttl: 60m
      max-restarts: 5

    controller:                 # the optional HTTP endpoint, off unless asked for
      enabled: false
      path: /api/acp
      allow-unauthenticated: false
      allow-request-overrides: false
      max-prompt-chars: 32000
      max-timeout: 10m

    protocol:                   # see "ACP v2 is gated, and then refused"
      max-version: 1            # raising this offers v2 and finds out what an agent claims
      strict: false             # fail, rather than clamp, when an agent answers impossibly

    observations:
      enabled: true             # when Micrometer is on the classpath

    registry:                   # only consulted for a runtime no adapter claims
      enabled: true
      url: https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json
      cache: ${user.home}/.acp-spring/agents
      refresh: 24h
      offline: false            # bundled snapshot and whatever is already installed
      require-checksum: true    # refuse an agent the registry publishes no sha256 for
      download-timeout: 10m
```

The same properties can arrive from a standalone `agents.yaml`, pulled in with
`spring.config.import: optional:agents.yaml`, where the `spring.acp` prefix is implied:

```yaml
# agents.yaml — an operator's or a buildpack's file, next to the jar
runtime: goose
workspace: /home/vcap/app/workspace
permissions:
  policy: deny
runtimes:
  goose:
    builtins: developer
```

`AgentsConfigDataLoader` re-keys the document and hands it back to Boot's own machinery, so
relaxed binding, profiles, `${}` resolution and origin tracking all still apply — a validation
failure points at the line in `agents.yaml`. A key that already starts with `spring.` is left
alone, per key rather than per file, so one file can carry the agent configuration it exists for
and the occasional unrelated property. The explicit form `optional:acp:<path>` names a file called
something else.

Carry over the wrapper's `GooseOptions` validation wholesale — `base-url` must be HTTPS or
loopback/`.apps.internal` with no userinfo/query/fragment, env keys `^[A-Za-z_][A-Za-z0-9_]*$`,
API key ≤16 KiB with no CR/LF, session names `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`. It is already
written and already right.

### MCP servers fail silently, and the ACP path carries no word of it

`mcp-servers` is the most portable thing in the configuration model — ACP passes it through
`session/new` verbatim — and also the one most likely to be accepted and then do nothing. It belongs
next to the `permissions.policy: deny` correction below: same species, a setting the system takes
without complaint and quietly declines to honour.

Measured against goose 1.51.0 with one unreachable HTTP server in `mcpServers[]`:

| Signal a client could watch | What arrives |
| --- | --- |
| `session/new` result | normal success — `sessionId`, `modes`, `configOptions` |
| JSON-RPC `error` | none |
| `session/update` of any kind | none |
| agent stderr (`AgentDiagnostics`) | **zero lines** |

The same server under goose's own CLI prints `⚠ Failed to start extension '…' (failed to initialize
MCP client: …), continuing without it`. The information exists inside the agent and is dropped on
the ACP path. So the application starts clean, the session opens, the model answers — and has no
tools. The only symptom is prose: the model saying it lacks tools its instructions say it has. ACP
offers nothing to ask afterwards either; there is no `tools/list` equivalent, and tools surface only
as `tool_call` events mid-turn.

Three unrelated causes were observed to produce that one indistinguishable outcome: a gateway
rejecting goose's `server/discover` probe (`MCP-Protocol-Version: 2026-07-28`) with a 400 whose `id`
is `"server-error"` rather than the request's, so goose cannot correlate the error and never falls
back to plain `initialize`; a proxy copying HTTP/2 pseudo-headers such as `:status` from
`java.net.http`'s `HttpResponse.headers()` into an HTTP/1.1 response; and a `202` for a JSON-RPC
notification answered with a chunked body instead of no body. A fourth variant is louder but no
clearer: an MCP endpoint that needs auth and is given none can block `session/new` until the turn
timeout, which surfaces as a Reactor timeout with no mention of MCP.

Two things are done about it, and one thing is deliberately not.

First, `DefaultAgentClient` names the requested servers at INFO when it opens or re-attaches a
session — host and path only, never headers or env, which carry the tokens. That detects nothing; it
puts *what was asked for* in the log beside a model saying it has no such tools.

Second, and this is what actually closes the gap: **the agent's own log is watched**, because the
measurement below shows the warning exists there and nowhere else. `AgentRuntime` gains two
declarations — `logDirectory(settings)` and `noticeOf(line)` — and `AgentLogWatcher` in
`core/process` does the reading. The division of labour is the one the SPI already had:
`noticeOf` is the file-based twin of `toolNameOf`, a pure function over the agent's own output, and
nothing in an adapter opens a socket or speaks a protocol on the agent's behalf. A recognised line
becomes an `AgentNotice` — severity, the server's name as the application spelled it, and the
agent's words, redacted on the way in because a log quotes the URLs it was given. Notices are logged
at WARN and readable from `AgentClient.notices()`. An application that would rather not start than
serve a half-equipped agent sets `spring.acp.mcp.on-server-failure: fail`, and `openSession` then
refuses a session whose server the agent said it could not load, closing it on the way out. It waits
`mcp.detect-timeout` (default 2s) first, because the warning and the `session/new` reply land within
the same tenth of a second in no guaranteed order — reading once, synchronously, would be a coin
toss. Silence always opens the session: nothing here infers a failure it was not told about.

Two limits, both deliberate. The watcher runs only for an agent **this** client started, since an
attached process's environment is unknown and its log would be a guess. And only the Goose adapter
implements either method — everywhere else the feature is inert, which the property documentation
says plainly, because a setting that silently did nothing for 41 of 44 runtimes would be this bug
wearing a different hat.

What is still **not** done is probing. A reachability check on startup would be this client talking
to the server — a plain `initialize`, no discovery probe, no version header — so against the gateway
above it would have reported both servers healthy while goose had zero tools: a green check on a
broken system, which is worse than no check. A probe that replayed the *agent's* handshake would
have caught it, but it is a second implementation of each agent's MCP client that goes stale when
that agent changes, and reading the agent's own verdict is both cheaper and closer to the truth. The
clean fix remains upstream: goose emitting over ACP, or at minimum on its own stderr, the
extension-failure warning it already writes to a file.

#### Where the silence holds, measured

`tools/mcp-silence-check.py` runs a real goose against a deliberately unreachable MCP server and
reports what each channel carried. It covers both transports and both ways of declaring a server,
because those are exactly the axes on which this library and the buildpack wrapper it succeeds
differ: the wrapper only ever runs `goose serve` and always sends an **empty** `mcpServers` array,
leaving the servers to `config.yaml`. Against goose 1.51.0, all four cells behave identically:

| transport | servers declared in | `session/new` | `session/update` | stdout/stderr | goose's log file |
| --- | --- | --- | --- | --- | --- |
| stdio (`goose acp`) | `session/new` | ok, <0.1 s | none about MCP | nothing | **the warning** |
| stdio (`goose acp`) | `config.yaml` | ok, <0.1 s | none about MCP | nothing | **the warning** |
| serve (WebSocket) | `session/new` | ok, <0.1 s | none about MCP | nothing | **the warning** |
| serve (WebSocket) | `config.yaml` | ok, <0.1 s | none about MCP | nothing | **the warning** |

So the wrapper is **not** better off, and for a reason worth knowing: goose does not write the
warning to its console at all under either transport. It writes it, as structured JSON, to
`$XDG_STATE_HOME/goose/logs/cli/<date>/<timestamp>.log`:

```
WARN goose::agents::agent | Failed to load extension silence-probe-mcp:
     failed to initialize MCP client: …
ERROR rmcp::transport::worker | worker quit with fatal: Transport channel closed,
     when Client(… ConnectError("tcp connect error", 127.0.0.1:9, ConnectionRefused) …)
```

Draining the child's stdout and stderr — which both this library's `AgentProcessSupervisor` and the
wrapper's `GooseServerSupervisor` do — therefore catches none of it. The same holds for a broken
stdio server (`process quit before initialization`), so it is the reporting path that is silent,
not one transport's error handling.

That measurement is what the watcher above is built on, and it also settles one design question
worth recording. Pointing `XDG_STATE_HOME` at `runtimeHome` would make the log's path certain, and
it would also move goose's *session* storage, which lives under the same root — an ephemeral
per-client directory would quietly break `session/load` and `session/resume` across restarts.
Trading one silent failure for another is not a fix, so `GooseRuntime.logDirectory` **reads** the
location goose is already using (`$XDG_STATE_HOME`, else `~/.local/state`) and a deployment that
puts it elsewhere says so with the tier-3 `log-dir`.

What this costs is a dependency on a log format that carries no compatibility promise: one regex
against `Failed to load extension <name>: <reason>`. `GooseNoticeTests` pins it against lines
captured verbatim from a real goose, and the live contract test
(`anUnreachableMcpServerIsReportedOrNotClaimed`) points a real agent at a really unreachable server
and asserts a notice arrives — an adapter that reports no log directory skips it instead, since
declining to report is an honest answer and claiming to report without being tested is not. That
test only runs live, so the day goose rewords its diagnostics is a release day rather than a commit.
Between releases a reworded goose goes quiet again. That is the honest shape of this, and it is why
the upstream fix is still the one that ends it.

### MCP credentials: a loopback proxy, one route per session

A header in `mcpServers` is frozen for the life of the session it was sent in, and it is sent to
the agent. Both are wrong for the credential most MCP servers actually want. The MCP authorization
spec's OAuth access tokens expire in hours, so a long conversation outlives the token it was opened
with; and in a multi-user application the token is a *person's*, and the agent process is shared
infrastructure that has no business holding it. `acp-meridian` found this the hard way and carried
~1,100 lines of per-application code to get around it, most of which belongs here.

So an application may supply an `McpCredentialsProvider` (`McpSettings.credentials`, or a bean).
For each session and each HTTP server it returns either nothing — the server goes to the agent as
configured, which is also what happens to every server when there is no provider — or an
`McpCredentials`, in which case:

- **The agent is told a loopback URL, not the server's.** `McpAccess` (one per connection) starts
  a JDK `HttpServer` on `127.0.0.1` the first time any server needs it, and publishes the session's
  servers at `http://127.0.0.1:<port>/<token>/<server>`. The agent sees no headers at all; the
  server's configured headers are added upstream by the proxy, alongside the credentials.
- **The token in the path is the access control.** It is 128 random bits per session. A loopback
  port is reachable by every process on the machine, so routes keyed by server name alone would let
  any local process spend any user's credentials. An unknown route is a 404 that says nothing else.
- **Credentials are asked for on every request.** `McpCredentials.headers()` runs per forwarded
  request, so a refresh lands between two tool calls instead of failing one. It is called
  concurrently; an implementation that refreshes must let only one refresh run per user and server,
  because an authorization server that rotates refresh tokens — the Tanzu MCP gateway's UAA does,
  measured — honours each once, and a lost race costs the user their sign-in.
- **A route lives exactly as long as its session.** `SessionRegistry` releases a session's grant
  whenever it forgets the session — close, idle eviction, the end of an ephemeral turn, a failed
  `on-server-failure: fail` check, client close — so a proxy URL the agent kept from a closed session
  answers 404. Closing the client stops the proxy.
- **Refusing is done before the agent hears of it.** Every server's credentials are asked for before
  any route is published or `session/new` is sent, on the caller's thread. A provider that throws —
  a user who has not signed in to one of the servers — refuses the session and leaves nothing behind.
  That is the moment a web application can still redirect someone to sign in; mid-turn, nobody is
  there to do it.

The forwarding itself carries over the four details that each made goose drop a server without a
word (see "Where the silence holds" above): no HTTP/2 pseudo-headers into the HTTP/1.1 response, no
body at all for a `202`/`204`, bodies flushed per chunk so SSE is not buffered, and the agent's own
headers — `User-Agent` included — passed through. The agent's `Authorization` is dropped: the proxy
is the only authority on credentials.

**Agent workarounds are the adapter's, applied by the proxy.** `AgentRuntime.mcpRequestFilters`
returns `McpRequestFilter`s that run, in order, on every request after its route is known and before
credentials are added; each forwards the request, possibly changed, or answers it locally so nothing
reaches the upstream. A filter cannot forge credentials — the proxy drops `Authorization` from
whatever a filter forwards and sets its own. While an adapter returns any, every HTTP server goes
through the proxy, credentialed or not, because a filter cannot touch traffic the agent sends
straight to the server. The one that exists: goose 1.51 opens an MCP connection with
`server/discover` at `MCP-Protocol-Version: 2026-07-28`, and the Tanzu MCP gateway answers with a
400 whose `id` is `"server-error"` — goose cannot correlate it, never falls back to `initialize`, and
the server silently never loads. `DiscoverProbeFilter` answers the probe with a well-formed
`-32601` carrying the request's id, which is what an older server should have said, and drops the
probe version header from everything else; goose then falls back and connects. It is opt-in
(`spring.acp.runtimes.goose.mcp.answer-discover: true`) because a server that does implement
discovery would lose its newer protocol, and it should be deleted the day either side is fixed.

**Principals.** A `SessionPrincipal` is who a session is for — just a name, never logged. It reaches
the provider, and it owns the session: a named session open for one principal is refused to another
with `SessionOwnershipException`. Session names are application-chosen, and one derived from
something two users can both produce (a ticket id) would otherwise hand the second user the first
user's conversation and, through its MCP routes, the first user's credentials. The principal is
given explicitly (`PromptSpec.principal`, `openSession(name, principal)`, `load`/`resume` with a
principal) or resolved by `McpSettings.principals` — a `SessionPrincipalResolver`, asked on the
caller's thread when a turn is described (`call()`/`stream()`), never on the thread that later
subscribes. That is what makes a resolver that reads a thread-bound security context correct in a
servlet application, and why a reactive application passes the principal explicitly. The pool reads
it eagerly for the same reason, since it only chooses a connection on subscription.

**Per-user OAuth: `acp-spring-mcp-oauth`.** A server configured with `auth: oauth` gets its tokens
from `OAuth2McpCredentialsProvider`, and all of the OAuth is somebody else's code, which is the point:

- **mcp-security** (spring-ai-community `mcp-client-security`, pinned at 0.1.14 with Spring AI and
  the MCP SDK excluded — only the parts that do not assume a Java MCP client are used) discovers the
  authorization server from the server's 401, registers the application dynamically, and — through
  `McpClientOAuth2Configurer`, which the application adds to its own `SecurityFilterChain` — puts
  `resource=` on the authorization request and the code exchange.
- **Spring Security** does everything else: the redirect, PKCE (the registration is a public
  client, so there is no secret to keep), the callback, and storing tokens per (registration,
  principal name) in an `OAuth2AuthorizedClientService`. The proxy reads from that same service
  through an `AuthorizedClientServiceOAuth2AuthorizedClientManager` — the service-backed manager,
  because the proxy asks from its own threads with no servlet request — which refreshes with
  `resource=` too.

Each protected server is its own OAuth client, with the server's name as its registration id: the
Tanzu gateway gives every server its own issuer, and binds `aud` to the server's URL, so a token for
one is useless at another. The provider's rules:

- A session on nobody's behalf is refused. A user with no token gets Spring Security's own
  `ClientAuthorizationRequiredException`, which its redirect filter turns into the trip to sign in —
  on a request thread. `requireAuthorized(principal)` exists so a controller can take that trip
  before it opens a session or starts streaming, since an exception from inside a started stream can
  only be an error.
- The first user to meet a server registers the application with it, serialized per server; the
  redirect URI's base comes from `spring.acp.mcp.oauth.base-url` or else the request that triggered
  the registration.
- Refreshes are serialized per (server, user) — the gateway rotates refresh tokens and honours each
  once. A refused refresh fails that one request, and Spring Security's failure handler removes the
  stored token, so the user's next session sends them to sign in again.
- The proxy hides the upstream's `WWW-Authenticate` on credentialed routes: an agent that saw a
  challenge could set off on a sign-in of its own, on a server with nobody at it.

`SecurityContextPrincipalResolver` makes the signed-in user the principal of every prompt described
on a request thread. Anonymous users are nobody: Spring Security keeps their tokens in the HTTP
session, not in the service the proxy reads.

**Terminal applications: `mode: local`.** Same provider, different `McpSignIn`. A web application
cannot sign anyone in from inside a call, so its sign-in throws for Spring Security's redirect
filter; a terminal application has the user at the keyboard, so `LoopbackSignIn` runs RFC 8252's
native-app flow on the spot — Spring Security's authorization request with PKCE, a listener on
`127.0.0.1`, Spring Security's token client to redeem the code, `resource=` on both — through an
`AuthorizationPrompt` that prints the URL and opens a browser. The listener's port is part of the
registered redirect URI, so it is chosen at registration and reused; when something else has taken
it, the registration is forgotten and made again once. The principal is the operating-system user
(`LocalPrincipalResolver`), and state defaults to one file, `FileMcpOAuthStore` at
`~/.config/<spring.application.name>/acp-mcp-oauth.json`, mode 600, written atomically. This is
what acp-meridian now runs on: its 1,118-line `mcp` package became a dependency and twenty lines of
YAML, verified live — first run signs in to both servers through the browser, later runs refresh
from the file without one, and the agent lists and calls the tools of both.

That live run found one more silent failure, in the proxy: `HttpServer` creates its dispatcher
thread in `start()`, and a thread is a daemon only if the thread that made it was. Started from an
application's main thread, the proxy kept a terminal application alive after `main` returned
without closing its context. It is now started from a daemon thread.

State lives in memory by default for web (restart means every user signs in again and the
application registers again) or, with `spring.acp.mcp.oauth.store: jdbc`, in the application's database:
`acp_mcp_client_registration` (this module's schema) and Spring Security's
`oauth2_authorized_client`. Registrations are first-writer-wins across instances, because a user who
signed in through one instance must be refreshable from another; the loser's client id is simply
never used. Refreshes are serialized across instances too: with the JDBC store, a `JdbcRefreshLock` takes
`SELECT … FOR UPDATE` on the user's `oauth2_authorized_client` row, in a transaction, before
refreshing, and whoever waited re-reads a token that is fresh by then and sends nothing. A fresh token
is handed out without the lock, so only a request that finds its token expired pays for it. A refused
refresh is carried out of the transaction as a value, not an exception, because Spring Security
removes the stored token on refusal and an exception would roll that back — leaving a dead token to be
refused on every request after. Tested with two instances sharing one database: with only in-JVM
locks both refresh and one is refused (sometimes signing the user out entirely); with the row lock
they refresh once between them. Known limit: tokens are stored as Spring Security stores them —
unencrypted.

All of it was verified against the Tanzu MCP gateway before being built (discovery, DCR, PKCE
sign-in with `resource=`, two forced refreshes through the same manager). The web flow is tested
end to end against a fake of that gateway: redirect, callback, token exchange, and the proxy calling
the server with the stored token.

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

A fifth applies only to `model`, and only when the application named its own endpoint: the value is
sent whether or not the agent advertised it, because the agent's catalogue is not what knows. See
[Bring-your-own endpoints](#bring-your-own-endpoints-and-who-gets-to-say-which-models-exist).

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

### Bring-your-own endpoints, and who gets to say which models exist

`spring.acp.provider.base-url` points an agent at an endpoint of the application's own — a platform
gateway, a proxy, a self-hosted model server. It is ordinary configuration, not an escape hatch, and
four properties are the whole of it:

```yaml
spring:
  acp:
    runtime: goose
    model: deepseek-ai/DeepSeek-V4-Flash-0731
    provider:
      id: openai
      api-type: openai
      base-url: https://gateway.example.com/team-x/openai   # canonical: ends at the version segment
      api-key: ${GENAI_API_KEY}
```

**Read-before-write has an exception, and this is it.** Tier 2 exists because an agent's advertised
list is the only thing that can tell a model from a typo. That reasoning fails the moment the
endpoint is not the agent's own vendor: goose 1.51 advertises its built-in catalogue of ~32 OpenAI
model ids, and nothing a private gateway serves is in it. Measured against a Tanzu GenAI endpoint,
refusing on that basis is also *unstable* — goose repopulates the list from the endpoint after the
provider is set, so of three sessions opened seconds apart in one process, the first two refused a
model the third accepted. So when `ProviderSpec.isByo()`:

* the **endpoint's own `/models` listing** decides (`ModelCatalog`, one `GET` per endpoint per
  process, cached, never fatal — an endpoint that publishes no listing refuses nothing);
* a model the agent never advertised is **sent anyway**, recorded as `Mechanism.ENDPOINT` rather than
  `CONFIG_OPTION` so `AgentSession.configuration()` still says exactly what happened;
* the failure message names what is really being served:
  `cannot honor model='deepseek-v4-flash': the endpoint at https://…/openai/v1 serves
  deepseek-ai/DeepSeek-V4-Flash-0731`.

This applies to `model` only. Mode and provider are the agent's own vocabulary, which it does know
the whole of; inventing values there would be guessing rather than deferring to something better
informed.

**One canonical shape, three vendor spellings.** `base-url` is stored as written and read through
`ProviderSpec.findApiBase()`, which appends `/v1` when the URL does not already end in a version
segment — platforms hand out `…/openai`, READMEs show `…/v1`, and both mean the same endpoint. Each
adapter derives its own spelling from that one shape; core never learns any of them:

| Runtime | How the endpoint and its model reach the agent |
| --- | --- |
| goose | `OPENAI_HOST` (origin **and** any path prefix, version segment stripped — goose appends the route), and `GOOSE_PROVIDER`/`GOOSE_MODEL` at launch |
| codex | a `[model_providers.<id>]` table in `config.toml` (no `wire_api`, see below), plus `model` and `model_provider`; the key stays in the environment via `env_key` |
| opencode | an `@ai-sdk/openai-compatible` provider in `opencode.json` with `options.baseURL` and `{env:…}` for the key; the model id is `<provider>/<model>`. Keyed `acp` when the provider id is just the api type, and that vendor is put in `disabled_providers` (see below) |
| registry | `OPENAI_BASE_URL` + `OPENAI_API_KEY`, the derived names, and nothing more — nothing here knows an unseen agent's config format |

`OPENAI_HOST` is the endpoint *minus its version segment*: goose reads it as host plus optional
prefix and appends the version and route itself. The original single-variable mapping passed the base
URL through verbatim, which sent a path-prefixed endpoint's requests to
`…/team-x/v1/chat/completions` — the prefix had to survive, and the version had to go.

**The route is deliberately not pinned.** `OPENAI_BASE_PATH` would fix the dialect, and an early cut
of this set it to `v1/chat/completions` — which works everywhere and is quietly the wrong default.
Goose chooses per model, and measured against one endpoint in one process it sent `gpt-5.6-terra` to
`/v1/responses` and `deepseek-ai/DeepSeek-V4-Flash-0731` to `/v1/chat/completions`. That choice is
better informed than anything this adapter could make, and it is not cosmetic: the Responses API
carries reasoning items across a turn, so pinning completions costs every reasoning model its
reasoning continuity and its cache hits. Prefer Responses where it exists, in other words, by not
taking the decision. An application that must pin one dialect — a gateway that implements only one,
say — still can, through the tier-3 `env` block, which is applied last and wins.

**OpenCode must not be told the endpoint *is* the vendor.** `provider.id: openai` beside
`api-type: openai` is the natural description of an OpenAI-compatible gateway, and goose reads it
that way. OpenCode merges a config entry named after a provider it ships with into that provider and
runs the built-in's loader on it; for `openai` the loader calls `sdk.responses(…)`, which
`@ai-sdk/openai-compatible` does not have, so every turn failed with `Z.responses is not a function`
(opencode 1.18.31, found running `acp-meridian` unchanged on opencode). The adapter therefore keys the
endpoint `acp` when the id is only the api type; the resolver's suffix match still finds
`acp/<model>`. It also disables that vendor: the endpoint's key is exported as `OPENAI_API_KEY`,
which switches OpenCode's built-in `openai` on with 49 models, and a model both offered would have
matched `openai/<model>` first and sent the gateway's key to the vendor.

The dialect is not always the agent's choice to make, though. Codex 1.12 has only one:

**Measured against the same Tanzu GenAI endpoint, all three runtimes, and it is not a clean sweep.**

goose and OpenCode both reach it and answer; Codex is wired correctly and still cannot. codex-acp
1.12 dropped the chat-completions dialect, and its only remaining wire API is `responses`, which an
OpenAI-compatible gateway serving `/chat/completions` answers with a 404 inside the first turn.
Naming the old dialect does not help: with `wire_api = "chat"` the process starts, but its config
fails to load and every `session/new` fails. Before sign-in the error is a misleading
"Authentication required", because the gateway provider the config described never took effect.
After `authenticate` it is the real one: "failed to load configuration: `wire_api = "chat"` is no
longer supported" (measured on 1.12.0 and 1.13.0). So this adapter writes
no `wire_api` at all (the one legal value is the default), and the pairing is a runtime-selection
fact rather than something configuration can fix. It is worth stating plainly because it is exactly
the kind of difference `spring.acp.runtime` is supposed to hide and here genuinely cannot: the
endpoint has to speak the dialect the agent speaks.

The same run found a flaw in the resolver, not the adapters. Codex advertises the `providers`
capability, so `providers/set` is tried — and fails, because acp-core 0.17.0 sends `id` where Codex
expects `providerId` (the same field-name mismatch already documented for `providers/list`). A
*failed* mechanism used to skip straight to unsupported, reporting `provider: UNSUPPORTED` for a
session whose `config.toml` had been naming that provider since launch. An error now falls through to
the adapter's out-of-band declaration first, and only then to unsupported.

**Why the model is named at launch at all.** For a BYO endpoint the adapters also carry
`model` out of band, and declare it through `appliedOutOfBand`. It is belt and braces on purpose: it
makes the first session behave like the tenth on an agent whose catalogue arrives late, and it is the
only mechanism at all on an agent with no model option. `AgentRuntimeContract` asserts the two halves
agree — whatever an adapter claims it carried at launch must actually appear in the environment or
the files it wrote — because an adapter that over-claims turns a failed model request into a silent
default.

Against a vendor the agent already knows, none of this happens: the model goes over the wire, where
the protocol can report what was applied.

### Tier 3 — runtime-specific escape hatch (`spring.acp.runtimes.<id>.*`)

Passed to that adapter untouched; ignored by every other adapter. The `@Query`-with-native-SQL
analogue. Precedence: tier 3 overrides tier 1 for the selected runtime.

```yaml
spring:
  acp:
    runtimes:
      goose:
        builtins: developer,todo
        env: { GOOSE_DISABLE_KEYRING: "1" }
        serve: { transport: websocket, host: 127.0.0.1, port: 0 }
        mcp: { answer-discover: true }   # see "MCP credentials": the server/discover workaround
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

That link hid a second trap, found running `acp-meridian` on Codex against OpenAI with an empty
`CODEX_HOME`: **codex-acp ignores an API key on its environment until the client sends ACP
`authenticate`.** With `OPENAI_API_KEY` set and no stored login, `session/new` answers "Authentication
required"; after `authenticate {methodId: "api-key"}` the same process opens sessions and runs turns
on that key. Every earlier Codex run had been passing on a developer's `codex login` all along. So
`AgentRuntime.authMethod` names a method, the core sends `authenticate` once per connection
right after `initialize` (skipped with a warning if the agent does not offer it, a startup failure if
it refuses), and `CodexRuntime` names `api-key` whenever an OpenAI key is configured against OpenAI
proper. A bring-your-own endpoint needs none: codex-acp reports it as a "Custom model gateway" and
reads the table's `env_key` itself.

`authenticate` *stores* the key by default, writing `auth.json` into `CODEX_HOME` — the user's
`~/.codex` if the home were left ambient, replacing their own login. A configured key therefore moves
the home and writes `cli_auth_credentials_store = "ephemeral"`, which (measured, codex-acp 1.12 and 1.13) keeps
the key in memory and puts no `auth.json` on disk; nor is the ambient `auth.json` linked in, and a
link left by an earlier run is removed. The configured key is the credential, not whatever login
happens to be lying around.

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

```java
// the same agent, where a Spring AI application already looks for a model
ChatResponse response = chatModel.call(new Prompt("Review the pending changes",
        AcpChatOptions.builder().session("review-123").mode("plan").build()));

// which ACP version this conversation is really in — not simply what the agent answered
int version = agentClient.protocolVersion();
```

```java
// the conversations the agent has, as opposed to the turns run in them
AgentSessions sessions = agentClient.sessions();
if (sessions.supports(AgentSessions.Operation.LIST)) {
    List<StoredSession> stored = sessions.list();            // follows the agent's cursor to the end
}
sessions.load("review-123", storedId);                        // replays history
sessions.resume("review-123", storedId);                      // reattaches without replaying
sessions.close("review-123");                                 // always works, told or not
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
    record UsageUpdated(long contextUsed, long contextSize,
                        Double costAmount, String costCurrency) implements AgentEvent {}
    record Completed(StopReason reason)                       implements AgentEvent {}
    record Failed(Throwable cause)                            implements AgentEvent {}
}
```

Two changes from the sketch, both made when the model met real agents. `ToolCallStarted` carries
`title` rather than `name`, because that is honestly what ACP gives — prose written for a human — and
the stable identifier an allowlist needs is vendor-specific and lives behind
`AgentRuntime.toolNameOf`. `UsageUpdated` arrived in M4 and not in M1, and the reason it took that
long is the reason it is portable now: goose returns token counts on the `session/prompt` response,
which looked like nowhere for an event to come from, but that field is goose's own and the
`usage_update` notification — which every agent that reports usage at all sends, and which
`acp-core` models completely — is in the schema.

**Preserve the wrapper's load-bearing invariant**: every turn emits exactly one terminal event
(`Completed` or `Failed`) — normal end, RPC error, timeout, dropped connection, or consumer
cancellation. Closing an unfinished stream sends `session/cancel` *before* completing. That
invariant is documented in `AcpTurn.java` and is why UI spinners don't hang; carry the test for it
across.

### Session operations are optional, and no two agents agree

Every method behind `AgentSessions` is gated on a capability the agent advertises, and the three
runtimes implement different subsets. Measured on the same machine, same day:

| | `session/list` | `session/load` | `session/resume` | `session/delete` | `session/close` |
| --- | --- | --- | --- | --- | --- |
| goose 1.51.0 | yes | yes | **no** | yes | yes |
| codex-acp 1.12.0 | yes | yes | yes | yes | yes |
| opencode 1.18.31 | yes | yes | yes | **no** | yes |

That table is the argument for `supports()` being part of the public contract rather than a
convenience: an application that assumed any one column would break on one of the three, and the
one it broke on would depend on which agent the operator chose. An operation the agent never
advertised throws `UnsupportedAgentOperationException` — the method-level counterpart to
`UnsupportedAgentOptionException` — naming the ACP method, rather than failing on the wire with an
error code the caller would have to interpret. `close` is the exception that proves the rule: it
always succeeds locally, because an agent that cannot be told a session is over is not a reason for
an application to be unable to end one.

For migration there is `AgentExecutor`, signature-for-signature the old `GooseExecutor` with
`GooseOptions` replaced by `AgentOptions`, including the newline-delimited
`message`/`notification`/`complete` JSON its consumers parse. Two methods did not come across:
`getConfiguration()` returned a parsed `~/.config/goose/config.yaml`, which no other agent has, and
what an application wanted from it — which model am I really using — is
`openSession(name).configuration()`, which is the negotiated truth rather than a file.

---

## Runtime SPI

```java
public interface AgentRuntime {
    String id();

    /** How to start it: command, args and environment. */
    AgentLaunchSpec launch(AgentSettings settings);

    /** Write agent-native config files before launch (config.toml, opencode.json, AGENTS.md…). */
    default void provision(AgentSettings settings) {}

    /** The ACP auth method to send `authenticate` with before the first session, if any. */
    default Optional<String> authMethod(AgentSettings settings) { return Optional.empty(); }

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

`AgentLaunchSpec` is sealed with two variants, and the difference between them is who owns the
process. `Stdio(command, args, env)` is owned by the transport: the SDK spawns the child and it dies
when the transport closes. `WebSocket(uri, headers, process)` reaches an agent that is a server,
either one this library starts and supervises (`ManagedProcess`) or one somebody else runs. Only
Goose offers the second shape; every other runtime is stdio-only, which is why this is a sealed
hierarchy rather than one record with optional fields.

`AgentClientFactory.connect(runtime, settings, transport)` is the seam between starting an agent and
talking to one — it is how the scripted agent is driven, and how a runtime attaching to something it
did not spawn comes in.

Discovery is a nested `@Configuration` per adapter, each carrying `@ConditionalOnClass`, not a
`@ConditionalOnClass` bean method. Boot reads the condition on a configuration class from the
bytecode; on a bean method it must reflect over the annotation, which throws `TypeNotPresentException`
for an absent adapter — and then logs it and registers the bean anyway, so the application dies on
`NoClassDefFoundError` at refresh. Since the entire point of these being optional dependencies is that
an application ships only the agents it wants, the condition has to hold when the class is genuinely
missing. There is a test for exactly that, with a `FilteredClassLoader`.

### The registry runtime (M4)

A second, smaller SPI sits beside `AgentRuntime`, and the difference is that it is asked a question
rather than announcing an answer:

```java
public interface AgentRuntimeProvider {
    Optional<AgentRuntime> forId(String runtimeId);
    default List<String> knownIds() { return List.of(); }
}
```

An adapter knows which agent it is and registers itself. A provider is handed the id an application
asked for and decides whether it can build something for it — which is the shape a catalogue of 41
agents needs, since starting 41 beans to find the one an application wants would be absurd.
Providers are consulted only after the registered adapters, so a compiled adapter always wins for
the same agent: an application with both `runtime-goose` and `runtime-registry` and
`spring.acp.runtime: goose` gets the adapter, with its `_meta` tool names and its served transport,
rather than a registry download of the same binary.

`RegistryAgentRuntime` is what a provider builds. Given `spring.acp.runtime: gemini` with no
compiled adapter, it resolves the entry from a cached registry snapshot, obtains the agent the way
the registry says to — `distribution.binary` (per-arch archive + SHA-256), `distribution.npx` or
`distribution.uvx` — and launches it with the arguments the registry says make it speak ACP.
Measured: `Connected to gemini-cli 0.60.0 over ACP v1`, a fourth agent this library has never had a
line of code about.

**What it cannot do is the more useful half of the documentation.** An adapter exists to carry what
ACP does not standardize: what an agent calls its options, where it reads its config file, which
environment variable holds a key, how it buries a tool name. None of that is in the registry and
none of it can be guessed. So this runtime provisions nothing, writes no files, never falls back
from a tool identifier to a human-readable title, and reaches the negotiated tier only through the
portable half of the option vocabulary — the ACP `category`, plus the obvious ids `model`, `mode`
and `provider`. An agent that names its options anything else reports them unsupported, honestly,
through the same `on-unsupported` an adapter would. Tier 3 still works, which is the point of tier 3.

**The digest is the feature, not a detail of it.** This downloads an executable and runs it in a
process holding the application's credentials with the application's workspace as its working
directory, so `require-checksum` defaults to on. That costs something real: the catalogue publishes
a `sha256` for **10 of its 19 binary agents** and not for the other 9, all-or-nothing per agent, so
those nine need `require-checksum: false` written down by an operator who meant it. The refusal
names the agent and says exactly that, because a refusal nobody can act on is just an outage. The
24 npx and uvx agents do not go through this path at all; npm and PyPI have integrity of their own.

**Unpacking is split by what the JDK can decompress, not by preference.** zip and gzip are in
`java.util.zip`, so `.zip` and `.tar.gz` are handled in process, with every entry's destination
checked against the real path of the install directory before it is written — an archive is a list
of paths chosen by whoever built it, and `../../.ssh/authorized_keys` is a valid entry name. Bzip2
and xz are not in the JDK at all, and **goose — the reference runtime — ships `.tar.bz2`**, so those
hand off to the system `tar`, which reads all three on macOS and Linux. Pulling in Commons Compress
for two formats would have put a second archive library on every application's classpath for an
archive most of them never download. Two registry entries publish a bare executable with no
container around it, so a file whose name matches no archive format is treated as the agent itself.

---

## Protocol version, observations, and Spring AI

Three things M4 added on top of the turn, none of which changes what a turn is.

### ACP v2 is gated, and then refused

ACP negotiates in one exchange: the client names the highest version it speaks, and the agent
answers with that version if it supports it or with its own latest if it does not. One integer each
way, which makes it look like a formality. It is not, for two reasons measured here.

**An agent's answer cannot be taken at its word.**

| offered | goose 1.51.0 | opencode 1.18.31 | codex-acp 1.12.0 |
| --- | --- | --- | --- |
| 1 | 1 | 1 | 1 |
| 2 | **2** | 1 | 1 |
| 3 | **3** | 1 | — |

goose echoes whatever it is given, including a version that does not exist, having shipped no part
of v2. This is the same shape as goose accepting a model id it has never heard of, and it has the
same consequence: a client that trusted the echo would believe it was in a conversation whose wire
format neither side is using. So the negotiated version is `min(offered, answered)`, never the
answer alone, and `spring.acp.protocol.strict` turns the clamp into a refusal for an operator who
would rather know. Two runtimes out of three do this correctly, which is exactly why a client cannot
rely on it.

**v2 is a draft, and this library cannot speak it.** It replaces the turn-based model — a prompt
response carries the `messageId` of the inserted message rather than a `stopReason` — and
restructures diffs, permission subjects and message patching. `acp-core` 0.17.0 models the v1 shapes
and declares `LATEST_PROTOCOL_VERSION = 1`, so a client that found itself in a v2 conversation would
decode a `PromptResponse` with a null stop reason and report every turn as having ended for no
reason. The protocol's own announcement says to gate v2 behind version negotiation **and** a feature
flag; this gates it behind both and then refuses to proceed, because the third thing it asks for — an
implementation of v2 — is not this library's to write while the SDK's records are v1.

Which leaves `max-version: 2` doing one honest job: finding out what an agent claims, with a
guaranteed loud failure instead of a silent misreading. The day `acp-core` models v2,
`AcpProtocol.HIGHEST_SPOKEN` is the constant that moves. `AgentClient.protocolVersion()` reports what
the conversation is really in, which is not simply what the agent said.

### Observations: two, and only two

A turn, and a tool call inside one. Both are things that take time and can fail, which is what an
observation is for; a session is neither and a prompt is an argument.

```
acp.turn{acp.runtime, acp.model, acp.session.kind, acp.outcome}
acp.tool.call{acp.runtime, acp.tool.kind, acp.tool.status}
```

Session names, tool call ids, tool titles, context and cost are high-cardinality, where they become
span attributes rather than meter dimensions. An application moves one across by contributing an
`ObservationConvention`, because that split is a deployment decision as much as a design one.

Three things about the implementation are not obvious.

**A reactive turn cannot be wrapped in a scope.** The idiomatic `observation.observe(() -> work())`
opens a thread-local scope around a block, and a turn is not a block: it is subscribed on one thread
and finished on whichever transport thread delivers the agent's last frame, minutes later. Wrapping
it would have timed the act of asking. So the observation is started on the subscribing thread —
where the caller's own observation is still current, which is how the turn becomes a child of it —
and stopped from wherever the turn actually ends. Tool call observations get their parent stated
explicitly for the mirror-image reason: they are created on a transport thread that has no current
observation for Micrometer to infer one from.

**A tool call the turn outlived is still stopped, tagged `unfinished`.** A cancelled turn produces
one every time, and an observation left open is a leaked span and a timer that never fires.

**`acp.model` is what the session is really using, and finding that out takes three sources.** The
negotiated tier's applied value first; then what the agent says it is currently set to, for the very
common case where the application asked for no model at all; and only then the request. A dashboard
grouped by the model an application asked for, while `on-unsupported: warn` quietly ran it on
another, would be worse than no dashboard — and one reading `unknown` for every application that
never set `spring.acp.model` would be worse still. Measured on the smoke app, which sets no model:
`acp.model=gpt-5.6-terra`.

`AgentObservations` is a four-method interface in core with no Micrometer type in it, and
`MicrometerAgentObservations` is the only class that names one — the same arrangement as the Spring
Boot dependencies, for the same reason.

### `AgentEvent.UsageUpdated`, and where usage actually comes from

M1 left usage out of the event model because goose returns token counts on the `session/prompt`
response, so an event would have had nowhere to come from. That was half right, and the other half
is the finding:

```
goose 1.51 session/prompt response:  {"stopReason":"end_turn",
                                      "usage":{"totalTokens":4441,"inputTokens":4436,"outputTokens":5}}
goose 1.51 session/update:           {"sessionUpdate":"usage_update","used":4441,"size":1050000,
                                      "cost":{"amount":0.008932,"currency":"USD"}}
```

The response field is **goose's own and not in the schema** — `PromptResponse` in the spec carries
`stopReason` and nothing else, which is also all `acp-core` models. The notification **is** in the
schema, and `acp-core` models it completely, cost included. So the portable source of usage is the
notification, `AgentEvent.UsageUpdated` comes from there, and it means the same thing on every agent
that sends one: context window consumed out of context window size, cumulative for the session, with
an optional cost. An update carrying neither number is dropped rather than reported as `0 of 0`,
which would read as an empty context window rather than as no measurement.

### `AcpChatModel`: the two models disagree about who owns the conversation

A chat completion is stateless — Spring AI sends the whole history every call, which is why
`ChatMemory` exists. An ACP session is stateful: the conversation lives inside the agent process,
which is also holding a file tree, a plan and a set of tool results that no message list can carry.
So the adapter reads `AcpChatOptions.session` and behaves differently:

- **no session named** — every call is a throwaway ACP session that knows nothing, so the whole
  prompt goes over, rendered with role labels. The closest thing to chat completion semantics, and
  the right default for an application managing history with `ChatMemory`;
- **a session named** — the agent already has everything up to its own last reply, so only the
  messages *after* the last assistant message are sent. Sending the history again would make the
  agent read its own previous answers as new instructions, and bill for the whole conversation on
  every turn.

Combining a named session with a `ChatMemory` advisor therefore means two memories of one
conversation, and the agent's is the one that matters. The default options carry no session, so one
`ChatModel` bean is not silently one shared conversation for the whole application.

`temperature`, `topP`, `topK`, `maxTokens`, `stopSequences` and the penalties have no ACP
equivalent — the agent owns the inference call and the protocol gives a client no way into it — so
they are logged once, by name, rather than dropped quietly. Tool calling is the same story from the
other side: the agent has its own tools and runs them itself, so Spring AI `ToolCallback`s are not
visible to it, and tool activity surfaces on the native `AgentClient` API instead.

ACP usage goes into `ChatResponseMetadata` as key values rather than into Spring AI's `Usage`, which
is prompt and completion tokens for one call. Putting "the whole conversation so far" in a field
every dashboard reads as "this call" would be the wrong number in the right place.

---

## What to port from `java-wrapper`, and what to drop

Port (these are proven and non-obvious):

| From | To | Why |
| --- | --- | --- |
| `acp/AcpSessionRegistry.java` | `session/SessionRegistry` | name→id map authoritative over the caller's `resume` flag; per-entry `Semaphore(1)` turn permit (not a lock — the releasing thread differs); idle TTL sweep |
| `acp/GooseServerSupervisor.java` | `process/AgentProcessSupervisor` *(ported in M3)* | virtual-thread stdout drain (an undrained pipe blocks the child), secret redaction, exponential-backoff restart capped in a 5-min window, health polling, shutdown hook |
| `acp/AcpPermissionPolicy` + `AcpClientRequestHandler` | `permission/PermissionPolicy` | deny-by-default, allowlist, `allow_once`/`reject_once` option selection |
| `acp/AcpEventTranslator.java` | `event/AgentEventMapper` | `session/update` → event mapping, drops `_meta.replay` history, synthesizes results for tool calls left open |
| `GooseOptions` validation | `config/` records | see tier 1 above |
| `GooseAutoConfiguration` shape | `AcpAutoConfiguration` | `SmartLifecycle` at `Integer.MAX_VALUE - 1000`; **startup failure logged, not thrown**, so an app healthy apart from its agent stays up |

Drop: the hand-rolled `AcpConnection` and its JSON-RPC request/response correlation — `acp-core`
covers all of it, and its 0.15.0 release specifically fixed notification ordering and
loss-on-graceful-close, the same class of bug that code exists to avoid.

One thing on the drop list came back. `WebSocketAcpTransport` was to be replaced by the SDK's, and
the SDK's cannot send a header, which Goose's server requires — so `transport/WebSocketAgentTransport`
is a smaller, Reactor-shaped descendant of it rather than a deletion. See the known gaps.

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

Found by running the SDK against three live agents. Four are worked around, two are designed around
and documented; none is suppressed. Each argues for keeping the SDK behind our own types rather than
exposing it in the public API.

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

**`WebSocketAcpClientTransport` cannot send a header.** *(worked around in M3.)* It builds its
socket inside `connect()` from `httpClient.newWebSocketBuilder()` and never exposes the builder, so
there is no way to add one — and Goose's ACP server refuses every connection that arrives without
`X-Secret-Key`, the alternative being a flag called `--dangerously-unauthenticated`. So the whole
served transport turns on a header the SDK's implementation cannot carry.
`WebSocketAgentTransport` follows the SDK's implementation closely and deliberately, adding headers,
a keepalive ping and a disconnect callback, so that the day the SDK grows a header hook this class
can be deleted rather than reconciled.

**A stdio connection cannot say whether its agent is still alive.** *(not worked around; designed
around in M3.)* `StdioAcpClientTransport` keeps the child `Process` in a private field, accepts a
`setExceptionHandler` it never calls, and exposes no liveness of any kind. So the only evidence of a
dead stdio agent is a request that does not come back. `AgentClient.isAlive()` is therefore
optimistic by contract — a transport that cannot tell answers `true`, and the promise is only that
`false` is never wrong. Replacement in the pool is real for a served agent, whose socket closes and
whose supervisor watches the process, and best effort for a stdio one. Reporting "possibly dead" for
every stdio agent would have made the answer useless to the only caller that needs it.

**`LATEST_PROTOCOL_VERSION` is 1, and the records are v1 shapes.** *(designed around in M4.)* The
SDK passes whatever `protocolVersion` it is given straight through and never checks the answer, so
offering v2 is possible; decoding v2 is not. `PromptResponse` models `stopReason`, which v2 replaces
with a `messageId`, and the `SessionUpdate` hierarchy is v1's. So the feature flag reaches the
handshake and stops there — see "ACP v2 is gated, and then refused". Not a defect so much as the SDK
being exactly as far along as the protocol's stable version; it is on this list because it is the
one thing standing between the flag and a working v2.

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

The conformance suite asserts both halves. The remaining exposure — an agent writing outside the
workspace **with its own tools** — is not something a client can close, and M3 did not close it:
restricting what the agent's own process can reach is the operating system's job. What M3 did close
is the half that is this library's, below.

### What M3 added, and what it is honestly for

`WorkspaceJail` confines every path the agent asks *this client* to touch. That is a smaller claim
than the name suggests and it is worth keeping the two apart: `fs/read_text_file` and
`fs/write_text_file` let an agent borrow the client's file access, which is useful to an IDE that
wants the edit in its own buffer and of no use to a server-side client, so both are off by default.
When they are on, the jail is the thing standing between an agent and the rest of the filesystem.

**`Path.normalize()` is not a jail.** It removes `..` textually, which stops
`workspace/../../etc/passwd` and stops nothing else. A symlink inside the workspace pointing at
`/etc` is an ordinary-looking relative path that resolves outside, and an agent can create one with
a single tool call and then ask this client to read through it. So every decision is made on the
*real* path, with symlinks followed, recomputed per call because the filesystem changes between
requests; for a path that does not exist yet — which is most writes — the walk goes up to the
deepest ancestor that does, and what does not exist cannot be a link. The root is realpathed too,
which is not cosmetic: on macOS a temp directory is reached through `/var`, itself a link to
`/private/var`, and a jail comparing the two spellings would refuse every path in its own workspace.

**Terminals are the same jail plus four more limits.** `terminal/*` is arbitrary code execution as
the JVM's user, in a process holding the application's credentials, so it is off by default; when it
is on, the working directory goes through the jail, output is bounded and reported `truncated`
rather than growing, a command that outlives `command-timeout` is killed, and every process is
killed when the client closes. `allowed-commands` is empty by default and empty means *all*, which
reads backwards until you notice that the capability itself is the gate — an application that turned
terminals on and allowed nothing would have built something that cannot work. It matches on the
command's file name, so `/usr/local/bin/mvn` and `mvn` are the same command; an allowlist that could
be evaded by spelling out the path would not be one.

**Secret redaction, and a bug the first version had.** A supervised agent's output goes into the
application's log, and agents print their configuration when they start, when they fail to start,
and whenever someone raises their log level to find out why. `SecretRedactor` blanks the shapes
these appear in — `key=value`, `"key": "value"`, `key: value`. The first version printed the token
for `Authorization: Bearer eyJ…`: a single pass consumes the first thing after the separator, which
is the word `Bearer`, and carries on from there. The wrapper this succeeds has the same bug. Found
by the test, not by reading it.

**The HTTP endpoint is opt-in twice over** — the WebFlux dependency, then
`spring.acp.controller.enabled` with no `matchIfMissing`, so adding the starter for an unrelated
reason cannot publish an agent endpoint as a side effect. A caller who reaches it can spend the
application's model budget and make the agent act on its workspace, so authentication is required
by default, per-request model and provider overrides are refused by default, and prompt length and
timeout are bounded. Credentials are fixed when the agent process starts and are not reachable from
a request at all. The streamed endpoint emits assistant text only: tool calls and plans name paths
and commands inside the workspace, and an endpoint that may face a browser should not be what
decides those are safe to publish.

### Baseline

- `permissions.policy: deny`; `filesystem.enabled: false`; `terminal.enabled: false`. What is not
  lent is declared unsupported in `clientCapabilities` rather than advertised and then refused — an
  agent that knows it cannot read files plans differently from one that finds out mid-turn — and the
  handler for a capability is registered only when that capability is on, so there is one source of
  truth rather than two that can disagree. See the correction above for what this does and does not
  buy.
- When filesystem *is* enabled, `WorkspaceFileSystem` resolves every `fs/read_text_file` and
  `fs/write_text_file` path against the session `cwd` via `Path.toRealPath()` and rejects escapes,
  including via symlink. Same jail for `terminal/create` `cwd`.
- Any WebFlux controller is opt-in (`spring.acp.controller.enabled: false` default), requires a
  `Principal`, and rejects request-level provider/model/credential overrides unless explicitly
  enabled. Credentials and endpoints are fixed at process start; only negotiated per-session
  options vary.
- Never log the process env, the WebSocket secret, or MCP headers. A supervised agent's own output
  goes through `SecretRedactor` on the way to the log, as a second line of defence rather than the
  first.

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
transport. Both arrived in M3.

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
| 8 | `ScriptedAgent` and `AgentRuntimeContract` — the TCK | `acp-spring-test` |
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

Deferred from M2 as planned, and delivered in M3: `AgentProcessSupervisor` restart/health logic, the
WebSocket transport, and `agents.yaml`.

**M3 — parity and ergonomics. Done.** Everything the buildpack's wrapper could do, the library now
does, without naming Goose anywhere but in the Goose adapter. 289 tests green: 244 run anywhere, 45
drive real agents and skip when one is unusable.

| Step | Delivered | Where |
| --- | --- | --- |
| 1 | `AgentSessions` — list (paginated), load, resume, delete, close, all capability-gated | `core/session` |
| 2 | `WorkspaceJail`, `WorkspaceFileSystem`, `WorkspaceTerminals`, `FileSystemAccess`, `TerminalAccess` | `core/workspace` |
| 3 | `WebSocketAgentTransport` and `AgentLaunchSpec.WebSocket` | `core/transport`, `core/runtime` |
| 4 | `AgentProcessSupervisor` and `SecretRedactor` | `core/process` |
| 5 | `AgentClientPool` — sticky sessions, idle sweep, connection replacement | `core/client` |
| 6 | `AgentExecutor` + `LegacyEventFormat`, the `GooseExecutor` migration path | `core/executor` |
| 7 | `GooseRuntime` over `goose serve`, generated secret, supervised sidecar | `runtime-goose` |
| 8 | `AgentsConfigDataLoader`, `AcpController`, `AcpWebFluxAutoConfiguration`, pool/fs/terminal properties | `spring-boot-autoconfigure` |
| 9 | Three contract tests, the security suites, and session operations in `ScriptedAgent` | `acp-spring-test`, `core` |

The served transport, verified end to end against a real `goose serve`: handshake, a turn that
terminates exactly once, a named session keeping context across turns over the socket, and the
server dying with the client that started it.

`agents.yaml` verified at the spelling the plan promised, with the smoke app: a bare
`runtime: opencode` in a file next to the application, pulled in with
`spring.config.import: optional:agents.yaml`, overrides `application.yaml`'s `runtime: goose` and
the application runs against OpenCode.

Eight things the build taught us that the plan had not anticipated:

- **The SDK's WebSocket transport cannot send a header, and Goose requires one.** The whole served
  path turns on `X-Secret-Key`, which `WebSocketAcpClientTransport` has no way to add. Writing our
  own was not the plan; it is now the fifth documented gap, and the class is shaped to be deleted
  when the SDK grows the hook.
- **A stdio connection cannot report that its agent has died.** Which made `isAlive()` a three-state
  answer — alive, dead, cannot tell — collapsed into two, with the contract that `false` is never
  wrong. Pool replacement is therefore real for a served agent and best effort for a stdio one, and
  saying so is better than a health check that invents an answer.
- **No two runtimes implement the same optional session methods.** goose has no `resume`, OpenCode
  no `delete`, Codex all five. This was expected to be a formality and turned out to be the reason
  `supports()` belongs in the public contract.
- **`LoadSessionResponse` drops `configOptions` the way `NewSessionResponse` does, and is worse.**
  It does not carry the session id either, so there is nothing to key the recovered options on. The
  fix is to claim them immediately after the call that produced them, under a lock that serializes
  load and resume — narrow, documented, and delete-on-sight when the SDK models the field. Without
  it, a loaded session could not negotiate a model at all.
- **`normalize()` is not a jail, and the workspace root has to be realpathed too.** The first is the
  symlink an agent can create with one tool call. The second is macOS: a temp directory reached
  through `/var`, which is a link to `/private/var`, and a jail comparing spellings that refuses
  every path in its own workspace.
- **The redactor printed the secret it was written to hide.** `Authorization: Bearer eyJ…` blanks
  the word `Bearer`: one pass consumes the first token after the separator and carries on. The
  wrapper being replaced has the same bug, unnoticed, which is the argument for the test.
- **The pool has to count open sessions, not assigned names.** A throwaway turn never takes a name
  but does occupy an agent for the length of a turn, and a pool counting names would have sent every
  one of them to the same process.
- **Replacing a connection quietly broke the stickiness it exists to preserve.** Releasing a dead
  connection's names is right for every name but the one being served at that moment: that one had
  just been reassigned, so the next call assigned it afresh to whichever connection was now least
  loaded — which is precisely not the one that had just opened the session. One conversation, two
  processes, one name. Caught by a test written after the code, and the test fails again the moment
  the one-line re-assertion is removed. The idle sweep could reach the same outcome by landing
  between assigning a name and opening its session, so it lets a name go only after it has been
  missing across two sweeps — minutes apart, which no session-open is.

**M4 — reach. Done.** The abstraction now reaches agents nobody wrote an adapter for, applications
that already have a `ChatModel`, and operators who want to see what their agents cost. 391 tests:
344 run anywhere, 45 drive real agents and skip when one is unusable, and 2 download a real agent
from the real registry and are opt-in.

| Step | Delivered | Where |
| --- | --- | --- |
| 1 | `AcpProtocol`, `ProtocolSettings`, `UnsupportedProtocolVersionException`, `AgentClient.protocolVersion()` | `core/protocol` |
| 2 | `AgentObservations` SPI, `MicrometerAgentObservations`, two documented observations and their conventions | `core/observation` |
| 3 | `AgentEvent.UsageUpdated`, mapped from the `usage_update` notification | `core/event` |
| 4 | `AgentRuntimeProvider` SPI; `SelectedRuntime` consults it after the adapters | `core/runtime`, `spring-boot-autoconfigure` |
| 5 | `AgentRegistry` with a bundled snapshot, `RegistrySettings`, `Platform` | `runtime-registry` |
| 6 | `AgentInstaller` and `Archives` — SHA-256, zip/tar.gz in process, bz2/xz through the system tar | `runtime-registry` |
| 7 | `RegistryAgentRuntime` — binary, npx and uvx distributions | `runtime-registry` |
| 8 | `AcpChatModel`, `AcpChatOptions`, `AcpChatModelAutoConfiguration` | `spring-ai`, `spring-boot-autoconfigure` |
| 9 | `spring.acp.protocol`, `.observations`, `.registry`; smoke app extended with all three | `spring-boot-autoconfigure`, `samples/smoke-app` |

The completion test, run a fourth time, against an agent this library has never had a line of code
about:

```
No adapter claims runtime 'gemini'; it was resolved from a runtime provider
Connected to gemini-cli 0.60.0 over ACP v1
```

(The turn after it fails with "Gemini API key is missing or not configured" on this machine, which
is the agent asking for a credential rather than anything the library got wrong.)

And the rest of M4 on the same run of the smoke app, which sets no model and names no agent:

```
Via Spring AI ChatModel: READY
ACP protocol: v1
Observed 2 turn(s) as [acp.model=gpt-5.6-terra, acp.outcome=END_TURN, acp.runtime=goose,
                       acp.session.kind=ephemeral] in 4521ms
Observed 1 turn(s) as [acp.model=gpt-5.6-terra, acp.outcome=END_TURN, acp.runtime=goose,
                       acp.session.kind=named] in 3180ms
Observed 1 tool call(s) as [acp.runtime=goose, acp.tool.kind=unknown, acp.tool.status=COMPLETED]
```

Eight things the build taught us that the plan had not anticipated:

- **goose answers whatever protocol version it is offered, including ones that do not exist.**
  Offered 3, it answers 3. OpenCode and Codex both clamp correctly, which is exactly why a client
  cannot rely on the answer: two out of three getting it right is how this stays invisible until it
  matters. Negotiation is `min(offered, answered)`, and the plan's one-line "let `initialize()`
  negotiate" turned into a class with a table of measurements behind it.
- **Half the registry's binary agents publish no checksum.** 10 of 19 do and 9 do not, all-or-nothing
  per agent. The plan said "per-arch URLs and SHA-256" as though it were a property of the data. So
  `require-checksum` is a property rather than a constant, on by default, and the refusal has to name
  the agent and say how to proceed — otherwise the feature is "any ACP agent, except these nine, for
  reasons the error does not give".
- **The JDK cannot decompress the reference runtime's archive.** goose ships `.tar.bz2`, and there is
  no bzip2 in `java.util.zip`. The split — zip and gzip in process, bz2 and xz through the system
  `tar` — is the JDK's line, not a preference, and it is the alternative to putting Commons Compress
  on every application's classpath for an archive most never download.
- **The usage the plan deferred was the wrong usage.** goose's token counts are on the
  `session/prompt` response, which is goose's own field and not in the schema; `usage_update` is in
  the schema, is modelled completely by the SDK, cost and all, and carries the numbers an application
  actually wants to watch. M1 deferred the event for want of a source and the source was there.
- **An observation cannot wrap a reactive turn in a scope.** `observe(() -> …)` would have timed the
  act of subscribing, because a turn is started on one thread and finished on the transport thread
  minutes later. Start-and-stop, with the parent captured at subscribe time and stated explicitly for
  tool calls, is the only shape that measures the turn.
- **The turn released its session in the router after it released the turn permit, and that is a
  race.** `FluxCreate` runs the downstream's `onComplete` before the sink's `onDispose`, so a caller
  blocking on a turn is freed — and can start the next one — while this turn's registration is still
  in the router. The next turn then fails with "already has an active turn" for a turn that had
  finished. Found by `GooseServeTests` failing about one run in ten, against a fast local socket;
  fixed by unregistering before the sink completes, with a test that fails again the moment that line
  moves back.
- **A property class for an optional module must not name that module's types.** `AcpProperties`
  instantiates its nested blocks from field initializers, so a `Registry` block defaulting to
  `RegistrySettings.DEFAULT_URL` would make the optional jar mandatory and kill an application at
  refresh on the very dependency it chose not to ship. Null means "the module's own default", and the
  conversion happens behind the class-level condition. Same trap as M2's `@ConditionalOnClass` bean
  method, one layer up.
- **"Unregistered runtime" quietly changed meaning, and two old tests caught it.** With a registry
  provider on the classpath, `spring.acp.runtime: gemini` is no longer a typo — so the M1 test that
  pinned it as one failed, correctly. Which raised the question the fix had to answer: an id a
  provider merely *enumerates* must not pass validation, or a tier-3 block would be valid on a machine
  with the registry jar and invalid on one without it.

---

## Verification

391 tests, in two halves that cost very different things.

`mvn test` runs the 368 that need no agent. The live suites are **opt-in**, behind
`-Dacp-spring.test.live=true`, and skip silently without it: every turn they run is charged to
whoever runs the build, against their own key, and a machine with all three agents logged in was
paying for around thirty turns on every `mvn install` — of which two per runtime, the agentic
file-writing ones, cost more than the other eight put together. That is the wrong trade for a
command run dozens of times a day. What changes between two builds is this library, and what the
live suite uniquely catches is the *agents* moving underneath it; the always-on gate against
regressions in our own protocol handling is `ScriptedAgent`'s wire tests. So the live suites run
when an adapter changes, before a release, and in CI. Opted in, they still skip per runtime when the
agent is absent or has no credentials — `AgentProbe.isUsable` answers for both questions, cheapest
first, and does not start an agent at all unless the build asked for one. Two further tests opt in
separately with `-Dacp-spring.test.registry.live=true` because they download 24 MB.

**Fast tests (344)** — turn semantics, session registry concurrency and permit accounting, event
mapping, permission policy, URL/header/env/secret validation, tier-3 normalization, model matching,
every branch of the negotiated tier, capability gating and pagination for session operations, pool
routing and replacement, the legacy event vocabulary, adapter launch and provisioning for all three
runtimes, protocol version reconciliation, observation tagging and tool call lifecycle, registry
parsing, digest verification, archive unpacking, the Spring AI message-splitting rule, and Boot
binding, `agents.yaml` loading, controller behaviour and adapter registration. No real agent.

Inside that figure are the security tests the M2 plan deferred, and they are the ones worth naming:
workspace escape via `..`, via an absolute path, via a symlinked file, via a symlinked directory and
via a write through one; terminal `cwd` confinement including through a symlink; the terminal
allowlist not being evadable by spelling out a path; output truncation; and secret redaction in each
shape an agent prints, including the `Bearer` case the first implementation got wrong.

**Wire tests (24)**, on `acp-test`'s in-memory transport with `ScriptedAgent` — a fake agent that
speaks raw JSON-RPC rather than the SDK's records, which is the point of it. The core's fast tests mock
`AcpAsyncClient`, and the SDK gaps this library works around are *format* gaps, invisible to a mock:
`configOptions` dropped from a typed response — from `session/new` and from `session/load`, where it
arrives with no session id to key it on — and a `sessionUpdate` discriminator with no record. The
scripted agent can also be told to misbehave the way real agents do —
`acceptsUnknownValues(true)` reproduces goose 1.51 storing a model it has never heard of, and
`echoesProtocolVersion(true)` reproduces it answering a version nobody offered — so the core's
defenses are testable without waiting for a vendor to ship the bug again.

**Runtime conformance (42 = 14 × 3)** — `AgentRuntimeContract` in `acp-spring-test`, extended once per
adapter. **This suite, not the `AgentRuntime` interface, is the definition of the abstraction:** an
interface only constrains signatures, and three adapters can satisfy one and still behave differently
enough that an application cannot move between them. It asserts only what ACP genuinely standardizes —
connect and negotiate, exactly-one-terminal-event, blocking call, named sessions keeping context,
cancellation reaching the agent, a tool-using turn still terminating once, deny-by-default blocking a
write in the agent's reviewing mode, the requested model applied by an advertised mechanism, a
different model applied, an unsupported model honoring both `fail` and `warn`, every optional session
operation being askable and saying no by name when it must, closing a named session working whether
or not the agent can be told, and — where the agent implements `session/load` — a closed conversation
being the same conversation when it comes back. It never asserts a model name, a tool name, or how an
agent phrases an answer; a test a runtime could only pass by behaving like Goose would make it a Goose
conformance suite.

Two of those fourteen assert the same invariant — that a tool-using turn still terminates exactly
once — over the same file-writing prompt, and an agentic turn is the most expensive thing this suite
does. Where a runtime names a `reviewingMode()`, the deny-by-default test covers it in the harder
case, the one where permission is actually requested and refused and a stall would really happen, so
`aToolUsingTurnStillTerminatesExactlyOnce` skips and only runs for an adapter with no reviewing mode
to fall back on. All three current adapters have one, so an opted-in run spends three agentic turns
rather than six.

The load test is skipped rather than asserted where `session/load` is absent, and that is the line
this suite walks: failing goose for not having `resume`, or OpenCode for not having `delete`, would
make it a feature matrix rather than a contract. What it does assert, for every agent that offers the
method, is the only reason to offer it.

Two deliberate concessions in it, both documented at the assertion:

- `reviewingMode()` is the one piece of vendor knowledge the suite cannot do without, because there is
  nothing in the protocol to derive "the mode in which this agent asks first" from.
- `AgentProbe` gates each suite on a real session rather than `agent --version`, because all three
  agents are installed long before they are usable, and an agent with no credentials answers the
  handshake and then fails inside a turn. It also supplies the model to ask for, since hardcoding one
  per agent would put three model catalogs into the test source.

**Registry tests (41, 2 opt-in)** — the catalogue read from the bundled snapshot with no network;
npx, uvx and per-platform binary entries, including an entry that publishes no digest; one unreadable
entry costing that agent rather than the catalogue; tar.gz, zip, tar.bz2 through the system tar, and
a bare executable; the executable bit surviving; a digest mismatch refused; an unverifiable artifact
refused by default and installed when told; an archive entry aimed at `../escaped` refused before
anything is written; and a stale or unreachable catalogue falling back rather than failing.

Two of them are the whole claim in one test: an agent is built into a `.tar.gz`, published through a
`file:` registry, and then goes through the ordinary `AgentClientFactory.create` path — resolve,
verify, unpack, chmod, spawn, handshake, prompt — with the second asserting that a digest mismatch
stops all of that before `exec`. The agent is fifteen lines of shell, deliberately: a test that
downloaded goose would be testing goose, a CDN and a network. Two things about being a fake had to be
learned the hard way and are commented where they bit — the shell's builtin `printf` block-buffers
into a pipe, so the handshake sat in a buffer until the test timed out; and `acp-core` issues
*string* request ids, which a digit-matching `sed` silently did not echo back.

The opt-in pair reach the published catalogue and a real release host, and prove the one thing no
offline test can: that the digests third parties publish match the bytes they serve.

**Multi-runtime smoke** — `samples/smoke-app` with all three adapters, the registry runtime, the
Spring AI adapter and actuator on one classpath. M2's completion test, now run a fourth time against
`gemini` — an agent with no adapter — and printing the negotiated ACP version and the turn and tool
call timers at the end.

**The served transport (3)** — `GooseServeTests`, live against a supervised `goose serve`: the
handshake over a socket that needs a generated secret in a header, a named session keeping context
across turns, and the server stopping when the client that started it closes. Separate from the
contract suite on purpose, because everything specific to this path is below the protocol and
invisible to a scripted agent.

Still planned:

1. **CI that installs all three** from the registry manifest, so the live suites run somewhere other
   than a developer machine. The registry runtime now makes that a few lines rather than a script.
2. **A concurrency test for the pool against real agents** — `max-processes: 2` with two
   conversations in flight. The fake-connection tests prove the routing; what they cannot prove is
   that two agent processes on one machine stay out of each other's way.
3. **A registry agent in the conformance suite.** `AgentRuntimeContract` runs against the three
   adapters; running it against `RegistryAgentRuntime` would say how much of the abstraction survives
   with no adapter knowledge at all, which is a more honest measure of "any ACP agent" than a
   handshake. It needs a registry agent this machine has credentials for, which is the obstacle.

## Repository layout

```
acp-spring/
├── pom.xml                                  # reactor
├── README.md
├── docs/design.md                           # this document
├── acp-spring-core/
├── acp-spring-runtime-goose/
├── acp-spring-runtime-codex/
├── acp-spring-runtime-opencode/
├── acp-spring-runtime-registry/
├── acp-spring-boot-autoconfigure/
├── acp-spring-boot-starter/
├── acp-spring-ai/
├── acp-spring-test/
└── samples/smoke-app/
```
