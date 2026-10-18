# Google/YouTube web profile: Termux ARM64

This is a runnable **live-unverified preset**, not a verified public-resource contract.
Cloud research received proxy CONNECT403, before origin TLS/HTTP; no raw markers were verified.
Success means only Google and YouTube HTML pages through the tested proxy, not video, general
Internet, QUIC or bulk throughput. Both sites belong to operator google. web-basic-v1 is unchanged.

## Build a public checkout

Use a supported Termux distribution, internal app storage, no root. Commands assume a new checkout.

```sh
pkg update
pkg install git golang make python ca-certificates nano curl coreutils
uname -m
go version
go env GOOS GOARCH
mkdir -p "$HOME/src"
git clone --branch main https://github.com/Seraf-seraf/vpn-sweep.git "$HOME/src/vpn-sweep"
cd "$HOME/src/vpn-sweep"
git rev-parse HEAD
GOTOOLCHAIN=local make android GO="$(command -v go)"
SWEEP_BIN="$HOME/src/vpn-sweep/build/vpn-sweep-android-arm64"
"$SWEEP_BIN" version
"$SWEEP_BIN" help
```

Expect Android/arm64 (uname aarch64). go.mod minimum is 1.24.0; tested compiler is 1.27.2.
No third-party Go modules, CGO or NDK required. Other compilers/device execution are unverified.
`help` is supported; `--help` is not. Local Linux checks use make check/test/vet/scan/build;
make android cross-builds the target. Existing fixtures depend on umask077; they remain unfixed.
No GitHub Actions workflows are installed or restored.

## Private import and profile validation (no network)

```sh
umask 077
SWEEP_PRIVATE="$HOME/.local/share/vpn-sweep"
mkdir -p "$SWEEP_PRIVATE"
chmod 700 "$SWEEP_PRIVATE"
nano "$SWEEP_PRIVATE/input.json"
chmod 600 "$SWEEP_PRIVATE/input.json"
"$SWEEP_BIN" inspect --input "$SWEEP_PRIVATE/input.json" --format json
cp examples/google-youtube-web-v1.json "$SWEEP_PRIVATE/policy.json"
chmod 600 "$SWEEP_PRIVATE/policy.json"
"$SWEEP_BIN" policy --policy "$SWEEP_PRIVATE/policy.json"
SWEEP_XRAY="$HOME/Xray-core/xray"
test -x "$SWEEP_XRAY"
"$SWEEP_XRAY" version
sha256sum "$SWEEP_XRAY"
```

Paste your Happ JSON in the editor, never in argv/history. Unknown fields/chains fail closed.
The policy command checks structure/scope only; it does NOT fetch sites or certify markers.
Its output must say live_unverified and network_requests=false. The core must report the token
ac5d8a8 or full ac5d8a83ba798c390cd89f235c3d4c35c77c1586. Identify checks this token and freezes
its binary SHA256 for the series/startups/resume. A version number alone is insufficient.
Private directories must be exactly0700 and files0600, outside Git and shared /sdcard storage.

## Finish resource verification on Termux

The preset requires all markers (case-sensitive): Google title plus name="q" and action="/search";
YouTube ytInitialData plus ytcfg.set and "richGridRenderer" (homepage content structure). These are candidate markers, not live-verified observations.
Each full body must be 4096..2097152 bytes, status200 and text/html. No fixed body hash/size is
invented. Profile permits min_bytes1024..2097152 and 2..8 distinct nonempty markers (4..1024 bytes).
Changing them means a reviewed, user-supplied body contract; it does not make it officially verified.
Do not replace them by generic brand words or loosen checks to accept consent/challenge pages.

Optionally inspect two raw responses per site from a known working network, without another VPN,
using the same User-Agent and identity encoding. These are reference requests, not tunnel success:

```sh
mkdir -p "$SWEEP_PRIVATE/reference"
chmod 700 "$SWEEP_PRIVATE/reference"
for round in 1 2; do
  curl --silent --show-error --proto '=https' --connect-timeout 10 --max-time 45 \
    --max-filesize 2097152 --user-agent 'vpn-sweep/1 connectivity-check' \
    --header 'Accept-Encoding: identity' \
    --dump-header "$SWEEP_PRIVATE/reference/google-$round.headers" \
    --output "$SWEEP_PRIVATE/reference/google-$round.html" \
    'https://www.google.com/?hl=en'
  curl --silent --show-error --proto '=https' --connect-timeout 10 --max-time 45 \
    --max-filesize 2097152 --user-agent 'vpn-sweep/1 connectivity-check' \
    --header 'Accept-Encoding: identity' \
    --dump-header "$SWEEP_PRIVATE/reference/youtube-$round.headers" \
    --output "$SWEEP_PRIVATE/reference/youtube-$round.html" \
    'https://www.youtube.com/?hl=en&gl=US'
done
```

Inspect headers/body privately: status200, text/html, expected actual page and all markers,
no consent/CAPTCHA/login challenge. curl checks TLS; do not use -k. These commands deliberately
never auto-follow redirects or accept consent. The profile disallows cross-host redirects;
same-host HTTPS redirects are bounded by five. A redirect to consent is inconclusive, not success.
If response contracts differ, stop and report anonymized error/context; do not bypass restrictions.
Bodies may contain tracking/session data; do not publish them. No video URL/token is obtained.
If no trusted reference network is available, run only as an explicitly unverified experiment;
semantic-marker failures cannot distinguish HTML drift from lack of access by themselves.

## Run a new search-v2 series on the intended network

Manually ensure Happ/another VPN does not capture Termux. One external IP cannot prove this.
The attestation flag is your statement, not independent environment detection. No system VPN is
changed. Choose new state/output names if these exist; preserve installation candidate-identity.key.

```sh
SWEEP_STATE="$SWEEP_PRIVATE/series-gy-web-v2-001"
SWEEP_RESULT="$SWEEP_PRIVATE/result-gy-web-v2-001"
SWEEP_NETWORK="restricted-network-A"
test ! -e "$SWEEP_STATE"
test ! -e "$SWEEP_RESULT"
"$SWEEP_BIN" run --mode core --core "$SWEEP_XRAY" \
  --input "$SWEEP_PRIVATE/input.json" --format json \
  --policy "$SWEEP_PRIVATE/policy.json" --allow-unverified-web-profile \
  --state "$SWEEP_STATE" --output "$SWEEP_RESULT" \
  --network-label "$SWEEP_NETWORK" --attest-no-other-vpn
```

No --space required: seeded broad default plus source baselines. For another space supply an explicit
private --space file on run; frozen policy/space/input are reused by resume. Defaults: session7200s,
candidate600s, startup15s, cleanup5s, interval2000ms, payload/journal256MiB, no attempt count cap.
Optional --session-seconds/--attempts/--payload-bytes/--journal-bytes configure budgets; no hidden30.
For example --session-seconds 600 is an explicit shorter experiment, not a new default.
One candidate at a time. Two rounds each require both pages; a fresh core precedes confirmation.
First fully satisfied policy stops search, exports private results and closes the tunnel.

## Stop, resume and inspect

Ctrl+C requests graceful cleanup and durable retry. Wait for exit; do not pkill all Xray processes.
Android force-stop/kill recovery is not device-verified. Re-establish shell variables above if using
another terminal. After the run stops:

```sh
"$SWEEP_BIN" status --state "$SWEEP_STATE"
"$SWEEP_BIN" report --state "$SWEEP_STATE"
"$SWEEP_BIN" resume --mode core --core "$SWEEP_XRAY" \
  --allow-unverified-web-profile \
  --state "$SWEEP_STATE" --output "$SWEEP_RESULT" \
  --network-label "$SWEEP_NETWORK" --attest-no-other-vpn
```

Do not give --input/--policy/--space/--ca-file on resume. Explicit opt-in is required again.
Core hash/network label must match. Operational budgets reset to defaults unless supplied again.
Changed policy/conditions require a new series; pre-search-v2 series fail closed.
State/report uses the single-writer lock: run them after stopping the active search.

Output working.json contains real credentials. report.json and verification.json identify
policy/scope and live_unverified preset; COMPLETE.json commits hashes. Files are0600.
Success never claims video or general Internet. If winner export failed, retry without search:

```sh
"$SWEEP_BIN" export --state "$SWEEP_STATE" --output "$SWEEP_RESULT"
```

Exit0 is policy satisfaction, 3 space exhausted, 4 budget pause, 130 interrupted, 2 other error.
Markers mismatch: composite_marker_missing; short/oversized body: body_too_small/body_too_large;
partial transfer: transfer_failed; wrong MIME: unexpected_mime; untrusted TLS: tls_failure;
consent: site_consent; CAPTCHA/sign-in challenge: site_challenge. All are non-success. See report
for per-probe outcomes; marker drift and resource failures do not prove server failure.
Android/Happ/Xray runtime/live acceptance remains unverified; media/QUIC/TUN/per-app unsupported.
