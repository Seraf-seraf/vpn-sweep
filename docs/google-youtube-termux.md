# Google, YouTube and video: Termux ARM64

The built-in google-youtube-v1 checks both pages and YouTube video delivery. It remains
live-unverified; desktop tests and cross-build do not establish Android device acceptance.

```sh
pkg install git golang make ca-certificates
mkdir -p "$HOME/src"
git clone https://github.com/Seraf-seraf/vpn-sweep.git "$HOME/src/vpn-sweep"
cd "$HOME/src/vpn-sweep"
make android
SWEEP_BIN="$HOME/src/vpn-sweep/build/vpn-sweep-android-arm64"
"$SWEEP_BIN" version
```

Store your exported configuration outside checkout and shared /sdcard storage:

```sh
umask 077
SWEEP_PRIVATE="$HOME/.local/share/vpn-sweep"
mkdir -p "$SWEEP_PRIVATE"
chmod 700 "$SWEEP_PRIVATE"
# Put your JSON configuration in $SWEEP_PRIVATE/input.json using an editor.
chmod 600 "$SWEEP_PRIVATE/input.json"
"$SWEEP_BIN" inspect --input "$SWEEP_PRIVATE/input.json"
"$SWEEP_BIN" policy
"$SWEEP_BIN" run --core "$HOME/Xray-core/xray" \
  --input "$SWEEP_PRIVATE/input.json" \
  --state "$SWEEP_PRIVATE/series-001" --output "$SWEEP_PRIVATE/result-001"
```

Xray must report commit ac5d8a8 or its full SHA. The app prepares its managed YouTube
venv automatically; missing Python/Node are installed with pkg. Node must be >=24.
Setup requires network access. No manual policy/space files, runtime paths or confirmation
flags are needed. No Android VPN setting is changed or independently verified.
An already active VPN can affect the tested path.

Watch stderr for preparation, current attempt/round/check, outcomes and stop reasons.
Ctrl-C requests cleanup; wait for exit before using status/report or another writer.

```sh
"$SWEEP_BIN" status --state "$SWEEP_PRIVATE/series-001"
"$SWEEP_BIN" report --state "$SWEEP_PRIVATE/series-001"
"$SWEEP_BIN" resume --core "$HOME/Xray-core/xray" \
  --state "$SWEEP_PRIVATE/series-001" --output "$SWEEP_PRIVATE/result-001"
```

The first success requires every page and the video samples again after core restart.
Working.json contains credentials; do not publish it. Missing/challenge/forbidden pages,
unsupported media and incomplete transfers are not success. A blocked required capability
pauses with a retryable candidate. See [usage](usage.md) and [video limits](video-profile.md).
