package org.springaicommunity.acp.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The jail's whole job is refusing paths that resolve outside the workspace, so most of
 * these are the ways out rather than the ways in.
 */
class WorkspaceJailTests {

	@TempDir
	Path workspace;

	@TempDir
	Path elsewhere;

	private WorkspaceJail jail() {
		return WorkspaceJail.around(workspace);
	}

	@Test
	@DisplayName("a relative path inside the workspace resolves against it")
	void relativeInside() throws IOException {
		Files.writeString(workspace.resolve("notes.md"), "hello");

		assertThat(jail().existing("notes.md")).isEqualTo(workspace.toRealPath().resolve("notes.md"));
	}

	@Test
	@DisplayName("an absolute path inside the workspace is accepted as given")
	void absoluteInside() throws IOException {
		Path file = Files.writeString(workspace.resolve("notes.md"), "hello");

		assertThat(jail().existing(file.toString())).isEqualTo(workspace.toRealPath().resolve("notes.md"));
	}

	@Test
	@DisplayName("climbing out with .. is refused")
	void dotDotEscape() {
		assertThatThrownBy(() -> jail().existing("../../etc/passwd")).isInstanceOf(WorkspaceAccessException.class)
			.hasMessageContaining("outside the workspace");
	}

	@Test
	@DisplayName("an absolute path outside the workspace is refused")
	void absoluteOutside() throws IOException {
		Path outside = Files.writeString(elsewhere.resolve("secrets.txt"), "s3cret");

		assertThatThrownBy(() -> jail().existing(outside.toString())).isInstanceOf(WorkspaceAccessException.class)
			.hasMessageContaining("outside the workspace");
	}

	/**
	 * The case that makes {@code normalize()} insufficient on its own. Nothing about this
	 * path is textually suspicious — no {@code ..}, no leading slash — and an agent can
	 * create the link with one tool call before asking the client to read through it.
	 */
	@Test
	@DisplayName("a symlink inside the workspace pointing out is refused")
	void symlinkEscape() throws IOException {
		Path secret = Files.writeString(elsewhere.resolve("secrets.txt"), "s3cret");
		Files.createSymbolicLink(workspace.resolve("innocent.txt"), secret);

		assertThatThrownBy(() -> jail().existing("innocent.txt")).isInstanceOf(WorkspaceAccessException.class)
			.hasMessageContaining("outside the workspace");
	}

	@Test
	@DisplayName("a symlinked directory inside the workspace pointing out is refused")
	void symlinkedDirectoryEscape() throws IOException {
		Files.writeString(elsewhere.resolve("secrets.txt"), "s3cret");
		Files.createSymbolicLink(workspace.resolve("shortcut"), elsewhere);

		assertThatThrownBy(() -> jail().existing("shortcut/secrets.txt")).isInstanceOf(WorkspaceAccessException.class)
			.hasMessageContaining("outside the workspace");
	}

	@Test
	@DisplayName("a symlink that stays inside the workspace is allowed")
	void symlinkInsideIsFine() throws IOException {
		Path target = Files.writeString(workspace.resolve("real.txt"), "hello");
		Files.createSymbolicLink(workspace.resolve("link.txt"), target);

		assertThat(jail().existing("link.txt")).isEqualTo(workspace.toRealPath().resolve("real.txt"));
	}

	@Test
	@DisplayName("a write target that does not exist yet is allowed, and its directories are made")
	void writeCreatesDirectories() {
		Path resolved = jail().forWriting("nested/deeper/new.txt");

		assertThat(resolved.getParent()).exists();
		assertThat(resolved).doesNotExist();
		assertThat(resolved.toString()).startsWith(jail().root().toString());
	}

	@Test
	@DisplayName("a write through a symlinked directory pointing out is refused")
	void writeThroughSymlinkEscape() throws IOException {
		Files.createSymbolicLink(workspace.resolve("out"), elsewhere);

		assertThatThrownBy(() -> jail().forWriting("out/planted.txt")).isInstanceOf(WorkspaceAccessException.class)
			.hasMessageContaining("outside the workspace");
	}

	@Test
	@DisplayName("writing over a directory is refused")
	void writeOverDirectory() throws IOException {
		Files.createDirectory(workspace.resolve("subdir"));

		assertThatThrownBy(() -> jail().forWriting("subdir")).isInstanceOf(WorkspaceAccessException.class)
			.hasMessageContaining("is a directory");
	}

	@Test
	@DisplayName("a terminal cwd outside the workspace is refused, and an empty one is the root")
	void directoryConfinement() throws IOException {
		Files.createDirectory(workspace.resolve("sub"));

		assertThat(jail().directory("sub")).isEqualTo(workspace.toRealPath().resolve("sub"));
		assertThat(jail().directory(null)).isEqualTo(workspace.toRealPath());
		assertThatThrownBy(() -> jail().directory(elsewhere.toString())).isInstanceOf(WorkspaceAccessException.class);
	}

	/**
	 * The root is realpathed too, and on macOS that is not cosmetic: a temp directory is
	 * handed out as {@code /var/folders/...} while {@code /var} is a link to
	 * {@code /private/var}, so a jail comparing the two spellings would refuse every path
	 * in its own workspace.
	 */
	@Test
	@DisplayName("the jail root is itself resolved through symlinks")
	void rootIsRealPathed() throws IOException {
		assertThat(jail().root()).isEqualTo(workspace.toRealPath());
	}

	@Test
	@DisplayName("a blank path is refused rather than resolving to the workspace itself")
	void blankPath() {
		assertThatThrownBy(() -> jail().existing("  ")).isInstanceOf(WorkspaceAccessException.class);
	}

}
