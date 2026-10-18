# Engineering gate contract

Local entrypoint: `make check GO=/absolute/path/to/go`. Tools: Go, Git, Python 3,
POSIX sh, gofmt, xargs. Loopback TCP/UDP socket capability is required by integration suites.
Unit/contract/integration packages execute once under `go test -race ./...`; vet is separate.
No credentials, privileges, containers or public network are required. The fake core only forwards loopback.
Outputs: ignored build/vpn-sweep and build/vpn-sweep-android-arm64. No deployment/publication.

GitHub Actions workflows were removed at the user's request. All checks are local-only:
`make check`, `make test`, `make vet`, `make scan`, `make build`, `make android`, and
`make package` remain available; package requires previously built artifacts.
There are no repository workflow triggers for push, pull requests, tags, schedules,
manual/API dispatch or reusable-workflow calls. No account/repository Actions settings or
historical runs were changed. Never publish private input/state/results/logs as artifacts.

The previous hosted run failed; removing CI does not turn that result into a pass.
Local test fixtures still depend on umask: existing t.TempDir directories may have 0755
permissions under umask 022, while private.Dir requires exactly 0700. Some state tests
also ignore setup errors, causing a subsequent nil-pointer panic. These defects remain
unfixed. Production permission checks are unchanged; local execution is not automatically
successful merely because hosted CI was removed.
