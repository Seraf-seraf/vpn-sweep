# Dependencies and licenses

| Component | Version | Role | License / status |
|---|---|---|---|
|vpn-sweep|local dev, schema v1|Product|MIT, repository LICENSE|
|Go|1.27.2 tested; go.mod declares 1.24.0 API baseline|Compiler and bundled standard library|BSD-3-Clause, GO-LICENSE.txt; Go1.24 itself not tested|
|Third-party Go modules|none|Production|go list -m all contains only this module; no go.sum needed|
|Xray|external target ac5d8a83ba798c390cd89f235c3d4c35c77c1586|User-supplied runtime|Not bundled; upstream MPL-2.0; actual supplied binary/license must be checked|
|Git|2.52.0 prepared environment|Local hygiene/build metadata|GPL-2.0, not bundled|
|Python 3|environment interpreter|Development secret scan|PSF, not bundled|

No SQLite driver, QUIC SDK, VPN binary or CA secret is vendored. Device trust-store availability
and exact target Xray acceptance are separate gates. The local package is not an accepted release.

Optional video resolver (not bundled into Go binary): official PyPI yt-dlp2026.8.19
(Unlicense), yt-dlp-ejs0.8.0 (wheel License-Expression: Unlicense AND MIT AND ISC).
Isolated spike installed only those two runtime packages plus venv pip25.0.1.
Tested Python3.12.14 (PSF) and Node24.19.0 (MIT plus bundled third-party notices).
Adapter requires Node>=24, verifies pinned package versions and freezes actual
binary/package/adapter hashes. Termux package/runtime compatibility is unverified.
No auto-update, remote EJS download, provider plugins, FFmpeg or new Go module.
See video-profile.md for scope, official sources, limitations and opt-in commands.
