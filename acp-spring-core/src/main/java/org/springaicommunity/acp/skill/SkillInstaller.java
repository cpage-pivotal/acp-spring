package org.springaicommunity.acp.skill;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.acp.config.AgentSettings;
import org.springaicommunity.acp.config.SkillSpec;

/**
 * Installs the configured skills into {@code <workspace>/.agents/skills/}, where goose, opencode and
 * codex all look for them: bundled ones copied out of the application's classpath, Git ones fetched.
 *
 * <p>The one thing this library writes into the workspace, and deliberately so. Everything an
 * adapter provisions goes under {@link AgentSettings#runtimeHome()}, because it is the agent's own
 * configuration; a skill is content the application asked the agent to have, and the workspace is
 * the only place every agent reads it from without being told. Measured against goose 1.51.0, which
 * has no setting that moves it.
 *
 * <p>Each skill this installs carries a {@value #MARKER} file naming where it came from. That is what
 * lets a skill removed from the configuration be removed from the workspace, and what stops this
 * from overwriting a skill somebody put there by hand.
 *
 * <p>Fails closed, as the buildpack did: a skill that cannot be fetched or does not pass the checks
 * stops the agent from starting, rather than starting it without a capability the application
 * depends on.
 */
public final class SkillInstaller {

	private static final Logger logger = LoggerFactory.getLogger(SkillInstaller.class);

	/** Marks a skill directory as this library's to replace or remove. */
	public static final String MARKER = ".acp-spring-skill";

	private static final String SKILL_FILE = "SKILL.md";

	/**
	 * What this JVM has already installed. A pool starts one client per agent process and restarts
	 * them, and none of that should mean fetching the same commit again.
	 */
	private static final Set<List<Object>> INSTALLED = ConcurrentHashMap.newKeySet();

	private final SkillFetcher fetcher;

	public SkillInstaller() {
		this(SkillFetcher.standard());
	}

	public SkillInstaller(SkillFetcher fetcher) {
		this.fetcher = fetcher;
	}

	/** Where the skills of an agent working in {@code workspace} live. */
	public static Path skillsDirectory(Path workspace) {
		return workspace.resolve(".agents").resolve("skills");
	}

	/** Installs {@code settings}' skills, once per workspace and list of skills in this JVM. */
	public static void installOnce(AgentSettings settings) {
		List<Object> key = List.of(settings.workspace(), settings.skills());
		synchronized (INSTALLED) {
			if (INSTALLED.contains(key)) {
				return;
			}
			new SkillInstaller().install(settings.workspace(), settings.skills());
			INSTALLED.add(key);
		}
	}

	/**
	 * Makes {@code workspace}'s installed skills exactly {@code skills}: each one fetched, checked and
	 * copied in, and any this library installed earlier that is no longer configured removed.
	 */
	public void install(Path workspace, List<SkillSpec> skills) {
		Path directory = skillsDirectory(workspace);
		removeUnconfigured(directory, skills.stream().map(SkillSpec::name).collect(Collectors.toSet()));
		for (SkillSpec skill : skills) {
			install(directory, skill);
		}
	}

	private void install(Path directory, SkillSpec skill) {
		Path staging = null;
		try {
			staging = Files.createTempDirectory("acp-spring-skill-");
			SkillFetcher.Fetched fetched = fetcher.fetch(skill, staging);
			String revision = fetched.revision();
			if (skill instanceof SkillSpec.Git git && git.isPinned() && !git.ref().equalsIgnoreCase(revision)) {
				throw new SkillInstallException(
						"skill '" + skill.name() + "' resolved to " + revision + ", not the pinned " + git.ref());
			}
			Path source = fetched.directory().normalize();
			check(skill, staging, source);

			Path target = directory.resolve(skill.name());
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
				if (!Files.isRegularFile(target.resolve(MARKER), LinkOption.NOFOLLOW_LINKS)) {
					throw new SkillInstallException("'" + target + "' exists and was not installed by acp-spring; "
							+ "remove it or give skill '" + skill.name() + "' another name");
				}
				delete(target);
			}
			copy(source, target);
			Files.writeString(target.resolve(MARKER),
					skill.describe() + "\n" + (revision == null ? "" : "commit " + revision + "\n"));

			logger.info("Installed skill {}{}", skill.describe(), revision == null ? "" : " at " + revision);
			if (skill instanceof SkillSpec.Git git && !git.isPinned()) {
				logger.warn("Skill '{}' is not pinned to a commit, so it can change between starts; "
						+ "set its ref to {} to pin it", skill.name(), revision);
			}
		}
		catch (IOException ex) {
			throw new SkillInstallException("Could not install skill " + skill.describe(), ex);
		}
		catch (SkillInstallException ex) {
			throw new SkillInstallException("Could not install skill " + skill.describe() + ": " + ex.getMessage(),
					ex);
		}
		finally {
			if (staging != null) {
				deleteQuietly(staging);
			}
		}
	}

	/**
	 * The buildpack's checks: the path exists in the repository, it holds a {@code SKILL.md}, nothing
	 * in it is a symbolic link — a link could point anywhere on this machine, and the agent would read
	 * through it — and {@code SKILL.md} has the expected digest when one was given.
	 */
	private static void check(SkillSpec skill, Path checkout, Path source) throws IOException {
		// Real paths, not lexical ones: a directory above the skill that is itself a link would pass a
		// startsWith on the path as written and still lead out of the checkout.
		if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
				|| !source.toRealPath().startsWith(checkout.toRealPath())) {
			throw new SkillInstallException("its path is not a directory in " + skill.describe());
		}
		try (Stream<Path> files = Files.walk(source)) {
			files.filter(Files::isSymbolicLink).findFirst().ifPresent(link -> {
				throw new SkillInstallException("it contains a symbolic link, " + source.relativize(link));
			});
		}
		Path skillFile = source.resolve(SKILL_FILE);
		if (!Files.isRegularFile(skillFile, LinkOption.NOFOLLOW_LINKS)) {
			throw new SkillInstallException("it has no " + SKILL_FILE);
		}
		if (skill.sha256() != null) {
			String actual = sha256(skillFile);
			if (!actual.equals(skill.sha256())) {
				throw new SkillInstallException(SKILL_FILE + " has sha256 " + actual + ", not " + skill.sha256());
			}
		}
	}

	private static void removeUnconfigured(Path directory, Set<String> configured) {
		if (!Files.isDirectory(directory)) {
			return;
		}
		try (Stream<Path> children = Files.list(directory)) {
			children.filter(child -> !configured.contains(child.getFileName().toString()))
				.filter(child -> Files.isRegularFile(child.resolve(MARKER), LinkOption.NOFOLLOW_LINKS))
				.forEach(child -> {
					delete(child);
					logger.info("Removed skill '{}', which is no longer configured", child.getFileName());
				});
		}
		catch (IOException ex) {
			throw new SkillInstallException("Could not read " + directory, ex);
		}
	}

	/** Copies the skill without the repository metadata, keeping file modes so scripts stay executable. */
	private static void copy(Path source, Path target) throws IOException {
		try (Stream<Path> files = Files.walk(source)) {
			for (Path file : (Iterable<Path>) files::iterator) {
				Path relative = source.relativize(file);
				if (!relative.toString().isEmpty() && relative.getName(0).toString().equals(".git")) {
					continue;
				}
				Path destination = target.resolve(relative.toString());
				if (Files.isDirectory(file)) {
					Files.createDirectories(destination);
				}
				else {
					Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES);
				}
			}
		}
	}

	private static String sha256(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[8192];
			for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
				digest.update(buffer, 0, read);
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

	private static void delete(Path root) {
		try (Stream<Path> files = Files.walk(root)) {
			for (Path file : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
				Files.delete(file);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not delete " + root, ex);
		}
	}

	private static void deleteQuietly(Path root) {
		try {
			delete(root);
		}
		catch (UncheckedIOException ex) {
			logger.debug("Could not delete {}", root, ex);
		}
	}
}
