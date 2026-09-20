package org.thought.acp.registry;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unpacks the four shapes the registry's agents actually ship in.
 *
 * <p>The split is the JDK's, not a preference. {@code zip} and {@code gzip} are in
 * {@code java.util.zip}, so those are handled here, in process, with the path checks below. Bzip2
 * and xz are not in the JDK at all — and goose, the reference runtime, ships {@code .tar.bz2} — so
 * those hand off to the system {@code tar}, which reads all three on both macOS and Linux. Pulling
 * in Commons Compress for the two formats would have put a second archive library on every
 * application's classpath for an archive most of them never download.
 *
 * <p>A file whose name matches nothing is treated as the executable itself, because two registry
 * entries publish exactly that: a bare binary with no container around it.
 *
 * <p><strong>Every entry's destination is checked before it is written.</strong> An archive is a
 * list of paths supplied by whoever built it, and {@code ../../.ssh/authorized_keys} is a valid
 * entry name. The check is the same one {@code WorkspaceJail} makes and for the same reason:
 * against the real path of the destination root, so a link inside the archive cannot redirect a
 * later entry out of it.
 */
final class Archives {

	private static final Logger logger = LoggerFactory.getLogger(Archives.class);

	/** A tar header block, and the size of every block in the file. */
	private static final int BLOCK = 512;

	/** Refuses an archive that decompresses to more than this. */
	private static final long MAX_EXTRACTED_BYTES = 4L * 1024 * 1024 * 1024;

	private Archives() {
	}

	/**
	 * Unpacks {@code archive} into {@code target}, which must already exist.
	 *
	 * @param name the archive's file name, which is the only thing that says what format it is
	 */
	static void extract(Path archive, Path target, String name) throws IOException {
		String lower = name.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".zip")) {
			unzip(archive, target);
		}
		else if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
			try (InputStream in = new GZIPInputStream(Files.newInputStream(archive), 8192)) {
				untar(in, target);
			}
		}
		else if (lower.endsWith(".tar")) {
			try (InputStream in = Files.newInputStream(archive)) {
				untar(in, target);
			}
		}
		else if (lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") || lower.endsWith(".tar.xz")
				|| lower.endsWith(".txz")) {
			systemTar(archive, target, name);
		}
		else {
			// Not an archive: the download is the agent.
			Files.copy(archive, target.resolve(fileName(name)), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static String fileName(String name) {
		int slash = name.lastIndexOf('/');
		String simple = slash < 0 ? name : name.substring(slash + 1);
		return simple.isBlank() ? "agent" : simple;
	}

	private static void unzip(Path archive, Path target) throws IOException {
		Path root = target.toRealPath();
		long written = 0;
		try (ZipInputStream in = new ZipInputStream(Files.newInputStream(archive))) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				Path destination = resolve(root, entry.getName());
				if (entry.isDirectory()) {
					Files.createDirectories(destination);
					continue;
				}
				Files.createDirectories(destination.getParent());
				written += copy(in, destination, written);
			}
		}
	}

	/**
	 * Reads a tar stream: ustar with GNU long names, which is what every agent in the registry
	 * produces.
	 *
	 * <p>Symbolic and hard links are skipped rather than created. An agent's archive has no
	 * legitimate reason to place a link, and a link is the one entry type whose target is not
	 * checked by checking where the entry itself lands.
	 */
	private static void untar(InputStream in, Path target) throws IOException {
		Path root = target.toRealPath();
		byte[] header = new byte[BLOCK];
		String longName = null;
		long written = 0;
		while (true) {
			if (!readFully(in, header)) {
				return;
			}
			if (isEmpty(header)) {
				return;
			}
			String name = longName != null ? longName : string(header, 0, 100);
			longName = null;
			String prefix = string(header, 345, 155);
			if (!prefix.isEmpty() && name.indexOf('/') != 0) {
				name = prefix + "/" + name;
			}
			long size = octal(header, 124, 12);
			char type = (char) (header[156] == 0 ? '0' : header[156]);
			int padding = (int) ((BLOCK - (size % BLOCK)) % BLOCK);

			switch (type) {
				case 'L' -> {
					longName = new String(readBytes(in, size), StandardCharsets.UTF_8).trim();
					skip(in, padding);
				}
				case '5' -> Files.createDirectories(resolve(root, name));
				case '0', '7' -> {
					Path destination = resolve(root, name);
					Files.createDirectories(destination.getParent());
					try (OutputStream out = Files.newOutputStream(destination)) {
						written += copyExactly(in, out, size, written);
					}
					if (isExecutable(octal(header, 100, 8))) {
						makeExecutable(destination);
					}
					skip(in, padding);
				}
				default -> {
					// 'x'/'g' pax metadata, '1'/'2' links, anything else: read past it.
					skip(in, size + padding);
				}
			}
		}
	}

	/**
	 * Hands a bzip2 or xz archive to the system {@code tar}.
	 *
	 * <p>{@code tar} refuses absolute paths and {@code ..} components on extraction on both macOS
	 * and GNU, so the path safety this class provides in process is provided by the tool out of it.
	 * The alternative — an agent installed from a compressed format the JVM cannot read — is no
	 * agent at all.
	 */
	private static void systemTar(Path archive, Path target, String name) throws IOException {
		logger.debug("Unpacking {} with the system tar: the JDK has no {} decompressor", name,
				name.toLowerCase(Locale.ROOT).contains("bz2") ? "bzip2" : "xz");
		ProcessBuilder builder = new ProcessBuilder("tar", "-xf", archive.toAbsolutePath().toString())
				.directory(target.toFile()).redirectErrorStream(true);
		Process process = builder.start();
		String output;
		try (InputStream in = process.getInputStream()) {
			output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
		}
		try {
			if (!process.waitFor(10, TimeUnit.MINUTES)) {
				process.destroyForcibly();
				throw new IOException("Unpacking " + name + " with the system tar timed out");
			}
		}
		catch (InterruptedException ex) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while unpacking " + name, ex);
		}
		if (process.exitValue() != 0) {
			throw new IOException("Could not unpack " + name + ": the system tar exited "
					+ process.exitValue() + (output.isEmpty() ? "" : " saying '" + output + "'")
					+ ". This format needs a tar on the PATH, or install the agent yourself and point"
					+ " spring.acp.runtimes at it");
		}
	}

	/**
	 * Where an entry may be written, or nowhere.
	 *
	 * @throws IOException if the entry names a path outside {@code root}
	 */
	private static Path resolve(Path root, String entryName) throws IOException {
		Path destination = root.resolve(entryName).normalize();
		if (!destination.startsWith(root)) {
			throw new IOException("Archive entry '" + entryName + "' would be written outside " + root);
		}
		return destination;
	}

	private static long copy(InputStream in, Path destination, long alreadyWritten) throws IOException {
		try (OutputStream out = Files.newOutputStream(destination)) {
			byte[] buffer = new byte[8192];
			long written = 0;
			int read;
			while ((read = in.read(buffer)) > 0) {
				written += read;
				requireWithinBudget(alreadyWritten + written);
				out.write(buffer, 0, read);
			}
			return written;
		}
	}

	private static long copyExactly(InputStream in, OutputStream out, long size, long alreadyWritten)
			throws IOException {
		byte[] buffer = new byte[8192];
		long remaining = size;
		while (remaining > 0) {
			int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
			if (read < 0) {
				throw new IOException("Archive ended in the middle of an entry");
			}
			requireWithinBudget(alreadyWritten + (size - remaining) + read);
			out.write(buffer, 0, read);
			remaining -= read;
		}
		return size;
	}

	private static void requireWithinBudget(long written) throws IOException {
		if (written > MAX_EXTRACTED_BYTES) {
			throw new IOException("Archive expands to more than " + MAX_EXTRACTED_BYTES + " bytes");
		}
	}

	static void makeExecutable(Path file) {
		try {
			Set<PosixFilePermission> permissions = new java.util.HashSet<>(Files.getPosixFilePermissions(file));
			permissions.add(PosixFilePermission.OWNER_EXECUTE);
			Files.setPosixFilePermissions(file, permissions);
		}
		catch (IOException | UnsupportedOperationException ex) {
			// Windows has no POSIX bits and does not need them.
			logger.debug("Could not mark {} executable", file, ex);
		}
	}

	private static boolean isExecutable(long mode) {
		return (mode & 0100) != 0;
	}

	/**
	 * @return false at a clean end of stream, true once the buffer is full
	 * @throws IOException if the stream ends part way through, which means a truncated archive
	 */
	private static boolean readFully(InputStream in, byte[] buffer) throws IOException {
		int offset = 0;
		while (offset < buffer.length) {
			int read = in.read(buffer, offset, buffer.length - offset);
			if (read < 0) {
				if (offset == 0) {
					return false;
				}
				throw new IOException("Archive ended after " + offset + " of " + buffer.length + " expected bytes");
			}
			offset += read;
		}
		return true;
	}

	private static byte[] readBytes(InputStream in, long size) throws IOException {
		byte[] bytes = new byte[(int) Math.min(size, 8192)];
		readFully(in, bytes);
		skip(in, size - bytes.length);
		return bytes;
	}

	private static void skip(InputStream in, long count) throws IOException {
		long remaining = count;
		while (remaining > 0) {
			long skipped = in.skip(remaining);
			if (skipped <= 0) {
				if (in.read() < 0) {
					return;
				}
				remaining--;
				continue;
			}
			remaining -= skipped;
		}
	}

	private static boolean isEmpty(byte[] block) {
		for (byte b : block) {
			if (b != 0) {
				return false;
			}
		}
		return true;
	}

	private static String string(byte[] block, int offset, int length) {
		int end = offset;
		while (end < offset + length && block[end] != 0) {
			end++;
		}
		return new String(block, offset, end - offset, StandardCharsets.UTF_8);
	}

	private static long octal(byte[] block, int offset, int length) {
		String value = string(block, offset, length).trim();
		if (value.isEmpty()) {
			return 0;
		}
		try {
			return Long.parseLong(value, 8);
		}
		catch (NumberFormatException ex) {
			return 0;
		}
	}
}
