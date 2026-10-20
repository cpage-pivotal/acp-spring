package org.springaicommunity.acp.config;

import java.net.URI;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Everything in a skill ends up on a git command line, in a classpath lookup or as a path
 * on disk, so the rules are the buildpack's and they are strict.
 */
class SkillSpecTests {

	private static final URI REPO = URI.create("https://github.com/owner/marketplace");

	@Test
	void theNameDefaultsToTheLastSegmentOfThePath() {
		assertThat(SkillSpec.Git.of(REPO, "plugins/mailgun/skills/mailgun/").name()).isEqualTo("mailgun");
		assertThat(SkillSpec.Bundled.of("skills/mailgun").name()).isEqualTo("mailgun");
	}

	@Test
	void withoutAPathAGitSkillIsNamedForItsRepository() {
		assertThat(SkillSpec.Git.of(URI.create("https://github.com/owner/my-skill.git"), null).name())
			.isEqualTo("my-skill");
	}

	@Test
	void aBundledSkillNeedsAPath() {
		assertThatIllegalArgumentException().isThrownBy(() -> SkillSpec.Bundled.of(" "));
	}

	@Test
	void onlyAFullCommitCountsAsPinned() {
		assertThat(new SkillSpec.Git(null, REPO, "main", "a", null, null).isPinned()).isFalse();
		assertThat(
				new SkillSpec.Git(null, REPO, "0123456789abcdef0123456789abcdef01234567", "a", null, null).isPinned())
			.isTrue();
		assertThat(SkillSpec.Git.of(REPO, "a").isPinned()).isFalse();
	}

	@Test
	void aPathMustStayInsideItsRoot() {
		assertThatIllegalArgumentException().isThrownBy(() -> SkillSpec.Git.of(REPO, "../etc"));
		assertThatIllegalArgumentException().isThrownBy(() -> SkillSpec.Git.of(REPO, "skills/../../etc"));
		assertThatIllegalArgumentException().isThrownBy(() -> SkillSpec.Bundled.of("/etc"));
		assertThatIllegalArgumentException().isThrownBy(() -> SkillSpec.Bundled.of("skills/../../etc"));
	}

	@Test
	void aRefCannotBeReadAsAGitOption() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new SkillSpec.Git(null, REPO, "--upload-pack=x", "a", null, null));
	}

	@Test
	void theRepositoryMustBeHttpsWithoutCredentials() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> SkillSpec.Git.of(URI.create("http://github.com/owner/repo"), "a"));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> SkillSpec.Git.of(URI.create("https://user:token@github.com/owner/repo"), "a"));
	}

	@Test
	void theTokenIsNeverPrinted() {
		SkillSpec.Git skill = new SkillSpec.Git(null, REPO, null, "a", "ghp_super-secret", null);

		assertThat(skill.toString()).doesNotContain("super-secret").contains("<redacted>");
		assertThat(skill.describe()).doesNotContain("super-secret");
	}

	@Test
	void aTokenCannotCarryALineBreak() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new SkillSpec.Git(null, REPO, null, "a", "ghp_x\nInjected: yes", null));
	}

	@Test
	void theNameMustBeSafeAsADirectory() {
		assertThatIllegalArgumentException().isThrownBy(() -> new SkillSpec.Bundled("Mail Gun", "a", null));
	}

	@Test
	void aDigestMustBeSha256() {
		assertThatIllegalArgumentException().isThrownBy(() -> new SkillSpec.Bundled(null, "a", "abc"));
		assertThat(new SkillSpec.Bundled(null, "a", "A".repeat(64)).sha256()).isEqualTo("a".repeat(64));
	}

}
