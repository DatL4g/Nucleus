#!/usr/bin/env bash
# Fetches the ANGLE runtime DLLs (libEGL.dll + libGLESv2.dll) used by the Tao
# Windows backend's Direct3D-11 render path, and drops them into the gitignored
# native resource directories.
#
# ANGLE (BSD-licensed, https://chromium.googlesource.com/angle/angle) translates
# the OpenGL ES calls Skia issues into Direct3D 11. This gives the Tao backend a
# DirectX render path with a guaranteed WARP software fallback (works on RDP / VM
# / driverless boxes where the native WGL path can only obtain a GL 1.1 context
# that Skia's DirectContext.makeGL() rejects).
#
# We do NOT commit the DLLs (binaries never live in git, see .gitignore) and we
# do NOT build ANGLE from source (depot_tools + GN, hours). Instead we extract
# them from a PINNED Electron release — a stable, versioned, BSD/MIT source that
# publishes an official SHASUMS256.txt. The expected hashes are also pinned here
# as defense-in-depth, so a tampered mirror is caught even if SHASUMS256.txt is
# swapped too.
#
# Runs both locally (plain bash) and in CI (build-natives.yaml, shell: bash).
# Usage: fetch-angle.sh [x64|arm64|all]   (default: all)
set -euo pipefail

ELECTRON_VERSION="v42.3.3"
BASE_URL="https://github.com/electron/electron/releases/download/${ELECTRON_VERSION}"

# SHA-256 of the Electron release zips (from the official SHASUMS256.txt for
# ${ELECTRON_VERSION}). Pinned here so the download is verified twice.
SHA_X64="d204d1aaf76e80db6102c482a2f7cc6d20c6c570e9ac6ac5bfee61155467e6a0"
SHA_ARM64="2f62636597a6a9693f51b428be73322713e970e348c5c4d0cccf37bf148c9c2f"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RES_DIR="$(cd "${SCRIPT_DIR}/../../resources/nucleus/native" && pwd)"

want="${1:-all}"

sha256_of() {
    # Cross-platform sha256: prefer sha256sum (git-bash/Linux), fall back to certutil (Windows).
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        certutil -hashfile "$1" SHA256 | sed -n 2p | tr -d ' \r'
    fi
}

fetch_arch() {
    local arch="$1" expected_sha="$2" out_subdir="$3"
    local zip_name="electron-${ELECTRON_VERSION}-win32-${arch}.zip"
    local url="${BASE_URL}/${zip_name}"
    local out_dir="${RES_DIR}/${out_subdir}"
    local tmp; tmp="$(mktemp -d)"
    trap 'rm -rf "${tmp}"' RETURN

    echo "==> Fetching ANGLE (${arch}) from ${zip_name}"
    curl -fL --retry 3 -o "${tmp}/${zip_name}" "${url}"

    local actual_sha; actual_sha="$(sha256_of "${tmp}/${zip_name}")"
    if [ "${actual_sha,,}" != "${expected_sha,,}" ]; then
        echo "ERROR: SHA-256 mismatch for ${zip_name}" >&2
        echo "  expected: ${expected_sha}" >&2
        echo "  actual:   ${actual_sha}" >&2
        exit 1
    fi
    echo "    SHA-256 OK (${actual_sha})"

    mkdir -p "${out_dir}"
    # -j: flatten (the DLLs sit at the zip root); -o: overwrite.
    unzip -j -o "${tmp}/${zip_name}" "libEGL.dll" "libGLESv2.dll" -d "${out_dir}"
    echo "    Extracted libEGL.dll + libGLESv2.dll -> ${out_dir}"
}

case "${want}" in
    x64)   fetch_arch "x64"   "${SHA_X64}"   "win32-x64" ;;
    arm64) fetch_arch "arm64" "${SHA_ARM64}" "win32-aarch64" ;;
    all)
        fetch_arch "x64"   "${SHA_X64}"   "win32-x64"
        fetch_arch "arm64" "${SHA_ARM64}" "win32-aarch64"
        ;;
    *) echo "Usage: $0 [x64|arm64|all]" >&2; exit 2 ;;
esac

echo "ANGLE DLLs ready."
