# Google and YouTube pages: Termux ARM64

The built-in `google-youtube-web-noise-v1` checks the Google and YouTube HTML pages in
two fresh-core rounds. For a candidate with `noise=true`, UDP/DNS through SOCKS must also
pass in both rounds; it is not run for `noise=false`. The policy does not require video
or downloads. It remains live-unverified; desktop tests and cross-build do not establish
Android device acceptance.

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

Xray must report commit ac5d8a8 or its full SHA. No manual policy/space files,
YouTube runtime dependencies or confirmation flags are needed. No Android VPN setting is changed or independently verified.
An already active VPN can affect the tested path.

Watch stderr for preparation, current attempt/round/check, outcomes and stop reasons.
Ctrl-C requests cleanup; wait for exit before using status/report or another writer.

```sh
"$SWEEP_BIN" status --state "$SWEEP_PRIVATE/series-001"
"$SWEEP_BIN" report --state "$SWEEP_PRIVATE/series-001"
"$SWEEP_BIN" resume --core "$HOME/Xray-core/xray" \
  --state "$SWEEP_PRIVATE/series-001" --output "$SWEEP_PRIVATE/result-001"
```

The first success requires both pages again after core restart and, for `noise=true`,
UDP/DNS in both rounds. `working.json` contains credentials; do not publish it. Missing,
challenge or forbidden pages are not success.
Saved series requiring YouTube video need a new series; status/report and export of verified
results remain available. See [usage](usage.md).
