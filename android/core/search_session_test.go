package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/android/core/searchbridge"
	"github.com/Seraf-seraf/vpn-sweep/internal/export"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/space"
)

type fakeEngineWorker struct {
	mu           sync.Mutex
	opens        int
	closes       int
	checks       int
	openStatus   int
	badReply     bool
	openStarted  chan struct{}
	openRelease  chan struct{}
	checkStarted chan struct{}
	checkRelease chan struct{}
	cancelOnce   sync.Once
}

func (w *fakeEngineWorker) Open(_ int64, _ int64, _ int, _ int64, _, _ []byte, _, _ int64) int {
	w.mu.Lock()
	w.opens++
	w.mu.Unlock()
	if w.openStarted != nil {
		close(w.openStarted)
		<-w.openRelease
	}
	return w.openStatus
}

func (w *fakeEngineWorker) Call(_ int64, operation int, input []byte, round int, _ int64) []byte {
	if w.badReply {
		return []byte(`{"result":{}}`)
	}
	if operation != SearchWorkerCheck {
		return []byte(`{"fault":6}`)
	}
	w.mu.Lock()
	w.checks++
	w.mu.Unlock()
	if w.checkStarted != nil {
		close(w.checkStarted)
		<-w.checkRelease
	}
	var p model.Probe
	_ = json.Unmarshal(input, &p)
	response, _ := json.Marshal(map[string]any{"fault": 0, "result": model.ProbeResult{
		ID: model.ID(), Probe: p.ID, Round: round, Outcome: "pass", Transport: "https-tcp", HTTPStatus: 200, TLSVerified: true, Bytes: p.Bytes,
	}})
	return response
}

func (*fakeEngineWorker) Failure(int64) int { return SearchWorkerReady }
func (w *fakeEngineWorker) Close(_ int64, _ int64) int {
	w.mu.Lock()
	w.closes++
	w.mu.Unlock()
	return SearchWorkerReady
}
func (w *fakeEngineWorker) Cancel(int64) {
	w.cancelOnce.Do(func() {
		if w.openRelease != nil {
			close(w.openRelease)
		}
		if w.checkRelease != nil {
			close(w.checkRelease)
		}
	})
}
func (*fakeEngineWorker) Progress(int, int, int64, int, int, int, int64, int64) {}

func searchSessionFixture(t *testing.T, worker *fakeEngineWorker) (*SearchSession, string) {
	t.Helper()
	input, err := importer.Parse([]byte("socks5://synthetic.invalid:1080"), "uri")
	if err != nil {
		t.Fatal(err)
	}
	profiles, _ := json.Marshal(input.Profiles)
	spaceBytes, _ := json.Marshal(space.Spec{Version: 1, Strategy: "exhaustive"})
	manifest := model.Manifest{Version: model.SchemaVersion, SearchVersion: model.SearchVersion, SeriesID: "synthetic",
		Mode: "core", Environment: "unverified", Core: model.CoreIdentity{Commit: model.TargetCommit}, Space: spaceBytes,
		Budgets: model.DefaultBudgets(), Policy: model.Policy{Version: "custom-v1", Rounds: 2, MinOperators: 1, Downloads: 1, MinDownloadBytes: 1,
			Probes: []model.Probe{
				{ID: "page", Operator: "a", URL: "https://a.invalid", Kind: "page", Contains: "expected", Status: 200, TimeoutSeconds: 1},
				{ID: "data", Operator: "b", URL: "https://b.invalid", Kind: "download", SHA256: strings.Repeat("0", 64), Bytes: 1, Status: 200, TimeoutSeconds: 1},
			}}}
	manifest.Budgets.IntervalMS = 0
	manifestBytes, _ := json.Marshal(manifest)
	parent := t.TempDir()
	staging := filepath.Join(parent, "staging")
	session, err := NewSearchSession(42, manifestBytes, profiles, filepath.Join(parent, "state"), staging, worker)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = session.Close() })
	return session, staging
}

func TestAndroidSearchSessionRunsEngineThroughFakeWorkerAndVerifiedExport(t *testing.T) {
	worker := &fakeEngineWorker{}
	session, staging := searchSessionFixture(t, worker)
	status, err := session.Run()
	if err != nil || status != "success" {
		t.Fatalf("status=%q error=%v", status, err)
	}
	if worker.opens != 2 || worker.closes != 2 || worker.checks != 3 {
		t.Fatalf("opens=%d closes=%d checks=%d", worker.opens, worker.closes, worker.checks)
	}
	if _, err := os.Stat(filepath.Join(staging, "COMPLETE.json")); err != nil {
		t.Fatal("completion marker missing")
	}
	if saved, err := export.SavedCandidates(staging); err != nil || len(saved) != 1 {
		t.Fatalf("export integrity not confirmed: %v", err)
	}
}

func TestAndroidSearchSessionWorkerFaultIsNotCandidateFailure(t *testing.T) {
	worker := &fakeEngineWorker{openStatus: SearchWorkerDeliveryUnknown}
	session, staging := searchSessionFixture(t, worker)
	status, err := session.Run()
	if status != "interrupted" || err == nil || err.Error() != "worker_delivery_unknown" || session.store.View().Retry == nil {
		t.Fatalf("status=%q error=%v", status, err)
	}
	if _, err := os.Stat(filepath.Join(staging, "COMPLETE.json")); !os.IsNotExist(err) {
		t.Fatal("fault published a success")
	}
	if _, err := session.Run(); err == nil || worker.opens != 1 {
		t.Fatal("quarantined generation replayed")
	}
}

func TestAndroidSearchSessionCancelDuringOpenWaitsForCleanup(t *testing.T) {
	worker := &fakeEngineWorker{openStarted: make(chan struct{}), openRelease: make(chan struct{})}
	session, _ := searchSessionFixture(t, worker)
	done := make(chan string, 1)
	go func() {
		status, err := session.Run()
		if err != nil {
			done <- "error"
			return
		}
		done <- status
	}()
	<-worker.openStarted
	if _, err := session.Run(); err == nil {
		t.Fatal("concurrent run accepted")
	}
	if err := session.Close(); err == nil {
		t.Fatal("running session closed")
	}
	session.Cancel()
	if status := <-done; status != "interrupted" {
		t.Fatalf("cancel status=%q", status)
	}
	if worker.closes != 1 {
		t.Fatal("cancelled Open did not finish cleanup")
	}
}

func TestAndroidSearchSessionRejectsMalformedWorkerReply(t *testing.T) {
	worker := &fakeEngineWorker{badReply: true}
	session, _ := searchSessionFixture(t, worker)
	_, err := session.Run()
	if !errors.Is(err, searchbridge.ErrWorkerUnavailable) {
		t.Fatalf("malformed response accepted: %v", err)
	}
	if worker.closes != 1 {
		t.Fatal("invalid response did not close worker")
	}
}

func TestAndroidSearchSessionCancelDuringCheckDoesNotDispatchAgain(t *testing.T) {
	worker := &fakeEngineWorker{checkStarted: make(chan struct{}), checkRelease: make(chan struct{})}
	session, _ := searchSessionFixture(t, worker)
	done := make(chan string, 1)
	go func() {
		status, err := session.Run()
		if err != nil {
			done <- "error"
			return
		}
		done <- status
	}()
	<-worker.checkStarted
	session.Cancel()
	if status := <-done; status != "interrupted" {
		t.Fatalf("cancel status=%q", status)
	}
	if worker.checks != 1 || worker.opens != 1 || worker.closes != 1 || session.store.View().Retry == nil {
		t.Fatal("cancelled check continued or lost retry state")
	}
}

func TestAndroidSearchOpenOutcomesKeepCandidateCapabilityAndCancellationDistinct(t *testing.T) {
	var candidate *searchbridge.CandidateOpenError
	err := searchWorkerOpenError(SearchWorkerCandidateConfigRejected|SearchWorkerRetryableFlag, context.Background(), context.Background())
	if !errors.As(err, &candidate) || candidate.Reason != searchbridge.CandidateConfigRejected || !candidate.Retryable {
		t.Fatal("candidate rejection or retryability lost")
	}
	if err := searchWorkerOpenError(SearchWorkerCapabilityUnavailable, context.Background(), context.Background()); !errors.Is(err, searchbridge.ErrCapabilityUnavailable) {
		t.Fatal("capability failure was not distinct")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := searchWorkerOpenError(SearchWorkerCancelled, ctx, ctx); !errors.Is(err, context.Canceled) {
		t.Fatal("cancellation was not preserved")
	}
	startup, stop := context.WithDeadline(context.Background(), time.Unix(0, 0))
	defer stop()
	if err := searchWorkerOpenError(SearchWorkerCancelled, ctx, startup); !errors.Is(err, context.Canceled) {
		t.Fatal("parent cancellation did not take precedence over startup timeout")
	}
	candidate = nil
	if err := searchWorkerOpenError(SearchWorkerDeadline, context.Background(), startup); !errors.As(err, &candidate) || candidate.Reason != searchbridge.CandidateStartTimeout {
		t.Fatal("clean startup deadline was not a candidate timeout")
	}
	if err := searchWorkerOpenError(SearchWorkerOwnerLost|SearchWorkerRetryableFlag, context.Background(), context.Background()); !errors.Is(err, searchbridge.ErrWorkerUnavailable) {
		t.Fatal("invalid retryability flag accepted on fatal failure")
	}
}

func TestAndroidSearchOpenStartupTimeoutAcceptsCleanedCancellation(t *testing.T) {
	worker := &fakeEngineWorker{
		openStatus:  SearchWorkerCancelled,
		openStarted: make(chan struct{}),
		openRelease: make(chan struct{}),
	}
	backend := &androidSearchWorker{callback: worker, startup: 50 * time.Millisecond, cleanupMillis: 1000}
	attempt, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, err := backend.Open(attempt, searchbridge.Identity{WorkerGeneration: 1}, model.Profile{}, nil)
	var candidate *searchbridge.CandidateOpenError
	if !errors.As(err, &candidate) || candidate.Reason != searchbridge.CandidateStartTimeout || attempt.Err() != nil {
		t.Fatalf("cleaned startup cancellation: %v", err)
	}
}

func TestAndroidSearchOpenStartupTimeoutDoesNotHideUnknownCleanup(t *testing.T) {
	worker := &fakeEngineWorker{
		openStatus:  SearchWorkerCleanupIncomplete,
		openStarted: make(chan struct{}),
		openRelease: make(chan struct{}),
	}
	backend := &androidSearchWorker{callback: worker, startup: 50 * time.Millisecond, cleanupMillis: 1000}
	attempt, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, err := backend.Open(attempt, searchbridge.Identity{WorkerGeneration: 1}, model.Profile{}, nil)
	if !errors.Is(err, searchbridge.ErrCleanupIncomplete) {
		t.Fatalf("unknown cleanup was hidden: %v", err)
	}
}
