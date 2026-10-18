# Usage and result scope

Build and validate locally:

```sh
make check GO=/absolute/path/to/go
./build/vpn-sweep version
./build/vpn-sweep inspect --input - --format json < examples/input.json
./build/vpn-sweep space
```

inspect with local input and space perform no network calls. `inspect --subscription-file`
explicitly fetches an HTTPS subscription. Never put subscription URLs or credentials in argv.
The sample endpoint is reserved `.invalid` and is not a working VPN configuration.

For an authorized future run, prepare private directories outside a Git checkout, with 0700
permissions, and input/policy/space/subscription files with 0600 permissions. The parent of
the series directory is the private installation root: it stores candidate-identity.key.
Do not delete that key if cross-series candidate identity matters. Different network/conditions
require a new series directory; old failures never become a global denylist.

Example command template (not executed by this implementation task):

```sh
vpn-sweep run --mode core \
  --core /absolute/path/to/xray \
  --input /private/vpn-sweep/input.json --format json \
  --policy /private/vpn-sweep/policy.json \
  --space /private/vpn-sweep/space.json \
  --state /private/vpn-sweep/series-001 \
  --output /private/vpn-sweep/result-001 \
  --network-label network-A --attest-no-other-vpn
```

The executable must report commit ac5d8a8 (or its full SHA), and its binary hash is frozen.
Every candidate config is checked by that executable before startup. A version label alone
does not authorize another commit. No executable is downloaded or selected from imported input.

Before tests, CLI emits an anonymized series_start event with effective budgets and policy.
The attestation flag is a user statement, not independent proof of absence of nested VPN.
Happ capturing Termux would invalidate the intended-network experiment. The CLI changes no VPN settings.

Resume and inspection:

```sh
vpn-sweep resume --core /absolute/path/to/xray \
  --state /private/vpn-sweep/series-001 --output /private/vpn-sweep/result-001 \
  --network-label network-A --attest-no-other-vpn
vpn-sweep status --state /private/vpn-sweep/series-001
vpn-sweep report --state /private/vpn-sweep/series-001
vpn-sweep export --state /private/vpn-sweep/series-001 --output /private/vpn-sweep/result-001
```

Resume reuses frozen profiles, policy, space and explicit test CA, and rejects changed core/network.
Operational budget flags apply per invocation and default to the documented defaults on resume;
use flags again when continuing with different limits. It does not silently change test policy.
SIGINT/SIGTERM stops children and preserves interrupted work. A killed process is recovered on next open.

Defaults: one active candidate and sequential probes; 7200s session, 256MiB useful payload,
256MiB journal, no attempt-count cap, 600s candidate, 15s startup, 5s TERM/KILL waits, 2000ms
between candidates. Zero removes session/payload/journal/attempt cap where documented in flags.
Safety bounds: session <=365 days or unlimited, candidate <=86400s, startup <=300s,
cleanup <=60s, interval <=60000ms. Useful payload is not exact mobile wire traffic.
There is no automatic request retry loop; fresh-round confirmation is separate from retries.

Exit codes: 0 command success/full policy success, 2 invalid/unsupported/infrastructure or
save/cleanup error, 3 researched finite space exhausted, 4 paused budget, 130 interrupted.
Status/report commands return 0 when reading the state succeeds; inspect their status field.

## Policy

An explicit --policy is required. No unverified public-resource registry is silently shipped
as reliable. examples/policy.template.json is a structural template using reserved hosts and
placeholder hashes; replace it with reviewed resource contracts before any authorized live run.
Operator independence is declared in policy; one host cannot count as different operators.
Different hostnames behind shared infrastructure are not automatically independent.

web-basic-v1 requires at least 3 page operators, 2 hashed downloads >=1MiB, 2 fresh core rounds.
Each page is repeated after restart; downloads occur in round 0. Every declared probe is required.
Checks verify TLS, status, redirects, semantic marker and/or hash, MIME when specified, exact
download size and deadlines. Partial, 403/429/challenge and HTTP200 stubs are not success.
custom-v1 is explicitly selected with --allow-custom-policy; output identifies that narrower scope.
Even custom-v1 requires fresh rounds and at least one validated transfer. It cannot mean universal Internet.

UDP entries specify an explicitly supplied echo endpoint and use SOCKS5 UDP ASSOCIATE with
a fresh nonce. This proves that inner UDP path only. No DNS/QUIC/TUN conclusion follows.
require_quic is rejected: no HTTP3 client is included. Media transfer is not implemented or claimed.

## Search and compatibility

Default broad space has 1,563,891,411,124,315 canonical combinations before per-profile filtering,
plus original baselines. Numeric range endpoints are paired with min<=max. It is intentionally
too large to expect exhaustive completion; explicit budgets pause it safely. A custom finite
space can be exhausted. Broad order is a seeded bijection, not one-factor-at-a-time tuning.
At most one in four issuance slots uses a bounded persisted neighborhood queue after semantic
partial progress; other slots continue global exploration. Server profiles alternate.

Implemented: VLESS/VMess/Trojan/Shadowsocks/SOCKS5 allowlisted schemas, TCP/raw/WS/gRPC as
accepted by the schema and version gates, TLS/REALITY, fragmentation, Mux/XUDP/fingerprint.
Every actual runtime combination remains subject to the target core's config validation.
REALITY transport and Vision/Mux constraints are enforced. Unknown chains, plugins, fields,
legacy VMess alterId, unsupported ciphers and insecure TLS are rejected, not converted.

Native core noises use noise=true + noises=[...] with rand/str/hex/base64, delay/applyTo.
Product safety envelope: at most 4 packets, each <=1200 bytes, delay <=1000ms. These are not
upstream field maxima. Only native Shadowsocks UDP with no Mux/outer TLS/WS is enabled in this
profile; an explicit UDP probe is required. The example noise-space can be combined with other
domains. Happ array/RandRange is never guessed. Noise payloads are private configuration data.

happ mode currently fails with happ_mapping_unverified for every version. Unknown Happ build,
full JSON passthrough behavior and UI/runtime mapping block its acceptance. Advanced fragmentation,
IP preference mapping, TUN/MTU/per-app VPN, Hysteria2/WireGuard/TUIC and general proxy chains are unsupported.

## Files and recovery

Private series: authenticated manifest, profiles.json, installation-key copy, optional trust.pem,
writer.lock and integrity-checked journal.jsonl. Public report output removes candidate parameter
values and never dumps source profiles, subscription URLs, bodies or core stderr.

Result directory: working.json (necessary credentials), verification.json (frozen policy and
conditions), report.json (anonymized measurements), COMPLETE.json (hash commit marker).
All files are 0600. A complete existing result is verified idempotently; conflicting/tampered
results are rejected. Failed export leaves a durable winner; resume/export retries saving without
another search. No found tunnel is intentionally kept running.

State schema v1 has no older deployed schema to migrate. Unknown versions fail closed; no
destructive auto-migration is attempted. Filesystem errors require reopen before more writes.
See decisions.md for fault/recovery guarantees and remaining platform assumptions.

## Исправленная сборка p1-fixed-search-v2

Команда version показывает search=2. Новые серии содержат search_version=2.
Старые серии нельзя продолжать: search_version_requires_new_series требует нового каталога
--state и повторного run с исходными входами. Старый каталог не удалять; status/report/export
остаются доступны. Это предотвращает сохранение пропусков из прежнего алгоритма dedup.
Timeout отдельного кандидата записывается как attempt_timeout и не останавливает перебор;
исчерпание session budget и внешняя отмена сохраняют кандидата для resume.
