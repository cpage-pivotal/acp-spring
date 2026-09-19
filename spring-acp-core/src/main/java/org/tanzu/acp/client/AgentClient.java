package org.tanzu.acp.client;

import java.util.function.Consumer;

import org.tanzu.acp.config.AgentOptions;
import org.tanzu.acp.event.AgentEvent;

import reactor.core.publisher.Flux;

/**
 * Sends requests to an ACP agent, whichever agent that happens to be.
 *
 * <pre>{@code
 * String answer = client.prompt()
 *         .session("review-123")
 *         .user("Inspect this change")
 *         .call()
 *         .content();
 *
 * Flux<AgentEvent> events = client.prompt()
 *         .user("Now focus on security")
 *         .stream()
 *         .events();
 * }</pre>
 *
 * <p>A prompt without an explicit {@code session} runs in a throwaway session that is closed when
 * the turn ends.
 */
public interface AgentClient extends AutoCloseable {

	/** Starts a prompt. Nothing is sent until {@code call()} or {@code stream()}. */
	PromptSpec prompt();

	/** Shorthand for {@code prompt().user(text)}. */
	default PromptSpec prompt(String text) {
		return prompt().user(text);
	}

	/** The id of the runtime behind this client, e.g. {@code goose}. */
	String runtimeId();

	@Override
	void close();

	/** Builds one turn. */
	interface PromptSpec {

		/** Runs in a named, reusable conversation. Repeat calls with the same name keep context. */
		PromptSpec session(String name);

		/** Appends user text to this turn's prompt. */
		PromptSpec user(String text);

		/** Per-request overrides. */
		PromptSpec options(AgentOptions options);

		/** Per-request overrides, built inline. */
		PromptSpec options(Consumer<AgentOptions.Builder> customizer);

		/** Runs the turn and blocks for the assistant text. */
		AgentResponse call();

		/** Runs the turn and streams it. Cancelling the subscription cancels the turn. */
		AgentStream stream();
	}

	/** A finished turn. */
	interface AgentResponse {

		/** The assistant text, with thoughts and tool activity excluded. */
		String content();

		/** Why the turn ended. */
		AgentEvent.Completed completion();
	}

	/** A turn in progress. */
	interface AgentStream {

		/** Every event, ending with exactly one terminal event. */
		Flux<AgentEvent> events();

		/** Assistant text only. */
		default Flux<String> content() {
			return events().filter(AgentEvent.Text.class::isInstance).map(AgentEvent.Text.class::cast)
					.map(AgentEvent.Text::text);
		}
	}
}
