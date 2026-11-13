#!/bin/bash
# The fex-arm64ec package the APK bundles (tools/build_local.sh DROIDDECK_FEX_TABLE): rows FEX-2610-101 and FEX-2610-175
# from a Blobferry checkout's FEX builds. Deterministic; prints every md5.
#   tools/make-fex-arm64ec-release.sh [out dir]   (default: <blobferry>/build/release/fex-arm64ec)
# BLOBFERRY_DIR: the Blobferry checkout (default ../bridge, else ../..: a worktree under bridge/build/).
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/.." && pwd)
bf=${BLOBFERRY_DIR:-}
if [[ -z "${bf}" ]]; then
    for candidate in "${repo_root}/../bridge" "${repo_root}/../.."; do
        if [[ -f "${candidate}/patches/fex/0001-arm64ec-recompile-blocks-that-keep-trapping-on-unaligned-atomics.patch" ]]; then
            bf=$(cd "${candidate}" && pwd)
            break
        fi
    done
fi
[[ -n "${bf}" ]] || { echo "no Blobferry checkout found; set BLOBFERRY_DIR" >&2; exit 1; }
out=${1:-${bf}/build/release/fex-arm64ec}
mkdir -p "${out}"
python3 "${repo_root}/tools/make-fex-arm64ec-package.py" build --version 2026.11.13.1 --date 2026-11-13 \
    --commit 2d90092006bf8e48a792aab94515ed01af5af685 --out "${out}" --zip \
    --row "stock_md5=863695500852d6df6a7b6b971893b4c1,fex_version=FEX-2610-101-g1c4f04b,fex_commit=1c4f04b71a4d,dll=${bf}/build/fex-build/patched-arm64ec/Bin/libarm64ecfex.dll,patch=${bf}/patches/fex/0001-arm64ec-recompile-blocks-that-keep-trapping-on-unaligned-atomics.patch" \
    --row "stock_md5=cdab6135bbbcd10e284c5ca332583fc0,fex_version=FEX-2610-175-geff9f45,proton_build=experimental-11.0-20261105c,fex_commit=eff9f459d8a13dc80aa9edb331c3da0e98334a38,dll=${bf}/build/stage-fex-175/libarm64ecfex.dll,patch=${bf}/build/stage-fex-175/0001-for-FEX-2610-175-geff9f45.patch"
(cd "${out}/fex-arm64ec-2026.11.13.1" && find . -type f | LC_ALL=C sort | xargs md5sum)
