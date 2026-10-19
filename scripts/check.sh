#!/bin/sh
set -eu
: "${GO:?set GO to the Go executable}"
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_dir"
format_files=$(git ls-files --cached --others --exclude-standard -- '*.go')
if [ -n "$format_files" ]; then
    # Repository source names are controlled and contain no whitespace.
    format_output=$(printf '%s\n' "$format_files" | xargs gofmt -l)
    if [ -n "$format_output" ]; then
        printf '%s\n' 'Go formatting check failed' >&2
        exit 1
    fi
fi
python3 scripts/test_secret_scan.py
python3 scripts/test_media_resolver.py
python3 scripts/secret-scan.py
"$GO" vet ./...
"$GO" test -race ./...
mkdir -p build
CGO_ENABLED=0 "$GO" build -trimpath -o build/vpn-sweep ./cmd/vpn-sweep
GOOS=android GOARCH=arm64 CGO_ENABLED=0 "$GO" build -trimpath -o build/vpn-sweep-android-arm64 ./cmd/vpn-sweep
