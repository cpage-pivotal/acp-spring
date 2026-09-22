package org.springaicommunity.acp.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Confines every path an agent names to the session's working directory.
 *
 * <p>{@code Path.normalize()} alone is not a jail. It removes {@code ..} textually, which stops
 * {@code workspace/../../etc/passwd} and stops nothing else: a symlink inside the workspace
 * pointing at {@code /etc} is an ordinary-looking relative path that resolves outside, and an agent
 * can create one with a single tool call before asking this client to read through it. So every
 * decision here is made on the <em>real</em> path — {@link Path#toRealPath} with symlinks followed
 * — and the root is realpathed too, because on macOS a temp directory is reached through
 * {@code /var}, which is itself a link to {@code /private/var}, and a jail that compared the two
 * spellings would reject everything.
 *
 * <p>For a path that does not exist yet, which is most writes, the walk goes up to the deepest
 * ancestor that does exist and realpaths that. What does not exist cannot be a symlink, so the
 * remaining segments are safe to append — and this is also why the check must be repeated on every
 * call rather than cached: the filesystem can change between one request and the next.
 */
public final class WorkspaceJail {

	private final Path root;

	private WorkspaceJail(Path root) {
		this.root = root;
	}

	/**
	 * @param workspace the directory the agent may touch; must exist
	 */
	public static WorkspaceJail around(Path workspace) {
		if (workspace == null) {
			throw new IllegalArgumentException("workspace must not be null");
		}
		try {
			return new WorkspaceJail(workspace.toRealPath());
		}
		catch (IOException ex) {
			throw new WorkspaceAccessException("Workspace '" + workspace + "' cannot be resolved", ex);
		}
	}

	/** The real path of the confined directory. */
	public Path root() {
		return root;
	}

	/** Resolves a path the agent named, which must already exist inside the workspace. */
	public Path existing(String requested) {
		Path resolved = confine(requested);
		if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
			throw new WorkspaceAccessException("'" + requested + "' does not exist in the workspace");
		}
		return resolved;
	}

	/** Resolves a path to write to, creating the directories under it if they are missing. */
	public Path forWriting(String requested) {
		Path resolved = confine(requested);
		if (Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
			throw new WorkspaceAccessException("'" + requested + "' is a directory");
		}
		Path parent = resolved.getParent();
		if (parent != null && !Files.isDirectory(parent)) {
			try {
				Files.createDirectories(parent);
			}
			catch (IOException ex) {
				throw new WorkspaceAccessException("Cannot create the directory for '" + requested + "'", ex);
			}
		}
		return resolved;
	}

	/** Resolves a directory to run something in, which must already exist inside the workspace. */
	public Path directory(String requested) {
		if (requested == null || requested.isBlank()) {
			return root;
		}
		Path resolved = confine(requested);
		if (!Files.isDirectory(resolved)) {
			throw new WorkspaceAccessException("'" + requested + "' is not a directory in the workspace");
		}
		return resolved;
	}

	private Path confine(String requested) {
		if (requested == null || requested.isBlank()) {
			throw new WorkspaceAccessException("the agent asked for a path but named none");
		}
		Path candidate;
		try {
			// A relative path is relative to the workspace, which is what the agent was told its cwd is.
			candidate = root.resolve(Paths.get(requested)).toAbsolutePath().normalize();
		}
		catch (InvalidPathException ex) {
			throw new WorkspaceAccessException("'" + requested + "' is not a usable path", ex);
		}

		Path existing = deepestExisting(candidate);
		Path realExisting;
		try {
			realExisting = existing.toRealPath();
		}
		catch (IOException ex) {
			throw new WorkspaceAccessException("'" + requested + "' cannot be resolved", ex);
		}
		if (!realExisting.startsWith(root)) {
			throw new WorkspaceAccessException(
					"'" + requested + "' resolves outside the workspace and was refused");
		}
		// Only the non-existent tail is re-attached, and a path that does not exist cannot be a link.
		Path tail = existing.relativize(candidate);
		return tail.toString().isEmpty() ? realExisting : realExisting.resolve(tail);
	}

	/**
	 * The deepest ancestor of {@code candidate} that exists.
	 *
	 * <p>For a path inside the workspace that is the workspace itself at worst, since the jail's own
	 * root exists by construction. For one outside it, the walk finds whatever real directory the
	 * request was aimed at, and the caller's {@code startsWith} check then refuses it — which is why
	 * this method does not need to know where the boundary is.
	 */
	private Path deepestExisting(Path candidate) {
		for (Path current = candidate; current != null; current = current.getParent()) {
			if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
				return current;
			}
		}
		throw new WorkspaceAccessException("'" + candidate + "' has no resolvable parent directory");
	}
}
