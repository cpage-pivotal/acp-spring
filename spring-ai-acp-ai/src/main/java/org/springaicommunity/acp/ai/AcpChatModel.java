package org.springaicommunity.acp.ai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springaicommunity.acp.client.AgentClient;
import org.springaicommunity.acp.event.AgentEvent;

import reactor.core.publisher.Flux;

/**
 * An ACP coding agent behind Spring AI's {@link ChatModel}.
 *
 * <p>
 * Useful because it puts an agent where a Spring AI application already has a seam —
 * advisors, {@code ChatClient}, evaluators, the observation stack — without that
 * application learning a second programming model. It is a real adapter rather than a
 * wrapper, because the two models disagree about one important thing, and pretending
 * otherwise would produce an object that looks right and costs twice as much per call.
 *
 * <h2>The disagreement: who owns the conversation</h2>
 *
 * <p>
 * A chat completion is stateless. Spring AI sends the whole history every time, which is
 * why {@code ChatMemory} exists and why a {@code Prompt} carries a list of messages. An
 * ACP session is stateful: the conversation lives inside the agent process, which is also
 * holding a file tree, a plan and a set of tool results that no message list can carry.
 *
 * <p>
 * So this adapter reads {@link AcpChatOptions#getSession()} and behaves differently:
 *
 * <ul>
 * <li><strong>No session named.</strong> Every call is a throwaway ACP session that knows
 * nothing, so the whole prompt is sent, rendered with role labels. This is the closest
 * thing to chat completion semantics and the right default for an application using
 * {@code ChatMemory} to manage history itself.</li>
 * <li><strong>A session named.</strong> The agent already has everything up to its own
 * last reply, so only the messages <em>after</em> the last assistant message are sent.
 * Sending the history again would make the agent read its own previous answers as new
 * instructions, and would bill for the whole conversation on every turn.</li>
 * </ul>
 *
 * <p>
 * Combining a named session with a {@code ChatMemory} advisor therefore means two
 * memories of the same conversation, and the agent's is the one that matters. Pick one.
 *
 * <h2>Options that do not exist over here</h2>
 *
 * <p>
 * {@code temperature}, {@code topP}, {@code topK}, {@code maxTokens},
 * {@code stopSequences} and the penalties have no ACP equivalent: the agent owns the
 * inference call and the protocol gives a client no way to reach into it. They are logged
 * once, by name, rather than dropped quietly — an application that set
 * {@code temperature: 0} for determinism deserves to be told it did not get it.
 *
 * <p>
 * Tool calling is the same story from the other side. The agent has its own tools and
 * runs them itself; {@code ToolCallbacks} registered on the Spring AI side are not
 * visible to it. Tool activity surfaces as {@code ToolCallStarted} and
 * {@code ToolCallUpdated} on the native {@link AgentClient} API, which is where an
 * application that cares about it should be.
 */
public class AcpChatModel implements ChatModel {

	private static final Logger logger = LoggerFactory.getLogger(AcpChatModel.class);

	private final AgentClient client;

	private final AcpChatOptions defaultOptions;

	/** So an ignored option is named once per model, not once per call. */
	private final Set<String> reportedUnsupported = java.util.concurrent.ConcurrentHashMap.newKeySet();

	public AcpChatModel(AgentClient client) {
		this(client, AcpChatOptions.builder().build());
	}

	public AcpChatModel(AgentClient client, AcpChatOptions defaultOptions) {
		this.client = client;
		this.defaultOptions = defaultOptions == null ? AcpChatOptions.builder().build() : defaultOptions;
	}

	@Override
	public ChatOptions getOptions() {
		return defaultOptions;
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		AcpChatOptions options = options(prompt);
		AgentClient.AgentResponse response = spec(prompt, options).call();
		return new ChatResponse(
				List.of(new Generation(new AssistantMessage(response.content()),
						ChatGenerationMetadata.builder().finishReason(finishReason(response.completion())).build())),
				metadata(options, null));
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		AcpChatOptions options = options(prompt);
		return Flux.defer(() -> {
			// The last usage the agent reported, so the terminal chunk can carry it.
			AtomicReference<AgentEvent.UsageUpdated> usage = new AtomicReference<>();
			return spec(prompt, options).stream().events().handle((event, sink) -> {
				switch (event) {
					case AgentEvent.Text text -> sink.next(chunk(text.text(), null, options, usage.get()));
					case AgentEvent.UsageUpdated updated -> usage.set(updated);
					case AgentEvent.Completed completed ->
						sink.next(chunk("", finishReason(completed), options, usage.get()));
					case AgentEvent.Failed failed -> sink.error(failed.cause());
					default -> {
						// Thoughts, tool calls and plans are agent activity, not
						// assistant output.
						// They are on the AgentClient API, which is where an application
						// that wants
						// them should be looking.
					}
				}
			});
		});
	}

	private ChatResponse chunk(String text, String finishReason, AcpChatOptions options,
			AgentEvent.UsageUpdated usage) {
		ChatGenerationMetadata generationMetadata = finishReason == null ? ChatGenerationMetadata.NULL
				: ChatGenerationMetadata.builder().finishReason(finishReason).build();
		return new ChatResponse(List.of(new Generation(new AssistantMessage(text), generationMetadata)),
				metadata(options, usage));
	}

	private AgentClient.PromptSpec spec(Prompt prompt, AcpChatOptions options) {
		reportUnsupported(prompt.getOptions());

		AgentClient.PromptSpec spec = client.prompt();
		if (options.getSession() != null) {
			spec = spec.session(options.getSession());
		}
		spec.user(render(prompt, options));
		return spec.options(o -> {
			if (options.getModel() != null) {
				o.model(options.getModel());
			}
			if (options.getMode() != null) {
				o.mode(options.getMode());
			}
			if (options.getTimeout() != null) {
				o.timeout(options.getTimeout());
			}
		});
	}

	/**
	 * Turns the prompt's messages into the text this turn sends.
	 *
	 * <p>
	 * Where the two models are reconciled. See the class documentation for the argument;
	 * this is the three lines that implement it.
	 */
	private String render(Prompt prompt, AcpChatOptions options) {
		List<Message> messages = prompt.getInstructions();
		boolean continuing = options.getSession() != null && client.session(options.getSession()).isPresent();
		List<Message> toSend = continuing ? sinceLastAssistantMessage(messages) : messages;
		if (toSend.isEmpty()) {
			throw new IllegalArgumentException(
					"Prompt for session '" + options.getSession() + "' adds nothing the agent has not already seen");
		}
		return toSend.stream()
			.map(AcpChatModel::render)
			.filter(text -> !text.isBlank())
			.reduce((a, b) -> a + "\n\n" + b)
			.orElse("");
	}

	/**
	 * The tail of the conversation the agent has not heard yet.
	 *
	 * <p>
	 * The agent's own last reply is the watermark, because it is the last thing both
	 * sides agree happened. Anything before it is history the agent is holding; anything
	 * after it is new.
	 */
	private static List<Message> sinceLastAssistantMessage(List<Message> messages) {
		int lastAssistant = -1;
		for (int i = messages.size() - 1; i >= 0; i--) {
			if (messages.get(i).getMessageType() == MessageType.ASSISTANT) {
				lastAssistant = i;
				break;
			}
		}
		return lastAssistant < 0 ? messages : new ArrayList<>(messages.subList(lastAssistant + 1, messages.size()));
	}

	/**
	 * One message as text.
	 *
	 * <p>
	 * A user message goes over bare, because that is what the agent expects to be
	 * prompted with. Everything else is labelled, because an agent reading a transcript
	 * needs to know which lines were its own — and ACP's prompt is content blocks with no
	 * roles to put them in.
	 */
	private static String render(Message message) {
		String text = message.getText() == null ? "" : message.getText();
		return switch (message.getMessageType()) {
			case USER -> text;
			case SYSTEM -> text.isBlank() ? "" : "[system]\n" + text;
			case ASSISTANT -> text.isBlank() ? "" : "[assistant]\n" + text;
			case TOOL -> text.isBlank() ? "" : "[tool result]\n" + text;
		};
	}

	private AcpChatOptions options(Prompt prompt) {
		ChatOptions requested = prompt.getOptions();
		if (requested == null) {
			return defaultOptions;
		}
		if (requested instanceof AcpChatOptions acp) {
			return acp.merge(defaultOptions);
		}
		// A plain ChatOptions can still carry the one option that means something here.
		return AcpChatOptions.builder()
			.model(requested.getModel() == null ? defaultOptions.getModel() : requested.getModel())
			.session(defaultOptions.getSession())
			.mode(defaultOptions.getMode())
			.timeout(defaultOptions.getTimeout())
			.build();
	}

	/** Names, once each, the options this application set and this model cannot honor. */
	private void reportUnsupported(ChatOptions requested) {
		if (requested == null) {
			return;
		}
		Set<String> ignored = new LinkedHashSet<>();
		if (requested.getTemperature() != null) {
			ignored.add("temperature");
		}
		if (requested.getTopP() != null) {
			ignored.add("topP");
		}
		if (requested.getTopK() != null) {
			ignored.add("topK");
		}
		if (requested.getMaxTokens() != null) {
			ignored.add("maxTokens");
		}
		if (requested.getStopSequences() != null && !requested.getStopSequences().isEmpty()) {
			ignored.add("stopSequences");
		}
		if (requested.getFrequencyPenalty() != null) {
			ignored.add("frequencyPenalty");
		}
		if (requested.getPresencePenalty() != null) {
			ignored.add("presencePenalty");
		}
		ignored.stream()
			.filter(reportedUnsupported::add)
			.forEach(option -> logger.warn(
					"ChatOptions.{} was set but runtime '{}' cannot honor it: ACP gives a client no way to"
							+ " reach the agent's own inference call. The agent's default applies.",
					option, client.runtimeId()));
	}

	/**
	 * Why the turn ended, in the vocabulary Spring AI's other models use.
	 *
	 * <p>
	 * {@code END_TURN} becomes {@code STOP} and {@code MAX_TOKENS} becomes {@code LENGTH}
	 * because those are what an advisor or an evaluator written against OpenAI is looking
	 * for. The two ACP reasons with no equivalent keep their own names rather than being
	 * flattened into one of these.
	 */
	private static String finishReason(AgentEvent.Completed completed) {
		if (completed == null || completed.reason() == null) {
			return "STOP";
		}
		return switch (completed.reason()) {
			case END_TURN -> "STOP";
			case MAX_TOKENS -> "LENGTH";
			case REFUSAL -> "CONTENT_FILTER";
			case CANCELLED -> "CANCELLED";
			case MAX_TURN_REQUESTS -> "MAX_TURN_REQUESTS";
		};
	}

	/**
	 * Response metadata, including ACP usage — as key values rather than as a
	 * {@code Usage}.
	 *
	 * <p>
	 * Spring AI's {@code Usage} is prompt tokens and completion tokens. ACP's
	 * {@code usage_update} is context window consumed out of context window size,
	 * cumulative for the session, plus an optional cost. Those are different
	 * measurements, and putting one in the other's field would put a number that means
	 * "the whole conversation so far" where every dashboard reads "this call".
	 */
	private ChatResponseMetadata metadata(AcpChatOptions options, AgentEvent.UsageUpdated usage) {
		ChatResponseMetadata.Builder builder = ChatResponseMetadata.builder()
			.model(modelOf(options))
			.keyValue("acp.runtime", client.runtimeId());
		if (options.getSession() != null) {
			builder = builder.keyValue("acp.session", options.getSession());
		}
		if (usage != null) {
			builder = builder.keyValue("acp.context.used", usage.contextUsed())
				.keyValue("acp.context.size", usage.contextSize());
			if (usage.costAmount() != null) {
				builder = builder.keyValue("acp.cost.amount", usage.costAmount())
					.keyValue("acp.cost.currency", usage.costCurrency() == null ? "" : usage.costCurrency());
			}
		}
		return builder.build();
	}

	/**
	 * The model the session really has, where the negotiated tier knows it; else what was
	 * asked.
	 */
	private String modelOf(AcpChatOptions options) {
		if (options.getSession() != null) {
			String applied = client.session(options.getSession())
				.flatMap(session -> session.configuration()
					.applied(org.springaicommunity.acp.runtime.AgentRuntime.PortableOption.MODEL))
				.orElse(null);
			if (applied != null) {
				return applied;
			}
		}
		return options.getModel() == null ? client.runtimeId().toLowerCase(Locale.ROOT) : options.getModel();
	}

}
