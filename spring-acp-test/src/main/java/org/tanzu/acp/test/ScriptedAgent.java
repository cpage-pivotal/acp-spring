package org.tanzu.acp.test;

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
 * An ACP agent that exists only to be talked to: no subprocess, no model, and every answer written
 * down in advance.
 *
 * <p>It speaks the wire format rather than the SDK's records — responses are built as maps and go
 * out as raw JSON-RPC results — and that is the whole point of it. Tests that mock
 * {@code AcpAsyncClient} prove the turn logic but not the format, and the two gaps this library
 * works around are both format gaps: {@code configOptions} that the SDK's {@code NewSessionResponse}
 * drops, and a {@code sessionUpdate} discriminator its {@code SessionUpdate} hierarchy has no record
 * for. Neither is reachable from a mock. Both are reachable from here.
 *
 * <p>It can also be told to behave badly in the specific ways real agents do — {@link
 * Builder#acceptsUnknownValues(boolean)} reproduces goose 1.51 storing a model id it has never heard
 * of — so the core's defenses can be tested without waiting for a vendor to ship the bug again.
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

	private final List<AcpSchema.NewSessionRequest> newSessions = new CopyOnWriteArrayList<>();

	private final AtomicInteger cancellations = new AtomicInteger();

	private final AtomicInteger sessionIds = new AtomicInteger();

	/** Mutable: a set_config_option changes the current value the way a real agent's would. */
	private final Map<String, Select> selects = new LinkedHashMap<>();

	private final Builder script;

	private ScriptedAgent(Builder script) {
		this.script = script;
		script.selects.forEach((id, select) -> selects.put(id, select.copy()));
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

	public int cancellations() {
		return cancellations.get();
	}

	public int prompts() {
		return prompts.size();
	}

	@Override
	public void close() {
		pair.closeGracefully().block(Duration.ofSeconds(5));
	}

	// --- dispatch ---------------------------------------------------------------------------

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
			case "initialize" -> Mono.just(initialize());
			case "session/new" -> Mono.just(newSession(request));
			case "session/set_config_option" -> Mono.just(setConfigOption(request));
			case "session/set_mode" -> Mono.just(setMode(request));
			case "session/set_model" -> Mono.just(setModel(request));
			case "providers/list" -> Mono.just(listProviders());
			case "providers/set" -> Mono.just(setProvider(request));
			case "session/prompt" -> prompt(request);
			case "session/close" -> Mono.just(Map.of());
			default -> throw new Rejected(-32601, PROTOCOL_ERROR_UNKNOWN_METHOD + ": " + request.method());
		};
	}

	private Map<String, Object> initialize() {
		Map<String, Object> capabilities = new LinkedHashMap<>();
		capabilities.put("loadSession", false);
		if (script.providers) {
			capabilities.put("providers", Map.of());
		}
		return Map.of("protocolVersion", 1, "agentCapabilities", capabilities, "agentInfo",
				Map.of("name", script.name, "version", script.version));
	}

	private Map<String, Object> newSession(AcpSchema.JSONRPCRequest request) {
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
	 * <p>Notifications are emitted before the response is returned, which is the ordering a real
	 * agent produces and the one the turn demultiplexer has to survive.
	 */
	private Mono<Map<String, Object>> prompt(AcpSchema.JSONRPCRequest request) {
		prompts.add(request.params());
		String sessionId = String.valueOf(asMap(request.params()).get("sessionId"));

		Mono<Void> updates = Mono.fromRunnable(() -> {
			if (script.emitsUnknownUpdate) {
				// The shape that makes acp-core log an ERROR per occurrence against a live goose.
				notify(sessionId, Map.of("sessionUpdate", "session_info_update", "info",
						Map.of("title", "scripted")));
			}
			script.toolCalls.forEach(tool -> {
				notify(sessionId, Map.of("sessionUpdate", "tool_call", "toolCallId", tool, "title", tool, "kind",
						"other", "status", "pending", "rawInput", Map.of("toolName", tool)));
				notify(sessionId, Map.of("sessionUpdate", "tool_call_update", "toolCallId", tool, "status",
						"completed"));
			});
			script.reply.forEach(text -> notify(sessionId,
					Map.of("sessionUpdate", "agent_message_chunk", "content", Map.of("type", "text", "text", text))));
		});

		Mono<Map<String, Object>> answer = Mono
				.just(Map.<String, Object>of("stopReason", script.stopReason));
		return script.promptDelay == null ? updates.then(answer)
				: updates.then(answer).delayElement(script.promptDelay);
	}

	private void notify(String sessionId, Map<String, Object> update) {
		agent.sendMessage(new AcpSchema.JSONRPCNotification("2.0", "session/update",
				Map.of("sessionId", sessionId, "update", update))).subscribe();
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

	/** Writes the script. Every default is the least surprising thing an agent could do. */
	public static final class Builder {

		private final Map<String, Select> selects = new LinkedHashMap<>();

		private final List<String> modes = new ArrayList<>();

		private final List<String> models = new ArrayList<>();

		private final List<String> toolCalls = new ArrayList<>();

		private final List<String> providerIds = new ArrayList<>();

		private List<String> reply = List.of("ok");

		private String name = "scripted";

		private String version = "0.0.0";

		private String stopReason = "end_turn";

		private boolean providers;

		private boolean acceptsUnknownValues;

		private boolean emitsUnknownUpdate;

		private Duration promptDelay;

		/** A {@code select} config option with an explicit set of legal values. */
		public Builder select(String id, String category, String current, String... values) {
			selects.put(id, new Select(id, category, current, List.of(values)));
			return this;
		}

		/** The legacy {@code modes} state, for testing the {@code session/set_mode} fallback. */
		public Builder modes(String... ids) {
			modes.clear();
			modes.addAll(List.of(ids));
			return this;
		}

		/** The legacy {@code models} state, for testing the {@code session/set_model} fallback. */
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

		/** The assistant text, one {@code agent_message_chunk} per argument. */
		public Builder reply(String... chunks) {
			reply = List.of(chunks);
			return this;
		}

		/** Emit a tool call and its completion, with {@code rawInput.toolName} set to the name. */
		public Builder toolCall(String name) {
			toolCalls.add(name);
			return this;
		}

		/**
		 * Store any value for any advertised option instead of rejecting unknown ones — goose 1.51's
		 * behavior for {@code model}, and the reason the resolver validates before it sets.
		 */
		public Builder acceptsUnknownValues(boolean accepts) {
			acceptsUnknownValues = accepts;
			return this;
		}

		/** Emit a {@code session_info_update}, which this SDK version has no record for. */
		public Builder emitsUnknownUpdate(boolean emits) {
			emitsUnknownUpdate = emits;
			return this;
		}

		/** Hold the prompt open, so a test can cancel a turn that is genuinely in flight. */
		public Builder promptDelay(Duration delay) {
			promptDelay = delay;
			return this;
		}

		public Builder stopReason(String reason) {
			stopReason = reason;
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
