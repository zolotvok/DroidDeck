"""guest-tools updater, PC side: tools/make-guest-tools-package.py and the guest check droiddeck-guest-tools-check."""
import argparse
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest
import zipfile

TOOLS = Path(__file__).resolve().parents[1]
AGENT = TOOLS / "linuxfs/overlay/usr/local/bin/droiddeck-agent"
CHECK = TOOLS / "linuxfs/overlay/usr/local/bin/droiddeck-guest-tools-check"
SESSION = TOOLS / "linuxfs/overlay/usr/local/bin/droiddeck-session"


def md5(data):
    return hashlib.md5(data).hexdigest()


class PackageToolTest(unittest.TestCase):
    def setUp(self):
        self.tool = runpy.run_path(str(TOOLS / "make-guest-tools-package.py"))
        self.dir = Path(tempfile.mkdtemp())

    def tearDown(self):
        import shutil
        shutil.rmtree(self.dir, ignore_errors=True)

    def build(self, agent=AGENT, zip_too=True, out=None):
        with contextlib.redirect_stdout(io.StringIO()):
            return self.tool["build"](argparse.Namespace(agent=str(agent), commit="b" * 40, date="2026-11-13", changelog="agent",
                                                         out=str(out or self.dir), zip=zip_too))

    def refused(self, source):
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(self.tool["Refused"]) as caught:
            self.tool["check"](source)
        return str(caught.exception)

    def test_build_is_deterministic_and_carries_exactly_the_allowlist(self):
        release = self.tool["RELEASE"].search(AGENT.read_bytes()).group(1).decode()
        folder = self.build()
        self.assertEqual("guest-tools-" + release, folder.name)
        files = sorted(p.relative_to(folder).as_posix() for p in folder.rglob("*") if p.is_file())
        self.assertEqual(["LICENSE", "SOURCE", "meta.json", "rootfs/usr/local/bin/droiddeck-agent"], files)
        meta = json.loads((folder / "meta.json").read_text())
        self.assertEqual(("guest-tools", release, 20), (meta["component"], meta["version"], meta["minApp"]))
        self.assertEqual(md5(AGENT.read_bytes()), meta["files"]["rootfs/usr/local/bin/droiddeck-agent"]["md5"])
        self.assertTrue(os.access(folder / "rootfs/usr/local/bin/droiddeck-agent", os.X_OK))
        archive = folder.with_name(folder.name + ".zip")
        first = archive.read_bytes()
        self.build(out=self.dir / "again")
        self.assertEqual(first, (self.dir / "again" / archive.name).read_bytes())
        self.assertEqual(hashlib.sha256(first).hexdigest(), Path(str(archive) + ".sha256").read_text().split()[0])
        with zipfile.ZipFile(archive) as z:
            self.assertTrue(all(i.date_time == (1980, 1, 1, 0, 0, 0) for i in z.infolist()))

    def test_check_refuses_unlisted_files_paths_and_bad_agents(self):
        folder = self.build(zip_too=False)
        (folder / "rootfs/usr/local/bin/droiddeck-mali-defaults").write_text("x")
        self.assertIn("not a guest-tools file", self.refused(folder))
        (folder / "rootfs/usr/local/bin/droiddeck-mali-defaults").unlink()
        (folder / "NOTES").write_text("x")
        self.assertIn("not a guest-tools file", self.refused(folder))
        (folder / "NOTES").unlink()
        (folder / "rootfs/usr/local/bin/link").symlink_to("droiddeck-agent")
        self.assertIn("symbolic link", self.refused(folder))
        (folder / "rootfs/usr/local/bin/link").unlink()
        agent = folder / "rootfs/usr/local/bin/droiddeck-agent"
        agent.write_bytes(agent.read_bytes() + b"\n# changed\n")
        self.assertIn("does not match meta.json", self.refused(folder))
        bad = self.dir / "bad-agent"
        for text, why in ((b"#!/bin/sh\nAGENT_RELEASE = \"1\"\n", "shebang"),
                          (b"#!/usr/bin/python3\nprint('--selftest')\n", "AGENT_RELEASE"),
                          (b"#!/usr/bin/python3\nAGENT_RELEASE = \"2026.1\"\n", "--selftest"),
                          (b"#!/usr/bin/python3\nAGENT_RELEASE = \"2026.1\"\nx = \"--selftest\"\ndef (:\n", "compile")):
            bad.write_bytes(text)
            with self.assertRaises(self.tool["Refused"]) as caught, contextlib.redirect_stdout(io.StringIO()):
                self.build(agent=bad, zip_too=False, out=self.dir / "bad")
            self.assertIn(why, str(caught.exception))

    def test_zip_traversal_is_refused(self):
        archive = self.dir / "guest-tools-2026.11.13.1.zip"
        with zipfile.ZipFile(archive, "w") as z:
            z.writestr("guest-tools-2026.11.13.1/rootfs/../../etc/passwd", b"x")
        self.assertIn("unsafe path", self.refused(archive))


class GuestCheckTest(unittest.TestCase):
    """droiddeck-guest-tools-check against a fake rootfs (BL_GUEST_TOOLS_ROOT) and HOME."""

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.root = self.dir / "rootfs"
        self.home = self.dir / "home"
        self.state = self.root / "usr/local/lib/droiddeck-guest-tools"
        self.live = self.root / "usr/local/bin/droiddeck-agent"
        self.live.parent.mkdir(parents=True)
        (self.state / "bundled/usr/local/bin").mkdir(parents=True)
        self.bundled = AGENT.read_bytes()
        (self.state / "bundled/usr/local/bin/droiddeck-agent").write_bytes(self.bundled)
        self.data = self.home / ".local/share/droiddeck-components"

    def tearDown(self):
        import shutil
        shutil.rmtree(self.dir, ignore_errors=True)

    def activate(self, agent, version="2026.11.14.1"):
        self.live.write_bytes(agent)
        self.live.chmod(0o755)
        (self.state / "active.json").write_text(json.dumps({"version": version, "files": {"usr/local/bin/droiddeck-agent": md5(agent)}}))

    def run_check(self):
        env = dict(os.environ, BL_GUEST_TOOLS_ROOT=str(self.root), HOME=str(self.home), BL_LAUNCH_DIR=str(self.dir / "launch"))
        done = subprocess.run([sys.executable, "-I", str(CHECK)], env=env, capture_output=True, timeout=120)
        self.assertEqual(0, done.returncode, done.stderr)
        return done

    def log(self):
        path = self.data / "install.log"
        return [line.split("\t") for line in path.read_text().splitlines()] if path.exists() else []

    def test_nothing_to_do_without_an_active_package(self):
        self.live.write_bytes(self.bundled)
        self.run_check()
        self.assertEqual(self.bundled, self.live.read_bytes())
        self.assertEqual([], self.log())

    def test_a_good_agent_passes_once_and_is_remembered(self):
        agent = self.bundled.replace(b"# changed nothing", b"")
        self.activate(agent, "2026.11.13.1")
        self.run_check()
        self.assertEqual(agent, self.live.read_bytes())
        self.assertTrue((self.state / "active.json").exists())
        self.assertEqual([["guest-tools", "selftest", "ok"]], [[f[1], f[2], f[7]] for f in self.log()])
        self.assertEqual(9, len(self.log()[0]))
        self.run_check()
        self.assertEqual(1, len(self.log()))

    def test_a_failing_selftest_puts_the_bundled_agent_back(self):
        agent = b"#!/usr/bin/python3\nimport sys\nAGENT_RELEASE = \"2026.11.14.1\"\nsys.exit(3 if sys.argv[1:] == ['--selftest'] else 0)\n"
        self.activate(agent)
        self.run_check()
        self.assertEqual(self.bundled, self.live.read_bytes())
        self.assertTrue(os.access(self.live, os.X_OK))
        self.assertFalse((self.state / "active.json").exists())
        refused = json.loads((self.data / "guest-tools-refused.json").read_text())
        self.assertEqual((md5(agent), True), (refused["agentMd5"], refused["restored"]))
        self.assertIn("--selftest failed (exit 3)", refused["reason"])
        entry = self.log()[-1]
        self.assertEqual(["guest-tools", "refused", "2026.11.14.1", "bundled", "guest", "SELFTEST_FAILED"],
                         [entry[1], entry[2], entry[3], entry[4], entry[6], entry[7]])

    def test_an_agent_that_does_not_compile_or_changed_is_refused(self):
        self.activate(b"#!/usr/bin/python3\nAGENT_RELEASE = \"2026.11.14.1\"\ndef (:\n")
        self.run_check()
        self.assertEqual(self.bundled, self.live.read_bytes())
        self.assertIn("does not compile", json.loads((self.data / "guest-tools-refused.json").read_text())["reason"])
        self.activate(self.bundled)
        self.live.write_bytes(self.bundled + b"\n")
        self.run_check()
        self.assertIn("changed since the app staged it", json.loads((self.data / "guest-tools-refused.json").read_text())["reason"])

    def test_an_old_agent_without_selftest_never_serves(self):
        # the agent before --selftest exits 2 without BL_LAUNCH_DIR (the check removes it) instead of serving
        old = self.bundled.replace(b'if argv == ["--selftest"]:', b'if argv == ["--no-such-flag"]:')
        self.assertNotEqual(old, self.bundled)
        self.activate(old)
        self.run_check()
        self.assertEqual(self.bundled, self.live.read_bytes())
        self.assertIn("exit 2", json.loads((self.data / "guest-tools-refused.json").read_text())["reason"])

    def test_the_session_runs_the_check_before_the_agent(self):
        text = SESSION.read_text()
        start = text.index("start_agent() {")
        body = text[start:text.index("\n}\n", start)]
        self.assertLess(body.index("droiddeck-guest-tools-check"), body.index("/usr/local/bin/droiddeck-agent \"$@\""))
        self.assertIn("|| :", body)

    def test_the_agent_selftest(self):
        env = {k: v for k, v in os.environ.items() if k != "BL_LAUNCH_DIR"}
        done = subprocess.run([sys.executable, "-I", str(AGENT), "--selftest"], env=env, capture_output=True, timeout=60)
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertRegex(done.stdout.decode(), r"^droiddeck-agent \d{4}\.\d{2}\.\d{2}\.\d+ selftest ok\n$")


if __name__ == "__main__":
    unittest.main()
