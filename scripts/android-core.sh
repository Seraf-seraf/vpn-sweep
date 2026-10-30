#!/bin/sh
set -eu
: "${GO:=go}"
: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK directory}"
: "${JAVA_HOME:?set JAVA_HOME to JDK 17}"
: "${ANDROID_NDK_HOME:=$ANDROID_HOME/ndk/28.2.13676358}"
export ANDROID_NDK_HOME
repo_dir=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
tools_dir="$repo_dir/build/android-tools"
mkdir -p "$tools_dir" "$repo_dir/android/app/libs"
cd "$repo_dir/android/core"
"$GO" build -mod=readonly -o "$tools_dir/gomobile" golang.org/x/mobile/cmd/gomobile
"$GO" build -mod=readonly -o "$tools_dir/gobind" golang.org/x/mobile/cmd/gobind
PATH="$tools_dir:$JAVA_HOME/bin:$(dirname -- "$(command -v "$GO")"):$PATH"
export PATH
GOOS=android GOARCH=arm64 CGO_ENABLED=1 "$GO" list -mod=readonly -deps -json \
    . golang.org/x/mobile/bind/seq > "$tools_dir/packages.json"
python3 "$repo_dir/scripts/android-notices.py" "$tools_dir/packages.json" \
    "$repo_dir/android/app/build/generated/licenses" "$repo_dir"
# init also installs gobind@latest; initialize its cache without replacing our pin.
gopath=$("$GO" env GOPATH)
mkdir -p "${gopath%%:*}/pkg/gomobile"
# anet uses Go internals on Android; Xray's pinned release uses this linker flag.
gomobile bind -target=android/arm64 -androidapi=34 -javapkg=com.seraf.vpnsweep.core \
    -trimpath -ldflags='-s -w -checklinkname=0' -o "$repo_dir/android/app/libs/sweep.aar" .
