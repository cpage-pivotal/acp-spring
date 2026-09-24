package org.springaicommunity.acp.test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;

import reactor.core.publisher.Mono;

/**
 * An ACP agent that exists only to be talked to: no subprocess, no model, and every
 * answer written down in advance.
 *
 * <p>
 * It speaks the wire format rather than the SDK's records — responses are built as maps
 * and go out as raw JSON-RPC results — and that is the whole point of it. Tests that mock
 * {@code AcpAsyncClient} prove the turn logic but not the format, and the two gaps this
 * library works around are both format gaps: {@code configOptions} that the SDK's
 * {@code NewSessionResponse} drops, and a {@code sessionUpdate} discriminator its
 * {@code SessionUpdate} hierarchy has no record for. Neither is reachable from a mock.
 * Both are reachable from here.
 *
 * <p>
 * It can also be told to behave badly in the specific ways real agents do —
 * {@link Builder#acceptsUnknownValues(boolean)} reproduces goose 1.51 storing a model id
 * it has never heard of — so the core's defenses can be tested without waiting for a
 * vendor to ship the bug again.
 *
 * <pre>{@code
 * try (ScriptedAgent agent = ScriptedAgent.builder()
 *         .select("model", "model", "gpt-5.4-mini", "gpt-5.4", "gpt-5.4-mini")
 *         .reply("hello")
 *         .build()) {
 *     AgentClient client = AgentClientFactory.connect(runtime, settings, agent.transport());
 * }
 * }</pre>
 */
public final class ScriptedAgent implements AutoCloseable {

	private static final String PROTOCOL_ERROR_UNKNOWN_METHOD = "Method not found";

	private final InMemoryTransportPair pair = InMemoryTransportPair.create();

	private final AcpAgentTransport agent = pair.agentTransport();

	private final List<String> methods = new CopyOnWriteArrayList<>();

	private final List<Object> prompts = new CopyOnWriteArrayList<>();

	private final List<Map<String, Object>> configSets = new CopyOnWriteArrayList<>();

	private final List<Map<String, Object>> providerSets = new CopyOnWriteArrayList<>();

	/** The method ids {@code authenticate} was called with, in order. */
	private final List<String> authentications = new CopyOnWriteArrayList<>();

	private volatile boolean authenticated;

	private final List<AcpSchema.NewSessionRequest> newSessions = new CopyOnWriteArrayList<>();

	private final AtomicInteger cancellations = new AtomicInteger();

	private final AtomicInteger sessionIds = new AtomicInteger();

	/**
	 * Mutable: a set_config_option changes the current value the way a real agent's
	 * would.
	 */
	private final Map<String, Select> selects = new LinkedHashMap<>();

	/** Mutable: session/delete removes from it, the way a real agent's storage would. */
	private final List<String> storedSessions = new CopyOnWriteArrayList<>();

	private final List<String> listed = new CopyOnWriteArrayList<>();

	private final List<String> loaded = new CopyOnWriteArrayList<>();

	private final List<List<Map<String, Object>>> attachedMcpServers = new CopyOnWriteArrayList<>();

	private final Builder script;

	private ScriptedAgent(Builder script) {
		this.script = script;
		script.selects.forEach((id, select) -> selects.put(id, select.copy()));
		storedSessions.addAll(script.storedSessions);
		agent.start(inbound -> inbound.flatMap(this::dispatch)).subscribe();
	}

	public static Builder builder() {
		return new Builder();
	}

	/** Hand this to {@code AgentClientFactory.connect}. */
	public AcpClientTransport transport() {
		return pair.clientTransport();
	}

	/** Every method the client called, in order. */
	public List<String> methods() {
		return List.copyOf(methods);
	}

	public List<AcpSchema.NewSessionRequest> newSessions() {
		return List.copyOf(newSessions);
	}

	/** Every {@code session/set_config_option} as {@code {configId, value}}. */
	public List<Map<String, Object>> configSets() {
		return List.copyOf(configSets);
	}

	public List<Map<String, Object>> providerSets() {
		return List.copyOf(providerSets);
	}

	/** Every {@code authenticate} method id, whether or not it was accepted. */
	public List<String> authentications() {
		return List.copyOf(authentications);
	}

	public int cancellations() {
		return cancellations.get();
	}

	public int prompts() {
		return prompts.size();
	}

	/** Session ids this agent still has stored, after any deletes. */
	public List<String> storedSessions() {
		return List.copyOf(storedSessions);
	}

	/** Session ids a client asked to load or resume, in order. */
	public List<String> loaded() {
		return List.copyOf(loaded);
	}

	/**
	 * The {@code mcpServers} each {@code session/load} or {@code session/resume} carried,
	 * raw, in the order they arrived — the re-declaration that has to be routed like a
	 * new session's.
	 */
	public List<List<Map<String, Object>>> attachedMcpServers() {
		return List.copyOf(attachedMcpServers);
	}

	@Override
	public void close() {
		pair.closeGracefully().block(Duration.ofSeconds(5));
	}

	// --- dispatch
	// ---------------------------------------------------------------------------

	private Mono<AcpSchema.JSONRPCMessage> dispatch(AcpSchema.JSONRPCMessage message) {
		if (message instanceof AcpSchema.JSONRPCNotification notification) {
			methods.add(notification.method());
			if ("session/cancel".equals(notification.method())) {
				cancellations.incrementAndGet();
			}
			return Mono.empty();
		}
		if (!(message instanceof AcpSchema.JSONRPCRequest request)) {
			return Mono.empty();
		}
		methods.add(request.method());
		try {
			return handle(request).<AcpSchema.JSONRPCMessage>map(result -> response(request, result))
				.switchIfEmpty(Mono.fromSupplier(() -> response(request, Map.of())));
		}
		catch (Rejected rejected) {
			return Mono.just(new AcpSchema.JSONRPCResponse("2.0", request.id(), null,
					new AcpSchema.JSONRPCError(rejected.code, rejected.getMessage(), null)));
		}
	}

	private Mono<Map<String, Object>> handle(AcpSchema.JSONRPCRequest request) {
		return switch (request.method()) {
			case "initialize" -> Mono.just(initialize(request));
			case "authenticate" -> Mono.just(authenticate(request));
			case "session/new" -> Mono.just(newSession(request));
			case "session/set_config_option" -> Mono.just(setConfigOption(request));
			case "session/set_mode" -> Mono.just(setMode(request));
			case "session/set_model" -> Mono.just(setModel(request));
			case "providers/list" -> Mono.just(listProviders());
			case "providers/set" -> Mono.just(setProvider(request));
			case "session/prompt" -> prompt(request);
			case "session/close" -> Mono.just(Map.of());
			case "session/list" -> Mono.just(listSessions());
			case "session/load" -> Mono.just(loadSession(request));
			case "session/resume" -> Mono.just(loadSession(request));
			case "session/delete" -> Mono.just(deleteSession(request));
			default -> throw new Rejected(-32601, PROTOCOL_ERROR_UNKNOWN_METHOD + ": " + request.method());
		};
	}

	private Map<String, Object> initialize(AcpSchema.JSONRPCRequest request) {
		Map<String, Object> capabilities = new LinkedHashMap<>();
		capabilities.put("loadSession", script.sessionOperations.contains("load"));
		if (script.providers) {
			capabilities.put("providers", Map.of());
		}
		Map<String, Object> sessionCapabilities = new LinkedHashMap<>();
		// ACP signals each of these by presence, so an absent key is how an agent says
		// "no".
		script.sessionOperations.stream()
			.filter(operation -> !operation.equals("load"))
			.forEach(operation -> sessionCapabilities.put(operation, Map.of()));
		if (!sessionCapabilities.isEmpty()) {
			capabilities.put("sessionCapabilities", sessionCapabilities);
		}
		return Map.of("protocolVersion", negotiatedVersion(request), "agentCapabilities", capabilities, "agentInfo",
				Map.of("name", script.name, "version", script.version), "authMethods",
				script.authMethods.stream().map(id -> Map.of("id", id, "name", id)).toList());
	}

	private Map<String, Object> authenticate(AcpSchema.JSONRPCRequest request) {
		String methodId = String.valueOf(asMap(request.params()).get("methodId"));
		authentications.add(methodId);
		if (!script.authMethods.contains(methodId)) {
			throw new Rejected(-32602, "Unknown auth method: " + methodId);
		}
		if (script.refusesAuthentication) {
			throw new Rejected(-32000, "Invalid credentials");
		}
		authenticated = true;
		return Map.of();
	}

	/**
	 * What this agent claims to speak, and the one place it can be told to lie about it.
	 *
	 * <p>
	 * {@link Builder#echoesProtocolVersion(boolean)} reproduces goose 1.51, which answers
	 * whatever version it is offered — including versions that do not exist. A client
	 * that believed the echo would think it was in a conversation whose wire format
	 * neither side is using, which is not something a mock of {@code AcpAsyncClient} can
	 * be made to demonstrate.
	 */
	private Object negotiatedVersion(AcpSchema.JSONRPCRequest request) {
		if (!script.echoesProtocolVersion) {
			return 1;
		}
		// Two shapes, because acp-test's in-memory transport hands the agent the client's
		// typed
		// record rather than the map a real socket would have produced.
		Object params = request.params();
		if (params instanceof AcpSchema.InitializeRequest typed) {
			return typed.protocolVersion();
		}
		if (params instanceof Map<?, ?> map && map.get("protocolVersion") instanceof Number offered) {
			return offered;
		}
		return 1;
	}

	/**
	 * Paginated when there is more than one, because following a cursor is the
	 * interesting case.
	 */
	private Map<String, Object> listSessions() {
		requireOperation("list");
		List<String> remaining = new java.util.ArrayList<>(storedSessions);
		remaining.removeAll(listed);
		if (remaining.isEmpty()) {
			return Map.of("sessions", List.of());
		}
		String next = remaining.get(0);
		listed.add(next);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("sessions", List.of(Map.of("sessionId", next, "cwd", "/tmp", "title", "stored " + next)));
		if (remaining.size() > 1) {
			result.put("nextCursor", "after-" + next);
		}
		return result;
	}

	/**
	 * Answers with the config options the agent advertises — which the SDK's
	 * {@code LoadSessionResponse} does not model, so a client that sees them here got
	 * them out of the raw payload. That is the whole reason this agent speaks JSON rather
	 * than records.
	 */
	private Map<String, Object> loadSession(AcpSchema.JSONRPCRequest request) {
		requireOperation(request.method().endsWith("resume") ? "resume" : "load");
		String sessionId = String.valueOf(asMap(request.params()).get("sessionId"));
		if (!storedSessions.isEmpty() && !storedSessions.contains(sessionId)) {
			throw new Rejected(-32602, "No such session: " + sessionId);
		}
		loaded.add(sessionId);
		Object servers = asMap(request.params()).get("mcpServers");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> declared = servers instanceof List<?> list ? (List<Map<String, Object>>) list
				: List.of();
		attachedMcpServers.add(List.copyOf(declared));
		Map<String, Object> result = new LinkedHashMap<>();
		if (!selects.isEmpty()) {
			result.put("configOptions", selects.values().stream().map(Select::toWire).toList());
		}
		return result;
	}

	private Map<String, Object> deleteSession(AcpSchema.JSONRPCRequest request) {
		requireOperation("delete");
		storedSessions.remove(String.valueOf(asMap(request.params()).get("sessionId")));
		return Map.of();
	}

	private void requireOperation(String operation) {
		if (!script.sessionOperations.contains(operation)) {
			throw new Rejected(-32601, PROTOCOL_ERROR_UNKNOWN_METHOD + ": session/" + operation);
		}
	}

	private Map<String, Object> newSession(AcpSchema.JSONRPCRequest request) {
		if (!script.authMethods.isEmpty() && !authenticated) {
			throw new Rejected(-32000, "Authentication required");
		}
		newSessions.add(agent.unmarshalFrom(request.params(), new com.agentclientprotocol.sdk.json.TypeRef<>() {
		}));
		String sessionId = "session-" + sessionIds.incrementAndGet();

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("sessionId", sessionId);
		if (!selects.isEmpty()) {
			result.put("configOptions", selects.values().stream().map(Select::toWire).toList());
		}
		if (!script.modes.isEmpty()) {
			result.put("modes", Map.of("currentModeId", script.modes.get(0), "availableModes",
					script.modes.stream().map(m -> Map.of("id", m, "name", m)).toList()));
		}
		if (!script.models.isEmpty()) {
			result.put("models", Map.of("currentModelId", script.models.get(0), "availableModels",
					script.models.stream().map(m -> Map.of("modelId", m, "name", m)).toList()));
		}
		return result;
	}

	private Map<String, Object> setConfigOption(AcpSchema.JSONRPCRequest request) {
		Map<String, Object> params = asMap(request.params());
		String configId = String.valueOf(params.get("configId"));
		String value = String.valueOf(params.get("value"));
		configSets.add(Map.of("configId", configId, "value", value));

		Select select = selects.get(configId);
		if (select == null) {
			throw new Rejected(-32602, "Unsupported config option: " + configId);
		}
		if (!select.values.contains(value) && !script.acceptsUnknownValues) {
			throw new Rejected(-32602, "Invalid params: " + configId + " has no value " + value);
		}
		select.current = value;
		return Map.of("configOptions", selects.values().stream().map(Select::toWire).toList());
	}

	private Map<String, Object> setMode(AcpSchema.JSONRPCRequest request) {
		String modeId = String.valueOf(asMap(request.params()).get("modeId"));
		if (!script.modes.contains(modeId)) {
			throw new Rejected(-32602, "Invalid mode: " + modeId);
		}
		return Map.of();
	}

	private Map<String, Object> setModel(AcpSchema.JSONRPCRequest request) {
		String modelId = String.valueOf(asMap(request.params()).get("modelId"));
		if (!script.models.contains(modelId)) {
			throw new Rejected(-32602, "Invalid model: " + modelId);
		}
		return Map.of();
	}

	private Map<String, Object> listProviders() {
		if (!script.providers) {
			throw new Rejected(-32601, PROTOCOL_ERROR_UNKNOWN_METHOD);
		}
		return Map.of("providers", script.providerIds.stream().map(id -> Map.of("id", id)).toList());
	}

	private Map<String, Object> setProvider(AcpSchema.JSONRPCRequest request) {
		if (!script.providers) {
			throw new Rejected(-32601, PROTOCOL_ERROR_UNKNOWN_METHOD);
		}
		Map<String, Object> params = asMap(request.params());
		providerSets.add(params);
		if (!script.providerIds.isEmpty() && !script.providerIds.contains(String.valueOf(params.get("id")))) {
			throw new Rejected(-32602, "Unknown provider: " + params.get("id"));
		}
		return Map.of();
	}

	/**
	 * Streams the scripted updates and then answers the prompt.
	 *
	 * <p>
	 * Notifications are emitted before the response is returned, which is the ordering a
	 * real agent produces and the one the turn demultiplexer has to survive.
	 */
	private Mono<Map<String, Object>> prompt(AcpSchema.JSONRPCRequest request) {
		prompts.add(request.params());
		String sessionId = String.valueOf(asMap(request.params()).get("sessionId"));

		Mono<Void> updates = Mono.fromRunnable(() -> {
			if (script.emitsUnknownUpdate) {
				// The shape that makes acp-core log an ERROR per occurrence against a
				// live goose.
				notify(sessionId, Map.of("sessionUpdate", "session_info_update", "info", Map.of("title", "scripted")));
			}
			script.toolCalls.forEach(tool -> {
				notify(sessionId, Map.of("sessionUpdate", "tool_call", "toolCallId", tool, "title", tool, "kind",
						"other", "status", "pending", "rawInput", Map.of("toolName", tool)));
				notify(sessionId,
						Map.of("sessionUpdate", "tool_call_update", "toolCallId", tool, "status", "completed"));
			});
			script.reply.forEach(text -> notify(sessionId,
					Map.of("sessionUpdate", "agent_message_chunk", "content", Map.of("type", "text", "text", text))));
		});

		Mono<Map<String, Object>> answer = Mono.just(Map.<String, Object>of("stopReason", script.stopReason));
		return script.promptDelay == null ? updates.then(answer)
				: updates.then(answer).delayElement(script.promptDelay);
	}

	private void notify(String sessionId, Map<String, Object> update) {
		agent
			.sendMessage(new AcpSchema.JSONRPCNotification("2.0", "session/update",
					Map.of("sessionId", sessionId, "update", update)))
			.subscribe();
	}

	private static AcpSchema.JSONRPCResponse response(AcpSchema.JSONRPCRequest request, Object result) {
		return new AcpSchema.JSONRPCResponse("2.0", request.id(), result, null);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> asMap(Object params) {
		if (params instanceof Map<?, ?> map) {
			return (Map<String, Object>) map;
		}
		return agent.unmarshalFrom(params, new com.agentclientprotocol.sdk.json.TypeRef<>() {
		});
	}

	/** A JSON-RPC error the agent chose to return, as opposed to one it caused. */
	private static final class Rejected extends RuntimeException {

		private final int code;

		private Rejected(int code, String message) {
			super(message);
			this.code = code;
		}

	}

	/** One {@code select} config option and its current value. */
	private static final class Select {

		private final String id;

		private final String category;

		private final List<String> values;

		private String current;

		private Select(String id, String category, String current, List<String> values) {
			this.id = id;
			this.category = category;
			this.current = current;
			this.values = List.copyOf(values);
		}

		private Select copy() {
			return new Select(id, category, current, values);
		}

		private Map<String, Object> toWire() {
			Map<String, Object> wire = new LinkedHashMap<>();
			wire.put("type", "select");
			wire.put("id", id);
			wire.put("name", id);
			if (category != null) {
				wire.put("category", category);
			}
			wire.put("currentValue", current);
			wire.put("options", values.stream().map(v -> Map.of("value", v, "name", v)).toList());
			return wire;
		}

	}

	/**
	 * Writes the script. Every default is the least surprising thing an agent could do.
	 */
	public static final class Builder {

		private final Map<String, Select> selects = new LinkedHashMap<>();

		private final List<String> modes = new ArrayList<>();

		private final List<String> models = new ArrayList<>();

		private final List<String> toolCalls = new ArrayList<>();

		private final List<String> providerIds = new ArrayList<>();

		private final List<String> sessionOperations = new ArrayList<>();

		private final List<String> storedSessions = new ArrayList<>();

		private final List<String> authMethods = new ArrayList<>();

		private List<String> reply = List.of("ok");

		private String name = "scripted";

		private String version = "0.0.0";

		private String stopReason = "end_turn";

		private boolean providers;

		private boolean acceptsUnknownValues;

		private boolean emitsUnknownUpdate;

		private boolean echoesProtocolVersion;

		private boolean refusesAuthentication;

		private Duration promptDelay;

		/** A {@code select} config option with an explicit set of legal values. */
		public Builder select(String id, String category, String current, String... values) {
			selects.put(id, new Select(id, category, current, List.of(values)));
			return this;
		}

		/**
		 * The legacy {@code modes} state, for testing the {@code session/set_mode}
		 * fallback.
		 */
		public Builder modes(String... ids) {
			modes.clear();
			modes.addAll(List.of(ids));
			return this;
		}

		/**
		 * The legacy {@code models} state, for testing the {@code session/set_model}
		 * fallback.
		 */
		public Builder models(String... ids) {
			models.clear();
			models.addAll(List.of(ids));
			return this;
		}

		/** Advertise the providers capability and accept {@code providers/set}. */
		public Builder providers(String... ids) {
			providers = true;
			providerIds.clear();
			providerIds.addAll(List.of(ids));
			return this;
		}

		/**
		 * Which optional session methods this agent implements: {@code list},
		 * {@code load}, {@code resume}, {@code delete}, {@code close}.
		 *
		 * <p>
		 * Named individually because that is how ACP works — each is its own capability
		 * and the three real runtimes implement different subsets — and because an agent
		 * that advertises nothing is the case a client most needs to handle without
		 * failing on the wire.
		 */
		public Builder sessionOperations(String... operations) {
			sessionOperations.clear();
			sessionOperations.addAll(List.of(operations));
			return this;
		}

		/**
		 * Session ids this agent has stored, for {@code session/list} and
		 * {@code session/load}.
		 */
		public Builder storedSessions(String... ids) {
			storedSessions.clear();
			storedSessions.addAll(List.of(ids));
			return this;
		}

		/** The assistant text, one {@code agent_message_chunk} per argument. */
		public Builder reply(String... chunks) {
			reply = List.of(chunks);
			return this;
		}

		/**
		 * Emit a tool call and its completion, with {@code rawInput.toolName} set to the
		 * name.
		 */
		public Builder toolCall(String name) {
			toolCalls.add(name);
			return this;
		}

		/**
		 * Store any value for any advertised option instead of rejecting unknown ones —
		 * goose 1.51's behavior for {@code model}, and the reason the resolver validates
		 * before it sets.
		 */
		public Builder acceptsUnknownValues(boolean accepts) {
			acceptsUnknownValues = accepts;
			return this;
		}

		/**
		 * Emit a {@code session_info_update}, which this SDK version has no record for.
		 */
		public Builder emitsUnknownUpdate(boolean emits) {
			emitsUnknownUpdate = emits;
			return this;
		}

		/**
		 * Hold the prompt open, so a test can cancel a turn that is genuinely in flight.
		 */
		public Builder promptDelay(Duration delay) {
			promptDelay = delay;
			return this;
		}

		public Builder stopReason(String reason) {
			stopReason = reason;
			return this;
		}

		/**
		 * Answer {@code initialize} with whatever version was offered, the way goose 1.51
		 * does.
		 */
		public Builder echoesProtocolVersion(boolean echoes) {
			echoesProtocolVersion = echoes;
			return this;
		}

		/**
		 * Offer these auth methods on {@code initialize}, and refuse {@code session/new}
		 * with "Authentication required" until one of them has been accepted — codex-acp
		 * 1.12's behavior with no stored login, whatever its environment holds.
		 */
		public Builder authMethods(String... ids) {
			authMethods.clear();
			authMethods.addAll(List.of(ids));
			return this;
		}

		/** Reject every {@code authenticate}, the way an agent handed a bad key does. */
		public Builder refusesAuthentication(boolean refuses) {
			refusesAuthentication = refuses;
			return this;
		}

		public Builder agentInfo(String name, String version) {
			this.name = name;
			this.version = version;
			return this;
		}

		public ScriptedAgent build() {
			return new ScriptedAgent(this);
		}

	}

}
