---
name: update-agent-versions
description: Check whether this project pins the latest released goose, codex-acp and opencode, and if not, bump the pins on a branch, build, and open a GitHub pull request. Use when asked to update, bump, refresh or check the agent versions (goose, codex, opencode), the bundled ACP registry snapshot, or codex-acp's default npm package.
---

# Update agent versions

This library does not package agent binaries, so "the version this project uses" means two
kinds of pin, each with its own source of truth:

| Pin | Where | Source of truth |
| --- | --- | --- |
| `goose`, `codex-acp`, `opencode` entries in the bundled registry snapshot | `spring-ai-acp-runtime-registry/src/main/resources/org/springaicommunity/acp/registry/registry-snapshot.json` | the live ACP registry, copied verbatim (it carries the download URLs and SHA-256s; never hand-write them) |
| `CodexRuntime.DEFAULT_PACKAGE` and the docs that quote it | `CodexRuntime.java` (field and class javadoc), `docs/user-guide.html` | npm's `latest` tag for `@agentclientprotocol/codex-acp`, which is what `npx` runs |

GitHub's latest release is shown only to spot a registry that lags upstream. If it does, pin
what the registry publishes and say so in the PR; do not invent a snapshot entry.

A third kind of mention is **not** a pin: "Verified against", "Measured against" and "captured
from" notes in code comments, tests, `README.md` (Status and the session-capability table) and
`docs/user-guide.html`. Each records a measurement made on that version. Never bump one just
because a newer release exists; see step 5.

## Steps

Run everything from the repository root.

1. **Check.**
   ```bash
   python3 .claude/skills/update-agent-versions/scripts/agent_versions.py check
   ```
   Exit 0 means every pin is current: report the table and stop. Exit 10 means at least one is
   behind; continue.

2. **Branch from an up-to-date `main`.** Direct changes to `main` are not accepted. Leave any
   unrelated local changes alone (stage only the files this skill touches); if they would
   conflict with the checkout, stop and ask.
   ```bash
   git fetch origin && git switch -c agent-versions-$(date +%Y-%m-%d) origin/main
   ```
   If an open PR from an earlier run already exists (`gh pr list --search "Update pinned agent versions" --state open`),
   update that branch instead of opening a second PR.

3. **Apply.**
   ```bash
   python3 .claude/skills/update-agent-versions/scripts/agent_versions.py apply
   git diff --stat
   ```
   The snapshot diff must contain only the bumped entries. Other agents in the snapshot may be
   stale too; this skill leaves them alone unless the user asks for a full refresh.

4. **Build.** `./mvnw spring-javaformat:apply && ./mvnw install`. The always-on suite must
   pass. `AgentRegistryTests` reads the bundled snapshot, so a failure there usually means the
   registry changed an entry's shape (e.g. a new distribution kind), which needs a code change
   and a human decision, not a test edit. Report it and stop.

5. **Live verification (opt-in, ask first).** The live suites spend real tokens, and moving a
   local install (`brew upgrade --cask block-goose`, `brew upgrade opencode`) changes the user's machine, so do
   neither without a yes. Codex needs no install: its live suite runs `npx` with the new
   `DEFAULT_PACKAGE`. Check what is installed with `goose --version` and `opencode --version`.
   With the user's go-ahead and the agent at the new version:
   ```bash
   ./mvnw -pl spring-ai-acp-runtime-goose test -Dspring-ai-acp.test.live=true      # likewise -codex, -opencode
   ```
   A live suite that *skips* (no credentials, agent absent) verified nothing. Only for a
   runtime whose suite actually ran and passed, update the version in `README.md`'s Status
   paragraph and its session-capability table row, after confirming the capability columns
   still hold (`sessions().supports(...)` against the new agent; the contract suite skips
   unsupported operations rather than failing). Leave every "Measured against" note in code
   and docs as it is unless that specific measurement was repeated.

6. **Commit, signed, one commit.** Stage only the touched files by path.
   ```bash
   git add <files> && git commit -S -m "Update pinned agent versions: goose X, codex-acp Y, opencode Z"
   ```
   Name only the agents that changed. The body lists each old → new pin and whether the live
   suites ran. End it with the attribution lines the session asks for.

7. **Push and open the PR.**
   ```bash
   git push -u origin HEAD
   gh pr create --base main --title "<commit subject>" --body-file <file>
   ```
   The body: the check table (current, latest, GitHub), links to each upstream release
   (`https://github.com/<repo>/releases/tag/v<version>`), the build result, which live suites
   ran (or that none did, so the README's verified versions are unchanged), and any registry
   lag. Give the user the PR URL.

## Gotchas

- `codex-acp` appears twice with different sources: the snapshot entry (registry) and
  `DEFAULT_PACKAGE` (npm). They can legitimately differ for a while; update each from its own
  source.
- `CodexRuntimeTests` pins `codex-acp@1.11.0` on purpose to test the `package` override. The
  script only replaces the exact old default, so it leaves that alone; keep it that way.
- The snapshot is written with `json.dumps(indent=2)` and ASCII escapes, which reproduces the
  file byte for byte. If the diff suddenly touches every line, the file's format changed;
  fix the writer rather than committing the churn.
- Never downgrade: the script bumps only when latest is strictly newer.
