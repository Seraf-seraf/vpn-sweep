# Video delivery checks (local implementation, live unverified)

New runs use built-in `google-youtube-v1`: Google and YouTube pages plus required
YouTube delivery. Legacy `representative-video-v1` series retain two independent capability results:
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

## Current preparation and launch

Dependencies are prepared automatically before a YouTube check. The app uses a private
managed venv with yt-dlp==2026.8.19 and yt-dlp-ejs==0.8.0, and finds Node >=24 automatically.
In Termux, missing runtimes are installed through pkg. Linux needs Python and Node on PATH.
The runtime-path flags have been removed. Setup is serialized and bounded to 10 minutes;
a failed install can be retried. Resume verifies the frozen identity and never upgrades it.

```sh
./build/vpn-sweep resolver-info
./build/vpn-sweep policy
./build/vpn-sweep run --core "$HOME/Xray-core/xray" \
  --input "$HOME/.local/share/vpn-sweep/input.json" \
  --state "$HOME/.local/share/vpn-sweep/series-001" \
  --output "$HOME/.local/share/vpn-sweep/result-001"
```

resolver-info may install dependencies; it does not extract YouTube or start Xray.
No policy file or allow-* flags are required. Built-in resources, required checks, sample
limits and search domains are internal. The test video is the public yt-dlp extractor test
video BaW_jenozKc; current extraction/delivery and availability remain live-unverified.
Additional sites can be added to the internal probe list. All declared checks are required.
Older saved policies keep their own scope on resume; JSON files in examples describe those
legacy schemas and are not a current CLI configuration interface.

## External acceptance blockers

Big Buck Bunny's Blender project license/provenance is documented as CC BY3.0
(https://peach.blender.org/about/, https://peach.blender.org/download/), but the
current environment could not retrieve those pages (research fetch402) or verify
an exact suitable MP4/reference hash. External media downloading was stopped;
no independently verified generic public MP4 reference is asserted. The built-in YouTube test video is selected, but its actual extraction/delivery has not been accepted live. Prior media-host
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
