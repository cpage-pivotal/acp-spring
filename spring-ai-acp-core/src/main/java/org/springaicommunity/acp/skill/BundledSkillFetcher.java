package org.springaicommunity.acp.skill;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.springaicommunity.acp.config.SkillSpec;

/**
 * Copies a skill packaged with the application out of the classpath.
 *
 * <p>
 * Found through its {@code SKILL.md} rather than its directory, because a jar is not
 * obliged to have entries for directories. Two shapes of classpath are handled: a
 * directory, which is what an IDE or {@code mvn spring-boot:run} runs from, and a jar,
 * which is what {@code java -jar} runs from. Spring Boot's nested jars are reached
 * through {@link JarURLConnection} like any other — Boot's own connection extends it — so
 * this needs nothing from Spring.
 */
public class BundledSkillFetcher {

	private static final String SKILL_FILE = "SKILL.md";

	private final ClassLoader classLoader;

	public BundledSkillFetcher() {
		this(defaultClassLoader());
	}

	public BundledSkillFetcher(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	public SkillFetcher.Fetched fetch(SkillSpec.Bundled skill, Path into) {
		String skillFile = skill.path() + "/" + SKILL_FILE;
		URL url = classLoader.getResource(skillFile);
		if (url == null) {
			throw new SkillInstallException("there is no " + skillFile + " on the classpath");
		}
		try {
			switch (url.getProtocol()) {
				case "file" -> copyDirectory(Path.of(url.toURI()).getParent(), into);
				case "jar" -> copyJarEntries((JarURLConnection) url.openConnection(), into);
				default -> throw new SkillInstallException(
						"cannot read a skill from a '" + url.getProtocol() + "' classpath entry");
			}
		}
		catch (IOException | URISyntaxException ex) {
			throw new SkillInstallException("could not read " + skillFile + " from the classpath", ex);
		}
		return new SkillFetcher.Fetched(into, null);
	}

	/**
	 * Links are copied as links, not followed, so that the installer's check sees them
	 * and refuses the skill — as it would a link in a Git checkout.
	 */
	private static void copyDirectory(Path source, Path into) throws IOException {
		try (Stream<Path> files = Files.walk(source)) {
			for (Path file : (Iterable<Path>) files::iterator) {
				Path destination = into.resolve(source.relativize(file).toString());
				if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
					Files.createDirectories(destination);
				}
				else {
					Files.copy(file, destination, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES);
				}
			}
		}
	}

	/**
	 * The jar is not closed: with caching on, which is the default, it is shared with the
	 * class loader that is still running this application.
	 */
	private static void copyJarEntries(JarURLConnection connection, Path into) throws IOException {
		JarFile jar = connection.getJarFile();
		String prefix = connection.getEntryName()
			.substring(0, connection.getEntryName().length() - SKILL_FILE.length());
		Enumeration<JarEntry> entries = jar.entries();
		while (entries.hasMoreElements()) {
			JarEntry entry = entries.nextElement();
			if (!entry.getName().startsWith(prefix) || entry.getName().length() == prefix.length()) {
				continue;
			}
			Path destination = into.resolve(entry.getName().substring(prefix.length())).normalize();
			if (!destination.startsWith(into)) {
				throw new SkillInstallException("jar entry '" + entry.getName() + "' leads out of the skill");
			}
			if (entry.isDirectory()) {
				Files.createDirectories(destination);
				continue;
			}
			Files.createDirectories(destination.getParent());
			try (InputStream in = jar.getInputStream(entry)) {
				Files.copy(in, destination);
			}
			markScriptsExecutable(destination);
		}
	}

	/**
	 * A jar keeps no file modes, and a skill's instructions may run a script directly
	 * rather than through its interpreter. A file that starts with a shebang is meant to
	 * be run.
	 */
	private static void markScriptsExecutable(Path file) throws IOException {
		byte[] start = new byte[2];
		try (InputStream in = Files.newInputStream(file)) {
			if (in.readNBytes(start, 0, 2) == 2 && start[0] == '#' && start[1] == '!') {
				file.toFile().setExecutable(true);
			}
		}
	}

	private static ClassLoader defaultClassLoader() {
		ClassLoader context = Thread.currentThread().getContextClassLoader();
		return context != null ? context : BundledSkillFetcher.class.getClassLoader();
	}

}
