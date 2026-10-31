# Runtime specification

[PLAN.md](PLAN.md) sections 1..16 record the original agreed plan; they do not by themselves
describe the current runtime. This document and [usage.md](usage.md) describe the current
CLI contract.

The CLI reads supported JSON configurations, arrays and plaintext lists of configuration
URIs from a local file or stdin, including URI lists obtained from a subscription. It does
not fetch subscription URLs or decode a base64 wrapper around the whole input. Android
subscription loading is a future feature and is not currently connected to the runtime;
see [android/README.md](../android/README.md). New CLI series use core mode, without a
happ/core mode selector.

Inputs are untrusted and size bounded.
Only allowlisted Xray outbound schemas are accepted. Direct/blackhole utility outbounds are
removed from the test envelope explicitly; unknown proxy chains are rejected.
The server outbound remains immutable while candidate client settings are rendered into a fresh envelope.

The public report contains opaque profile/candidate/attempt/series IDs, typed errors, timings,
payload byte counts and validator outcomes. It never contains source URLs, endpoint names,
credentials, raw external error messages or response bodies.

New CLI series use `google-youtube-web-noise-v1`: the Google and YouTube HTML pages
must pass in both fresh-core rounds. For a candidate with `noise=true`, a UDP/DNS probe
through SOCKS must also pass in both rounds; it is not run for `noise=false`. The default
policy does not require a download or a video check.

Status success requires all checks applicable to the series policy, successful child
cleanup and durable private export. Saved series retain their original policy; resume
and export compatibility for video series is described in [video-profile.md](video-profile.md).
Partial is never success.
Budget exhaustion pauses the series. Finite exhaustive traversal reports only the researched space.

New series record the network environment as unverified; no network label or attestation is requested.
Success confirms only the application probes, not the absence of another VPN. No system setting is changed.

Storage implementation and unsupported features are documented in EXECUTION.md, not silently substituted.
