package searchbridge

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"reflect"
	"strings"
	"testing"

	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/space"
	"github.com/Seraf-seraf/vpn-sweep/internal/state"
)

type fakeWorker struct {
	identities []Identity
	trace      []string
	openErr    error
	openErrors []error
	closeErr   error
	checkErr   error
	passed     bool
}

func (w *fakeWorker) Open(_ context.Context, identity Identity, _ model.Profile, _ map[string]any) (Round, error) {
	w.identities = append(w.identities, identity)
	w.trace = append(w.trace, "open")
	if len(w.openErrors) > 0 {
		err := w.openErrors[0]
		w.openErrors = w.openErrors[1:]
		if err != nil {
			return nil, err
		}
	}
	if w.openErr != nil {
		return nil, w.openErr
	}
	return &fakeRound{worker: w}, nil
}

type fakeRound struct{ worker *fakeWorker }

func (r *fakeRound) Check(_ context.Context, p model.Probe, round int) (model.ProbeResult, error) {
	r.worker.trace = append(r.worker.trace, "check")
	if r.worker.checkErr != nil {
		return model.ProbeResult{}, r.worker.checkErr
	}
	outcome := "failed"
	if r.worker.passed {
		outcome = "pass"
	}
	return model.ProbeResult{ID: model.ID(), Probe: p.ID, Round: round, Outcome: outcome, Transport: "https-tcp", HTTPStatus: 200, TLSVerified: r.worker.passed, Bytes: p.Bytes}, nil
}

func (r *fakeRound) UDP(context.Context, model.UDPProbe, int) (model.ProbeResult, error) {
	return model.ProbeResult{}, errors.New("unexpected_udp")
}

func (r *fakeRound) Media(context.Context, model.Policy, int) ([]model.ProbeResult, error) {
	return nil, errors.New("unexpected_media")
}

func (r *fakeRound) Failure() error { return nil }

func (r *fakeRound) Close() error {
	r.worker.trace = append(r.worker.trace, "close")
	return r.worker.closeErr
}

func newRunner(t *testing.T, worker *fakeWorker, copies int) *Runner {
	t.Helper()
	profiles := make([]model.Profile, copies)
	for i := range profiles {
		input, err := importer.Parse([]byte(fmt.Sprintf("socks5://synthetic-%d.invalid:1080", i)), "uri")
		if err != nil {
			t.Fatal(err)
		}
		profiles[i] = input.Profiles[0]
	}
	generator, err := space.New(space.Spec{Version: 1, Strategy: "exhaustive"})
	if err != nil {
		t.Fatal(err)
	}
	store, err := state.Open(t.TempDir(), 0)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = store.Close() })
	manifest := model.Manifest{
		Version: 1, SearchVersion: model.SearchVersion, Mode: "core",
		Core: model.CoreIdentity{Commit: model.TargetCommit}, Environment: "user_attested", NetworkLabel: "synthetic",
		Budgets: model.DefaultBudgets(),
		Policy: model.Policy{Version: "custom-v1", Rounds: 2, MinOperators: 1, Downloads: 1, MinDownloadBytes: 1,
			Probes: []model.Probe{
				{ID: "page", Operator: "a", URL: "https://a.invalid", Kind: "page", Contains: "expected", Status: 200, TimeoutSeconds: 1},
				{ID: "data", Operator: "b", URL: "https://b.invalid", Kind: "download", SHA256: strings.Repeat("0", 64), Bytes: 1, Status: 200, TimeoutSeconds: 1},
			}},
	}
	manifest.Budgets.IntervalMS = 0
	return &Runner{Manifest: manifest, Profiles: profiles, Key: make([]byte, 32), Generator: generator, Store: store, Worker: worker, Export: func(model.Attempt) error { return nil }, SessionGeneration: 42}
}

func TestAndroidSearchOpensAndClosesOneWorkerPerRound(t *testing.T) {
	worker := &fakeWorker{passed: true}
	runner := newRunner(t, worker, 1)
	status, err := runner.Run(context.Background())
	if err != nil || status != "success" {
		t.Fatalf("status=%q error=%v", status, err)
	}
	want := []string{"open", "check", "check", "close", "open", "check", "close"}
	if !reflect.DeepEqual(worker.trace, want) {
		t.Fatalf("operations %v, want %v", worker.trace, want)
	}
	identities := []Identity{{42, 1, 1, 1}, {42, 1, 2, 2}}
	if !reflect.DeepEqual(worker.identities, identities) {
		t.Fatalf("identities %v, want %v", worker.identities, identities)
	}
}

func TestAndroidSearchWorkerFailureCancelsSessionAndPreventsReplay(t *testing.T) {
	for _, fault := range []error{ErrBusy, ErrOwnerLost, ErrUnknownDelivery, ErrWorkerDied, ErrCleanupIncomplete} {
		t.Run(fault.Error(), func(t *testing.T) {
			worker := &fakeWorker{openErr: fault}
			runner := newRunner(t, worker, 2)
			status, err := runner.Run(context.Background())
			if status != "interrupted" || !errors.Is(err, fault) {
				t.Fatalf("status=%q error=%v", status, err)
			}
			view := runner.Store.View()
			if view.Attempts != 1 || view.Retry == nil || len(worker.identities) != 1 {
				t.Fatalf("failure treated as a candidate result: %+v", view)
			}
			if _, err := runner.Run(context.Background()); !errors.Is(err, fault) || len(worker.identities) != 1 {
				t.Fatalf("quarantined runner restarted: %v", err)
			}
		})
	}
}

func TestAndroidSearchCleanCandidateFailureContinues(t *testing.T) {
	candidate := &CandidateOpenError{Reason: CandidateConfigRejected, Retryable: true}
	modelError, ok := candidateOpenError(candidate)
	if !ok || modelError.Code != "core_config_rejected" || modelError.Phase != "core" || !modelError.Retryable {
		t.Fatal("candidate error lost its typed code or retryability")
	}
	worker := &fakeWorker{passed: true, openErrors: []error{candidate}}
	runner := newRunner(t, worker, 2)
	status, err := runner.Run(context.Background())
	if err != nil || status != "success" || runner.Store.View().Attempts != 2 {
		t.Fatalf("candidate failure interrupted search: status=%q error=%v", status, err)
	}
	if len(worker.identities) != 3 || worker.identities[0].Attempt != 1 || worker.identities[1].Attempt != 2 {
		t.Fatalf("candidate scheduling changed: %v", worker.identities)
	}
	var history bytes.Buffer
	if err := runner.Store.History(&history); err != nil || !strings.Contains(history.String(), "core_config_rejected") {
		t.Fatal("candidate failure was not recorded")
	}
}

func TestAndroidSearchCloseFailureQuarantinesBeforeNextRound(t *testing.T) {
	worker := &fakeWorker{passed: true, closeErr: errors.New("unsafe detail")}
	runner := newRunner(t, worker, 1)
	_, err := runner.Run(context.Background())
	if !errors.Is(err, ErrCleanupIncomplete) || len(worker.identities) != 1 {
		t.Fatalf("cleanup failure err=%v opens=%d", err, len(worker.identities))
	}
	if _, err := runner.Run(context.Background()); !errors.Is(err, ErrCleanupIncomplete) || len(worker.identities) != 1 {
		t.Fatalf("quarantined runner restarted: %v", err)
	}
}

func TestAndroidSearchResumesPausedBudgetWithSameState(t *testing.T) {
	worker := &fakeWorker{}
	runner := newRunner(t, worker, 2)
	runner.Manifest.Budgets.Attempts = 1
	status, err := runner.Run(context.Background())
	if err != nil || status != "paused_budget" || runner.Store.View().Attempts != 1 {
		t.Fatalf("first run status=%q error=%v", status, err)
	}
	worker.passed = true
	status, err = runner.Run(context.Background())
	if err != nil || status != "success" || runner.Store.View().Attempts != 2 {
		t.Fatalf("resumed status=%q error=%v", status, err)
	}
	if len(worker.identities) != 3 || worker.identities[1].WorkerGeneration != 2 || worker.identities[2].WorkerGeneration != 3 {
		t.Fatalf("worker generations were reused: %v", worker.identities)
	}
}
