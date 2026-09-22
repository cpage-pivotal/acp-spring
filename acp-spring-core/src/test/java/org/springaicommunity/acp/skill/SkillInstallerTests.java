package org.springaicommunity.acp.skill;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.acp.config.SkillSpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the installer lets into the workspace, with the fetch replaced by a directory it writes. */
class SkillInstallerTests {

	private static final URI REPO = URI.create("https://github.com/owner/marketplace");

	private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

	private static final String SKILL_PATH = "plugins/mailgun/skills/mailgun";

	@TempDir
	Path workspace;

	private final SkillFetcher repository = (skill, into) -> {
		try {
			Path skillDir = Files.createDirectories(into.resolve(SKILL_PATH));
			Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: mailgun\n---\n");
			Path script = Files.createDirectories(skillDir.resolve("scripts")).resolve("send_email.py");
			Files.writeString(script, "print('sent')\n");
			script.toFile().setExecutable(true);
			Files.createDirectories(into.resolve(".git"));
			return new SkillFetcher.Fetched(into.resolve(((SkillSpec.Git) skill).path()), COMMIT);
		}
		catch (IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
	};

	@Test
	void installsTheSkillDirectoryUnderAgentsSkills() throws IOException {
		new SkillInstaller(repository).install(workspace, List.of(SkillSpec.Git.of(REPO, SKILL_PATH)));

		Path installed = workspace.resolve(".agents/skills/mailgun");
		assertThat(installed.resolve("SKILL.md")).exists();
		assertThat(Files.getPosixFilePermissions(installed.resolve("scripts/send_email.py")))
			.contains(PosixFilePermission.OWNER_EXECUTE);
		assertThat(Files.readString(installed.resolve(SkillInstaller.MARKER))).contains(COMMIT);
		assertThat(installed.resolve(".git")).doesNotExist();
	}

	@Test
	void reinstallingReplacesWhatItInstalledBefore() throws IOException {
		SkillInstaller installer = new SkillInstaller(repository);
		installer.install(workspace, List.of(SkillSpec.Git.of(REPO, SKILL_PATH)));
		Path stale = workspace.resolve(".agents/skills/mailgun/stale.txt");
		Files.writeString(stale, "left over");

		installer.install(workspace, List.of(SkillSpec.Git.of(REPO, SKILL_PATH)));

		assertThat(stale).doesNotExist();
	}

	@Test
	void aSkillNoLongerConfiguredIsRemovedButOnlyIfItWasInstalledHere() throws IOException {
		SkillInstaller installer = new SkillInstaller(repository);
		installer.install(workspace, List.of(SkillSpec.Git.of(REPO, SKILL_PATH)));
		Path handMade = Files.createDirectories(workspace.resolve(".agents/skills/hand-made"));
		Files.writeString(handMade.resolve("SKILL.md"), "mine");

		installer.install(workspace, List.of());

		assertThat(workspace.resolve(".agents/skills/mailgun")).doesNotExist();
		assertThat(handMade.resolve("SKILL.md")).exists();
	}

	@Test
	void doesNotOverwriteASkillItDidNotInstall() throws IOException {
		Path handMade = Files.createDirectories(workspace.resolve(".agents/skills/mailgun"));
		Files.writeString(handMade.resolve("SKILL.md"), "mine");

		assertThatThrownBy(() -> new SkillInstaller(repository).install(workspace,
				List.of(SkillSpec.Git.of(REPO, SKILL_PATH))))
			.isInstanceOf(SkillInstallException.class).hasMessageContaining("not installed by acp-spring");
		assertThat(Files.readString(handMade.resolve("SKILL.md"))).isEqualTo("mine");
	}

	@Test
	void aPathWithoutSkillMdIsRefused() {
		assertThatThrownBy(() -> new SkillInstaller(repository).install(workspace,
				List.of(new SkillSpec.Git("scripts", REPO, null, SKILL_PATH + "/scripts", null, null))))
			.isInstanceOf(SkillInstallException.class).hasMessageContaining("no SKILL.md");
	}

	@Test
	void aPathMissingFromTheRepositoryIsRefused() {
		assertThatThrownBy(() -> new SkillInstaller(repository).install(workspace,
				List.of(SkillSpec.Git.of(REPO, "plugins/nothing"))))
			.isInstanceOf(SkillInstallException.class).hasMessageContaining("is not a directory");
	}

	@Test
	void aSymbolicLinkIsRefused() {
		SkillFetcher withLink = (skill, into) -> {
			SkillFetcher.Fetched fetched = repository.fetch(skill, into);
			try {
				Files.createSymbolicLink(into.resolve(SKILL_PATH).resolve("secrets"), Path.of("/etc"));
			}
			catch (IOException ex) {
				throw new java.io.UncheckedIOException(ex);
			}
			return fetched;
		};

		assertThatThrownBy(() -> new SkillInstaller(withLink).install(workspace,
				List.of(SkillSpec.Git.of(REPO, SKILL_PATH))))
			.isInstanceOf(SkillInstallException.class).hasMessageContaining("symbolic link");
		assertThat(workspace.resolve(".agents/skills/mailgun")).doesNotExist();
	}

	@Test
	void aBundledSkillRecordsNoCommit() throws IOException {
		SkillFetcher bundled = (skill, into) -> {
			SkillFetcher.Fetched fetched = repository.fetch(SkillSpec.Git.of(REPO, SKILL_PATH), into);
			return new SkillFetcher.Fetched(fetched.directory(), null);
		};

		new SkillInstaller(bundled).install(workspace, List.of(SkillSpec.Bundled.of("skills/mailgun")));

		assertThat(Files.readString(workspace.resolve(".agents/skills/mailgun").resolve(SkillInstaller.MARKER)))
			.isEqualTo("mailgun (classpath:skills/mailgun)\n");
	}

	@Test
	void aPinnedRefMustBeWhatWasFetched() {
		SkillSpec pinned = new SkillSpec.Git(null, REPO, "f".repeat(40), SKILL_PATH, null, null);

		assertThatThrownBy(() -> new SkillInstaller(repository).install(workspace, List.of(pinned)))
			.isInstanceOf(SkillInstallException.class).hasMessageContaining("not the pinned");
	}

	@Test
	void aDigestIsCheckedWhenGiven() {
		SkillSpec wrong = new SkillSpec.Git(null, REPO, null, SKILL_PATH, null, "0".repeat(64));

		assertThatThrownBy(() -> new SkillInstaller(repository).install(workspace, List.of(wrong)))
			.isInstanceOf(SkillInstallException.class).hasMessageContaining("sha256");
	}
}
