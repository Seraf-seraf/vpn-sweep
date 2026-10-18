# Runtime specification

The product contract is PLAN.md sections 1..16. Inputs are untrusted and size bounded.
Only allowlisted Xray outbound schemas are accepted. Direct/blackhole utility outbounds are
removed from the test envelope explicitly; unknown proxy chains are rejected.
The server outbound remains immutable while candidate client settings are rendered into a fresh envelope.

The public report contains opaque profile/candidate/attempt/series IDs, typed errors, timings,
payload byte counts and validator outcomes. It never contains source URLs, endpoint names,
credentials, raw external error messages or response bodies.

Status success requires all policy probes in both fresh-core rounds, required downloads,
successful child cleanup and durable private export. Partial is never success.
Budget exhaustion pauses the series. Finite exhaustive traversal reports only the researched space.

Network environment must be explicitly attested; this is not proof that Android has no other VPN.
Unknown/conflicting environments cannot produce a verified result. No system setting is changed.

Storage implementation and unsupported features are documented in EXECUTION.md, not silently substituted.
