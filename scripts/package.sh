#!/bin/sh
set -eu
: "${GO:?set GO to the Go executable}"
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_dir"
# Build/check is an explicit prerequisite, never rebuild a tested artifact here.
test -f build/vpn-sweep
test -f build/vpn-sweep-android-arm64
python3 scripts/secret-scan.py
mkdir -p dist
tar -czf dist/vpn-sweep-local.tar.gz build/vpn-sweep build/vpn-sweep-android-arm64 LICENSE README.md docs examples
sha256sum build/vpn-sweep build/vpn-sweep-android-arm64 dist/vpn-sweep-local.tar.gz > dist/SHA256SUMS
