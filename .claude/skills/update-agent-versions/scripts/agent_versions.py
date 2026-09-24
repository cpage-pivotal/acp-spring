#!/usr/bin/env python3
"""Compare this project's pinned goose, codex-acp and opencode versions with the latest
releases, and (with `apply`) move the pins forward.

Each pin follows its own source of truth:

- the goose, codex-acp and opencode entries in the bundled registry snapshot follow the
  live ACP registry, copied verbatim, because the snapshot must carry the registry's own
  download URLs and SHA-256s;
- CodexRuntime.DEFAULT_PACKAGE (and the docs that quote it) follows npm's `latest` tag,
  because that is what `npx` resolves.

GitHub's latest release is reported alongside, only to show when the registry lags upstream.

Usage (from the repository root):
  agent_versions.py check    exit 0 when every pin is current, 10 when one is behind
  agent_versions.py apply    rewrite the pins that are behind; prints what changed
"""

import json
import re
import sys
import urllib.request
from pathlib import Path

REGISTRY_URL = "https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json"
NPM_URL = "https://registry.npmjs.org/@agentclientprotocol/codex-acp/latest"
SNAPSHOT = Path("spring-ai-acp-runtime-registry/src/main/resources/org/springaicommunity/acp/registry/registry-snapshot.json")
CODEX_RUNTIME = Path("spring-ai-acp-runtime-codex/src/main/java/org/springaicommunity/acp/codex/CodexRuntime.java")
CODEX_PACKAGE_FILES = [CODEX_RUNTIME, Path("docs/user-guide.html")]
CODEX_PACKAGE = "@agentclientprotocol/codex-acp@"

# registry id -> GitHub repository
AGENTS = {
    "goose": "block/goose",
    "codex-acp": "agentclientprotocol/codex-acp",
    "opencode": "anomalyco/opencode",
}


def fetch_json(url):
    request = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "acp-spring-version-check"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def version_key(version):
    """Numeric release tuple; a pre-release sorts below its release."""
    match = re.match(r"v?(\d+)\.(\d+)\.(\d+)(-.+)?$", version)
    if not match:
        raise ValueError(f"not a version: {version}")
    major, minor, patch, pre = match.groups()
    return (int(major), int(minor), int(patch), 0 if pre else 1)


def newer(candidate, current):
    return version_key(candidate) > version_key(current)


def github_latest(repo):
    try:
        return fetch_json(f"https://api.github.com/repos/{repo}/releases/latest")["tag_name"].lstrip("v")
    except Exception as error:  # informational only
        return f"unavailable ({error})"


def load_snapshot():
    return json.loads(SNAPSHOT.read_text())


def write_snapshot(snapshot):
    # json.dumps with indent=2 and ASCII escapes reproduces the file byte for byte, so the
    # diff shows only the entries that changed.
    SNAPSHOT.write_text(json.dumps(snapshot, indent=2) + "\n")


def current_codex_package():
    match = re.search(r'DEFAULT_PACKAGE = "' + re.escape(CODEX_PACKAGE) + r'([^"]+)"', CODEX_RUNTIME.read_text())
    if not match:
        sys.exit(f"DEFAULT_PACKAGE not found in {CODEX_RUNTIME}")
    return match.group(1)


def plan():
    registry = fetch_json(REGISTRY_URL)
    live = {agent["id"]: agent for agent in registry["agents"]}
    snapshot = load_snapshot()
    pinned = {agent["id"]: agent for agent in snapshot["agents"]}

    rows = []
    for agent_id, repo in AGENTS.items():
        if agent_id not in live:
            sys.exit(f"{agent_id} is missing from the live registry; stop and investigate")
        rows.append({
            "pin": f"registry snapshot: {agent_id}",
            "agent": agent_id,
            "current": pinned[agent_id]["version"],
            "latest": live[agent_id]["version"],
            "github": github_latest(repo),
        })
    rows.append({
        "pin": "CodexRuntime.DEFAULT_PACKAGE",
        "agent": "codex-acp-npm",
        "current": current_codex_package(),
        "latest": fetch_json(NPM_URL)["version"],
        "github": github_latest(AGENTS["codex-acp"]),
    })
    for row in rows:
        row["behind"] = newer(row["latest"], row["current"])
    return rows, snapshot, live


def print_rows(rows):
    print(f"{'pin':<38} {'current':<10} {'latest':<10} {'github':<10} status")
    for row in rows:
        status = "BEHIND" if row["behind"] else "current"
        if not row["github"].startswith("unavailable") and newer(row["github"], row["latest"]):
            status += " (source lags GitHub)"
        print(f"{row['pin']:<38} {row['current']:<10} {row['latest']:<10} {row['github']:<10} {status}")


def check():
    rows, _, _ = plan()
    print_rows(rows)
    return 10 if any(row["behind"] for row in rows) else 0


def apply():
    rows, snapshot, live = plan()
    print_rows(rows)
    changed = False
    for row in rows:
        if not row["behind"]:
            continue
        changed = True
        if row["agent"] == "codex-acp-npm":
            old, new = CODEX_PACKAGE + row["current"], CODEX_PACKAGE + row["latest"]
            for path in CODEX_PACKAGE_FILES:
                text = path.read_text()
                if old in text:
                    path.write_text(text.replace(old, new))
                    print(f"updated {path}: {old} -> {new}")
        else:
            snapshot["agents"] = [live[row["agent"]] if agent["id"] == row["agent"] else agent
                                  for agent in snapshot["agents"]]
            print(f"updated {SNAPSHOT}: {row['agent']} {row['current']} -> {row['latest']}")
    if changed:
        write_snapshot(snapshot)
    else:
        print("nothing to update")
    return 0


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else "check"
    if command not in ("check", "apply"):
        sys.exit(__doc__)
    sys.exit(check() if command == "check" else apply())
