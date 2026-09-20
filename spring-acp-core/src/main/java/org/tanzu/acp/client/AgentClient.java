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

	/** What the agent called itself during {@code initialize}, when it said. */
	java.util.Optional<AgentInfo> agentInfo();

	/**
	 * The ACP version this connection settled on.
	 *
	 * <p>Not simply what the agent answered: an agent can answer a version nobody offered — goose
	 * 1.51 echoes whatever it is given, including versions that do not exist — so this is the result
	 * of reconciling the offer with the answer. See {@code AcpProtocol}.
	 */
	default int protocolVersion() {
		return org.tanzu.acp.protocol.AcpProtocol.V1;
	}

	/**
	 * Whether this connection is still usable.
	 *
	 * <p>Optimistic by contract: a transport that cannot tell answers {@code true}, because the
	 * alternative — reporting "possibly dead" for every stdio agent — would make the answer useless
	 * to the one caller that needs it. What it does promise is that {@code false} is never wrong.
	 */
	default boolean isAlive() {
		return true;
	}

	/**
	 * Closes the named sessions that have been idle longer than the configured TTL.
	 *
	 * <p>On the interface rather than hidden in a lifecycle bean because a session costs memory on
	 * the agent for as long as it is open, and an application that knows its own quiet periods can
	 * reclaim them sooner than a timer would. A pooled client sweeps on a schedule of its own, so
	 * most applications never call this.
	 */
	default void evictIdleSessions() {
	}

	/**
	 * The conversations this agent has, as opposed to the turns run in them: list, load, resume,
	 * delete, close.
	 *
	 * <p>Every one of them is optional in ACP and the runtimes disagree about which they implement,
	 * so ask {@code sessions().supports(...)} before spending a round trip on finding out.
	 */
	org.tanzu.acp.session.AgentSessions sessions();

	/**
	 * The named session, once a turn has opened it.
	 *
	 * <p>The way to find out what the negotiated tier actually achieved: {@code AgentSession
	 * .configuration()} says, per option, whether the request was applied and by which mechanism.
	 * With {@code on-unsupported: warn} that is the difference between the model an application asked
	 * for and the model it is talking to.
	 */
	java.util.Optional<org.tanzu.acp.session.AgentSession> session(String name);

	/**
	 * Opens the named session now, or returns the one already open, without prompting.
	 *
	 * <p>A turn does this for itself, so this is for the case where the answer is wanted before the
	 * question: what the agent advertises, and what the negotiated tier managed to apply, are both
	 * known as soon as the session exists. Also the only way to find out that a requested model is
	 * unsupported without paying for a turn to discover it.
	 */
	org.tanzu.acp.session.AgentSession openSession(String name);

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
