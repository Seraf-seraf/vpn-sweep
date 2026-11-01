GO ?= go

.PHONY: check test vet build android package scan xray-host android-core android-apk android-check android-ui-check android-preview
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
xray-host:
	@cd android/core && \
		version=$$(GOWORK=off "$(GO)" list -m -mod=readonly -f '{{if .Replace}}replaced{{else}}{{.Version}}{{end}}' github.com/xtls/xray-core) && \
		if [ "$$version" != 'v1.260327.1-0.20261016015535-ac5d8a83ba79' ]; then \
			printf 'unexpected or replaced Xray module pin: %s\n' "$$version" >&2; exit 1; \
		fi && \
		GOWORK=off "$(GO)" mod verify && \
		revision=$${version##*-} && \
		label=$$(printf '%.7s' "$$revision") && \
		mkdir -p ../../build && \
		GOWORK=off GOOS=linux CGO_ENABLED=0 "$(GO)" build -mod=readonly -trimpath -buildvcs=false \
			-ldflags="-X github.com/xtls/xray-core/core.build=$$label" \
			-o ../../build/xray-host-pinned github.com/xtls/xray-core/main
android-core:
	GO="$(GO)" sh scripts/android-core.sh
android-apk: android-core
	cd android && ./gradlew --no-daemon :app:assembleDebug
android-check: android-core
	cd android/core && $(GO) vet -mod=readonly ./... && $(GO) test -mod=readonly -race ./...
	cd android && ./gradlew --no-daemon :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
android-ui-check: android-core
	cd android && ./gradlew --no-daemon :app:connectedQaAndroidTest
android-preview: android-core
	cd android && ./gradlew --no-daemon --no-configuration-cache :app:updateQaScreenshotTest
