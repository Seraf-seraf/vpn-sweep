# Dependencies and licenses

| Component | Version | Role | License / status |
|---|---|---|---|
|vpn-sweep|local dev, schema v1|Product|MIT, repository LICENSE|
|Go|1.27.2 tested; go.mod declares 1.24.0 API baseline|Compiler and bundled standard library|BSD-3-Clause, GO-LICENSE.txt; Go1.24 itself not tested|
|Third-party Go modules|none|Production|go list -m all contains only this module; no go.sum needed|
|Xray|external target ac5d8a83ba798c390cd89f235c3d4c35c77c1586|User-supplied runtime|Not bundled; upstream MPL-2.0; actual supplied binary/license must be checked|
|Git|2.52.0 prepared environment|Local hygiene/build metadata|GPL-2.0, not bundled|
|Python 3|environment interpreter|Development secret scan|PSF, not bundled|
|PyYAML|6.0.3 available environment|One-off workflow YAML parse|MIT, not production or required gate dependency|
|actions/checkout|v4.2.2 declared|Hosted CI tooling|MIT, not bundled; hosted run unverified|
|actions/setup-go|v5.5.0 declared|Hosted CI tooling|MIT, not bundled; hosted run unverified|

No SQLite driver, QUIC SDK, VPN binary or CA secret is vendored. Device trust-store availability
and exact target Xray acceptance are separate gates. The local package is not an accepted release.
