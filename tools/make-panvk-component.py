#!/usr/bin/env python3
"""Builds the PanVK component zip the app installs (gpu/PanvkPackage): panvk-kbase-<V>.zip with one top folder
panvk-kbase-<V>/ holding libvulkan_panfrost.so, meta.json, SOURCE.txt, LICENSE, patches/*.patch and SHA256SUMS (every
other file), plus panvk-kbase-<V>.zip.sha256 next to it. The zip is deterministic (fixed times, sorted entries), so the
same inputs give the same bytes.

    tools/make-panvk-component.py --version v26.3.1-234-237 --so .../libvulkan_panfrost.so --meta .../meta.json \\
        --driver-version v26.3.1+234-237 --source-text SOURCE.txt --license LICENSES/MIT.txt --patch 237.patch --out DIR
"""
import argparse
import hashlib
import io
import json
import re
import sys
import zipfile
from pathlib import Path

VERSION = re.compile(r"[A-Za-z0-9._+-]{1,48}")
STAMP = (2026, 1, 1, 0, 0, 0)


def entry(z, name, data, mode=0o644):
    info = zipfile.ZipInfo(name, STAMP)
    info.external_attr = (0o100000 | mode) << 16
    info.create_system = 3
    info.compress_type = zipfile.ZIP_DEFLATED
    z.writestr(info, data, compresslevel=9)


def build(version, so, meta, driver_version, source_text, license_text, patches):
    if not VERSION.fullmatch(version):
        raise ValueError("bad version %r" % version)
    if so[:4] != b"\x7fELF" or so[4] != 2 or int.from_bytes(so[18:20], "little") != 183:
        raise ValueError("the .so is not a 64-bit ARM ELF")
    if b"libc.so.6" not in so:
        raise ValueError("the .so is not a glibc build")
    meta = dict(meta, kind="linux-vulkan-icd", backend="panvk-kbase", libraryName="libvulkan_panfrost.so",
                driverVersion=driver_version or meta.get("driverVersion") or version)
    files = {
        "libvulkan_panfrost.so": so,
        "meta.json": (json.dumps(meta, indent=2, sort_keys=True) + "\n").encode(),
        "SOURCE.txt": source_text.encode(),
        "LICENSE": license_text.encode(),
    }
    for name, data in patches:
        files["patches/" + name] = data
    sums = "".join("%s  ./%s\n" % (hashlib.sha256(files[n]).hexdigest(), n) for n in sorted(files))
    top = "panvk-kbase-%s/" % version
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as z:
        for name in sorted(files):
            entry(z, top + name, files[name], 0o755 if name.endswith(".so") else 0o644)
        entry(z, top + "SHA256SUMS", sums.encode())
    return out.getvalue()


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--version", required=True)
    p.add_argument("--so", required=True, type=Path)
    p.add_argument("--meta", required=True, type=Path, help="the PanVK build's meta.json (package-glibc.sh)")
    p.add_argument("--driver-version", default="")
    p.add_argument("--source-text", required=True, type=Path)
    p.add_argument("--license", required=True, type=Path)
    p.add_argument("--patch", action="append", default=[], type=Path)
    p.add_argument("--out", required=True, type=Path)
    a = p.parse_args(argv)
    data = build(a.version, a.so.read_bytes(), json.loads(a.meta.read_text()), a.driver_version, a.source_text.read_text(),
                 a.license.read_text(), [(x.name, x.read_bytes()) for x in a.patch])
    a.out.mkdir(parents=True, exist_ok=True)
    zip_path = a.out / ("panvk-kbase-%s.zip" % a.version)
    zip_path.write_bytes(data)
    digest = hashlib.sha256(data).hexdigest()
    (a.out / (zip_path.name + ".sha256")).write_text("%s  %s\n" % (digest, zip_path.name))
    print("%s  %s" % (digest, zip_path))
    return 0


if __name__ == "__main__":
    sys.exit(main())
