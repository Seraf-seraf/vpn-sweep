# Implementation decisions

## Storage and dependencies

The planning document proposed SQLite but left driver selection open. The implementation uses
a private, append-only, fsync-per-event JSONL journal with sequence numbers and SHA-256 chaining,
plus an atomic private manifest. This avoids an unverified Android SQLite driver and CGO/NDK
dependency. RunStore isolates this decision. A torn final record is truncated during locked
recovery; corruption of a complete record is fatal. One writer is protected by OS flock.
The journal stores measurements, not raw HTTP bodies or core stderr. Private server input is
stored separately with 0600 permissions. State version mismatches fail closed.

This is an explicit change from the proposed SQLite implementation, preserving durable resume
and per-attempt/per-probe history. It does not claim SQL querying or SQLite interoperability.
Streaming replay is linear in journal length; configurable journal byte limits bound disk use.

Initial production dependencies: Go standard library only, minimum Go 1.24 API baseline.
Build/testing toolchain: Go 1.27.2. Go standard library license: BSD-3-Clause.
QUIC has no standard-library HTTP3 client; QUIC probes are explicitly unsupported in this build,
never replaced with HTTP2. SOCKS5 UDP support is developed separately from TCP checks.

The actual Android device, Happ mapping and live VPN acceptance remain mandatory external gates.

## RunStore evidence and differences from SQLite

- A begin event contains candidate, attempt ID and next checkpoint in one hashed record.
  No network attempt starts before its write and fsync succeed.
- Probe outcomes and restart evidence are separate durable events. An interrupted end records
  the retry candidate; resume uses a new attempt ID before continuing the advanced cursor.
- Every byte-prefix truncation of a begin record is tested: incomplete records advance nothing;
  a complete committed record restores both attempt and checkpoint.
- Partial write and fsync errors poison the handle. Reopen determines committed versus torn
  state; an ambiguous full write is recovered as pending, never silently skipped.
- Hash chain, sequence and schema are checked for every complete record. Torn last records are
  truncated and fsynced under lock. Complete corruption is fatal. Manifest has keyed integrity;
  input snapshot identity is checked. This protects accidental corruption, not a malicious actor
  controlling all private files and keys.
- OS flock rejects a second writer. Concurrent Begin calls admit exactly one. A subprocess
  is terminated mid-write; reopening proves lock release and no candidate loss.
- Refinement queue consumption is part of begin; its interrupted retry is tested after reopen.
- A persistent installation key makes candidate IDs stable across series; series/attempt IDs
  remain distinct, and failed candidates are deduplicated only within that series.
- Unknown schema versions fail closed. There is no older deployed schema requiring migration;
  future migrations must be explicit, version-specific and preserve the original journal.

Differences: no SQL queries/indexes, no SQLite compatibility, linear streaming replay,
in-memory set of completed candidate IDs, and fsync-per-event write cost. Disk budget is explicit.
Durability assumes the filesystem honors fsync/rename/flock. Real Android filesystem/power-loss
behavior remains unverified; Linux fault/race tests do not establish device acceptance.

## Tracker attestation blocker

Canonical package discovered by skills.list:
`skill://plugin_asdk_app_sites_ae77444ef43481919c2588ef24f70c84/track-plan`.
Main resource is that package's SKILL.md. The exact sibling resource
`skill://plugin_asdk_app_sites_ae77444ef43481919c2588ef24f70c84/track-plan/scripts/source-snapshot.mjs`
was retried with both canonical package and c1 alias: `failed to read skill resource`.
The read skill documents no alternate implementation fallback for format-v2 attestation.
Plugin files were not changed. Actual results are recorded source-less, with no invented PASSED checks.

Step 6's initial result summary was sent before the first command output was inspected. That
first run failed sandbox loopback permission; an authorized escalated rerun and later full gates
actually passed. AppendObservation correction attempts were rejected VALIDATION_ERROR (missing
correctionOfEventId / unknown data field); later step-18 result explicitly records this correction.

## P1 fixes: search version 2

Candidate identity and Render share capability Mux normalization. Omitted mux preserves
source Mux values; explicit mux=true replaces them with core defaults unless supplied.
TCP concurrency 0/omitted maps to 8; XUDP omitted maps to 0; empty/omitted QUIC policy maps
to reject. Negative TCP/XUDP sentinel values remain distinct. Verified against exact target
ac5d8a83ba798c390cd89f235c3d4c35c77c1586 infra/conf/xray.go MuxConfig.Build and
app/proxyman/outbound/handler.go NewHandler (not latest). No live Xray execution implied.

Candidate namespace is candidate-v2; new manifests have search_version=2. Run rejects old
or unknown search versions with search_version_requires_new_series: an old advanced cursor
may already have skipped a distinct candidate. Original state remains readable for status,
report and export; create a new series with a new state directory to repeat the space.
Journal record version/hashchain/flock/fsync and server contract are unchanged.

Candidate deadline is recorded as attempt_timeout and completes that candidate; search
continues. Session deadline/external cancellation retain interrupted retry with a fresh attempt
on resume. Payload budget exhaustion retains existing retry semantics. Context cancellation
wins over a late pass; no incomplete policy result is exported. Tests cover Open, probe and
confirmation restart deadlines, cleanup, persisted completion/retry and legacy search rejection.

## Google/YouTube web-only profile

Separate google-youtube-web-v1 validation permits exactly two declared page targets, one
operator google, no downloads/UDP/QUIC, two fresh rounds. Legacy policy minimums unchanged.
Preset HTML markers remain live-unverified due cloud CONNECT403; explicit run/resume opt-in
and scoped reports prevent representing this as video/general Internet acceptance. Complete
bounded HTML receipt is measured, without false immutable hashes or bulk speed claims.
New optional omitempty probe fields preserve serialization of existing manifests; search-v2
and journal versions unchanged. Raw marker drift requires reviewed policy and a new series.
Unrelated umask-dependent fixtures remain unfixed; local gates run with explicit umask077.
