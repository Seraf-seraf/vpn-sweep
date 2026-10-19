# Execution evidence — 2026-10-18

Tracker plan: **vpn-sweep: реализация полного ТЗ Go CLI — 21 этап**
ID: `2c3c68d5-f288-4c9f-b7d5-24f5a99f2669`. UI Goal was not enabled.

This is a locally implemented and synthetically verified product, not a fully accepted Android/Happ/live release.
During the original local implementation, no commit/push/PR/deployment or live VPN was performed.
Later publication and the subsequent local-only verification decision are recorded below.

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
|18|Implemented locally|Makefile/scripts, format/scan/vet/race/build, local checks retained; automatic CI subsequently removed by user decision|
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
|Historical workflow YAML parse with PyYAML 6.0.3|PASS at implementation time; workflows subsequently removed, not a hosted CI pass|
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

## Local-only checks by user decision

Implementation commit 08edf68015618a9c8c4df7e3d34e3747e5305f1b was pushed to main.
Its GitHub Actions run https://github.com/Seraf-seraf/vpn-sweep/actions/runs/39133659445
failed with unsafe_directory test setup errors and a state-test nil-pointer panic.
Read-only reproduction identified t.TempDir permission dependence on umask: 0755 under
022 versus required 0700 under 077. Ignored Open error at store_test.go:55 leads to panic
at line 57. Earlier local passes used umask 0077; they do not establish portability.

At the user's explicit request both GitHub workflows were removed; checks are now local-only.
All tests, Makefile targets, check/build/package/scanner scripts and security guards remain
unchanged. The fixture permissions and ignored-error defects remain unresolved. No policy
was prepared. Removing CI is not a green CI result. Historical runs and Actions settings
were not deleted or changed. Android/Happ/Xray runtime and live acceptance remain unverified.

## Google/YouTube web-only profile

Implemented google-youtube-web-v1 with exactly two page targets, both operator google,
two rounds/restart, composite markers/minimum body/HTML MIME/full bounded transfer evidence.
Legacy web-basic/custom requirements and search-v2/journal semantics remain unchanged.
Explicit --allow-unverified-web-profile is required on run and resume. Offline policy
validation and exported/stdout verification_scope/preset_status prevent general-Internet or
video claims. Preset raw markers remain unverified after cloud CONNECT403; no live tests run.

GO=go make check and go test -race -count=1 ./... passed with explicit umask077, including
vet, two Python scanner tests, secret scan and Linux/Android ARM64 builds. Earlier new test
setup mistakes were corrected; old umask-dependent fixtures were not changed.
Independent reviewer /root/google_youtube_independent_review found no code P0–P3 in scope;
a P3 guide flag typo was corrected from --session-seconds600 to --session-seconds 600.
Reviewer independently passed uncached race, vet, scanner tests and offline policy smoke.
Android/Happ/Xray runtime/live remain unverified. CI remains absent. No production dependency added.

## Video extension (uncommitted; independent review in progress)

Tracker existing plan2c3c68d5-f288-4c9f-b7d5-24f5a99f2669 checkpoints30–33.
Compatibility spike: official PyPI yt-dlp2026.8.19/EJS0.8.0 in isolated /tmp venv;
Python3.12.14/Node24.19.0 offline identity verified. No YouTube extraction or live
VPN/media requests. Exact licensed public reference remains blocked/unverified;
no fake hashes. The deliberately unverified schema example cannot produce transfer
success. A suitable independently verified reference and permitted YouTube video
are required before external acceptance. See video-profile.md.

New independent reviewer /root/video_delivery_independent_review confirmed existing
workspace and uncommitted diff. Found client fallback and suppressed extraction
error defects. Fixed with a single web client and guards before other-client
requests, ignoreerrors=False, and negative regression tests. Review recheck and
final local gates are recorded below when actually complete.

Safe scoped tests pass under umask077/TMPDIR=/var/tmp; initial sandbox denied
loopback and /tmp/.git harness marker triggered correct output-inside-checkout
rejection. Narrow local-check execution resolved these infrastructure limits;
security guards and unrelated fixture permissions were not changed. The known
legacy umask fixture defect remains open. Canonical tracker snapshot helper still
fails to read through the documented skill URI; no source-bound PASSED claimed.

Final safe gates executed with `umask 077`, `TMPDIR=/var/tmp`, and explicit
VPN_SWEEP_TEST_PYTHON=/tmp/vpn-sweep-video-venv/bin/python,
VPN_SWEEP_TEST_JS=/opt/codex/runtimes/codex-primary-runtime/dependencies/node/bin/node:
`make check` PASS (format, 2 scanner tests, 4 resolver tests, scan76 files/staged0,
vet, race, Linux+Android build); `go test -race -count=1 ./...` PASS;
`go vet ./...` PASS; `git diff --check` and cached check PASS; no Go dependencies.
Offline built CLI `help`, `policy` on private copy of unverified example and
`resolver-info` PASS, network_requests=false for policy. No profile network run.
Independent reviewer /root/video_delivery_independent_review rechecked actual fixes:
full uncached race/vet PASS, media race with optional deps PASS, resolver4 tests
PASS. Both confirmed findings fixed; no remaining proven in-scope P0–P3.
Android output is ELF PIE aarch64 /system/bin/linker64, Go1.27.2, CGO_ENABLED=0;
actual Android/Happ/Xray/device/live acceptance remains unverified. No commit/push.

Final narrow independent recheck also covers Python datagram denial, all Node
DNS entry points and owned-child cleanup/no-foreign-kill regression: PASS, no new
proven P0–P3. Full `make check` and uncached full race/vet were repeated on final
production code and passed. Final build SHA256: Linux
4d3079df58b00de48707d4b40290c8ebbd0f75be303fdeb5aaf5b17f79be3b47;
Android f6f191e04206c8624ceee913c4e466e16d24b8ffc7b1ac67bce485d4c1a9e5d0.

## Strict MP4 P2 correction (authorized publication scope)

New independent read-only review /root/independent_video_adversarial_review found
one P2: invalid tkhd FullBox version255 passed MP4 and the TLS+SOCKS YouTube-range
probe. Both original /tmp overlay repros and permanent regressions were confirmed
RED before fixing. Strict v0/v1 tkhd sizes/offsets and full hdlr header validation
now reject them; media race including optional dependencies and original repros
PASS. The scoped media synthetic hdlr fixture was completed; unrelated legacy
fixtures and umask bug remain untouched. No codec/playback/live acceptance added.
New reviewer /root/strict_mp4_fix_independent_review and final gates are in progress;
results and publication evidence follow only after actual execution.

Strict-fix final independent report: /root/strict_mp4_fix_independent_review found
no remaining proven scoped P0–P3. Independently reran original malformed parser
and TLS+SOCKS HTTP206 repros and permanent regressions: PASS, malformed output
container_unsupported_or_invalid/ContainerVerified=false. Separate external overlay
exhausted tkhd versions2..255/flag bytes and hdlr invalid fixed-header bytes: PASS;
media uncached race with optional dependencies PASS. No decode expansion.

Parent actual gates on final production code: make check PASS; go test -race
-count=1 ./... PASS; go vet ./... PASS; scanner2 and resolver4 tests PASS;
secret scan77/staged0 no findings; make build android PASS; diff checks PASS.
All use umask077 TMPDIR=/var/tmp and explicit preinstalled optional dependencies.
No live media/VPN download, CI/security changes or unrelated fixture corrections.
Publication is authorized only after source-only staging and fresh-main audit;
actual resulting SHA/push verification are reported in tracker/chat.
