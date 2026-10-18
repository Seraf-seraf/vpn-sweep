GO ?= go

.PHONY: check test vet build android package scan
check:
	GO="$(GO)" sh scripts/check.sh
test:
	$(GO) test -race ./...
vet:
	$(GO) vet ./...
build:
	CGO_ENABLED=0 $(GO) build -trimpath -o build/vpn-sweep ./cmd/vpn-sweep
android:
	GOOS=android GOARCH=arm64 CGO_ENABLED=0 $(GO) build -trimpath -o build/vpn-sweep-android-arm64 ./cmd/vpn-sweep
scan:
	python3 scripts/secret-scan.py
package:
	GO="$(GO)" sh scripts/package.sh
