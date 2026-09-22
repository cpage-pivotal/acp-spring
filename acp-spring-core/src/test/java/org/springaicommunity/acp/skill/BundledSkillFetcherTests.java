package org.springaicommunity.acp.skill;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.acp.config.SkillSpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Both shapes a classpath comes in: a directory, as an IDE runs it, and a jar, as {@code java -jar}
 * does. Spring Boot's nested jar is the second shape behind its own {@code JarURLConnection}, and is
 * exercised by running an application, not here.
 */
class BundledSkillFetcherTests {

	private static final String SKILL_MD = "---\nname: mailgun\n---\n";

	private static final String SCRIPT = "#!/usr/bin/env python3\nprint('sent')\n";

	@TempDir
	Path temp;

	@Test
	void copiesASkillFromAClasspathDirectory() throws IOException {
		Path classes = temp.resolve("classes");
		Path skill = Files.createDirectories(classes.resolve("skills/mailgun/scripts"));
		Files.writeString(skill.getParent().resolve("SKILL.md"), SKILL_MD);
		Files.writeString(skill.resolve("send_email.py"), SCRIPT);
		Files.writeString(Files.createDirectories(classes.resolve("skills/other")).resolve("SKILL.md"), "other");

		Path into = fetch(classes, "skills/mailgun");

		assertThat(into.resolve("SKILL.md")).hasContent(SKILL_MD);
		assertThat(into.resolve("scripts/send_email.py")).hasContent(SCRIPT);
		assertThat(into.resolve("other")).doesNotExist();
	}

	@Test
	void copiesASkillFromAJarAndMarksItsScriptsExecutable() throws IOException {
		Path jar = temp.resolve("app.jar");
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
			// No directory entries, which a jar is allowed to leave out.
			put(out, "skills/mailgun/SKILL.md", SKILL_MD);
			put(out, "skills/mailgun/scripts/send_email.py", SCRIPT);
			put(out, "skills/mailgun/scripts/template.html", "<p/>");
			put(out, "skills/mailgunner/SKILL.md", "a different skill with a common prefix");
		}

		Path into = fetch(jar, "skills/mailgun");

		assertThat(into.resolve("SKILL.md")).hasContent(SKILL_MD);
		assertThat(into.resolve("scripts/send_email.py")).isExecutable();
		assertThat(into.resolve("scripts/template.html").toFile().canExecute()).isFalse();
		assertThat(into.resolve("mailgunner")).doesNotExist();
		assertThat(Files.list(into).map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("SKILL.md",
				"scripts");
	}

	@Test
	void aSkillMissingFromTheClasspathSaysWhatItLookedFor() throws IOException {
		Path classes = Files.createDirectories(temp.resolve("classes"));

		assertThatThrownBy(() -> fetch(classes, "skills/mailgun")).isInstanceOf(SkillInstallException.class)
			.hasMessageContaining("skills/mailgun/SKILL.md");
	}

	private Path fetch(Path classpathRoot, String path) throws IOException {
		Path into = Files.createDirectories(temp.resolve("into"));
		try (URLClassLoader loader = new URLClassLoader(new URL[] { classpathRoot.toUri().toURL() }, null)) {
			SkillFetcher.Fetched fetched = new BundledSkillFetcher(loader).fetch(SkillSpec.Bundled.of(path), into);
			assertThat(fetched.revision()).isNull();
			return fetched.directory();
		}
	}

	private static void put(JarOutputStream out, String name, String content) throws IOException {
		out.putNextEntry(new JarEntry(name));
		out.write(content.getBytes(StandardCharsets.UTF_8));
		out.closeEntry();
	}
}
