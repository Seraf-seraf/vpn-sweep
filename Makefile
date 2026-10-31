GO ?= go

.PHONY: check test vet build android package scan android-core android-apk android-check android-ui-check android-preview
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
