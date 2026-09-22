package org.springaicommunity.acp.skill;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.acp.config.SkillSpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The git sequence against a real repository on local disk. {@link SkillSpec.Git} only accepts
 * HTTPS, so the spec here is built for an HTTPS URL and the fetcher is pointed at the local one
 * through git's own {@code url.<base>.insteadOf}, which is the part of the URL git actually resolves.
 */
class GitSkillFetcherTests {

	private static final URI REPO = URI.create("https://example.invalid/owner/marketplace");

	private static final String SKILL_PATH = "plugins/mailgun/skills/mailgun";

	@TempDir
	Path temp;

	private Path origin;

	private Path config;

	@BeforeEach
	void createRepository() throws Exception {
		assumeTrue(gitAvailable(), "git is not on the PATH");
		origin = Files.createDirectories(temp.resolve("origin"));
		git(origin, "init", "-q", "-b", "main");
		Path skill = Files.createDirectories(origin.resolve(SKILL_PATH));
		Files.writeString(skill.resolve("SKILL.md"), "---\nname: mailgun\n---\n");
		Files.writeString(origin.resolve("README.md"), "not part of the skill");
		git(origin, "add", ".");
		git(origin, "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "one");
		// Lets a shallow fetch name a commit, as GitHub does.
		git(origin, "config", "uploadpack.allowAnySHA1InWant", "true");

		config = temp.resolve("gitconfig");
		Files.writeString(config, "[url \"" + origin.toUri() + "\"]\n\tinsteadOf = " + REPO + "\n"
				+ "[protocol \"file\"]\n\tallow = always\n");
	}

	@Test
	void fetchesTheDefaultBranch() throws Exception {
		Path into = Files.createDirectories(temp.resolve("checkout"));

		SkillFetcher.Fetched fetched = fetcher().fetch(SkillSpec.Git.of(REPO, SKILL_PATH), into);

		assertThat(fetched.revision()).isEqualTo(git(origin, "rev-parse", "HEAD").strip());
		assertThat(fetched.directory()).isEqualTo(into.resolve(SKILL_PATH));
		assertThat(fetched.directory().resolve("SKILL.md")).exists();
		assertThat(into.resolve("README.md")).doesNotExist();
	}

	@Test
	void fetchesAPinnedCommit() throws Exception {
		String head = git(origin, "rev-parse", "HEAD").strip();
		Path into = Files.createDirectories(temp.resolve("checkout"));

		SkillFetcher.Fetched fetched = fetcher().fetch(new SkillSpec.Git(null, REPO, head, SKILL_PATH, null, null),
				into);

		assertThat(fetched.revision()).isEqualTo(head);
	}

	/**
	 * Against a loopback HTTP server, which is the one place plain HTTP is allowed: what matters is
	 * the header git actually sent, and the server refusing everything afterwards is fine.
	 */
	@Test
	void sendsTheTokenAsBasicCredentialsToThatRepository() throws Exception {
		List<String> authorizations = new CopyOnWriteArrayList<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
			exchange.sendResponseHeaders(404, -1);
			exchange.close();
		});
		server.start();
		try {
			URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/owner/private");
			SkillSpec.Git skill = new SkillSpec.Git(null, url, null, "skills/a", "ghp_secret", null);
			Path into = Files.createDirectories(temp.resolve("checkout"));

			assertThatThrownBy(() -> fetcher().fetch(skill, into)).isInstanceOf(SkillInstallException.class)
				.message().doesNotContain("ghp_secret");

			String expected = "Basic " + Base64.getEncoder()
				.encodeToString("x-access-token:ghp_secret".getBytes(StandardCharsets.UTF_8));
			assertThat(authorizations).isNotEmpty().allMatch(expected::equals);
			assertThat(Files.readString(into.resolve(".git/config"))).doesNotContain("ghp_secret");
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void theTokenIsAppendedAfterTheEnvironmentsOwnGitConfig() {
		SkillSpec.Git skill = new SkillSpec.Git(null, REPO, null, "a", "t", null);

		Map<String, String> env = GitSkillFetcher.credentials(skill, Map.of("GIT_CONFIG_COUNT", "2"));

		assertThat(env).containsEntry("GIT_CONFIG_COUNT", "3")
			.containsEntry("GIT_CONFIG_KEY_2", "http." + REPO + ".extraHeader")
			.containsKey("GIT_CONFIG_VALUE_2");
	}

	@Test
	void withoutATokenNothingIsAdded() {
		assertThat(GitSkillFetcher.credentials(SkillSpec.Git.of(REPO, "a"), Map.of())).isEmpty();
	}

	/** git with this test's config only, so the developer's own cannot change the outcome. */
	private GitSkillFetcher fetcher() throws IOException {
		Path wrapper = temp.resolve("git.sh");
		Files.writeString(wrapper,
				"#!/bin/sh\nGIT_CONFIG_GLOBAL='" + config + "' GIT_CONFIG_NOSYSTEM=1 exec git \"$@\"\n");
		wrapper.toFile().setExecutable(true);
		return new GitSkillFetcher(wrapper.toString(), Map.of());
	}

	private static boolean gitAvailable() {
		try {
			return new ProcessBuilder("git", "--version").start().waitFor() == 0;
		}
		catch (Exception ex) {
			return false;
		}
	}

	private static String git(Path directory, String... arguments) throws Exception {
		List<String> command = new ArrayList<>(List.of("git"));
		command.addAll(List.of(arguments));
		Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor()).as(output).isZero();
		return output;
	}
}
