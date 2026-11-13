#!/usr/bin/env python3
"""The fex-arm64ec component package (docs/agents/release-perf-1111.md 3.1, 3.3): one patched libarm64ecfex.dll per
FEX build, keyed by the stock DLL's md5, for droiddeck-mali-defaults' fex_patch() and gpu/FexArm64ecPackage.kt.

  make-fex-arm64ec-package.py build --version V --date YYYY-MM-DD --commit SHA --out DIR [--zip] \
      --row stock=PATH,fex_version=..,dll=PATH,patch=PATH[,proton_build=..][,fex_commit=..][,supersedes=MD5:MD5..] [--row ...]
  make-fex-arm64ec-package.py check DIR-or-ZIP
  make-fex-arm64ec-package.py unpack DIR-or-ZIP OUT

Package `fex-arm64ec-<V>/`: meta.json (every other file with sha256 + md5), table.json (the rows), dll/, patches/,
LICENSE (FEX, MIT) and NOTICE. The same file set always gives the same bytes (sorted entries, fixed zip times).
check applies the app's rules: listed files only and all matching, ARM64EC PE DLLs whose FEX version string is the
row's, every md5 (stock, patched, superseded) in one role of one row only. Prints the md5s.

A row's stock md5 comes from the stock DLL itself (stock=PATH: Proton's own libarm64ecfex.dll, which must be ARM64EC and
carry the row's FEX version). stock_md5=<md5> instead is taken only with --typed-stock-md5 (nothing checks it then).
supersedes = patched md5s of older builds for the same stock DLL: the guest replaces such a DLL with this row's one."""
import argparse
import hashlib
import json
import re
import shutil
import struct
import sys
import zipfile
from pathlib import Path

COMPONENT = "fex-arm64ec"
PREFIX = COMPONENT + "-"
VERSION = re.compile(r"[A-Za-z0-9._+-]{1,48}")
HEX32 = re.compile(r"[0-9a-f]{32}")
HEX40 = re.compile(r"[0-9a-f]{40}")
FEX_VERSION = re.compile(rb"FEX-\d{4}(?:\.\d+)?(?:-\d+-g[0-9a-f]{6,})?")
FEX_VERSION_TEXT = re.compile(r"FEX-\d{4}(?:\.\d+)?(?:-\d+-g[0-9a-f]{6,})?")
PROTON_BUILD = re.compile(r"[A-Za-z0-9._+-]{1,64}")
REPO = "https://github.com/zolotvok/blobferry"
LICENSE = Path(__file__).resolve().parent / "linuxfs/fex-arm64ec/LICENSE"
MIN_APP = 20
MAX_ROWS = 64
ZIP_TIME = (1980, 1, 1, 0, 0, 0)
ROW_KEYS = ("stock_md5", "fex_version", "proton_build", "patched", "patched_md5", "patch", "patch_md5", "fex_commit", "supersedes")
EMPTY_MD5 = "d41d8cd98f00b204e9800998ecf8427e"


class Refused(Exception):
    pass


def digest(data, name):
    return hashlib.new(name, data).hexdigest()


def fex_version(data):
    found = FEX_VERSION.findall(data)
    return max(found, key=len).decode() if found else None


def arm64ec_dll(data):
    """A PE32+ DLL with machine AMD64 (0x8664) whose load config carries a CHPE (hybrid) metadata pointer: ARM64EC."""
    try:
        if data[:2] != b"MZ":
            return False
        pe = struct.unpack_from("<I", data, 0x3C)[0]
        if data[pe:pe + 4] != b"PE\0\0":
            return False
        machine, sections, _, _, _, optional_size, characteristics = struct.unpack_from("<HHIIIHH", data, pe + 4)
        opt = pe + 24
        if machine != 0x8664 or not characteristics & 0x2000 or struct.unpack_from("<H", data, opt)[0] != 0x20B:
            return False
        image_base = struct.unpack_from("<Q", data, opt + 24)[0]
        config_rva, config_size = struct.unpack_from("<II", data, opt + 112 + 10 * 8)
        if config_size < 0xD0:
            return False
        table = opt + optional_size

        def offset(rva):
            for i in range(sections):
                _, virtual_size, address, raw_size, raw = struct.unpack_from("<8sIIII", data, table + 40 * i)
                if address <= rva < address + max(virtual_size, raw_size) and rva - address < raw_size:
                    return raw + rva - address
            return None

        config = offset(config_rva)
        if config is None or struct.unpack_from("<I", data, config)[0] < 0xD0:
            return False
        chpe = struct.unpack_from("<Q", data, config + 0xC8)[0]
        return chpe > image_base and offset(chpe - image_base) is not None
    except struct.error:
        return False


def parse_row(text):
    row = {}
    for part in text.split(","):
        key, _, value = part.partition("=")
        if key not in ("stock", "stock_md5", "fex_version", "proton_build", "dll", "patch", "fex_commit", "supersedes") \
                or not value or key in row:
            raise Refused("bad --row part %r" % part)
        row[key] = value
    for key in ("fex_version", "dll", "patch"):
        if key not in row:
            raise Refused("--row needs %s" % key)
    if "stock" not in row and "stock_md5" not in row:
        raise Refused("--row needs stock=PATH (Proton's stock DLL)")
    if "supersedes" in row:
        row["supersedes"] = row["supersedes"].split(":")
        if not all(HEX32.fullmatch(v) for v in row["supersedes"]):
            raise Refused("supersedes must be md5s separated by ':'")
    return row


def stock_md5(row, typed_ok):
    """The row's stock md5: from stock=PATH (an ARM64EC DLL with the row's FEX version), or the typed stock_md5= with
    --typed-stock-md5. Both given: they must agree."""
    if "stock" not in row:
        if not typed_ok:
            raise Refused("%s: stock_md5= is typed by hand; give stock=PATH, or --typed-stock-md5" % row["fex_version"])
        if not HEX32.fullmatch(row["stock_md5"]):
            raise Refused("%s: stock_md5 is not 32 hex digits" % row["fex_version"])
        return row["stock_md5"]
    data = Path(row["stock"]).read_bytes()
    if not arm64ec_dll(data):
        raise Refused("%s is not an ARM64EC PE DLL" % row["stock"])
    if fex_version(data) != row["fex_version"]:
        raise Refused("%s: FEX version %s, the row says %s" % (row["stock"], fex_version(data), row["fex_version"]))
    md5 = digest(data, "md5")
    if row.get("stock_md5", md5) != md5:
        raise Refused("%s: md5 %s, the row says %s" % (row["stock"], md5, row["stock_md5"]))
    return md5


def build(args):
    if not VERSION.fullmatch(args.version):
        raise Refused("bad version %r" % args.version)
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", args.date) or not HEX40.fullmatch(args.commit):
        raise Refused("--date YYYY-MM-DD and --commit <40 hex> are required")
    rows = [parse_row(text) for text in args.row]
    files = {"LICENSE": LICENSE.read_bytes()}
    table = []
    notice = ["FEX (https://github.com/FEX-Emu/FEX), the arm64ec FEX in Proton (ARM64), rebuilt with one patch per FEX",
              "build that recompiles JIT blocks which keep trapping on unaligned atomics with an out-of-line fallback.",
              "One patched libarm64ecfex.dll per row of table.json:", ""]
    typed_ok = getattr(args, "typed_stock_md5", False)
    for row in rows:
        row["stock_md5"] = stock_md5(row, typed_ok)
        dll = Path(row["dll"]).read_bytes()
        patch = Path(row["patch"]).read_bytes()
        version = row["fex_version"]
        if not FEX_VERSION_TEXT.fullmatch(version) or fex_version(dll) != version:
            raise Refused("%s: FEX version %s, the row says %s" % (row["dll"], fex_version(dll), version))
        patched_md5 = digest(dll, "md5")
        name = "dll/libarm64ecfex-%s-%s.dll" % (version, patched_md5[:8])
        patch_name = "patches/0001-arm64ec-unaligned-atomic-fallback-%s.patch" % version
        if patch_name in files and files[patch_name] != patch:
            raise Refused("two different patches for %s" % version)
        files[name] = dll
        files[patch_name] = patch
        entry = {"stock_md5": row["stock_md5"], "fex_version": version}
        if row.get("proton_build"):
            entry["proton_build"] = row["proton_build"]
        entry.update(patched=name, patched_md5=patched_md5, patch=patch_name, patch_md5=digest(patch, "md5"))
        if row.get("fex_commit"):
            entry["fex_commit"] = row["fex_commit"]
        if row.get("supersedes"):
            entry["supersedes"] = row["supersedes"]
        table.append(entry)
        notice.append("  %s%s (upstream %s): stock md5 %s -> patched md5 %s" % (
            version, " / Proton " + row["proton_build"] if row.get("proton_build") else "", row.get("fex_commit", "?"),
            row["stock_md5"], patched_md5))
        if row.get("supersedes"):
            notice.append("    replaces the older patched builds %s" % ", ".join(row["supersedes"]))
        notice.append("    patch %s (md5 %s)" % (patch_name, entry["patch_md5"]))
    notice += ["", "The patches are the files in patches/ (md5s above). Package built from %s commit %s." % (REPO, args.commit),
               "FEX and these patches are under the MIT license; see LICENSE (FEX's own LICENSE). The patches are not part",
               "of FEX and are not supported by the FEX project.", "",
               "DroidDeck puts a row's DLL into an ARM64 Proton's files/lib/wine/aarch64-windows/ before a game starts, only",
               "over the stock DLL whose md5 is the row's stock_md5 (the stock copy is kept), and again after a Proton update",
               "or verify. A DLL that matches no row is left as it is (logged as fex-unmatched). BL_FEX_ARM64EC_PATCH=0 keeps",
               "or puts back the stock DLL. FEX_UNALIGNED_ATOMIC_FALLBACK=0 turns the patched behaviour off inside the DLL.", ""]
    files["NOTICE"] = "\n".join(notice).encode()
    files["table.json"] = (json.dumps(table, indent=2) + "\n").encode()
    meta = {"component": COMPONENT, "version": args.version, "date": args.date, "changelog": args.changelog,
            "source": {"repo": REPO, "commit": args.commit}, "minApp": MIN_APP,
            "files": {rel: {"sha256": digest(data, "sha256"), "md5": digest(data, "md5")} for rel, data in sorted(files.items())}}
    files["meta.json"] = (json.dumps(meta, indent=2) + "\n").encode()
    top = PREFIX + args.version
    out = Path(args.out) / top
    if out.exists():
        shutil.rmtree(out)
    for rel, data in sorted(files.items()):
        (out / rel).parent.mkdir(parents=True, exist_ok=True)
        (out / rel).write_bytes(data)
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
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)
    return archive


def read_tree(source):
    """(top folder name, {relative path: bytes}) of a package folder or zip."""
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
    for rel in files:
        if not rel or rel.startswith("/") or "\\" in rel or any(p in ("", ".", "..") for p in rel.split("/")):
            raise Refused("unsafe path %r" % rel)
    meta = json.loads(files.get("meta.json", b"null"))
    if not isinstance(meta, dict) or meta.get("component") != COMPONENT or meta.get("version") != top[len(PREFIX):]:
        raise Refused("meta.json is not the %s meta of %s" % (COMPONENT, top))
    listed = meta.get("files") or {}
    for rel in files:
        if rel != "meta.json" and rel not in listed:
            raise Refused("%s is not listed in meta.json" % rel)
    for rel, sums in listed.items():
        if rel not in files:
            raise Refused("meta.json lists %s, the package lacks it" % rel)
        if digest(files[rel], "sha256") != sums.get("sha256") or digest(files[rel], "md5") != sums.get("md5"):
            raise Refused("%s does not match meta.json" % rel)
    table = json.loads(files.get("table.json", b"null"))
    if not isinstance(table, list) or not 1 <= len(table) <= MAX_ROWS:
        raise Refused("table.json must be a list of 1..%d rows" % MAX_ROWS)
    seen, used = set(), set()
    for row in table:
        if not isinstance(row, dict) or set(row) - set(ROW_KEYS):
            raise Refused("bad row %r" % (row,))
        for key in ("stock_md5", "patched_md5", "patch_md5"):
            if not HEX32.fullmatch(str(row.get(key))):
                raise Refused("row %s: %s is not 32 hex digits" % (row.get("fex_version"), key))
        if not FEX_VERSION_TEXT.fullmatch(str(row.get("fex_version"))):
            raise Refused("row: bad fex_version %r" % row.get("fex_version"))
        if "proton_build" in row and not PROTON_BUILD.fullmatch(str(row["proton_build"])):
            raise Refused("row %s: bad proton_build" % row["fex_version"])
        if "fex_commit" in row and not re.fullmatch(r"[0-9a-f]{7,40}", str(row["fex_commit"])):
            raise Refused("row %s: bad fex_commit" % row["fex_version"])
        old = row.get("supersedes", [])
        if not isinstance(old, list) or ("supersedes" in row and not 1 <= len(old) <= MAX_ROWS) \
                or not all(HEX32.fullmatch(str(v)) for v in old):
            raise Refused("row %s: supersedes must be a list of 1..%d md5s" % (row["fex_version"], MAX_ROWS))
        for value in [row["stock_md5"], row["patched_md5"]] + old:
            if value == EMPTY_MD5:
                raise Refused("row %s: %s is an empty file's md5" % (row["fex_version"], value))
            if value in seen:
                raise Refused("md5 %s twice (one role in one row only)" % value)
            seen.add(value)
        dll = files.get(row.get("patched"))
        if not str(row.get("patched")).startswith("dll/") or dll is None or row["patched"] in used:
            raise Refused("row %s: patched must be its own dll/ file of the package" % row["fex_version"])
        used.add(row["patched"])
        if digest(dll, "md5") != row["patched_md5"]:
            raise Refused("row %s: %s is not md5 %s" % (row["fex_version"], row["patched"], row["patched_md5"]))
        if not arm64ec_dll(dll):
            raise Refused("row %s: %s is not an ARM64EC PE DLL" % (row["fex_version"], row["patched"]))
        if fex_version(dll) != row["fex_version"]:
            raise Refused("row %s: the DLL says %s" % (row["fex_version"], fex_version(dll)))
        patch = files.get(row.get("patch"))
        if not str(row.get("patch")).startswith("patches/") or patch is None or digest(patch, "md5") != row["patch_md5"]:
            raise Refused("row %s: patch %s missing or not md5 %s" % (row["fex_version"], row.get("patch"), row["patch_md5"]))
    stray = [rel for rel in files if rel.startswith("dll/") and rel not in used]
    if stray:
        raise Refused("DLLs no row uses: %s" % ", ".join(stray))
    for name in ("LICENSE", "NOTICE"):
        if name not in files:
            raise Refused("%s is missing" % name)
    print("%s ok: %d rows" % (top, len(table)))
    for row in table:
        print("  %-24s %-30s stock %s -> patched %s  patch %s%s" % (
            row["fex_version"], row.get("proton_build", "-"), row["stock_md5"], row["patched_md5"], row["patch_md5"],
            "  supersedes " + ",".join(row["supersedes"]) if row.get("supersedes") else ""))
    return top, table


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="op", required=True)
    b = sub.add_parser("build")
    b.add_argument("--version", required=True)
    b.add_argument("--date", required=True)
    b.add_argument("--commit", required=True, help="the Blobferry commit the patches come from")
    b.add_argument("--changelog", default="patched FEX arm64ec DLLs (unaligned-atomic fallback), one per FEX build")
    b.add_argument("--row", action="append", required=True)
    b.add_argument("--out", required=True)
    b.add_argument("--zip", action="store_true", help="also write <out>/fex-arm64ec-<V>.zip (+ .sha256)")
    b.add_argument("--typed-stock-md5", action="store_true", help="take a row's stock_md5= as typed (no stock DLL to check)")
    c = sub.add_parser("check")
    c.add_argument("package")
    u = sub.add_parser("unpack", help="check, then write the package's files into OUT (the APK assets; no top folder)")
    u.add_argument("package")
    u.add_argument("out")
    args = parser.parse_args(argv)
    try:
        if args.op == "build":
            build(args)
        elif args.op == "check":
            check(args.package)
        else:
            check(args.package)
            for rel, data in sorted(read_tree(args.package)[1].items()):
                target = Path(args.out) / rel
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
    except (Refused, OSError, ValueError, zipfile.BadZipFile) as error:
        print("refused: %s" % error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
