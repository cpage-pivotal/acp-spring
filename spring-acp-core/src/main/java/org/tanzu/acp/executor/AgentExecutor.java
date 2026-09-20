package org.tanzu.acp.executor;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.tanzu.acp.config.AgentOptions;

/**
 * The {@code GooseExecutor} shape, vendor-neutral, for applications moving off
 * {@code goose-buildpack}.
 *
 * <p>Signature-for-signature what that interface had, so the migration is a rename and a change of
 * option type rather than a rewrite: {@code GooseExecutor} becomes {@code AgentExecutor} and
 * {@code GooseOptions} becomes {@link AgentOptions}. What an application gets in return for the
 * rename is that {@code spring.acp.runtime} now decides which agent answers.
 *
 * <p>Two methods did not come across, both because they were Goose's rather than the protocol's.
 * {@code getConfiguration()} returned a parsed {@code ~/.config/goose/config.yaml}, which no other
 * agent has; what an application actually wanted from it — which model am I really using — is
 * {@code AgentClient.openSession(name).configuration()}, and it is the negotiated truth rather
 * than a file. {@code executeInSessionStreamingJson} did come across, because its event
 * vocabulary is what existing consumers parse.
 *
 * <p>New code should use {@code AgentClient} instead. This interface exists to make a migration
 * cheap, not to be the nicer of the two: it is blocking, it returns strings, and it cannot express
 * a tool call or a plan.
 */
public interface AgentExecutor {

	/** Runs one prompt in a throwaway session and returns the assistant's text. */
	String execute(String prompt);

	String execute(String prompt, AgentOptions options);

	CompletableFuture<String> executeAsync(String prompt);

	CompletableFuture<String> executeAsync(String prompt, AgentOptions options);

	/**
	 * Streams the assistant's text as it arrives.
	 *
	 * <p>The stream owns a running turn, so it must be closed — closing it early cancels the turn
	 * on the agent rather than leaving it to finish unobserved.
	 *
	 * <pre>{@code
	 * try (Stream<String> lines = executor.executeStreaming("analyse this")) {
	 *     lines.forEach(System.out::println);
	 * }
	 * }</pre>
	 */
	Stream<String> executeStreaming(String prompt);

	Stream<String> executeStreaming(String prompt, AgentOptions options);

	/** Whether the agent is connected and usable. */
	boolean isAvailable();

	/** The agent's own version string, or null if it did not give one. */
	String getVersion();

	/**
	 * Runs a prompt in a named session.
	 *
	 * <p>{@code resume} is accepted for source compatibility and deliberately not honoured as
	 * written: a known name always continues its conversation and an unknown one always starts
	 * one. That was already true in the wrapper, for the reason that survives the move — a caller
	 * retrying its first message sends {@code resume=false} a second time, and taking it literally
	 * would throw away the context the first attempt established.
	 */
	String executeInSession(String sessionName, String prompt, boolean resume);

	String executeInSession(String sessionName, String prompt, boolean resume, AgentOptions options);

	Stream<String> executeInSessionStreaming(String sessionName, String prompt, boolean resume);

	Stream<String> executeInSessionStreaming(String sessionName, String prompt, boolean resume, AgentOptions options);

	/**
	 * Streams a named session's turn as the newline-delimited JSON the buildpack's consumers parse:
	 * {@code message}, {@code notification} and a terminal {@code complete}.
	 */
	Stream<String> executeInSessionStreamingJson(String sessionName, String prompt, boolean resume);

	Stream<String> executeInSessionStreamingJson(String sessionName, String prompt, boolean resume,
			AgentOptions options);
}
