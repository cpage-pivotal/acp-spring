package org.tanzu.acp.workspace;

/**
 * Whether, and how far, the agent may use <em>this client's</em> filesystem.
 *
 * <p>Worth being precise about what this controls, because the name invites a bigger claim than it
 * can support. ACP's {@code fs/read_text_file} and {@code fs/write_text_file} let an agent borrow
 * the client's file access — useful to an IDE, which can then show the edit in the editor's own
 * buffer. Declining them does not take away the agent's <em>own</em> file access: measured against
 * all three runtimes, an agent in its default mode writes files with its own tools and never asks.
 * Restricting what the agent process itself can reach is the operating system's job, not this
 * record's.
 *
 * <p>Off by default, because a server-side client has no editor to show anything in and therefore
 * nothing to gain from lending the access.
 */
public record FileSystemAccess(boolean read, boolean write) {

	/** A read this large is not a source file, and holding it in memory helps nobody. */
	public static final long MAX_READ_BYTES = 10L * 1024 * 1024;

	public static FileSystemAccess none() {
		return new FileSystemAccess(false, false);
	}

	public static FileSystemAccess readOnly() {
		return new FileSystemAccess(true, false);
	}

	public static FileSystemAccess readWrite() {
		return new FileSystemAccess(true, true);
	}

	public boolean enabled() {
		return read || write;
	}
}
