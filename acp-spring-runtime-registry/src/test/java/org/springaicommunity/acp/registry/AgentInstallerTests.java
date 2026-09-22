package org.springaicommunity.acp.registry;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Downloading, verifying and unpacking an agent — all of it against archives built here and served
 * from {@code file:} URLs, so the whole path is exercised with no network and no vendor.
 *
 * <p>The digest cases are the ones that matter. Everything else in {@code AgentInstaller} is
 * plumbing around the decision to run a downloaded executable with the application's credentials in
 * its environment.
 */
class AgentInstallerTests {

	@TempDir
	Path temp;

	@Test
	void installsFromATarGzAndLeavesTheCommandExecutable() throws Exception {
		Path archive = tarGz(temp.resolve("agent.tar.gz"), Map.of("agent", "#!/bin/sh\necho hi\n"));
		RegistryEntry.Artifact artifact = artifact(archive, "./agent", sha256(archive));

		Path command = installer(true).install(entry("house"), artifact);

		assertThat(command).isRegularFile().isExecutable();
		assertThat(Files.readString(command)).contains("echo hi");
	}

	@Test
	void installsFromAZip() throws Exception {
		Path archive = zip(temp.resolve("agent.zip"), Map.of("bin/agent", "#!/bin/sh\nexit 0\n"));
		RegistryEntry.Artifact artifact = artifact(archive, "./bin/agent", sha256(archive));

		assertThat(installer(true).install(entry("house"), artifact)).isRegularFile().isExecutable();
	}

	@Test
	@DisplayName("a bare executable with no archive around it is installed as it is")
	void installsAnUncompressedBinary() throws Exception {
		// Two registry entries publish exactly this: a release asset that is the binary itself.
		Path binary = Files.writeString(temp.resolve("sigit-linux-arm64"), "#!/bin/sh\nexit 0\n");
		RegistryEntry.Artifact artifact = artifact(binary, "./sigit-linux-arm64", sha256(binary));

		assertThat(installer(true).install(entry("sigit"), artifact)).isRegularFile().isExecutable();
	}

	@Test
	void refusesADownloadWhoseDigestDoesNotMatch() throws Exception {
		Path archive = tarGz(temp.resolve("agent.tar.gz"), Map.of("agent", "#!/bin/sh\nexit 0\n"));
		RegistryEntry.Artifact artifact = artifact(archive, "./agent",
				"0000000000000000000000000000000000000000000000000000000000000000");

		assertThatThrownBy(() -> installer(true).install(entry("house"), artifact))
				.isInstanceOf(AgentInstaller.AgentInstallException.class).hasMessageContaining("hashes to")
				.hasMessageContaining("refusing to run it");
	}

	@Test
	@DisplayName("an artifact with no published digest is refused by default, and says how to allow it")
	void refusesAnUnverifiableArtifactUnlessToldOtherwise() throws Exception {
		Path archive = tarGz(temp.resolve("agent.tar.gz"), Map.of("agent", "x"));
		RegistryEntry.Artifact artifact = artifact(archive, "./agent", null);

		assertThatThrownBy(() -> installer(true).install(entry("cursor"), artifact))
				.isInstanceOf(AgentInstaller.AgentInstallException.class)
				.hasMessageContaining("publishes no sha256 for 'cursor'")
				.hasMessageContaining("require-checksum=false");
	}

	@Test
	void installsAnUnverifiableArtifactWhenTheOperatorSaidSoInWriting() throws Exception {
		Path archive = tarGz(temp.resolve("agent.tar.gz"), Map.of("agent", "#!/bin/sh\nexit 0\n"));
		RegistryEntry.Artifact artifact = artifact(archive, "./agent", null);

		assertThat(installer(false).install(entry("cursor"), artifact)).isRegularFile();
	}

	@Test
	void reusesAnInstallRatherThanDownloadingItAgain() throws Exception {
		Path archive = tarGz(temp.resolve("agent.tar.gz"), Map.of("agent", "#!/bin/sh\nexit 0\n"));
		RegistryEntry.Artifact artifact = artifact(archive, "./agent", sha256(archive));
		AgentInstaller installer = installer(true);

		Path first = installer.install(entry("house"), artifact);
		Files.delete(archive);
		Path second = installer.install(entry("house"), artifact);

		assertThat(second).isEqualTo(first).isRegularFile();
	}

	@Test
	void saysWhatTheArchiveHeldWhenTheCommandIsNotInIt() throws Exception {
		Path archive = tarGz(temp.resolve("agent.tar.gz"), Map.of("something-else", "x"));
		RegistryEntry.Artifact artifact = artifact(archive, "./agent", sha256(archive));

		assertThatThrownBy(() -> installer(true).install(entry("house"), artifact))
				.isInstanceOf(AgentInstaller.AgentInstallException.class).hasMessageContaining("does not contain")
				.hasMessageContaining("something-else");
	}

	@Test
	void refusesToDownloadAtAllWhenOffline() throws Exception {
		Path archive = tarGz(temp.resolve("agent.tar.gz"), Map.of("agent", "x"));
		RegistryEntry.Artifact artifact = artifact(archive, "./agent", sha256(archive));
		AgentInstaller installer = new AgentInstaller(
				new RegistrySettings(null, temp.resolve("cache"), null, true, true, null));

		assertThatThrownBy(() -> installer.install(entry("house"), artifact))
				.isInstanceOf(AgentInstaller.AgentInstallException.class).hasMessageContaining("offline is on");
	}

	@Test
	@DisplayName("an archive entry aimed outside the install directory is refused")
	@DisabledOnOs(OS.WINDOWS)
	void refusesAnArchiveThatWritesOutsideItself() throws Exception {
		// An archive is a list of paths chosen by whoever built it, and '../' is a valid entry name.
		Path archive = tarGz(temp.resolve("evil.tar.gz"), Map.of("../escaped", "owned"));
		RegistryEntry.Artifact artifact = artifact(archive, "./agent", sha256(archive));

		assertThatThrownBy(() -> installer(true).install(entry("evil"), artifact))
				.isInstanceOf(AgentInstaller.AgentInstallException.class)
				.hasRootCauseInstanceOf(java.io.IOException.class).rootCause()
				.hasMessageContaining("Archive entry '../escaped' would be written outside");
		assertThat(temp.resolve("escaped")).doesNotExist();
	}

	@Test
	void rejectsACommandThatIsNotARelativePath() throws Exception {
		Path archive = tarGz(temp.resolve("agent.tar.gz"), Map.of("agent", "x"));

		assertThatThrownBy(() -> installer(true).install(entry("house"),
				artifact(archive, "/etc/passwd", sha256(archive))))
						.isInstanceOf(AgentInstaller.AgentInstallException.class)
						.hasMessageContaining("is not a relative path");
	}

	private AgentInstaller installer(boolean requireChecksum) {
		return new AgentInstaller(
				new RegistrySettings(null, temp.resolve("cache"), null, false, requireChecksum, null));
	}

	private static RegistryEntry entry(String id) {
		return new RegistryEntry(id, id, "1.0.0", null);
	}

	private static RegistryEntry.Artifact artifact(Path archive, String command, String sha256) {
		return new RegistryEntry.Artifact(archive.toUri(), command, List.of("acp"), Map.of(), sha256);
	}

	static String sha256(Path file) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		digest.update(Files.readAllBytes(file));
		return HexFormat.of().formatHex(digest.digest());
	}

	/** A tar.gz built here, so the reader is tested against bytes rather than against a fixture. */
	static Path tarGz(Path target, Map<String, String> entries) throws IOException {
		try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(target))) {
			for (Map.Entry<String, String> entry : entries.entrySet()) {
				byte[] content = entry.getValue().getBytes(StandardCharsets.UTF_8);
				out.write(tarHeader(entry.getKey(), content.length));
				out.write(content);
				int padding = (512 - (content.length % 512)) % 512;
				out.write(new byte[padding]);
			}
			out.write(new byte[1024]);
		}
		return target;
	}

	private static byte[] tarHeader(String name, int size) {
		byte[] header = new byte[512];
		byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
		System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
		write(header, 100, "000755 ");
		write(header, 108, "000000 ");
		write(header, 116, "000000 ");
		write(header, 124, String.format("%011o ", size));
		write(header, 136, String.format("%011o ", 0));
		header[156] = '0';
		write(header, 257, "ustar");
		header[263] = '0';
		header[264] = '0';
		// The checksum is computed over a header whose checksum field reads as spaces.
		for (int i = 148; i < 156; i++) {
			header[i] = ' ';
		}
		int checksum = 0;
		for (byte b : header) {
			checksum += b & 0xFF;
		}
		write(header, 148, String.format("%06o\0 ", checksum));
		return header;
	}

	private static void write(byte[] header, int offset, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		System.arraycopy(bytes, 0, header, offset, bytes.length);
	}

	static Path zip(Path target, Map<String, String> entries) throws IOException {
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
			for (Map.Entry<String, String> entry : entries.entrySet()) {
				out.putNextEntry(new ZipEntry(entry.getKey()));
				out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				out.closeEntry();
			}
		}
		return target;
	}
}
