# Optional video delivery profile (local implementation, live unverified)

`representative-video-v1` has two independent capability results:
`video_transfer` (a declared, independently hashed clear MP4 reference) and
`youtube_delivery` (media bytes for one explicitly permitted public YouTube video).
A generic file, Google Storage object, HTML, thumbnail, or manifest alone never
satisfies YouTube delivery. Neither capability proves decoding, audio output,
playback/DRM/account access, general Internet, all services, or UDP/QUIC.
`google-youtube-web-v1` and `web-basic-v1` retain their contracts.

## Contract and budgets

Policy `video.required` explicitly selects required capabilities; there is no
implicit all-services/optional-diagnostics success rule. Both configured capabilities
are reported independently. Optional failures do not change the required outcome.
Two rounds use separate Xray processes. Each round repeats `samples` (suggested 3)
for each capability. Proposed defaults: `min_mbps:10`, `max_read_gap_ms:3000`,
`sample_bytes:4194304`, `timeout_seconds:30`. They are configurable, not guarantees
of 1080p playback. Mbps uses decimal bits/s over the **whole request duration**,
including connection/TLS/first byte; read gaps are measured while reading the body.
This is a finite set of delivery samples, not a continuous playback/stability test.

Sample limits: 64KiB..16MiB, 1..10 samples, timeout 1..300s, gap 1..30000ms,
threshold >0..10000Mbps. The reference must fit `sample_bytes` and be at least
64KiB. At the proposed sizes, both capabilities cost at most ~52MiB across two
rounds (48MiB media + ~4MiB resolver responses). Configure the existing session
`--payload-bytes`, `--attempt-seconds`, `--session-seconds`; e.g. 67108864,
240, 7200. The entire next round is reserved before starting any transfer.
Partial/failed media bytes are charged. Resolver failures conservatively charge
the full metadata allowance (~2MiB per round). No parallel network attempts.

Generic MP4 requires HTTP200, exact Content-Length, full bounded read, video/mp4,
TLS trust/hostname verification and independent SHA256 equality. MP4 inspection
requires ftyp, a structurally valid moov/trak with vide handler and positive track
height, and at least 64KiB mdat. It does not decode the codec. Only fast-start MP4
whose init metadata fits the first 1MiB is supported. Non-fast-start media is
unsupported, not silently accepted. Reads/hash/timing stream through a 32KiB buffer;
only a bounded 1MiB prefix is retained for structural inspection.

YouTube initially supports only clear direct HTTPS MP4 video formats at height
>=1080 returned dynamically by the pinned resolver, with an exact byte Range from
zero. Response must be HTTP206 with matching Content-Range/Content-Length and
video/mp4; container track height must also be >=1080. A range may end inside
mdat: this is explicitly a media byte-range check, not a complete segment decode.
SABR, DASH/HLS-only manifests, encrypted/DRM formats, extra required auth headers,
redirected media, and unsupported containers are not implemented. No client switch
is attempted on denial. A self-computed hash is recorded as a fingerprint, never
as an independent YouTube reference. Video ID, format identity, TLS-verified
YouTube media host and structural content establish the limited delivery evidence.

## Optional dependency spike and restrictions

Official PyPI wheels `yt-dlp==2026.8.19` and `yt-dlp-ejs==0.8.0` were installed only
in `/tmp/vpn-sweep-video-venv`. Offline identity was tested with Python 3.12.14 and
Node 24.19.0. Adapter requires Node >=24 and freezes actual versions and SHA256
of Python/Node, yt-dlp/EJS package contents, and the bundled adapter. No extraction,
YouTube download, cookies, accounts or live VPN were used for the spike.
The project Go requirement remains 1.24+, no new Go modules. Linux glibc ARM64
binaries are not Android/Termux binaries. Termux execution of these dependencies
is **unverified**.

The subprocess receives a bounded private stdin request. It runs Python `-I`,
fixed API arguments, no config, no plugins, no cache, no remote components,
no cookies/login/PO-token providers and no external downloader/FFmpeg. Python
socket/DNS guards allow only the exact loopback SOCKS address. The Node prelude
rejects normal network entry points; only Node version/EJS child commands are
allowed. These guards protect the pinned adapter's normal request paths; they are
not an OS sandbox for malicious replacement executables. Node `--permission`
alone does not deny network access. Resolver output is capped at 16KiB, metadata
TLS response bytes at ~2MiB; stderr is discarded. Only sanitized errors/evidence
are journaled. Signed URLs, cookies, auth headers and raw resolver JSON are never
saved. Only the same authenticated SOCKS is used for resolution and media reads.
Cancellation/timeout kills and reaps the owned process group including JS children.

Resolver/auth/challenge/token/unsupported endpoint outcomes are distinct from
candidate media/network failure. An explicitly required blocked capability pauses
with `capability_blocked` and preserves the candidate for retry, instead of
exhausting the space or declaring the server broken. Changing frozen dependencies
requires a new series. Signed URLs are resolved afresh each round and on resume;
they are not frozen. Old manifests/journal events omit the new optional fields,
retaining legacy serialization/hashchain/HMAC. SearchVersion remains 2.

## Actual CLI and Termux setup

These commands install dependencies **only when the user elects the optional
YouTube adapter**; the profile has no verified public media preset yet.

```sh
pkg install golang python nodejs-lts ca-certificates
umask 077
mkdir -p "$HOME/.local/share/vpn-sweep"
python -m venv "$HOME/.local/share/vpn-sweep/youtube-venv"
"$HOME/.local/share/vpn-sweep/youtube-venv/bin/python" -m pip install 'yt-dlp==2026.8.19' 'yt-dlp-ejs==0.8.0'
# In the cloned project:
go build -trimpath -o build/vpn-sweep ./cmd/vpn-sweep
./build/vpn-sweep resolver-info \
  --youtube-python "$HOME/.local/share/vpn-sweep/youtube-venv/bin/python" \
  --youtube-js "$(command -v node)"
```

`resolver-info` is offline and does not start Xray. It must succeed before using
the adapter. Node supplied by Termux must be >=24; package availability is not
promised. yt-dlp wheel: Unlicense; EJS wheel: Unlicense AND MIT AND ISC;
Python PSF; Node MIT plus third-party notices. Dependencies are not bundled in the
Go artifact. Sources: https://pypi.org/project/yt-dlp/2026.8.19/,
https://pypi.org/project/yt-dlp-ejs/0.8.0/,
https://github.com/yt-dlp/yt-dlp/wiki/EJS,
https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide.

`examples/representative-video-unverified.json` is a **schema example, not a
runnable verified preset**. Before a meaningful run, create a private policy
outside git and replace the example reference with an explicitly allowed public
fast-start MP4 URL, its independently established byte size and SHA256,
provenance/license, and `status:"verified_reference"`. To enable YouTube, add:

```json
"youtube": {"video_id":"PUBLIC_ID_11_CHARS", "permission":"documented permission and provenance", "min_height":1080}
```

The shown video ID is a placeholder and intentionally fails validation. Choose a
permitted public video yourself; do not supply cookies/account secrets. Add
`"youtube_delivery"` to `required` only if it must govern success. Offline
validation does not verify permission, endpoint availability or the reference.

After those prerequisites, the exact opt-in flags for the existing run command are:

```sh
base="$HOME/.local/share/vpn-sweep"
./build/vpn-sweep policy --policy "$base/video-policy.json"
./build/vpn-sweep run --mode core --core "$HOME/Xray-core/xray" \
  --input "$base/input-cli.json" --format json --policy "$base/video-policy.json" \
  --state "$base/video-series-v2" --output "$base/video-result-v2" \
  --network-label restricted-network --attest-no-other-vpn --allow-video-profile \
  --youtube-python "$base/youtube-venv/bin/python" --youtube-js "$(command -v node)" \
  --payload-bytes 67108864 --attempt-seconds 240
```

Only pass the two `--youtube-*` options when YouTube is configured. Input needs
0600; runtime/state/output directories 0700 outside git. A policy loader also
requires file0600. Xray identity must report exact target ac5d8a8; mode core does
not bypass that gate. No nested VPN; environment attestation is not proof.
Stop with Ctrl-C. Resume uses **no** input/policy/space flags:

```sh
./build/vpn-sweep resume --core "$HOME/Xray-core/xray" \
  --state "$base/video-series-v2" --output "$base/video-result-v2" \
  --network-label restricted-network --attest-no-other-vpn --allow-video-profile \
  --youtube-python "$base/youtube-venv/bin/python" --youtube-js "$(command -v node)" \
  --payload-bytes 67108864 --attempt-seconds 240
```

`report --state ...` emits sanitized journal evidence for partial/blocked runs.
Successful export includes `report.json` with independent `capabilities`,
`verification.json`, `working.json`, and final `COMPLETE.json`. Xray is closed
before export. Any policy/dependency/video constraints change needs a new series.

## External acceptance blockers

Big Buck Bunny's Blender project license/provenance is documented as CC BY3.0
(https://peach.blender.org/about/, https://peach.blender.org/download/), but the
current environment could not retrieve those pages (research fetch402) or verify
an exact suitable MP4/reference hash. External media downloading was stopped;
no runnable public reference or YouTube test video is asserted. Prior media-host
requests encountered managed proxy CONNECT403. No denial was bypassed. A user's
permission for a video does not supersede platform terms; the API route does not
provide an official media-download API (https://developers.google.com/youtube/terms/developer-policies).
Actual YouTube extraction, delivery and Android device acceptance remain unverified.

## Strict MP4 header regression fix

Track headers accept only tkhd FullBox v0 (84-byte payload, height offset80) or
v1 (96-byte payload, height offset92), with defined flag bits. hdlr requires the
complete 24-byte header, version0/zero flags and zero predefined/reserved fields.
Missing or duplicate required track headers fail closed. Invalid versions,
truncated/oversized tkhd and truncated hdlr cannot set ContainerVerified. This
validates the supported structural headers, not codec sample decoding/playback.
