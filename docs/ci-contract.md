# Engineering gate contract

Local entrypoint: `make check GO=/absolute/path/to/go`. Tools: Go, Git, Python 3,
POSIX sh, gofmt, xargs. Loopback TCP/UDP socket capability is required by integration suites.
Unit/contract/integration packages execute once under `go test -race ./...`; vet is separate.
No credentials, privileges, containers or public network are required. The fake core only forwards loopback.
Outputs: ignored build/vpn-sweep and build/vpn-sweep-android-arm64. No deployment/publication.

GitHub Actions is declared in check.yml (trigger graph) and gates.yml (reusable job).
Hosted execution and platform lint remain unverified; files have not been pushed.
Trigger matrix: PR/push including tags/manual dispatch run gates once; API dispatch follows
workflow_dispatch; schedules have no workflow; parent workflows can call gates.yml explicitly.
No release, upload or live tests run on any trigger. GitHub token has contents:read only;
checkout does not persist credentials. No privileged/container runner is used.
Never upload private input/state/results/logs as artifacts.
