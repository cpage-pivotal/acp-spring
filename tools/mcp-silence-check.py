#!/usr/bin/env python3
"""Measure whether goose says anything when it cannot reach an MCP server.

An MCP server an agent fails to connect to is invisible to an ACP client: the session opens
normally, no session/update arrives, and the only symptom is the model later saying it has no
tools. This script measures exactly where that silence holds, across the two axes that differ
between spring-ai-acp and the goose-buildpack java-wrapper:

  transport  stdio       `goose acp` on a pipe          -- spring-ai-acp's default
             serve       `goose serve` over WebSocket   -- the wrapper's only mode, and
                                                           spring-ai-acp's tier-3 serve.transport
  declared   session-new MCP servers in session/new     -- spring-ai-acp's portable mcp-servers
             config-yaml extensions in config.yaml      -- the wrapper's route (it always sends
                                                           an empty mcpServers array on purpose)

For each of the four cells it starts a real goose against a deliberately unreachable MCP server,
opens a session, and reports three things: what session/new answered, whether any session/update
arrived, and whether the agent's own stdout/stderr carried a word about the failure. A cell is
LOUD if the process output names the failure and SILENT if nothing anywhere did.

No turn is run, so this spends no tokens. That is sound because goose connects its MCP servers
during session/new -- an MCP endpoint that needs auth and is given none blocks session/new until
the turn timeout, which is how this failure class was first found. If a future goose defers the
connection to the first turn, this script would report SILENT for the wrong reason; --turn sends
one cheap prompt to rule that out, and that one DOES spend tokens.

Usage:
  python3 tools/mcp-silence-check.py                        # all four cells
  python3 tools/mcp-silence-check.py --cell serve/config-yaml
  python3 tools/mcp-silence-check.py --broken stdio         # a local command that exits instead
  python3 tools/mcp-silence-check.py --turn                 # also send one prompt (spends tokens)

Exit status is 0 when every cell was measured, 2 when a cell could not be measured (goose refused
to start, or session/new failed for a reason unrelated to MCP), and 1 with --require-loud if any
cell was SILENT.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import queue
import re
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

# A port nothing listens on. Port 9 (discard) is reserved and refuses fast, so the failure is a
# connection refusal rather than a timeout -- the loudest case the agent could possibly report.
UNREACHABLE_URL = "http://127.0.0.1:9/mcp"

SERVER_NAME = "silence-probe-mcp"

# What a line has to look like to count as the agent saying something. Deliberately generous:
# a false LOUD is easy to check by eye from the printed line, a false SILENT would be a wrong
# answer to the question this script exists to ask.
DIAGNOSTIC = re.compile(
    r"(extension|mcp|tool)", re.IGNORECASE)
FAILURE = re.compile(
    r"(fail|error|refus|unreach|timed?.?out|warn|cannot|could not|unable|⚠)", re.IGNORECASE)


class Agent:
	"""One goose process and the ACP connection to it."""

	def __init__(self, process: subprocess.Popen, send, messages: "queue.Queue[dict]", output: list[str]):
		self.process = process
		self._send = send
		self.messages = messages
		self.output = output
		self.notifications: list[dict] = []
		self._next_id = 0

	def request(self, method: str, params: dict, timeout: float) -> dict:
		self._next_id += 1
		request_id = self._next_id
		self._send(json.dumps({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}))
		deadline = time.monotonic() + timeout
		while True:
			remaining = deadline - time.monotonic()
			if remaining <= 0:
				raise TimeoutError(f"no response to {method} within {timeout:.0f}s")
			try:
				message = self.messages.get(timeout=remaining)
			except queue.Empty:
				continue
			if message.get("id") == request_id and ("result" in message or "error" in message):
				return message
			self._handle(message)

	def settle(self, seconds: float) -> None:
		"""Collects whatever arrives after a request, which is where an update would appear."""
		deadline = time.monotonic() + seconds
		while True:
			remaining = deadline - time.monotonic()
			if remaining <= 0:
				return
			try:
				self._handle(self.messages.get(timeout=remaining))
			except queue.Empty:
				return

	def _handle(self, message: dict) -> None:
		if "method" not in message:
			return
		if "id" in message:
			# An agent-to-client request. Nothing here can serve one, and refusing is honest.
			self._send(json.dumps({"jsonrpc": "2.0", "id": message["id"],
					"error": {"code": -32601, "message": "not implemented by mcp-silence-check"}}))
			return
		self.notifications.append(message)

	def close(self) -> None:
		try:
			self.process.terminate()
			self.process.wait(timeout=10)
		except Exception:
			self.process.kill()


class WebSocket:
	"""The smallest RFC 6455 client that can carry ACP, so this script needs no dependencies."""

	def __init__(self, host: str, port: int, path: str, headers: dict[str, str]):
		self.sock = socket.create_connection((host, port), timeout=30)
		key = base64.b64encode(os.urandom(16)).decode()
		lines = [f"GET {path} HTTP/1.1", f"Host: {host}:{port}", "Upgrade: websocket",
				"Connection: Upgrade", f"Sec-WebSocket-Key: {key}", "Sec-WebSocket-Version: 13"]
		lines += [f"{name}: {value}" for name, value in headers.items()]
		self.sock.sendall(("\r\n".join(lines) + "\r\n\r\n").encode())
		self.buffer = b""
		while b"\r\n\r\n" not in self.buffer:
			chunk = self.sock.recv(4096)
			if not chunk:
				raise ConnectionError("goose serve closed the connection during the WebSocket upgrade")
			self.buffer += chunk
		head, self.buffer = self.buffer.split(b"\r\n\r\n", 1)
		status = head.split(b"\r\n", 1)[0].decode(errors="replace")
		if "101" not in status:
			raise ConnectionError(f"goose serve refused the WebSocket upgrade: {status}")

	def _read(self, count: int) -> bytes:
		while len(self.buffer) < count:
			chunk = self.sock.recv(65536)
			if not chunk:
				raise ConnectionError("connection closed")
			self.buffer += chunk
		taken, self.buffer = self.buffer[:count], self.buffer[count:]
		return taken

	def send_text(self, text: str) -> None:
		payload = text.encode()
		header = bytearray([0x81])
		mask = os.urandom(4)
		length = len(payload)
		if length < 126:
			header.append(0x80 | length)
		elif length < (1 << 16):
			header.append(0x80 | 126)
			header += struct.pack("!H", length)
		else:
			header.append(0x80 | 127)
			header += struct.pack("!Q", length)
		header += mask
		masked = bytes(byte ^ mask[i % 4] for i, byte in enumerate(payload))
		self.sock.sendall(bytes(header) + masked)

	def recv_text(self) -> str | None:
		"""Returns the next text message, or None once the peer closes."""
		while True:
			first, second = self._read(2)
			opcode = first & 0x0F
			masked = second & 0x80
			length = second & 0x7F
			if length == 126:
				length = struct.unpack("!H", self._read(2))[0]
			elif length == 127:
				length = struct.unpack("!Q", self._read(8))[0]
			mask = self._read(4) if masked else b""
			payload = self._read(length)
			if masked:
				payload = bytes(byte ^ mask[i % 4] for i, byte in enumerate(payload))
			if opcode == 0x8:
				return None
			if opcode == 0x9:
				self.sock.sendall(bytes([0x8A, 0x80]) + os.urandom(4))
				continue
			if opcode in (0x1, 0x0):
				return payload.decode(errors="replace")


def config_yaml(broken: str | None, provider: str | None, model: str | None) -> str:
	"""goose's own config file, with the developer builtin so a session has at least one tool."""
	lines = ["GOOSE_TELEMETRY_ENABLED: false", "extensions:",
			"  developer:", "    enabled: true", "    type: builtin", "    name: developer",
			"    timeout: 300"]
	if broken == "http":
		lines += [f"  {SERVER_NAME}:", "    enabled: true", "    type: streamable_http",
				f"    name: {SERVER_NAME}", f"    uri: {UNREACHABLE_URL}", "    timeout: 30",
				"    envs: {}"]
	elif broken == "stdio":
		lines += [f"  {SERVER_NAME}:", "    enabled: true", "    type: stdio",
				f"    name: {SERVER_NAME}", "    cmd: /usr/bin/false", "    args: []",
				"    timeout: 30", "    envs: {}"]
	if provider:
		lines += ["providers:", f"  {provider}:", "    enabled: true", "    configured: true"]
		if model:
			lines.append(f"    model: {model}")
		lines.append(f"active_provider: {provider}")
	return "\n".join(lines) + "\n"


def log_lines(config_home: str) -> list[str]:
	"""Whatever goose wrote to its own log files during this run.

	A third channel, and the one that matters for the question this script exists to answer: a
	warning that reaches only a log file under the state directory is invisible to an ACP client
	and to the buildpack wrapper alike, both of which see the process\'s stdout and stderr and
	nothing else.
	"""
	found: list[str] = []
	# The path matters as much as the content: it is where an application would have to look.
	for root, _, files in os.walk(os.path.join(config_home, "state")):
		for name in files:
			try:
				path = os.path.join(root, name)
				shown = os.path.relpath(path, config_home)
				with open(path, errors="replace") as handle:
					for line in handle:
						if not ((DIAGNOSTIC.search(line) and FAILURE.search(line)) or SERVER_NAME in line):
							continue
						found.append(f"{shown}: {readable(line)}")
			except OSError:
				continue
	return found


def readable(line: str) -> str:
	"""goose logs structured JSON; the interesting part is level, target and message."""
	try:
		record = json.loads(line)
		return (f"{record.get('level', '?')} {record.get('target', '')} | "
				f"{record.get('fields', {}).get('message', '')}")
	except (json.JSONDecodeError, AttributeError):
		return line.rstrip()


def configured_provider() -> tuple[str | None, str | None]:
	"""The provider and model this machine's own goose is set up with.

	Read rather than required, so the check runs with no arguments. Only the provider's name is
	taken -- the credential stays wherever goose already keeps it.
	"""
	home = os.environ.get("XDG_CONFIG_HOME", os.path.expanduser("~/.config"))
	path = os.path.join(home, "goose", "config.yaml")
	try:
		with open(path) as handle:
			text = handle.read()
	except OSError:
		return None, None
	provider = re.search(r"^active_provider:\s*(\S+)", text, re.MULTILINE)
	if not provider:
		return None, None
	name = provider.group(1).strip("'\"")
	block = re.search(r"^  " + re.escape(name) + r":\n((?:    .*\n)*)", text, re.MULTILINE)
	model = re.search(r"^    model:\s*(\S+)", block.group(1), re.MULTILINE) if block else None
	return name, model.group(1).strip("'\"") if model else None


def mcp_server(broken: str) -> dict:
	if broken == "http":
		return {"type": "http", "name": SERVER_NAME, "url": UNREACHABLE_URL, "headers": []}
	return {"name": SERVER_NAME, "command": "/usr/bin/false", "args": [], "env": []}


def environment(home: str, args) -> dict[str, str]:
	"""The parent environment, redirected at a throwaway config home.

	Inherited on purpose: goose keeps API keys in the OS keyring or in the environment, and this
	needs a provider it can actually resolve or session/new fails before MCP is ever attempted.
	The keyring is deliberately NOT disabled for the same reason -- on a developer machine that is
	usually where the key is.
	"""
	env = dict(os.environ)
	env["XDG_CONFIG_HOME"] = home
	env["XDG_STATE_HOME"] = os.path.join(home, "state")
	env["XDG_DATA_HOME"] = os.path.join(home, "data")
	env["GOOSE_TELEMETRY_ENABLED"] = "false"
	if args.disable_keyring:
		env["GOOSE_DISABLE_KEYRING"] = "1"
	if args.provider:
		env["GOOSE_PROVIDER"] = args.provider
	if args.model:
		env["GOOSE_MODEL"] = args.model
	return env


def start_stdio(config_home: str, args) -> Agent:
	process = subprocess.Popen([args.goose, "acp"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
			stderr=subprocess.PIPE, env=environment(config_home, args), text=True, bufsize=1)
	messages: "queue.Queue[dict]" = queue.Queue()
	output: list[str] = []

	def read_protocol():
		for line in process.stdout:
			line = line.strip()
			if not line:
				continue
			try:
				messages.put(json.loads(line))
			except json.JSONDecodeError:
				# Not protocol, so it is the agent talking. Counts as output.
				output.append(line)

	def read_diagnostics():
		for line in process.stderr:
			output.append(line.rstrip())

	for target in (read_protocol, read_diagnostics):
		threading.Thread(target=target, daemon=True).start()

	def send(text: str) -> None:
		process.stdin.write(text + "\n")
		process.stdin.flush()

	return Agent(process, send, messages, output)


def start_serve(config_home: str, args) -> Agent:
	with socket.socket() as probe:
		probe.bind(("127.0.0.1", 0))
		port = probe.getsockname()[1]
	secret = base64.b16encode(os.urandom(16)).decode()
	env = environment(config_home, args)
	env["GOOSE_SERVER__SECRET_KEY"] = secret
	process = subprocess.Popen(
			[args.goose, "serve", "--host", "127.0.0.1", "--port", str(port)],
			stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
			env=env, text=True, bufsize=1)
	output: list[str] = []
	threading.Thread(target=lambda: [output.append(line.rstrip()) for line in process.stdout],
			daemon=True).start()

	deadline = time.monotonic() + args.startup_timeout
	while True:
		if process.poll() is not None:
			raise RuntimeError("goose serve exited during startup:\n  " + "\n  ".join(output[-10:]))
		try:
			urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2).read()
			break
		except (urllib.error.URLError, ConnectionError, OSError):
			if time.monotonic() > deadline:
				raise RuntimeError(f"goose serve was not healthy within {args.startup_timeout:.0f}s")
			time.sleep(0.2)

	websocket = WebSocket("127.0.0.1", port, "/acp", {"X-Secret-Key": secret})
	messages: "queue.Queue[dict]" = queue.Queue()

	def read_protocol():
		try:
			while True:
				text = websocket.recv_text()
				if text is None:
					return
				try:
					messages.put(json.loads(text))
				except json.JSONDecodeError:
					output.append(text)
		except (ConnectionError, OSError):
			return  # the process was terminated by this script

	threading.Thread(target=read_protocol, daemon=True).start()
	return Agent(process, websocket.send_text, messages, output)


def run_cell(transport: str, declared: str, args) -> dict:
	"""One measurement: start goose, open a session against a broken MCP server, listen."""
	result = {"transport": transport, "declared": declared, "session": "-", "updates": 0,
			"lines": [], "logs": [], "output": [], "methods": [], "verdict": "NOT MEASURED", "note": "", "detail": ""}
	config_home = tempfile.mkdtemp(prefix="mcp-silence-")
	workspace = tempfile.mkdtemp(prefix="mcp-silence-ws-")
	agent = None
	try:
		os.makedirs(os.path.join(config_home, "goose"), exist_ok=True)
		with open(os.path.join(config_home, "goose", "config.yaml"), "w") as handle:
			handle.write(config_yaml(args.broken if declared == "config-yaml" else None,
					args.provider, args.model))

		agent = start_stdio(config_home, args) if transport == "stdio" else start_serve(config_home, args)
		agent.request("initialize", {"protocolVersion": 1,
				"clientCapabilities": {"fs": {"readTextFile": False, "writeTextFile": False},
						"terminal": False}}, args.timeout)

		servers = [mcp_server(args.broken)] if declared == "session-new" else []
		started = time.monotonic()
		try:
			response = agent.request("session/new", {"cwd": workspace, "mcpServers": servers}, args.timeout)
		except TimeoutError:
			result["session"] = f"NO ANSWER in {args.timeout:.0f}s"
			result["verdict"] = "BLOCKED"
			result["note"] = "session/new never answered -- the loud-but-unhelpful failure mode"
			return result
		elapsed = time.monotonic() - started
		if "error" in response:
			result["session"] = f"error: {response['error'].get('message', '')[:80]}"
			result["detail"] = json.dumps(response["error"])[:2000]
			if not mentions_mcp(response["error"]):
				result["note"] = "session/new failed for its own reasons; the cell says nothing about MCP"
				return result
			result["verdict"] = "LOUD (over ACP)"
			return result

		session_id = response.get("result", {}).get("sessionId", "?")
		result["session"] = f"ok in {elapsed:.1f}s ({session_id[:8]})"

		if args.turn:
			try:
				agent.request("session/prompt", {"sessionId": session_id,
						"prompt": [{"type": "text", "text": "Reply with the single word: ok"}]},
						args.turn_timeout)
			except TimeoutError:
				result["note"] = "the turn did not finish in time"

		agent.settle(args.settle)
		result["updates"] = len(agent.notifications)
		result["output"] = list(agent.output)
		result["methods"] = sorted({n.get("method", "?") for n in agent.notifications})
		result["lines"] = [line for line in agent.output if DIAGNOSTIC.search(line) and FAILURE.search(line)]
		result["logs"] = log_lines(config_home)
		named = [n for n in agent.notifications if mentions_mcp(n)]
		if result["lines"]:
			result["verdict"] = "LOUD (process output)"
		elif named:
			result["verdict"] = "LOUD (over ACP)"
		elif result["logs"]:
			result["verdict"] = "LOG FILE ONLY"
		else:
			result["verdict"] = "SILENT"
		return result
	except Exception as ex:
		result["note"] = f"{type(ex).__name__}: {ex}"
		return result
	finally:
		if agent:
			result["output"] = result["output"] or list(agent.output)
			agent.close()
		if args.keep:
			result["kept"] = config_home
		else:
			shutil.rmtree(config_home, ignore_errors=True)
			shutil.rmtree(workspace, ignore_errors=True)


def mentions_mcp(payload) -> bool:
	text = json.dumps(payload)
	return bool(DIAGNOSTIC.search(text) and FAILURE.search(text)) or SERVER_NAME in text


def main() -> int:
	parser = argparse.ArgumentParser(description=__doc__,
			formatter_class=argparse.RawDescriptionHelpFormatter)
	parser.add_argument("--goose", default=os.environ.get("GOOSE_CLI_PATH", "goose"),
			help="the goose executable (default: GOOSE_CLI_PATH, else 'goose' on the PATH)")
	parser.add_argument("--cell", action="append", metavar="TRANSPORT/DECLARED",
			help="measure only this cell, e.g. serve/config-yaml; repeatable")
	parser.add_argument("--broken", choices=("http", "stdio"), default="http",
			help="what kind of unreachable MCP server to declare (default: http)")
	parser.add_argument("--provider", default=os.environ.get("GOOSE_PROVIDER"),
			help="provider to configure, if session/new needs one")
	parser.add_argument("--model", default=os.environ.get("GOOSE_MODEL"), help="model to configure")
	parser.add_argument("--timeout", type=float, default=90.0, help="per-request timeout in seconds")
	parser.add_argument("--settle", type=float, default=5.0,
			help="seconds to keep listening after the session opens (default: 5)")
	parser.add_argument("--startup-timeout", type=float, default=60.0,
			help="seconds to wait for 'goose serve' to report healthy")
	parser.add_argument("--turn", action="store_true",
			help="also send one prompt, to rule out MCP being connected lazily. SPENDS TOKENS.")
	parser.add_argument("--turn-timeout", type=float, default=180.0, help="timeout for --turn")
	parser.add_argument("--keep", action="store_true",
			help="keep each cell's throwaway config, state and workspace directories for inspection")
	parser.add_argument("--disable-keyring", action="store_true",
			help="start goose with GOOSE_DISABLE_KEYRING=1; only useful where the key is in the environment")
	parser.add_argument("--verbose", action="store_true",
			help="print the agent's own output and any error payload for each cell")
	parser.add_argument("--verbose-lines", type=int, default=15,
			help="how many trailing output lines --verbose prints (default: 15)")
	parser.add_argument("--require-loud", action="store_true",
			help="exit 1 if any cell was SILENT, for use as a regression gate")
	args = parser.parse_args()

	if not shutil.which(args.goose) and not os.path.exists(args.goose):
		print(f"goose not found: {args.goose}", file=sys.stderr)
		return 2

	version = subprocess.run([args.goose, "--version"], capture_output=True, text=True).stdout.strip()
	if not args.provider:
		args.provider, detected_model = configured_provider()
		args.model = args.model or detected_model
		if not args.provider:
			print("No provider configured and none detected from goose's own config.yaml; "
					"session/new will fail before MCP is attempted. Pass --provider.", file=sys.stderr)
	cells = [(t, d) for t in ("stdio", "serve") for d in ("session-new", "config-yaml")]
	if args.cell:
		wanted = {c.strip() for c in args.cell}
		cells = [(t, d) for t, d in cells if f"{t}/{d}" in wanted]
		if not cells:
			print(f"no such cell; choose from {[f'{t}/{d}' for t, d in cells]}", file=sys.stderr)
			return 2

	print(f"provider: {args.provider or '(none)'}, model: {args.model or '(agent default)'}")
	print(f"goose {version or '(unknown version)'}, broken MCP server: {args.broken} "
			f"({UNREACHABLE_URL if args.broken == 'http' else '/usr/bin/false'})")
	print(f"a turn {'IS' if args.turn else 'is NOT'} run, so this "
			f"{'spends' if args.turn else 'spends no'} tokens\n")

	results = []
	for transport, declared in cells:
		print(f"-- {transport}/{declared} ...", flush=True)
		result = run_cell(transport, declared, args)
		results.append(result)
		for line in result["lines"][:5]:
			print(f"     agent said: {line[:160]}")
		for line in result["logs"][:5]:
			print(f"     log file:   {line[:200]}")
		if result.get("kept"):
			print(f"     kept: {result['kept']}")
		if result["note"]:
			print(f"     note: {result['note']}")
		if args.verbose:
			if result["methods"]:
				print(f"     notifications: {', '.join(result['methods'])}")
			if result["detail"]:
				print(f"     detail: {result['detail']}")
			for line in result["output"][-args.verbose_lines:]:
				print(f"     out: {line[:200]}")

	names = {id(r): r["transport"] + "/" + r["declared"] for r in results}
	width = max(len(name) for name in names.values())
	print("\n" + "=" * (width + 58))
	print(f"{'cell'.ljust(width)}  {'session/new'.ljust(24)}  {'updates'.ljust(7)}  verdict")
	print("-" * (width + 58))
	for r in results:
		print(f"{names[id(r)].ljust(width)}  {r['session'][:24].ljust(24)}  "
				f"{str(r['updates']).ljust(7)}  {r['verdict']}")
	print("=" * (width + 58))
	print("\nSILENT means the session opened normally and nothing -- not the protocol, not the\n"
			"agent's own output, not even its log files -- said the MCP server was unreachable.\n"
			"LOG FILE ONLY means goose recorded it somewhere no ACP client and no supervisor reads,\n"
			"which is the same outcome for an application and a different fix. Both are the failure\n"
			"mode documented under the 'MCP Servers' section of docs/user-guide.html.")

	if any(r["verdict"] == "NOT MEASURED" for r in results):
		return 2
	if args.require_loud and any(r["verdict"] == "SILENT" for r in results):
		return 1
	return 0


if __name__ == "__main__":
	sys.exit(main())
