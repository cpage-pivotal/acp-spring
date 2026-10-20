package org.springaicommunity.acp.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ExecutablesTests {

	private static final String VARIABLE = "AGENT_CLI_PATH";

	@TempDir
	Path directory;

	@Test
	void unsetOrBlankNamesNothing() {
		assertThat(Executables.fromEnvironment(VARIABLE, Map.<String, String>of()::get)).isEmpty();
		assertThat(Executables.fromEnvironment(VARIABLE, Map.of(VARIABLE, " ")::get)).isEmpty();
	}

	@Test
	void namesAnExecutableFile() throws IOException {
		Path agent = Files.createFile(directory.resolve("agent"),
				PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));

		assertThat(Executables.fromEnvironment(VARIABLE, Map.of(VARIABLE, agent.toString())::get))
			.contains(agent.toString());
	}

	@Test
	void refusesAFileThatIsNotExecutable() throws IOException {
		Path agent = Files.createFile(directory.resolve("agent"));

		assertThatIllegalStateException()
			.isThrownBy(() -> Executables.fromEnvironment(VARIABLE, Map.of(VARIABLE, agent.toString())::get))
			.withMessageContaining(VARIABLE)
			.withMessageContaining(agent.toString());
	}

}
