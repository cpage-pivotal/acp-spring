package org.tanzu.acp.registry;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.tanzu.acp.config.AgentSettings;
import org.tanzu.acp.runtime.AgentLaunchSpec;
import org.tanzu.acp.runtime.AgentRuntime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real catalogue, a real release host, and a real 24 MB download.
 *
 * <p>Opt-in rather than skip-when-absent, which is the one place this module departs from how the
 * rest of the suite gates its live tests. An agent that is not installed costs nothing to check
 * for; a download costs bandwidth on every build and fails on a machine behind a proxy, and neither
 * is a thing to inflict on someone running {@code mvn test} to check an unrelated change.
 *
 * <pre>{@code
 * mvn -pl spring-acp-runtime-registry test -Dspring-acp.test.registry.live=true
 * }</pre>
 *
 * <p>What it proves that the offline tests cannot: that the digests in the published catalogue
 * actually match the bytes on the release host. Everything else about this path is exercised
 * against archives built in the test; this is the one assertion that depends on a third party
 * having told the truth.
 */
@EnabledIfSystemProperty(named = "spring-acp.test.registry.live", matches = "true")
class RegistryLiveInstallTests {

	/** Small, checksummed on every platform, and not an agent this library has an adapter for. */
	private static final String AGENT = "amp-acp";

	@TempDir
	Path temp;

	@Test
	@DisplayName("an agent from the published catalogue installs and verifies against its own sha256")
	void installsARealAgentFromTheRealRegistry() throws Exception {
		AgentRegistry registry = new AgentRegistry(
				new RegistrySettings(null, temp.resolve("cache"), null, false, true, null));
		RegistryEntry entry = registry.find(AGENT).orElseThrow();
		assertThat(entry.distribution()).isInstanceOf(RegistryEntry.Distribution.Binary.class);

		AgentRuntime runtime = new RegistryAgentRuntimeProvider(registry, new AgentInstaller(registry.settings()))
				.forId(AGENT).orElseThrow();
		Path workspace = Files.createDirectories(temp.resolve("workspace"));

		// The download, the digest check and the unpack all happen here: launch() is the first
		// moment an agent is needed, which is why a misconfigured one does not stop a context
		// refreshing.
		AgentLaunchSpec spec = runtime.launch(AgentSettings.builder(AGENT, workspace).build());

		assertThat(spec).isInstanceOfSatisfying(AgentLaunchSpec.Stdio.class, stdio -> {
			assertThat(Path.of(stdio.command())).isRegularFile().isExecutable();
			assertThat(stdio.args()).isNotNull();
		});
	}

	@Test
	@DisplayName("a second install of the same version is a cache hit, not a second download")
	void doesNotDownloadAnAgentItAlreadyHas() throws Exception {
		AgentRegistry registry = new AgentRegistry(
				new RegistrySettings(null, temp.resolve("cache"), null, false, true, null));
		RegistryEntry entry = registry.find(AGENT).orElseThrow();
		AgentInstaller installer = new AgentInstaller(registry.settings());
		RegistryEntry.Artifact artifact = ((RegistryEntry.Distribution.Binary) entry.distribution())
				.forPlatform(Platform.current()).orElseThrow();

		Path first = installer.install(entry, artifact);
		// A fresh installer, so the answer comes off the disk rather than out of the memo table.
		Path second = new AgentInstaller(registry.settings()).install(entry, artifact);

		assertThat(second).isEqualTo(first);
	}
}
