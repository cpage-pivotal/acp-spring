package org.springaicommunity.acp.skill;

import java.nio.file.Path;

import org.springaicommunity.acp.config.SkillSpec;

/**
 * Gets a skill's files onto disk. The seam between {@link SkillInstaller}, which decides what is safe
 * to install, and where the files come from, which a test replaces with a directory it writes.
 */
@FunctionalInterface
public interface SkillFetcher {

	/**
	 * Puts {@code skill}'s files somewhere under the empty directory {@code into}.
	 *
	 * @return where under {@code into} the skill's directory is, and what revision it was
	 */
	Fetched fetch(SkillSpec skill, Path into);

	/**
	 * @param directory the skill's own directory, the one holding {@code SKILL.md}
	 * @param revision the commit that was checked out, or {@code null} for a source without one
	 */
	record Fetched(Path directory, String revision) {
	}

	/** Bundled skills from this application's classpath, Git skills with the {@code git} on the PATH. */
	static SkillFetcher standard() {
		BundledSkillFetcher bundled = new BundledSkillFetcher();
		GitSkillFetcher git = new GitSkillFetcher();
		return (skill, into) -> switch (skill) {
			case SkillSpec.Bundled b -> bundled.fetch(b, into);
			case SkillSpec.Git g -> git.fetch(g, into);
		};
	}
}
