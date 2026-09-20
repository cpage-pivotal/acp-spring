package org.tanzu.acp.executor;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.tanzu.acp.client.AgentClient;
import org.tanzu.acp.config.AgentOptions;
import org.tanzu.acp.event.AgentEvent;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@link AgentExecutor} over an {@link AgentClient}.
 *
 * <p>A thin adapter on purpose. Everything it does — sessions, turns, the one-terminal-event
 * invariant, cancellation on close — is the client's; what this adds is the older shape of the
 * calls and the older shape of the output.
 */
public final class DefaultAgentExecutor implements AgentExecutor {

	private final AgentClient client;

	private final LegacyEventFormat format;

	public DefaultAgentExecutor(AgentClient client) {
		this.client = client;
		this.format = new LegacyEventFormat(client.runtimeId());
	}

	@Override
	public String execute(String prompt) {
		return execute(prompt, AgentOptions.none());
	}

	@Override
	public String execute(String prompt, AgentOptions options) {
		return client.prompt(require(prompt)).options(options).call().content();
	}

	@Override
	public CompletableFuture<String> executeAsync(String prompt) {
		return executeAsync(prompt, AgentOptions.none());
	}

	@Override
	public CompletableFuture<String> executeAsync(String prompt, AgentOptions options) {
		// The blocking call is real work on a real thread; boundedElastic is where that belongs.
		return Mono.fromSupplier(() -> execute(prompt, options)).subscribeOn(Schedulers.boundedElastic()).toFuture();
	}

	@Override
	public Stream<String> executeStreaming(String prompt) {
		return executeStreaming(prompt, AgentOptions.none());
	}

	@Override
	public Stream<String> executeStreaming(String prompt, AgentOptions options) {
		return text(client.prompt(require(prompt)).options(options).stream().events());
	}

	@Override
	public boolean isAvailable() {
		return client.isAlive();
	}

	@Override
	public String getVersion() {
		return client.agentInfo().map(info -> info.version()).orElse(null);
	}

	@Override
	public String executeInSession(String sessionName, String prompt, boolean resume) {
		return executeInSession(sessionName, prompt, resume, AgentOptions.none());
	}

	@Override
	public String executeInSession(String sessionName, String prompt, boolean resume, AgentOptions options) {
		return client.prompt().session(sessionName).user(require(prompt)).options(options).call().content();
	}

	@Override
	public Stream<String> executeInSessionStreaming(String sessionName, String prompt, boolean resume) {
		return executeInSessionStreaming(sessionName, prompt, resume, AgentOptions.none());
	}

	@Override
	public Stream<String> executeInSessionStreaming(String sessionName, String prompt, boolean resume,
			AgentOptions options) {
		return text(client.prompt().session(sessionName).user(require(prompt)).options(options).stream().events());
	}

	@Override
	public Stream<String> executeInSessionStreamingJson(String sessionName, String prompt, boolean resume) {
		return executeInSessionStreamingJson(sessionName, prompt, resume, AgentOptions.none());
	}

	@Override
	public Stream<String> executeInSessionStreamingJson(String sessionName, String prompt, boolean resume,
			AgentOptions options) {
		return client.prompt().session(sessionName).user(require(prompt)).options(options).stream().events()
				.flatMapIterable(format::render).toStream();
	}

	/**
	 * Assistant text only, one line per chunk.
	 *
	 * <p>{@code Flux.toStream} is what makes closing the stream cancel the turn: the returned
	 * stream holds the subscription, and its {@code close} disposes it, which reaches
	 * {@code AgentTurn} as consumer cancellation and sends {@code session/cancel}.
	 */
	private Stream<String> text(Flux<AgentEvent> events) {
		return events.filter(AgentEvent.Text.class::isInstance).map(AgentEvent.Text.class::cast)
				.map(AgentEvent.Text::text).toStream();
	}

	private static String require(String prompt) {
		if (prompt == null || prompt.isBlank()) {
			throw new IllegalArgumentException("prompt must not be blank");
		}
		return prompt;
	}
}
