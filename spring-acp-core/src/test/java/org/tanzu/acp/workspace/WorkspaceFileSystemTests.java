package org.tanzu.acp.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceFileSystemTests {

	private static final String SESSION = "sess-1";

	@TempDir
	Path workspace;

	@TempDir
	Path elsewhere;

	private WorkspaceFileSystem files(FileSystemAccess access) {
		return new WorkspaceFileSystem(workspace, access);
	}

	@Test
	@DisplayName("reading is refused outright when it was not lent")
	void readRefusedWhenDisabled() throws IOException {
		Files.writeString(workspace.resolve("a.txt"), "hello");

		StepVerifier.create(files(FileSystemAccess.none()).read(read("a.txt")))
				.expectError(WorkspaceAccessException.class).verify();
	}

	@Test
	@DisplayName("writing is refused when only reading was lent")
	void writeRefusedWhenReadOnly() {
		StepVerifier
				.create(files(FileSystemAccess.readOnly())
						.write(new AcpSchema.WriteTextFileRequest(SESSION, "a.txt", "nope")))
				.expectError(WorkspaceAccessException.class).verify();
	}

	@Test
	@DisplayName("a whole file comes back when reading was lent")
	void readsWholeFile() throws IOException {
		Files.writeString(workspace.resolve("a.txt"), "one\ntwo\n");

		StepVerifier.create(files(FileSystemAccess.readOnly()).read(read("a.txt")))
				.assertNext(response -> assertThat(response.content()).isEqualTo("one\ntwo\n")).verifyComplete();
	}

	@Test
	@DisplayName("line and limit select a window, one-based, as ACP specifies")
	void readsAWindow() throws IOException {
		Files.writeString(workspace.resolve("a.txt"), "one\ntwo\nthree\nfour\n");

		StepVerifier.create(files(FileSystemAccess.readOnly()).read(new AcpSchema.ReadTextFileRequest(SESSION,
				"a.txt", 2, 2)))
				.assertNext(response -> assertThat(response.content()).isEqualTo("two\nthree\n")).verifyComplete();
	}

	@Test
	@DisplayName("a window past the end of the file is empty rather than an error")
	void readsPastTheEnd() throws IOException {
		Files.writeString(workspace.resolve("a.txt"), "one\n");

		StepVerifier.create(
				files(FileSystemAccess.readOnly()).read(new AcpSchema.ReadTextFileRequest(SESSION, "a.txt", 99, 5)))
				.assertNext(response -> assertThat(response.content()).isEmpty()).verifyComplete();
	}

	@Test
	@DisplayName("a read through a symlink out of the workspace is refused")
	void readCannotEscape() throws IOException {
		Path secret = Files.writeString(elsewhere.resolve("secrets.txt"), "s3cret");
		Files.createSymbolicLink(workspace.resolve("innocent.txt"), secret);

		StepVerifier.create(files(FileSystemAccess.readWrite()).read(read("innocent.txt")))
				.expectError(WorkspaceAccessException.class).verify();
	}

	@Test
	@DisplayName("a write lands in the workspace, making directories as needed")
	void writesInsideTheWorkspace() {
		StepVerifier.create(files(FileSystemAccess.readWrite())
				.write(new AcpSchema.WriteTextFileRequest(SESSION, "nested/new.txt", "written"))).expectNextCount(1)
				.verifyComplete();

		assertThat(workspace.resolve("nested/new.txt")).hasContent("written");
	}

	@Test
	@DisplayName("a write aimed outside the workspace never happens")
	void writeCannotEscape() {
		Path target = elsewhere.resolve("planted.txt");

		StepVerifier
				.create(files(FileSystemAccess.readWrite())
						.write(new AcpSchema.WriteTextFileRequest(SESSION, target.toString(), "planted")))
				.expectError(WorkspaceAccessException.class).verify();

		assertThat(target).doesNotExist();
	}

	@Test
	@DisplayName("a file larger than the read limit is refused rather than held in memory")
	void refusesAnOversizeFile() throws IOException {
		byte[] big = new byte[(int) FileSystemAccess.MAX_READ_BYTES + 1];
		java.util.Arrays.fill(big, (byte) 'x');
		Files.write(workspace.resolve("big.txt"), big);

		StepVerifier.create(files(FileSystemAccess.readOnly()).read(read("big.txt")))
				.expectError(WorkspaceAccessException.class).verify();
	}

	private static AcpSchema.ReadTextFileRequest read(String path) {
		return new AcpSchema.ReadTextFileRequest(SESSION, path, null, null);
	}
}
