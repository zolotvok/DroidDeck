import base64
import hashlib
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import tracemalloc

SCRIPT = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-agent"
loader = importlib.machinery.SourceFileLoader("droiddeck_agent", str(SCRIPT))
spec = importlib.util.spec_from_loader(loader.name, loader)
agent = importlib.util.module_from_spec(spec)
loader.exec_module(agent)


class FakeDevTools(threading.Thread):

    def __init__(self, answer):
        super().__init__(daemon=True)
        self.answer = answer
        self.server = socket.socket()
        self.server.bind(("127.0.0.1", 0))
        self.server.listen(4)
        self.port = self.server.getsockname()[1]
        self.seen = []

    def run(self):
        while True:
            try:
                conn, _ = self.server.accept()
            except OSError:
                return
            threading.Thread(target=self.serve, args=(conn,), daemon=True).start()

    def serve(self, conn):
        head = b""
        while b"\r\n\r\n" not in head:
            head += conn.recv(4096)
        request = head.decode()
        if request.startswith("GET /json"):
            body = json.dumps([{"title": "SharedJSContext", "url": "https://steamloopback.host/", "type": "page",
                                "webSocketDebuggerUrl": "ws://127.0.0.1:%d/devtools/page/1" % self.port}]).encode()
            conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %d\r\n\r\n" % len(body) + body)
            conn.close()
            return
        key = [l.split(":", 1)[1].strip() for l in request.split("\r\n") if l.lower().startswith("sec-websocket-key")][0]
        accept = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
        conn.sendall(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                      "Sec-WebSocket-Accept: %s\r\n\r\n" % accept).encode())
        b0, b1 = conn.recv(2)
        n = b1 & 0x7F
        if n == 126:
            n = struct.unpack(">H", conn.recv(2))[0]
        mask = conn.recv(4)
        data = b""
        while len(data) < n:
            data += conn.recv(n - len(data))
        message = json.loads(bytes(b ^ mask[i % 4] for i, b in enumerate(data)))
        self.seen.append(message)
        for reply in ({"method": "Runtime.consoleAPICalled", "params": {}}, dict(self.answer, id=message["id"])):
            payload = json.dumps(reply).encode()
            header = bytes([0x81, 126]) + struct.pack(">H", len(payload)) if len(payload) > 125 else bytes([0x81, len(payload)])
            conn.sendall(header + payload)
        time.sleep(0.2)
        conn.close()


class FakeSteamClient(threading.Thread):

    def __init__(self, showing):
        super().__init__(daemon=True)
        self.showing = showing
        self.server = socket.socket()
        self.server.bind(("127.0.0.1", 0))
        self.server.listen(4)
        self.port = self.server.getsockname()[1]
        self.seen = []
        self.conn = None
        self.lock = threading.Lock()

    def run(self):
        while True:
            try:
                conn, _ = self.server.accept()
            except OSError:
                return
            threading.Thread(target=self.serve, args=(conn,), daemon=True).start()

    def send(self, conn, message):
        payload = json.dumps(message).encode()
        header = bytes([0x81, 126]) + struct.pack(">H", len(payload)) if len(payload) > 125 else bytes([0x81, len(payload)])
        with self.lock:
            conn.sendall(header + payload)

    def push(self, payload, name=None):
        self.send(self.conn, {"method": "Runtime.bindingCalled",
                              "params": {"name": name or agent.KEYBOARD_BINDING, "payload": payload}})

    def drop(self):
        self.conn.shutdown(socket.SHUT_RDWR)
        self.conn.close()

    def serve(self, conn):
        stream = conn.makefile("rb")
        head = b""
        while not head.endswith(b"\r\n\r\n"):
            head += stream.read(1)
        request = head.decode()
        if request.startswith("GET /json"):
            body = json.dumps([{"title": "Steam Big Picture Mode", "url": "https://steamloopback.host/routes/",
                                "webSocketDebuggerUrl": "ws://127.0.0.1:%d/devtools/page/2" % self.port},
                               {"title": "SharedJSContext", "url": "https://steamloopback.host/",
                                "webSocketDebuggerUrl": "ws://127.0.0.1:%d/devtools/page/1" % self.port}]).encode()
            conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %d\r\n\r\n" % len(body) + body)
            conn.close()
            return
        key = [l.split(":", 1)[1].strip() for l in request.split("\r\n") if l.lower().startswith("sec-websocket-key")][0]
        accept = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
        conn.sendall(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                      "Sec-WebSocket-Accept: %s\r\n\r\n" % accept).encode())
        self.conn = conn
        try:
            while True:
                b0, b1 = stream.read(2)
                n = b1 & 0x7F
                if n == 126:
                    n = struct.unpack(">H", stream.read(2))[0]
                elif n == 127:
                    n = struct.unpack(">Q", stream.read(8))[0]
                mask = stream.read(4)
                data = stream.read(n)
                message = json.loads(bytes(b ^ mask[i % 4] for i, b in enumerate(data)))
                self.seen.append(message)
                self.push("true", name="unrelated")
                result = {"result": {"type": "boolean", "value": self.showing}} if message["method"] == "Runtime.evaluate" else {}
                self.send(conn, {"id": message["id"], "result": result})
        except (OSError, ValueError):
            conn.close()


class AgentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.dir = Path(self.temp.name)
        (self.dir / "resp").mkdir()

    def ask(self, request):
        path = self.dir / "req.json"
        path.write_text(json.dumps(request))
        agent.answer(self.dir, path)
        self.assertFalse(path.exists(), "the request file is consumed")
        return json.loads((self.dir / "resp" / "req.json").read_text())

    def vr_helper(self, body):
        helper = self.dir / "droiddeck-steamvr"
        helper.write_text("#!/bin/sh\n" + body)
        old = os.environ.get("DROIDDECK_VR_HELPER")
        os.environ["DROIDDECK_VR_HELPER"] = str(helper)
        self.addCleanup(lambda: os.environ.pop("DROIDDECK_VR_HELPER") if old is None
                        else os.environ.__setitem__("DROIDDECK_VR_HELPER", old))
        return helper

    def test_vr_status_is_parsed(self):
        self.vr_helper('echo "installed=1 running=0 installing=0 driver=1"\n')
        answer = self.ask({"kind": "vr", "action": "status"})
        self.assertTrue(answer["ok"])
        self.assertEqual((True, False, False, True),
                         (answer["installed"], answer["running"], answer["installing"], answer["driver"]))

    def test_vr_runs_only_its_own_actions(self):
        log = self.dir / "args"
        self.vr_helper('echo "$@" > %s\n' % log)
        self.assertTrue(self.ask({"kind": "vr", "action": "start"})["ok"])
        self.assertEqual("start", log.read_text().strip())
        answer = self.ask({"kind": "vr", "action": "rm -rf /"})
        self.assertFalse(answer["ok"])
        self.assertEqual("INVALID_REQUEST", answer["error"]["code"])

    def test_vr_without_a_helper_is_unavailable(self):
        os.environ["DROIDDECK_VR_HELPER"] = str(self.dir / "missing")
        self.addCleanup(os.environ.pop, "DROIDDECK_VR_HELPER")
        answer = self.ask({"kind": "vr", "action": "status"})
        self.assertEqual("UNAVAILABLE", answer["error"]["code"])

    def fake_proc(self, pid, parent, comm, env, cmdline=None):
        root = self.dir / "proc" / str(pid)
        root.mkdir(parents=True)
        (root / "comm").write_text(comm + "\n")
        if cmdline is not None:
            (root / "cmdline").write_bytes(b"".join(arg.encode() + b"\0" for arg in cmdline))
        (root / "stat").write_text("%d (%s) S %d 0 0" % (pid, comm, parent))
        (root / "environ").write_bytes(b"\0".join(b"%s=%s" % (k.encode(), v.encode()) for k, v in env.items()) + b"\0")

    def tag(self, wins, arch="arm64"):
        snap = {"displays": {":0": [], ":1": wins}}
        with patch.dict(os.environ, {"DISPLAY": ":0", "BL_STEAM_CLIENT_ARCH": arch}), \
                patch.object(agent.subprocess, "run") as run, patch.object(agent, "log"):
            run.return_value.returncode = 0
            tagged = agent.tag_game_windows(snap, proc=self.dir / "proc")
        return tagged, [call.args[0][-1] for call in run.call_args_list]

    def test_a_sandboxed_flatpak_window_is_tagged_with_its_shortcut(self):
        self.fake_proc(40, 1, "gjs", {"FLATPAK_ID": "com.github.tchx84.Flatseal", "SteamAppId": "2166984148"})
        win = {"window": 7, "map": "viewable", "class": "com.github.tchx84.Flatseal", "pid": None}
        self.assertEqual(self.tag([win]), (True, ["2166984148"]))

    def test_a_flatpak_window_named_after_the_app_is_matched(self):
        self.fake_proc(40, 1, "vacuumtube", {"FLATPAK_ID": "rocks.shy.VacuumTube", "SteamAppId": "3000000001"})
        win = {"window": 7, "map": "viewable", "class": "VacuumTube", "pid": None}
        self.assertEqual(self.tag([win]), (True, ["3000000001"]))

    def test_a_window_whose_process_has_no_app_id_falls_back_to_its_flatpak(self):
        self.fake_proc(40, 1, "bwrap", {"FLATPAK_ID": "com.github.tchx84.Flatseal", "SteamAppId": "2166984148"})
        self.fake_proc(41, 1, "gjs", {})
        win = {"window": 7, "map": "viewable", "class": "com.github.tchx84.Flatseal", "pid": 41}
        self.assertEqual(self.tag([win]), (True, ["2166984148"]))

    def test_a_flatpak_shared_by_two_shortcuts_is_not_guessed(self):
        self.fake_proc(40, 1, "gjs", {"FLATPAK_ID": "com.github.tchx84.Flatseal", "SteamAppId": "2166984148"})
        self.fake_proc(42, 1, "gjs", {"FLATPAK_ID": "com.github.tchx84.Flatseal", "SteamAppId": "3000000003"})
        win = {"window": 7, "map": "viewable", "class": "com.github.tchx84.Flatseal", "pid": None}
        self.assertEqual(self.tag([win]), (False, []))

    def test_a_window_outside_the_reaper_takes_its_process_app_id(self):
        self.fake_proc(30, 1, "proot", {})
        self.fake_proc(31, 30, "electron", {"SteamAppId": "3000000002"})
        win = {"window": 8, "map": "viewable", "class": "electron", "pid": 31}
        self.assertEqual(self.tag([win]), (True, ["3000000002"]))

    def test_windows_gamescope_already_resolves_are_left_alone(self):
        self.fake_proc(20, 1, "reaper", {})
        self.fake_proc(21, 20, "game", {"SteamAppId": "413150"})
        wins = [{"window": 9, "map": "viewable", "class": "game", "pid": 21},
                {"window": 10, "map": "viewable", "class": "tagged", "pid": None, "appId": 5},
                {"window": 11, "map": "viewable", "class": "other", "pid": None},
                {"window": 12, "map": "unmapped", "class": "game", "pid": 21},
                {"window": 13, "map": "viewable", "class": "steam_app_550", "pid": None}]
        self.assertEqual(self.tag(wins), (False, []))

    def test_a_window_left_under_another_reaper_takes_its_process_app_id(self):
        self.fake_proc(20, 1, "reaper", {}, ["reaper"])
        self.fake_proc(21, 20, "Watch_Dogs.exe", {"SteamAppId": "3529167647"})
        self.fake_proc(30, 1, "reaper", {}, ["reaper", "SteamLaunch", "AppId=769", "--", "steam"])
        self.fake_proc(31, 30, "upc.exe", {"SteamAppId": "3529167647"})
        wins = [{"window": 9, "map": "viewable", "class": "watch_dogs.exe", "pid": 21},
                {"window": 10, "map": "viewable", "class": "upc.exe", "pid": 31}]
        self.assertEqual(self.tag(wins), (True, ["3529167647", "3529167647"]))

    def test_a_window_under_its_own_launch_reaper_is_left_to_gamescope(self):
        self.fake_proc(20, 1, "reaper", {}, ["reaper", "SteamLaunch", "AppId=3529167647", "--", "proton"])
        self.fake_proc(21, 20, "reaper", {}, ["reaper", "SteamLaunch", "AppId=769", "--", "tool"])
        self.fake_proc(22, 21, "Watch_Dogs.exe", {"SteamAppId": "3529167647"})
        self.fake_proc(30, 1, "reaper", {}, ["reaper"])
        self.fake_proc(31, 30, "steamwebhelper", {"SteamAppId": "769"})
        self.fake_proc(32, 30, "helper", {})
        wins = [{"window": 9, "map": "viewable", "class": "watch_dogs.exe", "pid": 22},
                {"window": 10, "map": "viewable", "class": "steamwebhelper", "pid": 31},
                {"window": 11, "map": "viewable", "class": "helper", "pid": 32}]
        self.assertEqual(self.tag(wins), (False, []))

    def test_a_proton_window_left_by_its_killed_launch_is_tagged_on_the_arm64_client(self):
        # Proton names its windows steam_app_<SteamAppId>; the arm64 client must not stop at the class.
        self.fake_proc(20, 1, "reaper", {}, ["reaper", "SteamLaunch", "AppId=769", "--", "steam"])
        self.fake_proc(21, 20, "upc.exe", {"SteamAppId": "2274579123"})
        self.fake_proc(30, 1, "wineserver", {})
        self.fake_proc(31, 30, "WatchDogs2.exe", {"SteamAppId": "2274579123"})
        self.fake_proc(40, 1, "reaper", {}, ["reaper", "SteamLaunch", "AppId=2274579123", "--", "proton"])
        self.fake_proc(41, 40, "WatchDogs2.exe", {"SteamAppId": "2274579123"})
        wins = [{"window": 9, "map": "viewable", "class": "steam_app_2274579123", "pid": 21},
                {"window": 10, "map": "viewable", "class": "steam_app_2274579123", "pid": 31},
                {"window": 11, "map": "viewable", "class": "steam_app_2274579123", "pid": 41}]
        self.assertEqual(self.tag(wins), (True, ["2274579123", "2274579123"]))

    def test_the_class_gives_the_id_when_the_environment_cannot_be_read(self):
        self.fake_proc(30, 1, "wineserver", {})
        self.fake_proc(31, 30, "WatchDogs2.exe", {})
        win = {"window": 10, "map": "viewable", "class": "steam_app_2274579123", "pid": 31}
        self.assertEqual(self.tag([win]), (True, ["2274579123"]))

    def test_every_tag_decision_is_logged_once(self):
        self.fake_proc(40, 1, "reaper", {}, ["reaper", "SteamLaunch", "AppId=550", "--", "proton"])
        self.fake_proc(41, 40, "game.exe", {"SteamAppId": "550"})
        self.fake_proc(30, 1, "wineserver", {})
        self.fake_proc(31, 30, "upc.exe", {"SteamAppId": "2274579123"})
        snap = {"displays": {":0": [], ":1": [
            {"window": 50, "map": "viewable", "class": "steam_app_550", "pid": 41},
            {"window": 51, "map": "viewable", "class": "steam_app_2274579123", "pid": 31}]}}
        with patch.dict(os.environ, {"DISPLAY": ":0", "BL_STEAM_CLIENT_ARCH": "arm64"}), \
                patch.object(agent.subprocess, "run") as run, patch.object(agent, "log") as log:
            run.return_value.returncode = 0
            agent.tag_game_windows(snap, proc=self.dir / "proc")
            agent.tag_game_windows(snap, proc=self.dir / "proc")
        lines = [call.args[0] for call in log.call_args_list]
        self.assertEqual(1, sum("0x32 on :1" in line and "skipped: under its own launch reaper AppId=550" in line
                                and "41 game.exe < 40 reaper[AppId=550] < 1" in line for line in lines), lines)
        self.assertEqual(2, sum("0x33 on :1" in line and "tagged STEAM_GAME=2274579123: ok (no reaper above it" in line
                                for line in lines), lines)

    def test_the_x86_client_still_tags_its_game_classes(self):
        win = {"window": 13, "map": "viewable", "class": "steam_app_550", "pid": None}
        self.assertEqual(self.tag([win], arch="x86_64"), (True, ["550"]))

    def test_exec_output_and_status(self):
        answer = self.ask({"kind": "exec", "argv": ["sh", "-c", "echo out; echo err >&2; exit 3"], "env": {"X": "1"}})
        self.assertTrue(answer["ok"])
        self.assertEqual(3, answer["exitCode"])
        self.assertEqual("out\n", answer["stdout"]["text"])
        self.assertEqual("err\n", answer["stderr"]["text"])
        self.assertFalse(answer["timedOut"])

    def test_exec_environment_stdin_and_cap(self):
        answer = self.ask({"kind": "exec", "argv": ["sh", "-c", "printf %s \"$X\"; cat"], "env": {"X": "a"}, "stdin": "b"})
        self.assertEqual("ab", answer["stdout"]["text"])
        big = self.ask({"kind": "exec", "argv": ["sh", "-c", "head -c 300000 /dev/zero"]})
        self.assertTrue(big["stdout"]["truncated"])
        self.assertEqual(agent.OUTPUT_CAP, len(big["stdout"]["text"]))

    def test_exec_timeout_kills_the_process_group(self):
        started = time.monotonic()
        answer = self.ask({"kind": "exec", "argv": ["sh", "-c", "sleep 30 & sleep 30"], "timeout": 0.5})
        self.assertTrue(answer["timedOut"])
        self.assertLess(time.monotonic() - started, 10)

    def test_exec_memory_is_bounded_while_draining_large_output(self):
        tracemalloc.start()
        try:
            answer = agent.run_exec({"argv": [sys.executable, "-c", "import os; [os.write(1, b'x' * 65536) for _ in range(1024)]"]})
            _, peak = tracemalloc.get_traced_memory()
        finally:
            tracemalloc.stop()
        self.assertEqual(0, answer["exitCode"])
        self.assertEqual(agent.OUTPUT_CAP, len(answer["stdout"]["text"]))
        self.assertTrue(answer["stdout"]["truncated"])
        self.assertLess(peak, 6 * 1024 * 1024)

    def test_exec_drains_both_streams_while_writing_stdin(self):
        program = "import os, sys; os.write(1, b'x' * 300000); print(len(sys.stdin.read()), file=sys.stderr)"
        answer = self.ask({"kind": "exec", "argv": [sys.executable, "-c", program], "stdin": "y" * 100000})
        self.assertFalse(answer["timedOut"])
        self.assertTrue(answer["stdout"]["truncated"])
        self.assertEqual("100000\n", answer["stderr"]["text"])

    def test_flooding_both_streams_still_times_out(self):
        program = "import os\nwhile True:\n os.write(1, b'o' * 65536)\n os.write(2, b'e' * 65536)"
        answer = self.ask({"kind": "exec", "argv": [sys.executable, "-c", program], "timeout": 0.5})
        self.assertTrue(answer["timedOut"])
        for name in ("stdout", "stderr"):
            self.assertEqual(agent.OUTPUT_CAP, len(answer[name]["text"]))
            self.assertTrue(answer[name]["truncated"])

    def test_timeout_applies_when_parent_exits_with_inherited_pipes(self):
        started = time.monotonic()
        answer = self.ask({"kind": "exec", "argv": ["sh", "-c", "sleep 30 & exit 0"], "timeout": 0.5})
        self.assertTrue(answer["timedOut"])
        self.assertLess(time.monotonic() - started, 5)

    def test_invalid_timeout_and_input_are_refused_before_exec(self):
        for value in (0, -1, 601, "NaN", "Infinity", "invalid"):
            answer = self.ask({"kind": "exec", "argv": ["true"], "timeout": value})
            self.assertEqual("INVALID_REQUEST", answer["error"]["code"])
        for fields in ({"stdin": 1}, {"env": []}, {"env": {"BAD=KEY": "value"}}, {"argv": ["true\0"]}):
            answer = self.ask({"kind": "exec", "argv": ["true"], **fields})
            self.assertEqual("INVALID_REQUEST", answer["error"]["code"])

    def test_errors_are_answers(self):
        self.assertEqual("INVALID_REQUEST", self.ask({"kind": "exec", "argv": []})["error"]["code"])
        self.assertEqual("EXEC_FAILED", self.ask({"kind": "exec", "argv": ["/no/such/program"]})["error"]["code"])
        self.assertEqual("UNKNOWN_REQUEST", self.ask({"kind": "nope"})["error"]["code"])
        self.assertEqual("INVALID_APP_ID", self.ask({"kind": "quit", "appId": "x"})["error"]["code"])
        (self.dir / "req.json").write_text("{not json")
        agent.answer(self.dir, self.dir / "req.json")
        self.assertEqual("INVALID_REQUEST", json.loads((self.dir / "resp" / "req.json").read_text())["error"]["code"])

    def test_request_shape_and_size_are_bounded(self):
        self.assertEqual("INVALID_REQUEST", self.ask([])["error"]["code"])
        self.assertEqual("INVALID_REQUEST", self.ask({"kind": "ping", "unused": "x" * agent.REQUEST_CAP})["error"]["code"])

    def test_replies_are_owner_only_even_when_replacing_a_public_file(self):
        path = self.dir / "private.json"
        path.write_text("old")
        path.chmod(0o644)
        agent.write_json(path, {"ok": True})
        self.assertEqual(0o600, path.stat().st_mode & 0o777)

    def test_oversized_websocket_frames_are_refused_before_reading_payload(self):
        ws = agent.WebSocket.__new__(agent.WebSocket)
        ws.buffer = bytes([0x81, 127]) + struct.pack(">Q", agent.RESPONSE_CAP + 1)
        with self.assertRaises(agent.AgentError) as caught:
            ws.recv()
        self.assertEqual("RESPONSE_TOO_LARGE", caught.exception.code)

    def test_devtools_targets_cannot_connect_to_remote_hosts(self):
        for url in ("ws://example.com/devtools", "ws://127.0.0.1@example.com/devtools", "wss://127.0.0.1/devtools"):
            with patch.object(agent.socket, "create_connection") as connect:
                with self.assertRaises(agent.AgentError) as caught:
                    agent.WebSocket(url, 1)
                self.assertEqual("INVALID_TARGET", caught.exception.code)
                connect.assert_not_called()

    def test_cdp_without_a_port(self):
        os.environ.pop("BL_CDP_PORT", None)
        self.assertEqual("NO_DEVTOOLS", self.ask({"kind": "cdp", "expression": "1"})["error"]["code"])

    def test_cdp_eval(self):
        server = FakeDevTools({"result": {"result": {"type": "number", "value": 42}}})
        server.start()
        self.addCleanup(server.server.close)
        (self.dir / "cdp-port").write_text("%d\n" % server.port)
        answer = self.ask({"kind": "cdp", "expression": "6 * 7"})
        self.assertTrue(answer["ok"], answer)
        self.assertEqual(42, answer["value"])
        self.assertEqual("SharedJSContext", answer["target"])
        self.assertEqual("Runtime.evaluate", server.seen[0]["method"])
        self.assertEqual("6 * 7", server.seen[0]["params"]["expression"])
        targets = self.ask({"kind": "cdp", "op": "targets"})
        self.assertEqual("SharedJSContext", targets["targets"][0]["title"])
        missing = self.ask({"kind": "cdp", "expression": "1", "target": "Nope"})
        self.assertEqual("NO_TARGET", missing["error"]["code"])

    def test_steam_selection(self):
        with patch.object(agent, "cdp_eval", return_value={"value": "3180310"}) as evaluate:
            answer = self.ask({"kind": "steam-selection"})
        self.assertEqual(3180310, answer["appId"])
        self.assertIn("document.activeElement", evaluate.call_args.args[1])
        self.assertEqual("Steam Big Picture Mode", evaluate.call_args.kwargs["target"])

    def test_steam_selection_allows_no_highlight(self):
        with patch.object(agent, "cdp_eval", return_value={"value": None}):
            self.assertIsNone(self.ask({"kind": "steam-selection"})["appId"])

    def test_steam_selection_rejects_invalid_app_id(self):
        with patch.object(agent, "cdp_eval", return_value={"value": "4294967296"}):
            answer = self.ask({"kind": "steam-selection"})
        self.assertEqual("INVALID_SELECTION", answer["error"]["code"])

    def test_shortcut_cgameid_waits_for_exact_steam_overview_mapping(self):
        game_id = str((3551182975 << 32) | 0x02000000)
        self.assertEqual(3551182975, agent.steam_shortcut_app_id(game_id))
        self.assertIsNone(agent.steam_shortcut_app_id("413150"))
        self.assertIsNone(agent.steam_shortcut_app_id(str((3551182975 << 32) | 0x03000000)))
        clock = [0.0]
        with patch.object(agent, "cdp_eval", side_effect=[{"value": None}, {"value": "3551182975"}]) as evaluate, \
             patch.object(agent.time, "monotonic", side_effect=lambda: clock[0]), \
             patch.object(agent.time, "sleep", side_effect=lambda delay: clock.__setitem__(0, clock[0] + delay)):
            result = agent.wait_for_steam_shortcut_overview(self.dir, game_id, timeout=1)
        self.assertEqual({"supported": True, "ready": True, "appId": 3551182975}, result)
        expression = evaluate.call_args_list[0].args[1]
        self.assertIn("GetAppOverviewByGameID", expression)
        self.assertIn(json.dumps(game_id), expression)
        self.assertNotIn("RequestAppDetails", expression)
        self.assertFalse(evaluate.call_args_list[0].kwargs["await_promise"])

    def test_wrong_overview_app_id_keeps_shortcut_url_held_until_timeout(self):
        game_id = str((3537741571 << 32) | 0x02000000)
        clock = [0.0]
        with patch.object(agent, "cdp_eval", return_value={"value": "646570"}) as evaluate, \
             patch.object(agent.time, "monotonic", side_effect=lambda: clock[0]), \
             patch.object(agent.time, "sleep", side_effect=lambda delay: clock.__setitem__(0, clock[0] + delay)):
            with self.assertRaises(agent.AgentError) as raised:
                agent.wait_for_steam_shortcut_overview(self.dir, game_id, timeout=0.5)
        self.assertEqual("STEAM_OVERVIEW_TIMEOUT", raised.exception.code)
        self.assertGreater(evaluate.call_count, 0)
        self.assertTrue(all("RequestAppDetails" not in call.args[1] for call in evaluate.call_args_list))

    def test_normal_steam_ids_and_unknown_cgameids_skip_overview_wait(self):
        for game_id in ("413150", str((123456789 << 32) | 0x03000000), "18446744073709551616"):
            with self.subTest(game_id=game_id), patch.object(agent, "cdp_eval") as evaluate:
                result = agent.wait_for_steam_shortcut_overview(self.dir, game_id)
            self.assertFalse(result["supported"])
            evaluate.assert_not_called()

    def test_shortcut_state_tells_a_lost_shortcut_from_a_loading_library(self):
        game_id = str((3333273269 << 32) | 0x02000000)
        self.assertEqual("not-shortcut", agent.steam_shortcut_state(self.dir, "413150"))
        for values, wanted, calls in ((["loading", "ready"], "ready", 2), (["loading", "missing"], "missing", 3),
                                      ([None], "loading", 3)):
            clock = [0.0]
            replies = [{"value": v} for v in values] + [{"value": values[-1]}] * 10
            with self.subTest(values=values), patch.object(agent, "cdp_eval", side_effect=replies) as evaluate, \
                 patch.object(agent.time, "monotonic", side_effect=lambda: clock[0]), \
                 patch.object(agent.time, "sleep", side_effect=lambda delay: clock.__setitem__(0, clock[0] + delay)):
                self.assertEqual(wanted, agent.steam_shortcut_state(self.dir, game_id, timeout=1))
            self.assertEqual(calls, evaluate.call_count)
            self.assertIn("GetAppOverviewByGameID(%s)" % json.dumps(game_id), evaluate.call_args_list[0].args[1])

    def test_shortcut_state_waits_out_an_unreachable_client_but_not_other_errors(self):
        game_id = str((3333273269 << 32) | 0x02000000)
        clock = [0.0]
        with patch.object(agent, "cdp_eval", side_effect=agent.AgentError("NO_DEVTOOLS", "down")), \
             patch.object(agent.time, "monotonic", side_effect=lambda: clock[0]), \
             patch.object(agent.time, "sleep", side_effect=lambda delay: clock.__setitem__(0, clock[0] + delay)):
            self.assertEqual("loading", agent.steam_shortcut_state(self.dir, game_id, timeout=1))
        with patch.object(agent, "cdp_eval", side_effect=agent.AgentError("CDP_EXCEPTION", "boom")):
            with self.assertRaises(agent.AgentError):
                agent.steam_shortcut_state(self.dir, game_id, timeout=1)

    def test_shortcut_state_command_exits_with_the_state(self):
        game_id = str((3333273269 << 32) | 0x02000000)
        launch = self.dir.parent
        with patch.dict(os.environ, {"BL_LAUNCH_DIR": str(launch)}):
            for state, code in (("ready", 0), ("missing", 4), ("not-shortcut", 3), ("loading", 1)):
                with self.subTest(state=state), patch.object(agent, "steam_shortcut_state", return_value=state) as ask:
                    self.assertEqual(code, agent.main(["--steam-shortcut-state", game_id, "2"]))
                self.assertEqual((launch / "agent", game_id, "2"), ask.call_args.args)
            with patch.object(agent, "steam_shortcut_state", side_effect=agent.AgentError("CDP_EXCEPTION", "boom")):
                self.assertEqual(1, agent.main(["--steam-shortcut-state", game_id]))
            self.assertEqual(2, agent.main(["--steam-shortcut-state"]))

    def test_asleep_command_reports_steams_own_sleep_state(self):
        with patch.dict(os.environ, {"BL_LAUNCH_DIR": str(self.dir.parent)}):
            for value, code in (("yes", 0), ("no", 5), ("unknown", 1), (None, 1)):
                with self.subTest(value=value), patch.object(agent, "cdp_eval", return_value={"value": value}) as ask:
                    self.assertEqual(code, agent.main(["--steam-asleep"]))
                self.assertIn("m_bSuspending", ask.call_args.args[1])
                self.assertFalse(ask.call_args.kwargs["await_promise"])
            with patch.object(agent, "cdp_eval", side_effect=agent.AgentError("NO_DEVTOOLS", "down")):
                self.assertEqual(1, agent.main(["--steam-asleep"]))

    def test_supports_vr_command_separates_vr_flat_and_unknown_apps(self):
        with patch.dict(os.environ, {"BL_LAUNCH_DIR": str(self.dir.parent)}):
            for value, code in ((True, 0), (False, 5), (None, 1)):
                with self.subTest(value=value), patch.object(agent, "cdp_eval", return_value={"value": value}) as ask:
                    self.assertEqual(code, agent.main(["--steam-supports-vr", "204360"]))
                self.assertIn('GetAppOverviewByGameID?.("204360")', ask.call_args.args[1])
                self.assertIn("vr_supported", ask.call_args.args[1])
            with patch.object(agent, "cdp_eval", side_effect=agent.AgentError("NO_DEVTOOLS", "down")):
                self.assertEqual(1, agent.main(["--steam-supports-vr", "204360"]))
            for bad in ([], ["1;x"], ["-1"], ["1", "2"]):
                self.assertEqual(2, agent.main(["--steam-supports-vr"] + bad))

    def test_idle_inhibit_command_reports_whether_steam_reached_the_state(self):
        with patch.dict(os.environ, {"BL_LAUNCH_DIR": str(self.dir.parent)}):
            for value, code in ((True, 0), (False, 1), (None, 1)):
                with self.subTest(value=value), patch.object(agent, "cdp_eval", return_value={"value": value}) as ask:
                    self.assertEqual(code, agent.main(["--steam-idle-inhibit", "on"]))
                self.assertIn("toggle_idle_suspend_inhibit 1", ask.call_args.args[1])
                self.assertTrue(ask.call_args.args[1].rstrip().endswith("(true)"))
            with patch.object(agent, "cdp_eval", return_value={"value": True}) as ask:
                self.assertEqual(0, agent.main(["--steam-idle-inhibit", "off"]))
            self.assertTrue(ask.call_args.args[1].rstrip().endswith("(false)"))
            with patch.object(agent, "cdp_eval", side_effect=agent.AgentError("NO_DEVTOOLS", "down")):
                self.assertEqual(1, agent.main(["--steam-idle-inhibit", "on"]))
            self.assertEqual(2, agent.main(["--steam-idle-inhibit", "maybe"]))

    def test_idle_suspend_seconds_follow_the_power_source(self):
        vpower = self.dir / "ac_status"
        with patch.object(agent, "cdp_eval", return_value={"value": [3600, 900]}):
            vpower.write_text("Connected\n")
            self.assertEqual(3600, agent.steam_idle_suspend_seconds(self.dir, vpower))
            vpower.write_text("Disconnected\n")
            self.assertEqual(900, agent.steam_idle_suspend_seconds(self.dir, vpower))
        with patch.object(agent, "cdp_eval", return_value={"value": None}):
            self.assertIsNone(agent.steam_idle_suspend_seconds(self.dir, vpower))
        with patch.dict(os.environ, {"BL_LAUNCH_DIR": str(self.dir.parent)}), \
                patch.object(agent, "steam_idle_suspend_seconds", return_value=None):
            self.assertEqual(1, agent.main(["--steam-idle-suspend-seconds"]))
    def test_shortcut_runs_through_client_rungame_after_overview(self):
        game_id = str((2196463994 << 32) | 0x02000000)
        with patch.object(agent, "cdp_eval", side_effect=[{"value": "2196463994"}, {"value": "started"}]) as evaluate:
            result = agent.run_steam_shortcut(self.dir, game_id, timeout=1)
        self.assertEqual({"supported": True, "started": True, "appId": 2196463994}, result)
        self.assertEqual(2, evaluate.call_count)
        expression = evaluate.call_args_list[1].args[1]
        self.assertIn("SteamClient.Apps.RunGame(%s, \"\", -1, 100)" % json.dumps(game_id), expression)
        self.assertFalse(evaluate.call_args_list[1].kwargs["await_promise"])

    def test_run_shortcut_skips_normal_ids_and_reports_rungame_failure(self):
        with patch.object(agent, "cdp_eval") as evaluate:
            self.assertFalse(agent.run_steam_shortcut(self.dir, "413150")["supported"])
        evaluate.assert_not_called()
        game_id = str((2196463994 << 32) | 0x02000000)
        with patch.object(agent, "cdp_eval", side_effect=[{"value": "2196463994"}, {"value": None}]):
            with self.assertRaises(agent.AgentError) as raised:
                agent.run_steam_shortcut(self.dir, game_id, timeout=1)
        self.assertEqual("RUN_GAME_FAILED", raised.exception.code)

    def test_cdp_exception(self):
        server = FakeDevTools({"result": {"result": {"type": "object"}, "exceptionDetails": {
            "text": "Uncaught", "exception": {"description": "ReferenceError: SteamClient is not defined"}}}})
        server.start()
        self.addCleanup(server.server.close)
        (self.dir / "cdp-port").write_text(str(server.port))
        answer = self.ask({"kind": "cdp", "expression": "SteamClient"})
        self.assertEqual("JS_EXCEPTION", answer["error"]["code"])
        self.assertIn("ReferenceError", answer["error"]["message"])

    def test_serve_answers_request_files(self):
        server = subprocess.Popen([sys.executable, str(SCRIPT), "--no-focus"],
                                  env=dict(os.environ, BL_LAUNCH_DIR=str(self.dir)),
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        def stop_server():
            server.terminate()
            server.wait(timeout=5)
        self.addCleanup(stop_server)
        agent_dir = self.dir / "agent"
        for _ in range(100):
            if (agent_dir / "hello.json").exists():
                break
            time.sleep(0.02)
        hello = json.loads((agent_dir / "hello.json").read_text())
        self.assertEqual(server.pid, hello["pid"])
        self.assertFalse(hello["focus"])
        (agent_dir / "req" / "a.json.tmp").write_text(json.dumps({"kind": "ping"}))
        os.replace(agent_dir / "req" / "a.json.tmp", agent_dir / "req" / "a.json")
        for _ in range(200):
            if (agent_dir / "resp" / "a.json").exists():
                break
            time.sleep(0.02)
        self.assertEqual(agent.VERSION, json.loads((agent_dir / "resp" / "a.json").read_text())["version"])

    def test_game_pids_reads_the_steam_environment(self):
        import subprocess
        if not Path("/proc").is_dir():
            self.skipTest("needs /proc")
        child = subprocess.Popen(["sleep", "30"], env=dict(os.environ, SteamAppId="424242"))
        self.addCleanup(lambda: (child.kill(), child.wait()))
        time.sleep(0.2)
        self.assertIn(child.pid, agent.game_pids(424242))


class SteamKeyboardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.dir = Path(self.temp.name)

    def state(self):
        try:
            return json.loads((self.dir / "keyboard.json").read_text())
        except (OSError, ValueError):
            return None

    def wait_for(self, check):
        for _ in range(200):
            if check():
                return
            time.sleep(0.02)
        self.fail("timed out; keyboard.json is %s" % self.state())

    def test_report_writes_only_changes(self):
        watcher = agent.KeyboardWatcher(self.dir)
        watcher.report(False)
        self.assertEqual({"steamKeyboard": False, "seq": 1}, self.state())
        watcher.report(False)
        watcher.report(True)
        self.assertEqual({"steamKeyboard": True, "seq": 2}, self.state())

    def test_follows_the_clients_keyboard_and_rechecks_while_idle(self):
        client = FakeSteamClient(False)
        client.start()
        self.addCleanup(client.server.close)
        (self.dir / "cdp-port").write_text(str(client.port))
        watcher = agent.KeyboardWatcher(self.dir)
        failure = []
        with patch.object(agent, "KEYBOARD_CHECK_SECONDS", 0.3):
            thread = threading.Thread(target=lambda: failure.append(self.capture(watcher.watch)), daemon=True)
            thread.start()
            self.wait_for(lambda: self.state() == {"steamKeyboard": False, "seq": 1})
            self.assertEqual("Runtime.addBinding", client.seen[0]["method"])
            self.assertEqual(agent.KEYBOARD_BINDING, client.seen[0]["params"]["name"])
            self.assertIn("IsShowingVirtualKeyboard", client.seen[1]["params"]["expression"])
            self.assertIn(agent.KEYBOARD_BINDING, client.seen[1]["params"]["expression"])
            self.assertNotIn("__BINDING__", client.seen[1]["params"]["expression"])
            client.push("true")
            self.wait_for(lambda: (self.state() or {}).get("steamKeyboard") is True)
            client.push("false")
            self.wait_for(lambda: (self.state() or {}).get("steamKeyboard") is False)
            client.showing = True
            self.wait_for(lambda: (self.state() or {}).get("steamKeyboard") is True)
            self.assertGreaterEqual(len([m for m in client.seen if m["method"] == "Runtime.evaluate"]), 2)
            client.drop()
            thread.join(5)
        self.assertFalse(thread.is_alive())
        self.assertIsInstance(failure[0], OSError)

    def test_any_loss_reports_the_keyboard_closed_and_backs_off(self):
        watcher = agent.KeyboardWatcher(self.dir)
        watcher.report(True)

        def attached_then_lost():
            watcher.attached = True
            raise OSError("DevTools closed the connection")

        steps = iter([agent.AgentError("NO_DEVTOOLS", "no port"), agent.AgentError("NO_DEVTOOLS", "no port"),
                      attached_then_lost, KeyError("SteamUIStore")])

        def watch():
            step = next(steps, None)
            if step is None:
                raise SystemExit
            if callable(step):
                step()
            raise step

        with patch.object(watcher, "watch", side_effect=watch), patch.object(agent.time, "sleep") as sleep, \
                patch.object(agent, "log") as log:
            with self.assertRaises(SystemExit):
                watcher.run()
        self.assertEqual({"steamKeyboard": False, "seq": 2}, self.state())
        self.assertEqual([2, 4, 2, 4], [c.args[0] for c in sleep.call_args_list])
        self.assertFalse(watcher.attached)
        self.assertEqual(3, log.call_count)

    def test_requests_and_route_files(self):
        watcher = agent.KeyboardWatcher(self.dir)
        watcher.request()
        watcher.request()
        self.assertEqual({"seq": 2}, json.loads((self.dir / "keyboard-request.json").read_text()))
        self.assertFalse(watcher.wanted_route())
        (self.dir / "keyboard-route").write_text("bottom")
        self.assertTrue(watcher.wanted_route())
        (self.dir / "keyboard-route").write_text("top")
        self.assertFalse(watcher.wanted_route())
        watcher.event({"method": "Runtime.bindingCalled", "params": {"name": agent.KEYBOARD_BINDING, "payload": "request"}})
        self.assertEqual({"seq": 3}, json.loads((self.dir / "keyboard-request.json").read_text()))
        self.assertIsNone(self.state())

    def test_dismissals_are_written_for_the_app(self):
        watcher = agent.KeyboardWatcher(self.dir)
        watcher.event({"method": "Runtime.bindingCalled", "params": {"name": agent.KEYBOARD_BINDING, "payload": "dismiss"}})
        watcher.event({"method": "Runtime.bindingCalled", "params": {"name": agent.KEYBOARD_BINDING, "payload": "dismiss"}})
        self.assertEqual({"seq": 2}, json.loads((self.dir / "keyboard-dismiss.json").read_text()))
        self.assertFalse((self.dir / "keyboard-request.json").exists())

    @unittest.skipUnless(shutil.which("node"), "node is not installed")
    def test_page_script_reports_when_the_routed_field_loses_focus(self):
        harness = r"""
const assert = require('assert');
globalThis.window = globalThis;
const fs = require('fs');
const script = fs.readFileSync(process.argv[2], 'utf8');
const route = fs.readFileSync(process.argv[3], 'utf8');
const calls = [];
window.droiddeckSteamKeyboard = value => calls.push(value);
const open = { m_currentValue: false };
const vkm = {
  m_currentVirtualKeyboardRef: null,
  IsShowingVirtualKeyboard: { m_currentValue: false },
  m_bIsInlineVirtualKeyboardOpen: open,
  m_bIsVirtualKeyboardModal: { m_currentValue: false },
  BUseVRKeyboard: () => false,
  m_Instance: { IsDesktopOverlayWindow: () => false },
  SetActiveVirtualKeyboardTarget(ref, props) { this.m_currentVirtualKeyboardRef = ref; },
  SetVirtualKeyboardHidden() { open.m_currentValue = false; this.m_currentVirtualKeyboardRef = null; },
  ShowVirtualKeyboard(ref, props, modal) { this.m_currentVirtualKeyboardRef = ref; open.m_currentValue = true; },
};
globalThis.SteamUIStore = { WindowStore: { SteamUIWindows: [{ VirtualKeyboardManager: vkm }] } };
eval(script);
vkm.ShowVirtualKeyboard('a', {}, false);
eval(route.replace('__ROUTE__', 'true'));
assert.strictEqual(open.m_currentValue, false);
assert.strictEqual(vkm.m_currentVirtualKeyboardRef, 'a');
const wait = ms => new Promise(done => setTimeout(done, ms));
(async () => {
  await wait(500);
  assert.deepStrictEqual(calls.filter(c => c === 'dismiss'), []);
  vkm.m_currentVirtualKeyboardRef = null;
  await wait(500);
  assert.deepStrictEqual(calls.filter(c => c === 'dismiss'), ['dismiss']);
  vkm.ShowVirtualKeyboard('b', {}, false);
  assert.strictEqual(open.m_currentValue, false);
  vkm.m_currentVirtualKeyboardRef = null;
  await wait(160);
  vkm.ShowVirtualKeyboard('c', {}, false);
  await wait(500);
  assert.deepStrictEqual(calls.filter(c => c === 'dismiss'), ['dismiss']);
  vkm.m_currentVirtualKeyboardRef = null;
  await wait(500);
  assert.deepStrictEqual(calls.filter(c => c === 'dismiss'), ['dismiss', 'dismiss']);
  let focused = false;
  vkm.ShowVirtualKeyboard({ BIsElementValidForInput: () => focused }, {}, false);
  await wait(500);
  assert.deepStrictEqual(calls.filter(c => c === 'dismiss'), ['dismiss', 'dismiss']);
  focused = true;
  await wait(500);
  assert.deepStrictEqual(calls.filter(c => c === 'dismiss'), ['dismiss', 'dismiss']);
  focused = false;
  await wait(500);
  assert.deepStrictEqual(calls.filter(c => c === 'dismiss'), ['dismiss', 'dismiss', 'dismiss']);
  const doc = { activeElement: { tagName: 'INPUT', type: 'text' } };
  vkm.m_ownerWindow = { document: doc };
  vkm.ShowVirtualKeyboard({ BIsElementValidForInput: () => false }, {}, false);
  await wait(500);
  assert.deepStrictEqual(calls.filter(c => c === 'dismiss'), ['dismiss', 'dismiss', 'dismiss']);
  doc.activeElement = { tagName: 'DIV' };
  await wait(500);
  assert.deepStrictEqual(calls.filter(c => c === 'dismiss'), ['dismiss', 'dismiss', 'dismiss', 'dismiss']);
  clearInterval(window.__droiddeckKeyboard.timer);
})().catch(e => { console.error(e); process.exit(1); });
"""
        (self.dir / "keyboard.js").write_text(agent.KEYBOARD_JS)
        (self.dir / "route.js").write_text(agent.KEYBOARD_ROUTE_JS)
        (self.dir / "harness.js").write_text(harness)
        result = subprocess.run(["node", str(self.dir / "harness.js"), str(self.dir / "keyboard.js"), str(self.dir / "route.js")],
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_route_is_applied_once_per_change(self):
        watcher = agent.KeyboardWatcher(self.dir)
        sent = []
        with patch.object(watcher, "call", side_effect=lambda ws, method, params: sent.append(params["expression"]) or {}):
            watcher.apply_route(None)
            watcher.apply_route(None)
            (self.dir / "keyboard-route").write_text("bottom")
            watcher.apply_route(None)
            watcher.apply_route(None, force=True)
        self.assertEqual(3, len(sent))
        self.assertTrue(sent[0].rstrip().endswith("(false)"))
        self.assertTrue(sent[1].rstrip().endswith("(true)"))
        self.assertNotIn("__ROUTE__", sent[2])
        self.assertNotIn("__BINDING__", sent[2])

    @unittest.skipUnless(shutil.which("node"), "node is not installed")
    def test_page_script_routes_the_keyboard_to_the_app(self):
        harness = r"""
const assert = require('assert');
globalThis.window = globalThis;
const fs = require('fs');
const script = fs.readFileSync(process.argv[2], 'utf8');
const route = fs.readFileSync(process.argv[3], 'utf8');
const calls = [];
window.droiddeckSteamKeyboard = value => calls.push(value);
const shown = [], targets = [];
const open = { m_currentValue: false };
const vkm = {
  IsShowingVirtualKeyboard: { m_currentValue: false },
  m_bIsInlineVirtualKeyboardOpen: open,
  m_bIsVirtualKeyboardModal: { m_currentValue: false },
  BUseVRKeyboard: () => false,
  m_Instance: { IsDesktopOverlayWindow: () => false },
  SetActiveVirtualKeyboardTarget(ref, props) { targets.push(ref); },
  SetVirtualKeyboardHidden() { open.m_currentValue = false; },
  ShowVirtualKeyboard(ref, props, modal) { shown.push(ref); open.m_currentValue = true; },
};
globalThis.SteamUIStore = { WindowStore: { SteamUIWindows: [{ VirtualKeyboardManager: vkm }] } };
eval(script);
vkm.ShowVirtualKeyboard('a', {}, false);
assert.deepStrictEqual(shown, ['a']);
assert.strictEqual(eval(route.replace('__ROUTE__', 'true')), true);
assert.strictEqual(open.m_currentValue, false);
assert.deepStrictEqual(calls.filter(c => c === 'request'), ['request']);
vkm.ShowVirtualKeyboard('b', {}, false);
assert.deepStrictEqual(shown, ['a']);
assert.deepStrictEqual(targets, ['b']);
assert.deepStrictEqual(calls.filter(c => c === 'request'), ['request', 'request']);
vkm.ShowVirtualKeyboard('c', {}, true);
assert.deepStrictEqual(shown, ['a', 'c']);
eval(script);
vkm.ShowVirtualKeyboard('d', {}, false);
assert.deepStrictEqual(targets, ['b', 'd']);
eval(route.replace('__ROUTE__', 'false'));
vkm.ShowVirtualKeyboard('e', {}, false);
assert.deepStrictEqual(shown, ['a', 'c', 'e']);
clearInterval(window.__droiddeckKeyboard.timer);
"""
        (self.dir / "keyboard.js").write_text(agent.KEYBOARD_JS)
        (self.dir / "route.js").write_text(agent.KEYBOARD_ROUTE_JS)
        (self.dir / "harness.js").write_text(harness)
        result = subprocess.run(["node", str(self.dir / "harness.js"), str(self.dir / "keyboard.js"), str(self.dir / "route.js")],
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_no_shared_context_yet(self):
        with patch.object(agent, "cdp_targets", return_value=[{"title": "Steam", "webSocketDebuggerUrl": "ws://127.0.0.1:1/x"}]):
            with self.assertRaises(agent.AgentError) as caught:
                agent.KeyboardWatcher(self.dir).watch()
        self.assertEqual("NO_TARGET", caught.exception.code)

    @unittest.skipUnless(shutil.which("node"), "node is not installed")
    def test_page_script_watches_every_window_with_or_without_subscribe(self):
        harness = r"""
const assert = require('assert');
globalThis.window = globalThis;
const script = require('fs').readFileSync(process.argv[2], 'utf8');
const calls = [];
window.droiddeckSteamKeyboard = value => calls.push(value);
const tick = ms => new Promise(done => setTimeout(done, ms));
const value = subscribe => {
  const v = { m_currentValue: false, m_callbacks: [] };
  if (subscribe) v.Subscribe = cb => { v.m_callbacks.push(cb); return { Unsubscribe() {} }; };
  v.set = next => { v.m_currentValue = next; for (const cb of v.m_callbacks) cb(next); };
  return v;
};
(async () => {
  assert.strictEqual(eval(script), false);
  assert.deepStrictEqual(calls, ['false']);
  const main = value(true), overlay = value(false);
  globalThis.SteamUIStore = { WindowStore: { SteamUIWindows: [{ VirtualKeyboardManager: { IsShowingVirtualKeyboard: main } }] } };
  assert.strictEqual(eval(script), false);
  main.set(true);
  await tick(20);
  assert.deepStrictEqual(calls, ['false', 'true']);
  main.set(false);
  await tick(20);
  SteamUIStore.WindowStore.SteamUIWindows.push({}, { VirtualKeyboardManager: { IsShowingVirtualKeyboard: overlay } });
  overlay.m_currentValue = true;
  await tick(700);
  assert.deepStrictEqual(calls, ['false', 'true', 'false', 'true']);
  assert.strictEqual(eval(script), true);
  assert.strictEqual(main.m_callbacks.length, 1);
  clearInterval(window.__droiddeckKeyboard.timer);
})().catch(e => { console.error(e); process.exit(1); });
"""
        (self.dir / "keyboard.js").write_text(agent.KEYBOARD_JS)
        (self.dir / "harness.js").write_text(harness)
        result = subprocess.run(["node", str(self.dir / "harness.js"), str(self.dir / "keyboard.js")],
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr)

    @staticmethod
    def capture(call):
        try:
            call()
        except Exception as e:
            return e


class FocusReassertTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.proc = Path(self.temp.name)
        self.now = [100.0]
        self.focus = agent.FocusReassert(clock=lambda: self.now[0])

    def fake_proc(self, pid, parent, comm, cmdline=None, state="S"):
        root = self.proc / str(pid)
        root.mkdir(parents=True)
        (root / "comm").write_text(comm + "\n")
        if cmdline is not None:
            (root / "cmdline").write_bytes(b"".join(arg.encode() + b"\0" for arg in cmdline))
        (root / "stat").write_text("%d (%s) %s %d 0 0" % (pid, comm, state, parent))

    def snap(self, wm_state="iconic", baselayer=(413091, 769), pid=31, window=0x2400001):
        return {"baselayerAppIds": list(baselayer), "displays": {":0": [], ":1": [
            {"window": 0x1e00012, "map": "viewable", "appId": 2274579123, "pid": 21, "wmState": "normal"},
            {"window": window, "map": "viewable", "appId": 2274579123, "pid": pid, "wmState": wm_state}]}}

    def plan(self, snap):
        with patch.dict(os.environ, {"DISPLAY": ":0"}):
            return self.focus.plan(snap, proc=self.proc)

    def test_off_by_default(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertFalse(agent.FocusReassert.enabled())
        with patch.dict(os.environ, {"BL_FOCUS_REASSERT": "1"}):
            self.assertTrue(agent.FocusReassert.enabled())

    def test_a_minimized_orphan_behind_steam_is_restored_first_in_the_base_layer(self):
        self.fake_proc(30, 1, "wineserver")
        self.fake_proc(31, 30, "WatchDogs2.exe")
        self.fake_proc(21, 30, "upc.exe")
        actions = self.plan(self.snap())
        self.assertEqual(1, len(actions))
        kind, display, window, app, pid, order = actions[0][:6]
        self.assertEqual(("restore", ":1", 0x2400001, 2274579123, 31), (kind, display, window, app, pid))
        self.assertEqual([2274579123, 413091, 769], order)
        # Steam put the game back, but after itself: still behind.
        self.now[0] += 20
        self.assertEqual("restore", self.plan(self.snap(baselayer=(413091, 769, 2274579123)))[0][0])

    def test_tries_are_spaced_and_capped(self):
        self.fake_proc(30, 1, "wineserver")
        self.fake_proc(31, 30, "WatchDogs2.exe")
        self.assertEqual(1, len(self.plan(self.snap())))
        self.now[0] += 2
        self.assertEqual([], self.plan(self.snap()))
        for _ in range(agent.FocusReassert.TRIES - 1):
            self.now[0] += agent.FocusReassert.GAP
            self.assertEqual(1, len(self.plan(self.snap())))
        self.now[0] += agent.FocusReassert.GAP
        self.assertEqual([], self.plan(self.snap()))

    def test_left_alone_when_steam_runs_it_or_it_is_not_minimized(self):
        self.fake_proc(40, 1, "reaper", ["reaper", "SteamLaunch", "AppId=2274579123", "--", "proton"])
        self.fake_proc(41, 40, "WatchDogs2.exe")
        self.assertEqual([], self.plan(self.snap(pid=41)))  # own launch reaper: Steam tracks it
        self.fake_proc(30, 1, "wineserver")
        self.fake_proc(31, 30, "WatchDogs2.exe")
        self.assertEqual([], self.plan(self.snap(wm_state="normal")))
        self.assertEqual([], self.plan(self.snap(baselayer=(413091, 2274579123, 769))))  # game already first
        self.fake_proc(32, 30, "WatchDogs2.exe", state="Z")
        self.assertEqual([], self.plan(self.snap(pid=32)))

    def test_the_window_control_is_released_when_the_window_goes(self):
        self.fake_proc(30, 1, "wineserver")
        self.fake_proc(31, 30, "WatchDogs2.exe")
        self.focus.control = (":1", 0x2400001, 31, [0])
        self.assertEqual([], [a for a in self.plan(self.snap(wm_state="normal")) if a[0] == "release"])
        gone = {"baselayerAppIds": [413091, 769], "displays": {":0": [], ":1": []}}
        self.assertEqual([("release", ":1", 0x2400001, [0], "window gone")], self.plan(gone))
        self.assertIsNone(self.focus.control)

    def test_apply_writes_state_base_layer_and_control_and_logs(self):
        written = []

        class FakeX:
            root = 1

            def __init__(self, display):
                self.display = display

            def set_prop32(self, window, name, kind, values):
                written.append((self.display, window, name, kind, list(values)))

            def prop(self, window, name):
                return [0]

            def close(self):
                pass

        action = ("restore", ":1", 0x2400001, 2274579123, 31, [2274579123, 413091, 769], "why", 1)
        with patch.dict(os.environ, {"DISPLAY": ":0"}), patch.object(agent, "X", FakeX), \
                patch.object(agent, "log") as log:
            self.focus.apply([action])
            self.focus.apply([("release", ":1", 0x2400001, [0], "window gone")])
        self.assertEqual([
            (":1", 0x2400001, "WM_STATE", "WM_STATE", [1, 0]),
            (":0", 1, "GAMESCOPECTRL_BASELAYER_APPID", "CARDINAL", [2274579123, 413091, 769]),
            (":0", 1, "GAMESCOPECTRL_BASELAYER_WINDOW", "CARDINAL", [0x2400001]),
            (":0", 1, "GAMESCOPE_RESTORE_FOCUS_WINDOW", "CARDINAL", [1]),
            (":1", 1, "GAMESCOPE_RESTORE_FOCUS_WINDOW", "CARDINAL", [1]),
            (":0", 1, "GAMESCOPECTRL_BASELAYER_WINDOW", "CARDINAL", [0]),
        ], written)
        lines = [call.args[0] for call in log.call_args_list]
        self.assertTrue(lines[0].startswith("focus reassert #1: game window 0x2400001 on :1 (app 2274579123)"), lines)
        self.assertIn("released GAMESCOPECTRL_BASELAYER_WINDOW", lines[1])


class FocusGuardTest(unittest.TestCase):
    """wd1-vivoboost-0800 L1 (upstream-rebase-1102 § 20): the web helper crashed, the reloaded Steam UI took the
    front and dropped WD1 (3529167647) from its base layer while the game and its launch reaper lived on."""

    APP = 3529167647
    GAME = 0x4200005
    STEAM_WIN = 0x2200037

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.proc = Path(self.temp.name)
        self.now = [1000.0]
        self.events = []
        self.menu = [None]
        self.browser_pids = [{500}]
        self.user = [None]
        self.windows = [[]]
        self.guard = agent.FocusGuard(clock=lambda: self.now[0], proc=self.proc, menu_press=lambda: self.menu[0],
                                      events=self.events, browsers=lambda: set(self.browser_pids[0]),
                                      user_input=lambda: self.user[0], agent_windows=lambda: list(self.windows[0]))
        self.fake_proc(9451, 1, "reaper", cmdline=["reaper", "SteamLaunch", "AppId=%d" % self.APP, "--", "sh"])
        self.fake_proc(10747, 9451, "Watch_Dogs.exe", env={"ENABLE_MALI_COMPAT": "1", "ENABLE_MALI_WSI": "1"})
        self.env = patch.dict(os.environ, {"DISPLAY": ":0"})
        self.env.start()
        self.addCleanup(self.env.stop)
        os.environ.pop("BL_FOCUS_GUARD", None)
        self.logs = patch.object(agent, "log")
        self.log = self.logs.start()
        self.addCleanup(self.logs.stop)

    def fake_proc(self, pid, parent, comm, cmdline=None, state="S", env=None):
        root = self.proc / str(pid)
        root.mkdir(parents=True, exist_ok=True)
        (root / "comm").write_text(comm + "\n")
        if cmdline is not None:
            (root / "cmdline").write_bytes(b"".join(arg.encode() + b"\0" for arg in cmdline))
        (root / "environ").write_bytes(b"".join(("%s=%s" % kv).encode() + b"\0" for kv in (env or {}).items()))
        (root / "stat").write_text("%d (%s) %s %d 0 0" % (pid, comm, state, parent))

    def snap(self, focused, baselayer, focusable=None, wm_state="normal", steam_pid=8300, game=True):
        wins = [{"window": self.GAME, "map": "viewable", "appId": None, "pid": 10747, "wmState": wm_state,
                 "geometry": [0, 0, 1600, 720]}] if game else []
        return {"focusedApp": focused, "focusedWindow": self.GAME if focused == self.APP else self.STEAM_WIN,
                "focusableApps": focusable if focusable is not None else [self.APP, 769],
                "focusableWindows": [{"window": self.GAME, "appId": self.APP, "pid": 10747}],
                "baselayerAppIds": list(baselayer),
                "displays": {":0": [{"window": self.STEAM_WIN, "map": "viewable", "appId": 769, "pid": steam_pid}],
                             ":1": wins}}

    def playing(self, seconds=agent.FocusGuard.LAUNCH_SPAN + 60):
        # past the launch phase: the reload rules alone
        self.assertEqual([], self.guard.plan(self.snap(self.APP, [413091, self.APP, 769])))
        self.now[0] += seconds
        self.assertEqual([], self.guard.plan(self.snap(self.APP, [413091, self.APP, 769])))

    def dropped(self, **kw):
        return self.snap(769, [413091, 769], focusable=[769], **kw)

    def lines(self):
        return [c.args[0] for c in self.log.call_args_list]

    def test_reload_after_a_web_helper_crash_puts_the_game_back(self):
        self.playing()
        self.events.append((self.now[0] - 110, "Steam keyboard: Target crashed"))
        self.now[0] += 5
        self.assertEqual([], self.guard.plan(self.dropped(wm_state="iconic")))  # settles first
        self.assertAlmostEqual(self.now[0] + agent.FocusGuard.BACKOFF[0], self.guard.wake)
        self.now[0] += agent.FocusGuard.BACKOFF[0]
        actions = self.guard.plan(self.dropped(wm_state="iconic"))
        self.assertEqual(1, len(actions))
        kind, display, window, app, pid, order = actions[0][:6]
        self.assertEqual(("restore", ":1", self.GAME, self.APP, 10747), (kind, display, window, app, pid))
        self.assertEqual([413091, self.APP, 769], order)  # Steam's own layout while a game runs
        self.assertEqual((1, "iconic"), actions[0][7:])
        text = "\n".join(self.lines())
        self.assertIn("Steam took the front from app %d" % self.APP, text)
        self.assertIn("its launch reaper alive", text)
        self.assertIn("Target crashed (115 s ago)", text)

    def test_tries_back_off_and_stop_at_the_cap(self):
        self.playing()
        self.events.append((self.now[0], "Steam keyboard: Target crashed"))
        tries = []
        for _ in range(40):
            self.now[0] += 1
            for action in self.guard.plan(self.dropped()):
                tries.append((action[7], self.now[0]))
        self.assertEqual([1, 2, 3], [t for t, _ in tries])
        gaps = [b - a for (_, a), (_, b) in zip(tries, tries[1:])]
        self.assertEqual(list(agent.FocusGuard.BACKOFF[1:]), gaps)
        self.assertIn("giving up for this reload", "\n".join(self.lines()))

    def test_back_in_front_then_dropped_again_counts_in_the_same_reload(self):
        self.playing()
        self.events.append((self.now[0], "Steam keyboard: Target crashed"))
        self.now[0] += 3
        self.assertEqual([], self.guard.plan(self.dropped()))
        self.now[0] += 3
        self.assertEqual(1, self.guard.plan(self.dropped())[0][7])
        self.now[0] += 1
        self.guard.plan(self.snap(self.APP, [413091, self.APP, 769]))  # back in front
        self.assertIn("back in front after try 1", "\n".join(self.lines()))
        self.now[0] += 1
        self.assertEqual([], self.guard.plan(self.dropped()))
        self.now[0] += agent.FocusGuard.BACKOFF[1]
        self.assertEqual(2, self.guard.plan(self.dropped())[0][7])
        # Stable for EPISODE_SPAN: the reload is handled; the old crash does not arm a new one.
        self.guard.plan(self.snap(self.APP, [413091, self.APP, 769]))
        self.now[0] += agent.FocusGuard.EPISODE_SPAN
        self.guard.plan(self.snap(self.APP, [413091, self.APP, 769]))
        self.assertIsNone(self.guard.episode)
        for _ in range(10):
            self.now[0] += 2
            self.assertEqual([], [a for a in self.guard.plan(self.dropped()) if a[0] == "restore"])

    def test_steam_menu_over_the_game_is_left_alone(self):
        # Guide/QAM: Steam goes before the game but keeps it in its base layer.
        # A real menu comes with the user's Guide/QAM stamp; even right after a reload it is left alone.
        self.playing()
        self.events.append((self.now[0], "Steam keyboard: Target crashed"))
        self.menu[0] = (self.now[0] + 2.5, "guide")  # Steam comes to the front at +3
        self.now[0] += 1
        for _ in range(10):
            self.now[0] += 2
            self.assertEqual([], self.guard.plan(self.snap(769, [413091, 769, self.APP])))
        self.assertIn("after a user guide press; left alone", "\n".join(self.lines()))

    def test_a_second_steal_after_a_reload_with_the_game_in_the_base_layer_is_the_reload(self):
        # wd2-uncap-1535: web helper OOM kill, try 1 put WD2 back, 2 s later Steam in front again, WD2 behind it
        self.playing()
        self.events.append((self.now[0], "Steam keyboard: Target crashed"))
        self.now[0] += 1
        self.assertEqual(1, self.restores_within(self.dropped())[1])
        self.guard.plan(self.snap(self.APP, [413091, self.APP, 769]))  # back in front
        self.now[0] += 2
        hit = self.restores_within(self.snap(769, [413091, 769, self.APP]), seconds=15)
        self.assertIsNotNone(hit)
        self.assertEqual(2, hit[1])
        self.assertNotIn("Steam's menu over the game", "\n".join(self.lines()))

    def test_fresh_reload_evidence_covers_a_steal_with_the_game_in_the_base_layer(self):
        self.playing()
        self.events.append((self.now[0] - 30, "Steam keyboard: Target crashed"))
        self.now[0] += 1
        self.assertIsNotNone(self.restores_within(self.snap(769, [413091, 769, self.APP])))

    def test_old_reload_evidence_leaves_steams_menu_alone(self):
        self.playing()
        self.events.append((self.now[0] - agent.FocusGuard.RELOAD_SPAN - 30, "Steam keyboard: Target crashed"))
        self.now[0] += 1
        self.assertIsNone(self.restores_within(self.snap(769, [413091, 769, self.APP]), seconds=20))
        self.assertIn("Steam's menu over the game", "\n".join(self.lines()))

    def test_a_guide_press_before_steam_took_the_front_is_the_user(self):
        self.playing()
        self.events.append((self.now[0] - 30, "Steam keyboard: Target crashed"))
        self.menu[0] = (self.now[0] + 0.5, "guide")
        self.now[0] += 1.5
        for _ in range(10):
            self.assertEqual([], self.guard.plan(self.dropped()))
            self.now[0] += 2
        self.assertIn("after a user guide press; left alone", "\n".join(self.lines()))

    def test_no_reload_evidence_no_action(self):
        # A quit from Steam's menu also drops the game while it is still exiting.
        self.playing()
        self.events.append((self.now[0] - agent.FocusGuard.EVIDENCE_WINDOW - 60, "Steam keyboard: Target crashed"))
        for _ in range(10):
            self.now[0] += 2
            self.assertEqual([], self.guard.plan(self.dropped()))
        self.assertIn("no web helper crash or Steam window remap", "\n".join(self.lines()))

    def test_a_steam_window_remap_or_web_helper_restart_is_evidence(self):
        self.playing()
        self.now[0] += 3
        self.guard.plan(self.dropped(steam_pid=13802))  # Steam's window now belongs to another process
        self.now[0] += agent.FocusGuard.BACKOFF[0]
        actions = self.guard.plan(self.dropped(steam_pid=13802))
        self.assertEqual("restore", actions[0][0])
        self.assertIn("Steam window remap", "\n".join(self.lines()))
        self.assertEqual(["steamwebhelper restarted (browser pid [500] -> [501])"], [
            w for _, w in self.restart_seen()])

    def restart_seen(self):
        guard = agent.FocusGuard(clock=lambda: self.now[0], proc=self.proc, menu_press=lambda: None, events=[],
                                 browsers=lambda: set(self.browser_pids[0]))
        guard.sample_browsers(self.now[0])
        self.browser_pids[0] = {501}
        guard.sample_browsers(self.now[0] + agent.FocusGuard.BROWSER_EVERY)
        return guard.seen

    # ---- launch phase (w3-uncap L1, 11-05 16:02:41-45: in front 4 s, then Steam in front, no press, no reload) ----

    def launched(self, seconds=4):
        self.assertEqual([], self.guard.plan(self.snap(self.APP, [413091, self.APP, 769])))
        self.now[0] += seconds
        self.assertEqual([], self.guard.plan(self.snap(self.APP, [413091, self.APP, 769])))

    def restores_within(self, snap, seconds=10):
        """Plans every second; the first restore's (time after the steal, attempt) or None."""
        start = self.now[0]
        while self.now[0] - start <= seconds:
            restore = [a for a in self.guard.plan(snap) if a[0] == "restore"]
            if restore:
                return self.now[0] - start, restore[0][7]
            self.now[0] += 1
        return None

    def test_launch_steam_in_front_without_user_input_puts_the_game_back(self):
        self.launched()
        self.now[0] += 1
        self.assertEqual([], self.guard.plan(self.dropped()))  # [413091, 769]: no reload evidence at all
        self.now[0] += 1
        hit = self.restores_within(self.snap(769, [413091, 769, self.APP]))  # then Steam before the game
        self.assertIsNotNone(hit)
        self.assertLessEqual(hit[0], agent.FocusGuard.BACKOFF[0])
        self.assertEqual(1, hit[1])
        text = "\n".join(self.lines())
        self.assertIn("launch phase: Steam took the front", text)
        self.assertNotIn("Steam's menu over the game", text)
        self.assertNotIn("(a quit?)", text)

    def test_launch_steam_in_front_right_away_is_not_short(self):
        self.launched(seconds=1)  # under HOLD_MIN
        self.now[0] += 1
        self.assertIsNotNone(self.restores_within(self.dropped()))

    def test_launch_a_user_guide_press_is_the_user(self):
        self.launched()
        self.menu[0] = (self.now[0] + 0.5, "guide")
        self.now[0] += 1
        self.assertIsNone(self.restores_within(self.snap(769, [413091, 769, self.APP]), seconds=20))
        self.assertIn("after a user guide press; left alone", "\n".join(self.lines()))

    def test_launch_an_earlier_user_press_turns_the_launch_rule_off(self):
        self.launched(seconds=30)
        self.menu[0] = (self.now[0] - 20, "qam")  # opened and closed the QAM while the game was in front
        self.now[0] += 1
        self.assertIsNone(self.restores_within(self.snap(769, [413091, 769, self.APP]), seconds=20))

    def test_agent_qam_opens_the_tools_menu_left_alone_and_not_the_users(self):
        # rc2fix-retest-1105 F1b: droiddeckctl QAM at ~180 s; libfakeinput stamped it (98630.0, window 98630.0-98630.0);
        # Steam's overlay [413091, app, 769] then held the front against 3 tries for 46 s.
        self.launched()
        t = self.now[0] + 0.3
        self.windows[0] = [(t, t)]
        self.menu[0] = (t, "qam")
        self.now[0] += 1
        self.assertIsNone(self.restores_within(self.snap(769, [413091, self.APP, 769], focusable=[self.APP]), seconds=20))
        text = "\n".join(self.lines())
        self.assertIn("qam press at %.1f is agent input (window %.1f-%.1f); not the user's" % (t, t, t), text)
        self.assertIn("after our tools' qam press (agent input); left to the tool", text)
        self.assertNotIn("after a user", text)
        # the tool closes its menu; a later steal in the launch phase is still a steal, not the user's choice
        self.guard.plan(self.snap(self.APP, [413091, self.APP, 769]))
        self.now[0] += 30
        self.guard.plan(self.snap(self.APP, [413091, self.APP, 769]))
        self.now[0] += 1
        self.assertIsNotNone(self.restores_within(self.dropped()))

    def test_agent_guide_press_after_the_launch_is_no_user_press_either(self):
        self.playing()
        self.events.append((self.now[0], "Steam keyboard: Target crashed"))
        self.windows[0] = [(self.now[0], self.now[0] + 0.2)]
        self.menu[0] = (self.now[0] + 0.1, "guide")
        self.now[0] += 1
        self.assertIsNone(self.restores_within(self.dropped(), seconds=20))  # the tool's menu
        self.now[0] += 60
        self.guard.plan(self.snap(self.APP, [413091, self.APP, 769]))
        self.now[0] += 5
        self.guard.plan(self.snap(self.APP, [413091, self.APP, 769]))
        self.events.append((self.now[0], "Steam keyboard: Target crashed"))
        self.now[0] += 1
        self.assertIsNotNone(self.restores_within(self.dropped()))  # a later reload is not excused by the old press

    def test_real_input_on_steam_after_it_took_the_front_is_the_user(self):
        self.launched()
        self.now[0] += 1
        self.assertEqual([], self.guard.plan(self.dropped()))
        self.user[0] = self.now[0] + 0.5  # a tap on Steam's UI
        self.now[0] += 1
        self.assertIsNone(self.restores_within(self.dropped(), seconds=20))
        self.assertIn("the user's own input on its UI; left alone", "\n".join(self.lines()))

    def test_real_input_on_the_game_before_steam_took_the_front_does_not_count(self):
        self.launched()
        self.user[0] = self.now[0] - 0.5  # a tap on the game
        self.now[0] += 1
        self.assertIsNotNone(self.restores_within(self.dropped()))

    def test_launch_rule_ends_after_the_launch_span(self):
        self.launched(seconds=agent.FocusGuard.LAUNCH_SPAN + 5)
        self.now[0] += 1
        self.assertIsNone(self.restores_within(self.snap(769, [413091, 769, self.APP]), seconds=20))
        self.assertIn("Steam's menu over the game", "\n".join(self.lines()))

    def test_launch_rule_obeys_the_off_switch(self):
        with patch.dict(os.environ, {"BL_FOCUS_GUARD": "0"}):
            self.launched()
            self.now[0] += 1
            self.assertIsNone(self.restores_within(self.dropped(), seconds=20))

    def test_input_stamp_files(self):
        root = Path(self.temp.name) / "pad"
        root.mkdir()
        (root / "user").write_text("93206974\n")
        (root / "agent").write_text("1000 1500\n2000 2100\n")
        self.assertEqual(93206.974, agent.read_stamp(root / "user"))
        self.assertEqual([(1.0, 1.5), (2.0, 2.1)], agent.read_agent_windows(root / "agent"))
        self.assertIsNone(agent.read_stamp(root / "missing"))
        self.assertEqual([], agent.read_agent_windows(root / "missing"))

    def test_only_games_on_the_bridge_unless_asked(self):
        self.fake_proc(10747, 9451, "Watch_Dogs.exe", env={})
        self.playing()
        self.events.append((self.now[0], "Steam keyboard: Target crashed"))
        for _ in range(6):
            self.now[0] += 2
            self.assertEqual([], self.guard.plan(self.dropped()))
        self.assertIn("not watching app %d" % self.APP, "\n".join(self.lines()))
        self.guard.held = None
        with patch.dict(os.environ, {"BL_FOCUS_GUARD": "1"}):
            self.assertEqual((True, "BL_FOCUS_GUARD=1"), self.guard.enabled_for(10747))
        self.fake_proc(10747, 9451, "Watch_Dogs.exe", env={"ENABLE_MALI_COMPAT": "1", "BL_FOCUS_GUARD": "0"})
        self.assertEqual((False, "the game's BL_FOCUS_GUARD=0"), self.guard.enabled_for(10747))
        with patch.dict(os.environ, {"BL_FOCUS_GUARD": "0"}):
            self.assertTrue(agent.FocusGuard.off())
        self.assertFalse(agent.FocusGuard.off())

    def test_gone_game_stops_and_the_window_control_is_released(self):
        self.playing()
        self.events.append((self.now[0], "Steam keyboard: Target crashed"))
        self.now[0] += 1
        self.assertEqual([], self.guard.plan(self.dropped()))
        self.now[0] += agent.FocusGuard.BACKOFF[0]
        self.assertEqual("restore", self.guard.plan(self.dropped())[0][0])
        self.guard.pin = {"display": ":1", "window": self.GAME, "pid": 10747, "original": [0], "at": self.now[0],
                          "back": None}
        self.now[0] += 1
        self.assertEqual([("release", ":1", self.GAME, [0], "window gone")],
                         self.guard.plan(self.dropped(game=False)))
        self.assertIsNone(self.guard.held)
        self.assertIsNone(self.guard.pin)

    def test_the_window_control_goes_at_a_user_press_or_after_the_hold(self):
        self.playing()
        pin = {"display": ":1", "window": self.GAME, "pid": 10747, "original": [0], "at": self.now[0], "back": None}
        self.guard.pin = dict(pin)
        self.menu[0] = (self.now[0] + 1, "qam")
        self.now[0] += 2
        self.assertEqual([("release", ":1", self.GAME, [0], "user qam press")],
                         self.guard.plan(self.snap(self.APP, [413091, self.APP, 769])))
        self.guard.pin = dict(pin, back=self.now[0])
        self.menu[0] = None
        self.now[0] += agent.FocusGuard.PIN_HOLD
        self.assertEqual("release", self.guard.plan(self.snap(self.APP, [413091, self.APP, 769]))[0][0])
        self.guard.pin = dict(pin, at=self.now[0])
        self.assertEqual([("release", ":1", self.GAME, [0], "Steam put itself before the game [413091, 769, %d]"
                           % self.APP)], [a for a in self.guard.plan(self.snap(769, [413091, 769, self.APP]))
                                          if a[0] == "release"])

    def test_apply_writes_state_base_layer_control_and_restore(self):
        written = []

        class FakeX:
            root = 1

            def __init__(self, display):
                self.display = display

            def set_prop32(self, window, name, kind, values):
                written.append((self.display, window, name, kind, list(values)))

            def prop(self, window, name):
                return [0]

            def close(self):
                pass

        action = ("restore", ":1", self.GAME, self.APP, 10747, [413091, self.APP, 769], "why", 1, "iconic")
        with patch.object(agent, "X", FakeX):
            self.guard.apply([action])
            self.assertEqual(self.GAME, self.guard.pin["window"])
            self.guard.apply([("release", ":1", self.GAME, [0], "window gone")])
            self.guard.pin = None
            self.guard.apply([action], control_busy=True)
        self.assertEqual([
            (":1", self.GAME, "WM_STATE", "WM_STATE", [1, 0]),
            (":0", 1, "GAMESCOPECTRL_BASELAYER_APPID", "CARDINAL", [413091, self.APP, 769]),
            (":0", 1, "GAMESCOPECTRL_BASELAYER_WINDOW", "CARDINAL", [self.GAME]),
            (":0", 1, "GAMESCOPE_RESTORE_FOCUS_WINDOW", "CARDINAL", [1]),
            (":1", 1, "GAMESCOPE_RESTORE_FOCUS_WINDOW", "CARDINAL", [1]),
            (":0", 1, "GAMESCOPECTRL_BASELAYER_WINDOW", "CARDINAL", [0]),
            (":1", self.GAME, "WM_STATE", "WM_STATE", [1, 0]),
            (":0", 1, "GAMESCOPECTRL_BASELAYER_APPID", "CARDINAL", [413091, self.APP, 769]),
            (":0", 1, "GAMESCOPE_RESTORE_FOCUS_WINDOW", "CARDINAL", [1]),
            (":1", 1, "GAMESCOPE_RESTORE_FOCUS_WINDOW", "CARDINAL", [1]),
        ], written)
        lines = self.lines()
        self.assertTrue(lines[0].startswith("focus guard try 1/3: game window 0x4200005 on :1 (app %d" % self.APP),
                        lines)
        self.assertIn("WM_STATE Normal on 0x4200005 (was iconic)", lines[0])
        self.assertIn("released GAMESCOPECTRL_BASELAYER_WINDOW", lines[1])
        self.assertIn("left to focus reassert", lines[2])

    def test_helpers(self):
        self.assertEqual([413091, 7, 769], agent.guard_order([413091, 769], 7))
        self.assertEqual([7, 413091], agent.guard_order([413091, 7], 7))
        events = []
        clock = lambda: 5.0
        with patch.object(agent, "WEBHELPER_EVENTS", events):
            self.assertTrue(agent.note_webhelper_trouble(Exception('{"code": -32000, "message": "Target crashed"}'),
                                                         clock))
            self.assertTrue(agent.note_webhelper_trouble(Exception("DevTools closed the connection"), clock))
            self.assertFalse(agent.note_webhelper_trouble(Exception("No SharedJSContext yet"), clock))
        self.assertEqual([(5.0, "Steam keyboard: Target crashed"),
                          (5.0, "Steam keyboard: closed the connection")], events)
        stamp = self.proc / "menu"
        self.assertIsNone(agent.read_menu_press(stamp))
        stamp.write_text("123456 guide\n")
        self.assertEqual((123.456, "guide"), agent.read_menu_press(stamp))
        self.fake_proc(600, 1, "steamwebhelper", cmdline=["steamwebhelper", "-lang=en"])
        self.fake_proc(601, 600, "steamwebhelper", cmdline=["steamwebhelper", "--type=renderer"])
        self.assertEqual({600}, agent.webhelper_browsers(self.proc))


class VrFocusTest(unittest.TestCase):
    def test_scene_dashboard_and_external_focus_changes(self):
        focus = agent.VrSceneFocus()
        self.assertEqual(focus.order([9, 7], [7, 42], 42), [42, 9, 7])
        self.assertIsNone(focus.order([42, 9, 7], [7, 42], 42))
        self.assertEqual(focus.order([42, 9, 7], [7, 42], None), [9, 7])
        self.assertIsNone(focus.order([9, 7], [7], 42))
        self.assertEqual(focus.order([9, 7], [7, 42], 42), [42, 9, 7])
        self.assertIsNone(focus.order([123, 7], [7, 42], None))

    def test_scene_requires_live_server_and_game_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = root / 'scene.json'
            for pid in (100, 200):
                (root / str(pid)).mkdir()
            (root / '100/comm').write_text('vrserver\n')
            (root / '200/environ').write_bytes(b'SteamAppId=42\0Other=value\0')
            state.write_text(json.dumps(dict(serverPid=100, scenePid=200, dashboard=False)))
            self.assertEqual(agent.vr_scene_app(state, root), 42)
            state.write_text(json.dumps(dict(serverPid=100, scenePid=200, dashboard=True)))
            self.assertIsNone(agent.vr_scene_app(state, root))
            state.write_text(json.dumps(dict(serverPid=100, scenePid=200, dashboard=False)))
            (root / '100/comm').write_text('other\n')
            self.assertIsNone(agent.vr_scene_app(state, root))
            (root / '100/comm').unlink()
            self.assertIsNone(agent.vr_scene_app(state, root))


class TestNudgeDialog(unittest.TestCase):
    def setUp(self):
        self.moved = []
        moved = self.moved

        class MockX:
            def __init__(self, display):
                self.display = display
                self.root = 1

            def geometry(self, win):
                return (0, 0, 1600, 720)

            def move(self, win, x, y):
                moved.append((self.display, win, x, y))

            def close(self):
                pass

        self.mock_x = MockX

    def nudge(self, watcher, snap):
        with patch.object(agent, "X", side_effect=self.mock_x), patch("time.sleep"):
            watcher.nudge_dialog(snap)

    def snapshot(self, focusable=()):
        win = lambda w, geo, app=22330, pid=10449, **kw: dict(dict(
            window=w, appId=app, pid=pid, map="viewable", overrideRedirect=False, inputOnly=False, geometry=geo), **kw)
        return {
            "focusedWindow": 0x2000035,
            "focusableWindows": [{"window": 0x2000035, "appId": 769, "pid": 8750}]
            + [{"window": w, "appId": 22330, "pid": 10449} for w in focusable],
            "displays": {
                ":1": [
                    win(0x1c00003, [479, 120, 640, 480]),  # Oblivion's launcher, not focusable
                    win(0x1c00006, [661, 322, 278, 76]),  # a message box, not focusable
                    win(0x1c00009, [0, 0, 1600, 720]),  # fullscreen game window
                    win(0x1c0000a, [100, 100, 200, 200], overrideRedirect=True),  # a menu
                    win(0x600004, [0, 0, 1, 1], app=None, pid=None),  # 1x1 helper
                ],
                ":0": [win(0x2000035, [0, 0, 1600, 720], app=769, pid=8750)],  # Steam
            },
        }

    def test_moves_unfocusable_dialogs_one_pixel_and_back(self):
        watcher = agent.FocusWatcher(Path("/tmp/focus.json"))
        self.nudge(watcher, self.snapshot())
        self.assertEqual([
            (":1", 0x1c00003, 480, 121), (":1", 0x1c00003, 479, 120),
            (":1", 0x1c00006, 662, 323), (":1", 0x1c00006, 661, 322),
        ], self.moved)

    def test_leaves_windows_gamescope_already_focuses(self):
        watcher = agent.FocusWatcher(Path("/tmp/focus.json"))
        self.nudge(watcher, self.snapshot(focusable=(0x1c00003, 0x1c00006)))
        self.assertEqual([], self.moved)

    def test_same_window_id_on_another_server_is_not_focusable(self):
        watcher = agent.FocusWatcher(Path("/tmp/focus.json"))
        snap = self.snapshot()
        snap["focusableWindows"].append({"window": 0x1c00003, "appId": 769, "pid": 8750})
        self.nudge(watcher, snap)
        self.assertIn((":1", 0x1c00003, 480, 121), self.moved)

    def test_gives_up_after_three_tries_and_forgets_closed_windows(self):
        watcher = agent.FocusWatcher(Path("/tmp/focus.json"))
        snap = self.snapshot()
        for _ in range(5):
            self.nudge(watcher, snap)
        self.assertEqual(agent.FocusWatcher.NUDGE_TRIES * 4, len(self.moved))
        snap["displays"][":1"] = []
        self.nudge(watcher, snap)
        self.assertEqual({}, watcher.nudged)

    def test_can_be_turned_off(self):
        watcher = agent.FocusWatcher(Path("/tmp/focus.json"))
        with patch.dict(os.environ, {"BL_NUDGE_DIALOGS": "0"}):
            self.nudge(watcher, self.snapshot())
        self.assertEqual([], self.moved)


class StagedPadApply(agent.PadApply):
    """PadApply with the focus and the three stages replaced by recorders."""

    def __init__(self, base, stages="ABC"):
        evdev = base / "evdev"
        (evdev / ".padapply").mkdir(parents=True)
        super().__init__(base, evdev, str(base / "pad.log"), stages, display=":99")
        self.SETTLE = 1.0
        self.focus = 3749198560
        self.focusable = None              # None: the focused app is focusable
        self.ran = []
        for name in "abc":
            setattr(self, "stage_" + name, lambda app, n=name.upper(): self.ran.append((n, app)) or "ok " + n)

    def focused_app(self):
        return self.focus

    def focusable_apps(self):
        return [769, self.focus] if self.focusable is None else self.focusable

    def write(self, **values):
        state = dict(adopts=0, node="event16", fed=0, dead=0, guide=0)
        state.update(values)
        (self.dir / "state").write_text(" ".join("%s=%s" % kv for kv in state.items()) + "\n")

    def lines(self):
        path = Path(self.log_file)
        return [l.split("agent: padapply: ", 1)[1] for l in path.read_text().splitlines()] if path.exists() else []


class PadApplyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)

    def test_state_line_is_parsed(self):
        path = self.base / "state"
        path.write_text("adopts=2 node=event16 fed=0 dead=1 guide=0\n")
        self.assertEqual({"adopts": 2, "node": "event16", "fed": 0, "dead": 1, "guide": 0},
                         agent.read_padapply_state(path))
        path.write_text("garbage\n")
        self.assertIsNone(agent.read_padapply_state(path))
        self.assertIsNone(agent.read_padapply_state(self.base / "missing"))

    def test_stages_escalate_on_dead_marks_until_fed(self):
        pad = StagedPadApply(self.base)
        pad.step(0.0)                      # no state yet: no adopt so far
        pad.write(adopts=1)
        pad.step(0.1)
        pad.step(0.5)
        self.assertEqual([], pad.ran)      # not settled yet
        pad.step(1.2)
        self.assertEqual([("A", 3749198560)], pad.ran)
        pad.write(adopts=1, dead=1)
        pad.step(2.0)
        pad.write(adopts=1, dead=2)
        pad.step(3.5)
        pad.write(adopts=1, dead=3)
        pad.step(5.0)
        pad.step(5.1)
        self.assertEqual(["A", "B", "C"], [n for n, _ in pad.ran])
        pad.write(adopts=1, dead=3, fed=4, guide=2)
        pad.step(6.0)
        pad.write(adopts=1, dead=4, fed=4, guide=2)
        pad.step(7.0)
        self.assertEqual([
            "adopt #1 of event16 seen",
            "adopt #1: settled -> stage A on app 3749198560: ok A",
            "adopt #1: dead mark 1 -> stage B on app 3749198560: ok B",
            "adopt #1: dead mark 2 -> stage C on app 3749198560: ok C",
            "adopt #1: dead mark 3, all stages (ABC) used",
            "adopt #1: the pad is fed again (after stage C)",
        ], pad.lines())

    def test_fed_before_settle_runs_nothing(self):
        pad = StagedPadApply(self.base)
        pad.step(0.0)
        pad.write(adopts=1)
        pad.step(0.1)
        pad.write(adopts=1, fed=2)
        pad.step(0.4)
        pad.step(2.0)
        self.assertEqual([], pad.ran)
        self.assertEqual("adopt #1: the pad is fed again (after no stage)", pad.lines()[-1])

    def test_no_stage_without_a_game_in_focus(self):
        pad = StagedPadApply(self.base)
        pad.step(0.0)
        pad.focus = 769                    # the adopt came with the game's exit
        pad.write(adopts=1)
        pad.step(0.1)
        pad.step(1.5)
        pad.write(adopts=1, dead=1)
        pad.step(2.0)
        self.assertEqual([], pad.ran)
        pad.focus = 3749198560             # back in the game: the next dead mark gets stage A
        pad.write(adopts=1, dead=2)
        pad.step(3.0)
        self.assertEqual([("A", 3749198560)], pad.ran)
        self.assertIn("adopt #1: settled, not acting (focus 769)", pad.lines())

    def test_no_stage_for_a_game_that_is_exiting(self):
        # control-padapply run 1: the adopt came with the game's exit; gamescope still had it
        # focused but no longer focusable.
        pad = StagedPadApply(self.base)
        pad.step(0.0)
        pad.focusable = [769]
        pad.write(adopts=1)
        pad.step(0.1)
        pad.step(1.5)
        self.assertEqual([], pad.ran)
        self.assertIn("adopt #1: settled, not acting (focus 3749198560 not in the focusable apps [769])",
                      pad.lines())

    def test_default_settle_outlasts_steams_exit_switch(self):
        self.assertGreaterEqual(agent.PadApply.SETTLE, 2.5)

    def test_a_new_adopt_starts_over(self):
        pad = StagedPadApply(self.base, stages="BC")
        pad.step(0.0)
        pad.write(adopts=1)
        pad.step(0.1)
        pad.step(1.5)                      # B is invisible: it runs on its own after the settle
        pad.write(adopts=2)
        pad.step(2.0)
        pad.step(3.5)
        self.assertEqual(["B", "B"], [n for n, _ in pad.ran])

    def test_guide_stage_only_waits_for_a_dead_mark(self):
        pad = StagedPadApply(self.base, stages="C")
        pad.step(0.0)
        pad.write(adopts=1)
        pad.step(0.1)
        pad.step(1.5)
        self.assertEqual([], pad.ran)
        pad.write(adopts=1, dead=1)
        pad.step(2.0)
        self.assertEqual(["C"], [n for n, _ in pad.ran])

    def test_agent_restart_ignores_an_earlier_adopt(self):
        pad = StagedPadApply(self.base)
        pad.write(adopts=3, dead=2)
        pad.step(0.0)
        pad.step(2.0)
        self.assertEqual([], pad.ran)
        self.assertEqual([], pad.lines())

    def test_stale_state_from_an_older_steam_does_not_hide_a_new_adopt(self):
        # control-padapply2: .padapply/state from the previous session (adopts=1) survived the
        # session start; the new Steam process's adopt #1 compared equal and started no cycle.
        pad = StagedPadApply(self.base)
        pad.write(adopts=1, fed=3, pid=10436, at=5123456)
        pad.step(0.0)                      # the agent starts on the stale state
        pad.step(0.5)
        self.assertEqual([], pad.ran)
        self.assertEqual([], pad.lines())
        pad.write(adopts=1, pid=12140, at=777000)
        pad.step(1.0)
        pad.write(adopts=1, dead=0, pid=12140, at=777000)
        pad.step(1.5)                      # same adopt rewritten: no second cycle
        pad.step(2.1)
        self.assertEqual([("A", 3749198560)], pad.ran)
        self.assertEqual(["adopt #1 of event16 seen (Steam pid 12140)",
                          "adopt #1: settled -> stage A on app 3749198560: ok A"], pad.lines())

    def test_stage_a_toggles_configurator_focus_over_devtools(self):
        devtools = FakeDevTools({"result": {"result": {"type": "string", "value": "ForceConfiguratorFocus true/false"}}})
        devtools.start()
        self.addCleanup(devtools.server.close)
        (self.base / "cdp-port").write_text(str(devtools.port))
        pad = agent.PadApply(self.base, self.base / "evdev", None, "A", display=":99")
        self.assertEqual("ForceConfiguratorFocus true/false", pad.stage_a(3749198560))
        expression = devtools.seen[0]["params"]["expression"]
        self.assertIn("ForceConfiguratorFocus(true)", expression)
        self.assertIn("ForceConfiguratorFocus(false)", expression)
        self.assertLess(expression.index("(true)"), expression.index("(false)"))

    def test_guide_stage_closes_the_menu_it_opened(self):
        pad = agent.PadApply(self.base, self.base / "evdev", None, "C", display=":99")
        pad.FOCUS_WAIT = 0.3
        focus = [3749198560]
        taps = []

        def tap():
            taps.append(1)
            focus[0] = 769 if len(taps) == 1 else 3749198560
            return True
        pad.request_guide = tap
        pad.focused_app = lambda: focus[0]
        self.assertEqual("guide taps 1+1: Steam menu opened and closed, focus back on 3749198560",
                         pad.stage_c(3749198560))
        self.assertEqual(2, len(taps))

    def test_guide_stage_never_taps_twice_when_the_menu_did_not_open(self):
        pad = agent.PadApply(self.base, self.base / "evdev", None, "C", display=":99")
        pad.FOCUS_WAIT = 0.2
        taps = []
        pad.request_guide = lambda: taps.append(1) or True
        pad.focused_app = lambda: 3749198560
        self.assertEqual("guide tap 1 did not bring the Steam menu; no second tap", pad.stage_c(3749198560))
        self.assertEqual(1, len(taps))

    def test_guide_request_is_taken_by_fakeinput(self):
        pad = agent.PadApply(self.base, self.base / "evdev", None, "C", display=":99")
        pad.dir.mkdir(parents=True)
        (pad.dir / "state").write_text("adopts=1 node=event16 fed=0 dead=1 guide=0\n")

        def fakeinput():
            for _ in range(100):
                if (pad.dir / "guide").exists():
                    (pad.dir / "guide").unlink()
                    (pad.dir / "state").write_text("adopts=1 node=event16 fed=0 dead=1 guide=1\n")
                    return
                time.sleep(0.01)
        threading.Thread(target=fakeinput, daemon=True).start()
        self.assertTrue(pad.request_guide())
        # Nobody takes it: the request is withdrawn, so a later Deck reader cannot tap late.
        self.assertFalse(pad.request_guide())
        self.assertFalse((pad.dir / "guide").exists())


if __name__ == "__main__":
    unittest.main()
