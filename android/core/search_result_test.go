package mobile

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/android/core/searchbridge"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/space"
	"github.com/Seraf-seraf/vpn-sweep/internal/state"
)

func TestAndroidSearchVerifiedResultRealExportWithFakeWorker(t *testing.T) {
	worker := &fakeEngineWorker{}
	session, _ := searchSessionFixture(t, worker)
	status, err := session.Run()
	if err != nil || status != "success" || worker.closes != 2 {
		t.Fatalf("run status=%q cleanup=%d err=%v", status, worker.closes, err)
	}
	result, err := session.TakeVerifiedResult()
	if err != nil {
		t.Fatal(err)
	}
	hash := sha256.New()
	for {
		chunk, err := result.ReadChunk(maxSearchResultChunkBytes)
		if err != nil {
			t.Fatal(err)
		}
		if len(chunk) == 0 {
			break
		}
		if _, err := hash.Write(chunk); err != nil {
			t.Fatal(err)
		}
	}
	if hex.EncodeToString(hash.Sum(nil)) != result.Digest() {
		t.Fatal("delivered bytes do not match the verified digest")
	}
	if err := result.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestAndroidSearchVerifiedResultZeroValueFailsClosed(t *testing.T) {
	var result SearchVerifiedResult
	if _, err := result.ReadChunk(1); err == nil {
		t.Fatal("zero value accepted a read")
	}
	for i := 0; i < 2; i++ {
		if err := result.Close(); !errors.Is(err, errSearchResultCleanupUnknown) {
			t.Fatalf("zero-value close was not unknown: %v", err)
		}
	}
}

func resultSessionFixture(t *testing.T) (*SearchSession, string) {
	t.Helper()
	dir := t.TempDir()
	store, err := state.Open(filepath.Join(dir, "state"), 0)
	if err != nil {
		t.Fatal(err)
	}
	candidate := strings.Repeat("a", 64)
	if err := store.Begin(model.Attempt{ID: "synthetic", Candidate: model.Candidate{ID: candidate}}, "1"); err != nil {
		t.Fatal(err)
	}
	if err := store.End("verified_pending_export"); err != nil {
		t.Fatal(err)
	}
	session := &SearchSession{
		runner: &searchbridge.Runner{Manifest: model.Manifest{SeriesID: "synthetic-series"}},
		store:  store, stagingDir: filepath.Join(dir, "stage"), resultReady: true,
	}
	t.Cleanup(func() { _ = session.Close() })
	return session, candidate
}

func fakeResultFile(t *testing.T, content []byte) *os.File {
	t.Helper()
	file, err := os.CreateTemp(t.TempDir(), "result-")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := file.Write(content); err != nil {
		t.Fatal(err)
	}
	if _, err := file.Seek(0, 0); err != nil {
		t.Fatal(err)
	}
	return file
}

func TestAndroidSearchVerifiedResultFollowsSuccessfulFakeRun(t *testing.T) {
	input, err := importer.Parse([]byte("socks5://synthetic.invalid:1080"), "uri")
	if err != nil {
		t.Fatal(err)
	}
	generator, err := space.New(space.Spec{Version: 1, Strategy: "exhaustive"})
	if err != nil {
		t.Fatal(err)
	}
	store, err := state.Open(t.TempDir(), 0)
	if err != nil {
		t.Fatal(err)
	}
	worker := &fakeEngineWorker{}
	manifest := model.Manifest{SeriesID: "synthetic-series", SearchVersion: model.SearchVersion,
		Mode: "core", Core: model.CoreIdentity{Commit: model.TargetCommit}, Environment: "unverified",
		Budgets: model.DefaultBudgets(), Policy: model.Policy{Version: "custom-v1", Rounds: 2,
			MinOperators: 1, Downloads: 1, MinDownloadBytes: 1, Probes: []model.Probe{
				{ID: "page", Operator: "a", URL: "https://a.invalid", Kind: "page", Contains: "expected", Status: 200, TimeoutSeconds: 1},
				{ID: "data", Operator: "b", URL: "https://b.invalid", Kind: "download", SHA256: strings.Repeat("0", 64), Bytes: 1, Status: 200, TimeoutSeconds: 1},
			}}}
	manifest.Budgets.IntervalMS = 0
	var exported string
	runner := &searchbridge.Runner{SessionGeneration: 42, Manifest: manifest, Profiles: input.Profiles,
		Key: make([]byte, 32), Generator: generator, Store: store,
		Worker: &androidSearchWorker{callback: worker, startup: time.Second, cleanupMillis: 1000},
		Export: func(a model.Attempt) error { exported = a.Candidate.ID; return nil }}
	session := &SearchSession{runner: runner, store: store, stagingDir: "synthetic-staging"}
	t.Cleanup(func() { _ = session.Close() })
	status, err := session.Run()
	if err != nil || status != "success" || exported == "" || worker.closes != 2 {
		t.Fatalf("fake run status=%q cleanup=%d err=%v", status, worker.closes, err)
	}
	file := fakeResultFile(t, []byte("fake verified result"))
	result, err := session.takeVerifiedResult(func(dir, series, candidate string) (*os.File, string, error) {
		if dir != "synthetic-staging" || series != manifest.SeriesID || candidate != exported {
			t.Fatal("successful run did not select its exported winner")
		}
		return file, strings.Repeat("a", 64), nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := result.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err := session.Run(); err == nil {
		t.Fatal("successful session restarted after result handoff")
	}
}

func TestAndroidSearchVerifiedResultUsesWinnerOnce(t *testing.T) {
	session, candidate := resultSessionFixture(t)
	file := fakeResultFile(t, []byte("synthetic-result"))
	digest := strings.Repeat("b", 64)
	called := 0
	opener := func(dir, series, id string) (*os.File, string, error) {
		called++
		if dir != session.stagingDir || series != "synthetic-series" || id != candidate {
			t.Fatal("wrong verified export identity")
		}
		return file, digest, nil
	}
	result, err := session.takeVerifiedResult(opener)
	if err != nil || result.Digest() != digest {
		t.Fatalf("verified result unavailable: %v", err)
	}
	first, err := result.ReadChunk(9)
	if err != nil || string(first) != "synthetic" {
		t.Fatalf("first chunk: %q %v", first, err)
	}
	second, err := result.ReadChunk(32)
	if err != nil || string(second) != "-result" {
		t.Fatalf("second chunk: %q %v", second, err)
	}
	last, err := result.ReadChunk(32)
	if err != nil || len(last) != 0 {
		t.Fatalf("EOF: %v %v", last, err)
	}
	if _, err := session.takeVerifiedResult(opener); err == nil || called != 1 {
		t.Fatal("result was opened more than once")
	}
	if err := result.Close(); err != nil {
		t.Fatal(err)
	}
	if err := result.Close(); err != nil {
		t.Fatal("successful close was not idempotent", err)
	}
}

func TestAndroidSearchVerifiedResultRequiresSuccessfulRunAndDoesNotRetryUnknown(t *testing.T) {
	session, _ := resultSessionFixture(t)
	session.resultReady = false
	called := 0
	opener := func(string, string, string) (*os.File, string, error) {
		called++
		return nil, "", model.Err("export_cleanup_unknown", "export")
	}
	if _, err := session.takeVerifiedResult(opener); err == nil || called != 0 {
		t.Fatal("result opened before successful run")
	}
	session.resultReady = true
	if _, err := session.takeVerifiedResult(opener); err == nil || model.Code(err) != "export_cleanup_unknown" {
		t.Fatalf("unknown cleanup was hidden: %v", err)
	}
	if _, err := session.takeVerifiedResult(opener); err == nil || called != 1 {
		t.Fatal("unknown open was retried")
	}
}

func TestAndroidSearchVerifiedResultOpenDoesNotBlockCancelOrAllowConcurrentClose(t *testing.T) {
	session, _ := resultSessionFixture(t)
	file := fakeResultFile(t, []byte("result"))
	entered := make(chan struct{})
	release := make(chan struct{})
	done := make(chan error, 1)
	go func() {
		result, err := session.takeVerifiedResult(func(string, string, string) (*os.File, string, error) {
			close(entered)
			<-release
			return file, strings.Repeat("c", 64), nil
		})
		if result != nil {
			_ = result.Close()
		}
		done <- err
	}()
	<-entered
	returned := make(chan struct{})
	go func() { session.Cancel(); close(returned) }()
	select {
	case <-returned:
	case <-time.After(time.Second):
		close(release)
		t.Fatal("cancel blocked")
	}
	if err := session.Close(); err == nil {
		t.Fatal("session closed while result open was in flight")
	}
	close(release)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestAndroidSearchVerifiedResultStreamIsBoundedAfterGrowth(t *testing.T) {
	session, _ := resultSessionFixture(t)
	file := fakeResultFile(t, bytes.Repeat([]byte{'x'}, int(maxSearchResultBytes+1)))
	result, err := session.takeVerifiedResult(func(string, string, string) (*os.File, string, error) {
		return file, strings.Repeat("d", 64), nil
	})
	if err != nil {
		t.Fatal(err)
	}
	defer result.Close()
	var read int64
	for {
		chunk, err := result.ReadChunk(maxSearchResultChunkBytes)
		read += int64(len(chunk))
		if err != nil {
			if err.Error() != "search_result_limit" || read != maxSearchResultBytes {
				t.Fatalf("unexpected limit: bytes=%d err=%v", read, err)
			}
			break
		}
		if len(chunk) == 0 {
			t.Fatal("oversized result reached EOF without error")
		}
	}
	if _, err := result.ReadChunk(maxSearchResultChunkBytes); err == nil {
		t.Fatal("limit error was not sticky")
	}
}

func TestAndroidSearchVerifiedResultStreamBoundaryAndChunkValidation(t *testing.T) {
	for _, size := range []int64{maxSearchResultBytes - 1, maxSearchResultBytes} {
		t.Run(fmt.Sprint(size), func(t *testing.T) {
			session, _ := resultSessionFixture(t)
			file := fakeResultFile(t, nil)
			if err := file.Truncate(size); err != nil {
				t.Fatal(err)
			}
			result, err := session.takeVerifiedResult(func(string, string, string) (*os.File, string, error) {
				return file, strings.Repeat("f", 64), nil
			})
			if err != nil {
				t.Fatal(err)
			}
			defer result.Close()
			for _, invalid := range []int64{0, -1, maxSearchResultChunkBytes + 1} {
				if _, err := result.ReadChunk(invalid); err == nil {
					t.Fatalf("accepted chunk size %d", invalid)
				}
			}
			var read int64
			for {
				chunk, err := result.ReadChunk(maxSearchResultChunkBytes)
				if err != nil {
					t.Fatal(err)
				}
				if len(chunk) == 0 {
					break
				}
				read += int64(len(chunk))
			}
			if read != size {
				t.Fatalf("read %d bytes, expected %d", read, size)
			}
		})
	}
}

func TestAndroidSearchVerifiedResultCloseFailureRemainsUnknown(t *testing.T) {
	session, _ := resultSessionFixture(t)
	file := fakeResultFile(t, []byte("result"))
	result, err := session.takeVerifiedResult(func(string, string, string) (*os.File, string, error) {
		return file, strings.Repeat("e", 64), nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		if err := result.Close(); err == nil || !errors.Is(err, errSearchResultCleanupUnknown) {
			t.Fatalf("unknown close was lost: %v", err)
		}
	}
}
