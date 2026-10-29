# Usage and result scope

Build and inspect without network access:

```sh
make build
./build/vpn-sweep version
./build/vpn-sweep inspect --input - < examples/input.json
./build/vpn-sweep space
./build/vpn-sweep policy
```

Input is a local JSON configuration, JSON array, or a list of individual configuration
links such as vless:// and trojan://. The format is detected automatically; --format
accepts auto/json/uri. Subscription import and whole-input base64 wrappers are removed.
The synthetic .invalid example is not a working VPN configuration.

Start a search with private input/state/output outside checkout:

```sh
vpn-sweep run --core /absolute/path/to/xray \
  --input /private/vpn-sweep/input.json \
  --state /private/vpn-sweep/series-001 \
  --output /private/vpn-sweep/result-001
```

Files require 0600 and directories 0700. The parent of the series stores the installation
candidate-identity.key. Keep that key for stable candidate identity across series.
The external Xray must report commit ac5d8a8 or its full SHA; its hash is frozen.
Imported server addresses, credentials and transport/security contracts are preserved.

Search parameters are built in. New searches require the Google and YouTube HTML
pages in two fresh-core rounds; video delivery is not checked. The command policy
validates the built-in rules offline; it does not fetch the sites. The public preset
remains live-unverified and does not establish general Internet or QUIC access.

Resume and inspection:

```sh
vpn-sweep resume --core /absolute/path/to/xray \
  --state /private/vpn-sweep/series-001 --output /private/vpn-sweep/result-001
vpn-sweep status --state /private/vpn-sweep/series-001
vpn-sweep report --state /private/vpn-sweep/series-001
vpn-sweep export --state /private/vpn-sweep/series-001 --output /private/vpn-sweep/result-001
```

Resume keeps the frozen policy, search space, profiles and trust roots. A changed
core is rejected. Saved series requiring YouTube video cannot resume: start a new series;
status, report and export of already verified results remain available. Legacy separate
video series without YouTube retain their saved policy. New manifests record
environment=unverified; legacy attestations are not renewed by resume.

Run/resume print preparation, attempts, rounds, probe outcomes, core cleanup and stop
reasons to stderr automatically. Stdout remains JSON. Logs omit endpoints, URLs,
credentials, candidate parameters and user-supplied probe names. Duplicate/inapplicable
candidate messages are aggregated. Ctrl-C stops owned children and preserves retry state.

Remaining budget flags apply per invocation: --session-seconds (7200), --payload-bytes
(268435456), --journal-bytes (268435456), --attempts (0: unlimited), --attempt-seconds (600),
--startup-seconds (15), --interval-ms (2000). Zero removes the session/payload/journal/attempt
cap. Cleanup waits are internal: 5s for TERM and 5s for KILL. Payload is useful bytes,
not exact mobile wire traffic. Budget exhaustion pauses; it does not mean space exhausted.

Success stops search, closes Xray and saves working.json, verification.json, report.json
and the COMPLETE.json integrity marker. Existing complete exports are verified idempotently;
a durable winner can be exported again without searching. Saved series keep their original checks.

Exit codes: 0 success, 2 invalid/unsupported/infrastructure/save error, 3 finite space
exhausted, 4 budget pause, 130 interrupted. Status/report return 0 when reading succeeds;
inspect their status field. Search versions older than 2 need a new series; old state
must not be deleted. QUIC/TUN/MTU/per-app VPN and guessed Happ UI mapping remain unsupported.
