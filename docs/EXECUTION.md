# Execution evidence — 2026-10-18

Tracker plan: **vpn-sweep: реализация полного ТЗ Go CLI — 21 этап**
ID: `2c3c68d5-f288-4c9f-b7d5-24f5a99f2669`. UI Goal was not enabled.

This is a locally implemented and synthetically verified product, not a fully accepted Android/Happ/live release.
No commit, push, PR, deployment, real subscription or live VPN connection was performed.

## 21-stage reconciliation

|Stage|Local result|Evidence / remaining acceptance|
|---|---|---|
|1|Implemented|PLAN/spec/compatibility, exact target commit and explicit unknowns|
|2|Implemented|LF/text/binary attributes; ignore/tracked/staged checks, staged remains empty|
|3|Local implementation complete|Stdlib-only target spike and product Android ARM64 cross-build; device execution unverified|
|4|Implemented|CLI/schema/strict JSON/keyed identity tests|
|5|Implemented|JSON/array/URI/base64 parsers, preservation and unsafe/unknown negatives|
|6|Implemented|Local TLS subscription limits/redirect/auth/trust/deadline tests; no real subscriptions|
|7|Implemented with runtime gate|Capabilities/renderer tests; actual target Xray config acceptance unverified here, checked before each future run|
|8|Implemented|Lazy big.Int exhaustive/broad generator, range pairs, conditional canonicalization, deterministic bijection and bounded refinement|
|9|Implemented locally|fsync journal, atomic issuance, integrity/lock/version/recovery/fault/race/crash tests; Android FS semantics unverified|
|10|Implemented locally|Fake-core startup/config/crash/delay/TERM/KILL/Wait, no foreign kills, Linux parent-death test|
|11|Implemented|TLS/content/hash/size/redirect probes and explicit no-direct-fallback negative|
|12|Partial capability accepted locally|SOCKS5 UDP nonce echo tested; QUIC explicit unsupported, never TCP success|
|13|Implemented|web-basic/custom policy, fresh rounds, no partial success and site-error distinctions|
|14|Implemented|Search/resume, IDs, budgets, first success stop, persisted 75/25 refinement, controlled CLI E2E|
|15|Implemented|Private export/verification/report/hash marker, permissions, idempotence/tamper/no-secret E2E|
|16|BLOCKED|Happ version/build and UI/runtime mapping unavailable; happ mode fails closed|
|17|BLOCKED|No actual Android/Termux device/version; compile is not device acceptance|
|18|Implemented locally|Makefile/scripts, format/scan/vet/race/build, workflow YAML parse; hosted CI/platform lint unverified|
|19|BLOCKED|Live VPN testing explicitly forbidden in current authorization|
|20|BLOCKED|Live testing forbidden and intended restricted network unavailable|
|21|Local package prepared|Tested Linux/Android binaries, docs/examples/licenses and SHA256 checksums; not published|

Tracker result records are source-less. Canonical snapshot helper is unavailable (decisions.md);
required source-bound checks have **not** been marked PASSED. Tracker readyToFinish is false,
including completed local stages. Plan remains RUNNING; no PausePlan/FinishPlan was inferred.

## Executed commands and outcomes

All commands ran in /workspace/vpn-sweep after loading /workspace/toolchains/vpn-sweep-env.sh.
Loopback integration tests needed an authorized sandbox escalation; no external VPN was run.

|Command|Outcome|
|---|---|
|`GO=go sh scripts/check.sh`|Exit 0: gofmt, secret scan, vet, race suite, Linux and Android builds|
|`go test -race ./...` (inside gate)|PASS for all packages containing tests; no race report|
|`go vet ./...`|Exit 0|
|`python3 scripts/test_secret_scan.py`|2 contract tests PASS|
|`python3 scripts/secret-scan.py`|No findings; tracked, untracked non-ignored and staged contents inspected; staged 0|
|`git diff --check`, `git diff --cached --check`|Exit 0|
|`git check-attr`, `git check-ignore`|LF/text/binary and private/build paths verified; examples remain visible|
|workflow YAML parse with PyYAML 6.0.3|PASS; not GitHub platform lint or hosted execution|
|`go list -m all`|Only github.com/Seraf-seraf/vpn-sweep; no third-party Go modules|
|`file build/vpn-sweep`|ELF x86-64, static|
|`file build/vpn-sweep-android-arm64`|ELF PIE aarch64, /system/bin/linker64, CGO_ENABLED=0|
|`go version -m build/vpn-sweep-android-arm64`|go1.27.2, GOOS=android, GOARCH=arm64, GOARM64=v8.0|

The gate was rerun after material code changes. Earlier failures were corrected: initial default
Go cache path was read-only (fixed by prepared toolchain environment), first loopback test was
sandbox-denied (authorized rerun passed), format check found model.go (formatted), and scanner
initially matched its own literals/internal state directory (fixed with contract tests).
Compilation errors during implementation were fixed before final gates.

## Important negative evidence

- Reachable direct HTTPS target receives no request when the SOCKS endpoint is unavailable.
- HTTP200 wrong content, untrusted TLS, 403/429/challenge, bad hash and disallowed redirects fail.
- UDP runs through a SOCKS5 association; unavailable proxy does not use direct UDP.
- Missing round/restart/download cannot become full success; QUIC requirement is unsupported.
- First full success closes both core sessions and exports once; resume verifies/reuses the winner.
- Other process remains alive after runner cleanup; parent SIGKILL removes its fake-core listener on Linux.
- Every truncated begin prefix, partial write/fsync fault, concurrent writer and abrupt writer exit
  preserves correct issuance/retry semantics. Corrupt complete record/manifest fails closed.
- Synthetic secret-marker does not appear in CLI stdout/stderr/public report/history.

## Unsupported / unverified

Happ mapping; Advanced fragmentation; Happ array/RandRange/IP preference; TUN/MTU/per-app Android
VPN; HTTP3/QUIC probes; media delivery; general proxy chains/plugins; Hysteria2/WireGuard/TUIC;
legacy VMess and unlisted ciphers. Core noises have a deliberately narrow native-UDP profile.
Public resource contracts/operator independence need review; no live registry validation was claimed.
OS network environment is user-attested, not independently proven free of nested VPN.
Actual target Xray binary, Android runtime/CA/filesystem/lifecycle and both live acceptance stages
are unverified. Go1.24 is declared baseline, tested compiler is 1.27.2 only.

## Authorized P1 correction checkpoints (after original 21 stages)

22. Goal: resolve Mux ID/render mismatch and per-attempt timeout stall. Allowed: scoped code,
regression tests and local gates. Excluded: new protocol support, journal redesign, live tests.
Stop criterion: regression suite plus uncached race/vet/scanner/build gates pass.
23. Goal: independent verification of changed code. Allowed: new read-only reviewer and safe
local tests; related fixes only if demonstrated. Excluded: publication/live VPN. Stop criterion:
reviewer reports no remaining proven P0–P3 in the two-P1 scope.
24. Goal: corrected delivery. Allowed: rebuild/audit source and Android artifacts, upload new
versions and update existing delivery page. Excluded: deletion of old Library versions,
commit/push/release. Stop criterion: uploaded bytes and SHA256 match audited artifacts, page
labels corrected versions and marks old files superseded.

Regression tests first reproduced Mux ID collision and paused_budget for candidate timeout
at Open, HTTPS probe and confirmation restart. Search version 2 fails closed on legacy series
rather than silently continuing a cursor affected by old dedup. RunStore implementation unchanged.
Canonical track-plan source-snapshot resource retried: failed to read skill resource; no
fallback is documented, no plugin changes or fabricated snapshot/PASSED attestation.

Checkpoint 22 local outcome: GO=go make check and go test -race -count=1 ./... PASS;
vet, 2 Python scanner tests, scan62/staged0, Linux and Android ARM64 builds PASS.
Initial sandbox execution denied loopback sockets; explicitly permitted local-only rerun passed.
Checkpoint 23 independent reviewer /root/p1_fix_independent_review: no proven P0–P3 in
scope of the two P1 fixes and related regressions. Reviewer independently ran uncached race,
vet, scanners, diff-check, Linux/Android builds and an extra UDP deadline overlay test PASS.
Not a full re-audit outside changed scope; device/Happ/Xray/live acceptance remains unverified.
