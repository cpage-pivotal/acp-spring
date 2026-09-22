package org.springaicommunity.acp.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Answers {@code fs/read_text_file} and {@code fs/write_text_file}, confined to the workspace.
 *
 * <p>Every path goes through {@link WorkspaceJail}, every operation is refused unless it was
 * explicitly lent, and the work happens on the bounded-elastic scheduler because file I/O on the
 * transport's inbound thread would stall every other message the agent is sending — including the
 * turn's own event stream.
 */
public final class WorkspaceFileSystem {

	private static final Logger logger = LoggerFactory.getLogger(WorkspaceFileSystem.class);

	private final WorkspaceJail jail;

	private final FileSystemAccess access;

	public WorkspaceFileSystem(Path workspace, FileSystemAccess access) {
		this.jail = WorkspaceJail.around(workspace);
		this.access = access == null ? FileSystemAccess.none() : access;
	}

	public Mono<AcpSchema.ReadTextFileResponse> read(AcpSchema.ReadTextFileRequest request) {
		return Mono.fromCallable(() -> {
			if (!access.read()) {
				throw new WorkspaceAccessException("This client does not lend the agent file reads");
			}
			Path file = jail.existing(request.path());
			if (!Files.isRegularFile(file)) {
				throw new WorkspaceAccessException("'" + request.path() + "' is not a regular file");
			}
			long size = Files.size(file);
			if (size > FileSystemAccess.MAX_READ_BYTES) {
				throw new WorkspaceAccessException("'" + request.path() + "' is " + size
						+ " bytes, over the " + FileSystemAccess.MAX_READ_BYTES + " byte read limit");
			}
			logger.debug("Reading {} for the agent", file);
			return new AcpSchema.ReadTextFileResponse(slice(file, request.line(), request.limit()));
		}).subscribeOn(Schedulers.boundedElastic());
	}

	public Mono<AcpSchema.WriteTextFileResponse> write(AcpSchema.WriteTextFileRequest request) {
		return Mono.fromCallable(() -> {
			if (!access.write()) {
				throw new WorkspaceAccessException("This client does not lend the agent file writes");
			}
			Path file = jail.forWriting(request.path());
			logger.debug("Writing {} for the agent", file);
			Files.writeString(file, request.content() == null ? "" : request.content(), StandardCharsets.UTF_8);
			return new AcpSchema.WriteTextFileResponse();
		}).subscribeOn(Schedulers.boundedElastic());
	}

	/**
	 * The whole file, or the window {@code line} and {@code limit} asked for.
	 *
	 * <p>{@code line} is 1-based in ACP and a window that starts past the end of the file is an
	 * empty answer rather than an error — an agent paging through a file it is also editing will
	 * ask for one eventually, and failing the request would turn a race into a turn-ending error.
	 */
	private String slice(Path file, Integer line, Integer limit) throws IOException {
		if (line == null && limit == null) {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		int from = line == null ? 1 : Math.max(line, 1);
		try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
			Stream<String> window = lines.skip(from - 1L);
			if (limit != null && limit >= 0) {
				window = window.limit(limit);
			}
			List<String> selected = window.toList();
			return selected.isEmpty() ? "" : String.join("\n", selected) + "\n";
		}
	}

	/** The confined root, for anything else that needs to resolve an agent-supplied path. */
	public WorkspaceJail jail() {
		return jail;
	}
}
