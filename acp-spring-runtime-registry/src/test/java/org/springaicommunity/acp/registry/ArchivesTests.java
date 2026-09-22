package org.springaicommunity.acp.registry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The formats agents actually ship in, including the one the JDK cannot decompress.
 *
 * <p>goose — the reference runtime, and the one most likely to be installed this way by someone
 * evaluating the library — publishes {@code .tar.bz2}, which has no decompressor in the JDK. That
 * path hands off to the system {@code tar}, and this is the test that says whether it works.
 */
class ArchivesTests {

	@TempDir
	Path temp;

	@Test
	void unpacksATarGz() throws Exception {
		Path archive = AgentInstallerTests.tarGz(temp.resolve("a.tar.gz"),
				Map.of("bin/agent", "hello", "README", "ignore me"));
		Path target = Files.createDirectories(temp.resolve("out"));

		Archives.extract(archive, target, "a.tar.gz");

		assertThat(Files.readString(target.resolve("bin/agent"))).isEqualTo("hello");
		assertThat(target.resolve("README")).exists();
	}

	@Test
	void unpacksAZip() throws Exception {
		Path archive = AgentInstallerTests.zip(temp.resolve("a.zip"), Map.of("agent", "hello"));
		Path target = Files.createDirectories(temp.resolve("out"));

		Archives.extract(archive, target, "a.zip");

		assertThat(Files.readString(target.resolve("agent"))).isEqualTo("hello");
	}

	@Test
	@DisplayName("a tar.bz2 goes through the system tar, because the JDK has no bzip2")
	@DisabledOnOs(OS.WINDOWS)
	void unpacksATarBz2ThroughTheSystemTar() throws Exception {
		assumeTrue(hasSystemTar(), "no tar on the PATH");
		Path source = Files.createDirectories(temp.resolve("src"));
		Files.writeString(source.resolve("agent"), "hello");
		Path archive = temp.resolve("a.tar.bz2");
		int exit = new ProcessBuilder("tar", "-cjf", archive.toString(), "-C", source.toString(), "agent")
				.inheritIO().start().waitFor();
		assumeTrue(exit == 0, "this tar cannot write bzip2");
		Path target = Files.createDirectories(temp.resolve("out"));

		Archives.extract(archive, target, "goose-aarch64-apple-darwin.tar.bz2");

		assertThat(Files.readString(target.resolve("agent"))).isEqualTo("hello");
	}

	@Test
	@DisplayName("an unrecognised name is the executable, not an archive")
	void treatsAnUnknownExtensionAsTheBinaryItself() throws Exception {
		Path binary = Files.writeString(temp.resolve("sigit-linux-arm64"), "ELF-ish");
		Path target = Files.createDirectories(temp.resolve("out"));

		Archives.extract(binary, target, "sigit-linux-arm64");

		assertThat(Files.readString(target.resolve("sigit-linux-arm64"))).isEqualTo("ELF-ish");
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	void keepsTheExecutableBitATarRecorded() throws Exception {
		Path archive = AgentInstallerTests.tarGz(temp.resolve("a.tar.gz"), Map.of("agent", "#!/bin/sh\n"));
		Path target = Files.createDirectories(temp.resolve("out"));

		Archives.extract(archive, target, "a.tar.gz");

		assertThat(target.resolve("agent")).isExecutable();
	}

	private static boolean hasSystemTar() {
		try {
			return new ProcessBuilder("tar", "--version").redirectErrorStream(true)
					.redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0;
		}
		catch (Exception ex) {
			return false;
		}
	}
}
