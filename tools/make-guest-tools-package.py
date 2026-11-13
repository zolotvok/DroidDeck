#!/usr/bin/env python3
"""The guest-tools component package (docs/agents/release-perf-1111.md 3.1, 3.3): the guest agent as an updater package,
for gpu/GuestToolsPackage.kt.

  make-guest-tools-package.py build --commit SHA --out DIR [--agent PATH] [--date YYYY-MM-DD] [--changelog TEXT] [--zip]
  make-guest-tools-package.py check DIR-or-ZIP

Package `guest-tools-<V>/`: meta.json (every other file with sha256 + md5), rootfs/usr/local/bin/droiddeck-agent,
LICENSE (DroidDeck, GPL-3) and SOURCE. V is the agent's AGENT_RELEASE line. The same files always give the same bytes
(sorted entries, fixed zip times and modes). check applies the app's rules: only the allowlisted rootfs files, LICENSE,
SOURCE and meta.json; every file listed and matching; the version YYYY.M.D.N; the agent a python3 script (first line
exactly `#!/usr/bin/python3` or `#!/usr/bin/env python3`, LF; size bound, UTF-8) whose AGENT_RELEASE is the version and
which compiles and has --selftest. Prints the md5s."""
import argparse
import hashlib
import json
import re
import shutil
import sys
import zipfile
from pathlib import Path

COMPONENT = "guest-tools"
PREFIX = COMPONENT + "-"
AGENT = "usr/local/bin/droiddeck-agent"
ALLOWLIST = (AGENT,)  # GuestToolsPackage.ALLOWLIST
TOP_FILES = ("LICENSE", "SOURCE")
# the package version (= AGENT_RELEASE), GuestToolsPackage.VERSION: a date and a serial, compared numerically
VERSION = re.compile(r"\d{4}\.\d{1,2}\.\d{1,2}\.\d{1,3}")
RELEASE = re.compile(rb'^AGENT_RELEASE = "([A-Za-z0-9._+-]{1,48})"[ \t]*$', re.M)
# the session execs the agent through its shebang: exactly one of these first lines (no flags, no \r)
SHEBANGS = (b"#!/usr/bin/python3\n", b"#!/usr/bin/env python3\n")
HEX40 = re.compile(r"[0-9a-f]{40}")
REPO = "https://github.com/zolotvok/DroidDeck"
TOOLS = Path(__file__).resolve().parent
DEFAULT_AGENT = TOOLS / "linuxfs/overlay" / AGENT
LICENSE = TOOLS.parent / "LICENSE"
MIN_APP = 20
MAX_SCRIPT = 2 << 20
ZIP_TIME = (1980, 1, 1, 0, 0, 0)


class Refused(Exception):
    pass


def digest(data, name):
    return hashlib.new(name, data).hexdigest()


def check_agent(data, version=None):
    """The app's script rules (GuestToolsPackage.checkScript / verify), plus a compile."""
    if not 0 < len(data) <= MAX_SCRIPT:
        raise Refused("the agent must be 1..%d bytes" % MAX_SCRIPT)
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise Refused("the agent is not UTF-8: %s" % error) from error
    if "\0" in text or not data.startswith(SHEBANGS):
        raise Refused("the agent's first line must be exactly #!/usr/bin/python3 or #!/usr/bin/env python3 (LF)")
    found = RELEASE.search(data)
    if not found:
        raise Refused("the agent has no AGENT_RELEASE line")
    release = found.group(1).decode()
    if not VERSION.fullmatch(release):
        raise Refused("AGENT_RELEASE %r is not YYYY.M.D.N" % release)
    if version is not None and release != version:
        raise Refused("the agent says AGENT_RELEASE %s, the package is %s" % (release, version))
    if '"--selftest"' not in text:
        raise Refused("the agent has no --selftest")
    try:
        compile(data, AGENT, "exec")
    except SyntaxError as error:
        raise Refused("the agent does not compile: %s" % error) from error
    return release


def build(args):
    agent = Path(args.agent).read_bytes()
    version = check_agent(agent)
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", args.date) or not HEX40.fullmatch(args.commit):
        raise Refused("--date YYYY-MM-DD and --commit <40 hex> are required")
    md5 = digest(agent, "md5")
    files = {
        "rootfs/" + AGENT: agent,
        "LICENSE": LICENSE.read_bytes(),
        "SOURCE": ("DroidDeck guest agent (droiddeck-agent), release %s\n"
                   "Source: %s, commit %s, file tools/linuxfs/overlay/%s\n"
                   "md5 %s, sha256 %s\n"
                   "License: GPL-3.0-or-later (LICENSE). Installed by DroidDeck's guest-tools updater over the agent the APK\n"
                   "bundles while not older than it; the guest runs `droiddeck-agent --selftest` before the session uses it.\n"
                   % (version, REPO, args.commit, AGENT, md5, digest(agent, "sha256"))).encode(),
    }
    meta = {"component": COMPONENT, "version": version, "date": args.date, "changelog": args.changelog,
            "source": {"repo": REPO, "commit": args.commit}, "minApp": MIN_APP,
            "files": {rel: {"sha256": digest(data, "sha256"), "md5": digest(data, "md5")} for rel, data in sorted(files.items())}}
    files["meta.json"] = (json.dumps(meta, indent=2) + "\n").encode()
    top = PREFIX + version
    out = Path(args.out) / top
    if out.exists():
        shutil.rmtree(out)
    for rel, data in sorted(files.items()):
        (out / rel).parent.mkdir(parents=True, exist_ok=True)
        (out / rel).write_bytes(data)
    (out / "rootfs" / AGENT).chmod(0o755)
    check(out)
    if args.zip:
        archive = write_zip(top, files, Path(args.out) / (top + ".zip"))
        check(archive)
        data = archive.read_bytes()
        Path(str(archive) + ".sha256").write_text("%s  %s\n" % (digest(data, "sha256"), archive.name))
        print("zip %s md5 %s sha256 %s" % (archive, digest(data, "md5"), digest(data, "sha256")))
    return out


def write_zip(top, files, archive):
    """The package as a zip with the same bytes for the same files (sorted entries, fixed times and modes)."""
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
        for rel, data in sorted(files.items()):
            info = zipfile.ZipInfo(top + "/" + rel, ZIP_TIME)
            info.create_system = 3
            info.external_attr = (0o100755 if rel.startswith("rootfs/") else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)
    return archive


def read_tree(source):
    """(top folder name, {relative path: bytes}) of a package folder or zip; links refused."""
    source = Path(source)
    files = {}
    if source.is_dir():
        for path in sorted(source.rglob("*")):
            if path.is_symlink():
                raise Refused("symbolic link in the package: %s" % path)
            if path.is_file():
                files[path.relative_to(source).as_posix()] = path.read_bytes()
        return source.name, files
    with zipfile.ZipFile(source) as z:
        names = z.namelist()
        tops = {name.split("/", 1)[0] for name in names}
        if len(tops) != 1 or len(names) != len(set(names)):
            raise Refused("a package has exactly one top folder %s<version>/" % PREFIX)
        top = tops.pop()
        for info in z.infolist():
            if info.is_dir():
                continue
            if (info.external_attr >> 16) & 0o170000 == 0o120000:
                raise Refused("symbolic link in the package: %s" % info.filename)
            files[info.filename[len(top) + 1:]] = z.read(info)
    return top, files


def check(source):
    top, files = read_tree(source)
    if not top.startswith(PREFIX) or not VERSION.fullmatch(top[len(PREFIX):]):
        raise Refused("not a %s<version> package: %s" % (PREFIX, top))
    version = top[len(PREFIX):]
    for rel in files:
        if not rel or rel.startswith("/") or "\\" in rel or any(p in ("", ".", "..") for p in rel.split("/")):
            raise Refused("unsafe path %r" % rel)
        if rel != "meta.json" and rel not in TOP_FILES and not (rel.startswith("rootfs/") and rel[len("rootfs/"):] in ALLOWLIST):
            raise Refused("%s is not a guest-tools file" % rel)
    meta = json.loads(files.get("meta.json", b"null"))
    if not isinstance(meta, dict) or meta.get("component") != COMPONENT or meta.get("version") != version:
        raise Refused("meta.json is not the %s meta of %s" % (COMPONENT, top))
    listed = meta.get("files")
    if not isinstance(listed, dict) or not all(isinstance(sums, dict) for sums in listed.values()):
        raise Refused("meta.json files must map each path to {sha256, md5}")
    for rel in files:
        if rel != "meta.json" and rel not in listed:
            raise Refused("%s is not listed in meta.json" % rel)
    for rel, sums in listed.items():
        if rel not in files:
            raise Refused("meta.json lists %s, the package lacks it" % rel)
        if digest(files[rel], "sha256") != sums.get("sha256") or digest(files[rel], "md5") != sums.get("md5"):
            raise Refused("%s does not match meta.json" % rel)
    for name in TOP_FILES + ("rootfs/" + AGENT,):
        if name not in files:
            raise Refused("%s is missing" % name)
    check_agent(files["rootfs/" + AGENT], version)
    print("%s ok: agent md5 %s" % (top, digest(files["rootfs/" + AGENT], "md5")))
    return top, version


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="op", required=True)
    b = sub.add_parser("build")
    b.add_argument("--agent", default=str(DEFAULT_AGENT), help="the agent script (default: this checkout's)")
    b.add_argument("--commit", required=True, help="the DroidDeck commit the agent comes from (40 hex)")
    b.add_argument("--date", required=True)
    b.add_argument("--changelog", default="DroidDeck guest agent")
    b.add_argument("--out", required=True)
    b.add_argument("--zip", action="store_true", help="also write <out>/guest-tools-<V>.zip (+ .sha256)")
    c = sub.add_parser("check")
    c.add_argument("package")
    args = parser.parse_args(argv)
    try:
        if args.op == "build":
            build(args)
        else:
            check(args.package)
    except (Refused, OSError, ValueError, zipfile.BadZipFile, AttributeError, TypeError, KeyError) as error:
        print("refused: %s" % error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
