package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

type fakeEmbeddedCore struct {
	closes       atomic.Int32
	closeEntered chan struct{}
	closeRelease chan struct{}
}

type blockedCurrentNetwork struct {
	once    sync.Once
	entered chan struct{}
	release chan struct{}
}

func (n *blockedCurrentNetwork) Current() bool {
	n.once.Do(func() { close(n.entered) })
	<-n.release
	return true
}
func (*blockedCurrentNetwork) QueryDNS([]byte, int64) ([]byte, error)    { return nil, nil }
func (*blockedCurrentNetwork) AllowSocket(int, int, string, string) bool { return false }

func TestAndroidSearchEmbeddedBlockedEpochCheckQuarantinesWithinBudget(t *testing.T) {
	owner := &blockedCurrentNetwork{entered: make(chan struct{}), release: make(chan struct{})}
	ownerCtx, cancel := context.WithCancel(context.Background())
	defer cancel()
	guard := &protection{search: owner, searchContext: ownerCtx, searchCancel: cancel}
	previous := activeProtection.Swap(guard)
	defer activeProtection.Store(previous)
	worker := newSearchEmbeddedWorker(guard, func(ctx context.Context, _ context.Context, _ *protection, _ model.Profile, _ map[string]any) (searchCore, *SearchWorkerProbes, error) {
		<-ctx.Done()
		return nil, nil, ctx.Err()
	})
	profile, params := embeddedInput(t)
	done := make(chan int, 1)
	go func() { done <- worker.OpenStatus(profile, params, 20, 20) }()
	<-owner.entered
	select {
	case got := <-done:
		close(owner.release)
		if got != SearchWorkerCleanupIncomplete {
			t.Fatalf("blocked owner status=%d", got)
		}
	case <-time.After(time.Second):
		close(owner.release)
		<-done
		t.Fatal("blocked owner exceeded startup and cleanup budgets")
	}
	if err := worker.Close(1000); err != nil {
		t.Fatal("released owner did not finish cleanup", err)
	}
}

func TestAndroidSearchEmbeddedStartupBudgetQuarantinesBlockedStart(t *testing.T) {
	_, guard := installFakeOwner(t)
	entered := make(chan struct{})
	release := make(chan struct{})
	worker := newSearchEmbeddedWorker(guard, func(context.Context, context.Context, *protection, model.Profile, map[string]any) (searchCore, *SearchWorkerProbes, error) {
		close(entered)
		<-release
		return nil, nil, errSearchCoreCreation
	})
	profile, params := embeddedInput(t)
	done := make(chan error, 1)
	go func() { done <- worker.Open(profile, params, 20, 20) }()
	<-entered
	select {
	case err := <-done:
		close(release)
		if err == nil || err.Error() != "worker_cleanup_incomplete" {
			t.Fatalf("blocked start reported confirmed cleanup: %v", err)
		}
	case <-time.After(time.Second):
		close(release)
		<-done
		t.Fatal("Open exceeded startup and cleanup budgets")
	}
}

func (c *fakeEmbeddedCore) Close() error {
	c.closes.Add(1)
	if c.closeEntered != nil {
		close(c.closeEntered)
		<-c.closeRelease
	}
	return nil
}

func embeddedInput(t *testing.T) ([]byte, []byte) {
	t.Helper()
	input, err := importer.Parse([]byte("socks5://synthetic.invalid:1080"), "uri")
	if err != nil {
		t.Fatal(err)
	}
	profile, _ := json.Marshal(input.Profiles[0])
	return profile, []byte(`{}`)
}

func TestAndroidSearchEmbeddedWorkerOneStartAndCleanup(t *testing.T) {
	_, guard := installFakeOwner(t)
	instance := &fakeEmbeddedCore{}
	starts := 0
	worker := newSearchEmbeddedWorker(guard, func(_ context.Context, _ context.Context, _ *protection, _ model.Profile, _ map[string]any) (searchCore, *SearchWorkerProbes, error) {
		starts++
		probes, err := newSearchWorkerProbes("127.0.0.1:1080", "user", "password")
		return instance, probes, err
	})
	profile, params := embeddedInput(t)
	if err := worker.Open(profile, params, 1000, 1000); err != nil {
		t.Fatal(err)
	}
	if _, err := worker.Probes(); err != nil {
		t.Fatal("ready worker did not expose probes")
	}
	if err := worker.Open(profile, params, 1000, 1000); err == nil || starts != 1 {
		t.Fatal("worker accepted a second generation")
	}
	worker.Cancel()
	if _, err := worker.Probes(); err == nil {
		t.Fatal("cancelled worker kept dispatch open")
	}
	if err := worker.Close(1000); err != nil || instance.closes.Load() != 1 {
		t.Fatalf("cleanup err=%v count=%d", err, instance.closes.Load())
	}
	if err := worker.Close(1000); err != nil || instance.closes.Load() != 1 {
		t.Fatal("cleanup repeated")
	}
}

func TestAndroidSearchEmbeddedOpenCleansPartialStart(t *testing.T) {
	_, guard := installFakeOwner(t)
	instance := &fakeEmbeddedCore{}
	worker := newSearchEmbeddedWorker(guard, func(context.Context, context.Context, *protection, model.Profile, map[string]any) (searchCore, *SearchWorkerProbes, error) {
		return instance, nil, errors.New("search_core_start_failed")
	})
	profile, params := embeddedInput(t)
	if err := worker.Open(profile, params, 1000, 1000); err == nil || instance.closes.Load() != 1 {
		t.Fatal("partial Open did not clean its instance")
	}
	if _, err := worker.Probes(); err == nil {
		t.Fatal("failed Open exposed probes")
	}
}

func TestAndroidSearchEmbeddedUnknownCoreCleanupStaysQuarantined(t *testing.T) {
	_, guard := installFakeOwner(t)
	worker := newSearchEmbeddedWorker(guard, func(context.Context, context.Context, *protection, model.Profile, map[string]any) (searchCore, *SearchWorkerProbes, error) {
		return nil, nil, errSearchCoreCreation
	})
	profile, params := embeddedInput(t)
	if err := worker.Open(profile, params, 1000, 1000); err == nil || err.Error() != "worker_cleanup_incomplete" {
		t.Fatalf("unknown partial cleanup was accepted: %v", err)
	}
	if err := worker.Close(1000); err == nil {
		t.Fatal("unknown partial cleanup became confirmed")
	}
}

func TestAndroidSearchEmbeddedCancelDuringStartCleansBeforeReturn(t *testing.T) {
	_, guard := installFakeOwner(t)
	instance := &fakeEmbeddedCore{}
	started := make(chan struct{})
	worker := newSearchEmbeddedWorker(guard, func(ctx context.Context, _ context.Context, _ *protection, _ model.Profile, _ map[string]any) (searchCore, *SearchWorkerProbes, error) {
		close(started)
		<-ctx.Done()
		return instance, nil, errors.New("search_start_interrupted")
	})
	profile, params := embeddedInput(t)
	done := make(chan error, 1)
	go func() { done <- worker.Open(profile, params, 1000, 1000) }()
	<-started
	worker.Cancel()
	if err := <-done; err == nil || instance.closes.Load() != 1 {
		t.Fatalf("cancel err=%v closes=%d", err, instance.closes.Load())
	}
}

func TestAndroidSearchEmbeddedCloseTimeoutDoesNotConfirmCleanup(t *testing.T) {
	_, guard := installFakeOwner(t)
	instance := &fakeEmbeddedCore{closeEntered: make(chan struct{}), closeRelease: make(chan struct{})}
	worker := newSearchEmbeddedWorker(guard, func(_ context.Context, _ context.Context, _ *protection, _ model.Profile, _ map[string]any) (searchCore, *SearchWorkerProbes, error) {
		probes, err := newSearchWorkerProbes("127.0.0.1:1080", "user", "password")
		return instance, probes, err
	})
	profile, params := embeddedInput(t)
	if err := worker.Open(profile, params, 1000, 1000); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- worker.Close(1) }()
	<-instance.closeEntered
	if err := <-done; err == nil || err.Error() != "worker_cleanup_incomplete" {
		t.Fatalf("timeout confirmed cleanup: %v", err)
	}
	close(instance.closeRelease)
	if err := worker.Close(1000); err != nil || instance.closes.Load() != 1 {
		t.Fatal("later cleanup confirmation failed", err)
	}
}

func TestAndroidSearchEmbeddedUnrecognizedStartErrorIsSanitized(t *testing.T) {
	_, guard := installFakeOwner(t)
	worker := newSearchEmbeddedWorker(guard, func(context.Context, context.Context, *protection, model.Profile, map[string]any) (searchCore, *SearchWorkerProbes, error) {
		return nil, nil, errors.New("private payload must not escape")
	})
	profile, params := embeddedInput(t)
	if err := worker.Open(profile, params, 1000, 1000); err == nil || err.Error() != "search_worker_unavailable" {
		t.Fatalf("unrecognized error was not sanitized: %v", err)
	}
}

func TestAndroidSearchEmbeddedAttemptExpiryCancelsProbes(t *testing.T) {
	_, guard := installFakeOwner(t)
	instance := &fakeEmbeddedCore{}
	worker := newSearchEmbeddedWorker(guard, func(_ context.Context, _ context.Context, _ *protection, _ model.Profile, _ map[string]any) (searchCore, *SearchWorkerProbes, error) {
		probes, err := newSearchWorkerProbes("127.0.0.1:1080", "user", "password")
		return instance, probes, err
	})
	worker.cancel()
	worker.ctx, worker.cancel = context.WithTimeout(guard.searchContext, 100*time.Millisecond)
	profile, params := embeddedInput(t)
	if err := worker.Open(profile, params, 1000, 1000); err != nil {
		t.Fatal(err)
	}
	probes, err := worker.Probes()
	if err != nil {
		t.Fatal(err)
	}
	<-worker.ctx.Done()
	select {
	case <-probes.ctx.Done():
	case <-time.After(time.Second):
		t.Fatal("expired attempt did not cancel probes")
	}
	if _, err := worker.Probes(); err == nil {
		t.Fatal("expired attempt exposed probes")
	}
	if err := worker.Close(1000); err != nil || instance.closes.Load() != 1 {
		t.Fatal("expired attempt was not cleaned", err)
	}
}

func TestAndroidSearchEmbeddedStatusPreservesTypedRetryability(t *testing.T) {
	for _, retry := range []bool{false, true} {
		_, guard := installFakeOwner(t)
		worker := newSearchEmbeddedWorker(guard, func(context.Context, context.Context, *protection, model.Profile, map[string]any) (searchCore, *SearchWorkerProbes, error) {
			return nil, nil, &model.Error{Code: "core_start_failed", Phase: "core", Retryable: retry}
		})
		profile, params := embeddedInput(t)
		want := SearchWorkerCandidateStartFailed
		if retry {
			want |= SearchWorkerRetryableFlag
		}
		if status := worker.OpenStatus(profile, params, 1000, 1000); status != want {
			t.Fatalf("retry=%v status=%d want=%d", retry, status, want)
		}
	}
}

func TestAndroidSearchEmbeddedBeginDefersCandidateCleanup(t *testing.T) {
	_, guard := installFakeOwner(t)
	instance := &fakeEmbeddedCore{}
	worker := newSearchEmbeddedWorker(guard, func(context.Context, context.Context, *protection, model.Profile, map[string]any) (searchCore, *SearchWorkerProbes, error) {
		return instance, nil, &model.Error{Code: "core_start_failed", Phase: "core", Retryable: true}
	})
	profile, params := embeddedInput(t)
	status := worker.BeginOpenStatus(profile, params, 1000)
	if status != SearchWorkerCandidateStartFailed|SearchWorkerRetryableFlag {
		t.Fatalf("candidate status=%d", status)
	}
	if instance.closes.Load() != 0 {
		t.Fatal("Begin cleaned the core before the coordinator requested Close")
	}
	if !errors.Is(worker.ctx.Err(), context.Canceled) || !searchCurrent(guard) {
		t.Fatal("failed Begin did not cancel work while retaining its owner")
	}
	if _, err := worker.Probes(); err == nil {
		t.Fatal("failed Begin exposed probes")
	}
	if err := worker.Close(1000); err != nil || instance.closes.Load() != 1 {
		t.Fatal("explicit Close did not clean the core", err)
	}
}

func TestAndroidSearchEmbeddedBeginReadyAndDuplicateAdmission(t *testing.T) {
	_, guard := installFakeOwner(t)
	instance := &fakeEmbeddedCore{}
	worker := newSearchEmbeddedWorker(guard, func(_ context.Context, _ context.Context, _ *protection, _ model.Profile, _ map[string]any) (searchCore, *SearchWorkerProbes, error) {
		probes, err := newSearchWorkerProbes("127.0.0.1:1080", "user", "password")
		return instance, probes, err
	})
	profile, params := embeddedInput(t)
	if status := worker.BeginOpenStatus(profile, params, 1000); status != SearchWorkerReady {
		t.Fatalf("ready status=%d", status)
	}
	if status := worker.BeginOpenStatus(profile, params, 1000); status != SearchWorkerBusy {
		t.Fatalf("duplicate status=%d", status)
	}
	if _, err := worker.Probes(); err != nil || worker.ctx.Err() != nil || instance.closes.Load() != 0 {
		t.Fatal("duplicate admission disrupted the active worker")
	}
	if err := worker.Close(1000); err != nil || instance.closes.Load() != 1 {
		t.Fatal("explicit Close did not clean the ready core", err)
	}
}

func TestAndroidSearchEmbeddedBeginCancellationWaitsForExplicitCleanup(t *testing.T) {
	_, guard := installFakeOwner(t)
	instance := &fakeEmbeddedCore{}
	entered := make(chan struct{})
	release := make(chan struct{})
	worker := newSearchEmbeddedWorker(guard, func(context.Context, context.Context, *protection, model.Profile, map[string]any) (searchCore, *SearchWorkerProbes, error) {
		close(entered)
		<-release
		return instance, nil, nil
	})
	profile, params := embeddedInput(t)
	done := make(chan int, 1)
	go func() { done <- worker.BeginOpenStatus(profile, params, 10000) }()
	<-entered
	worker.Cancel()
	if status := <-done; status != SearchWorkerCancelled || instance.closes.Load() != 0 {
		close(release)
		t.Fatalf("cancel status=%d closes=%d", status, instance.closes.Load())
	}
	if err := worker.Close(10); err == nil || err.Error() != "worker_cleanup_incomplete" {
		close(release)
		t.Fatal("Close confirmed cleanup while startup was still blocked", err)
	}
	close(release)
	if err := worker.Close(1000); err != nil || instance.closes.Load() != 1 {
		t.Fatal("late core was not cleaned exactly once", err)
	}
}

func TestAndroidSearchEmbeddedBeginTimeoutKeepsLateCore(t *testing.T) {
	_, guard := installFakeOwner(t)
	instance := &fakeEmbeddedCore{}
	entered := make(chan struct{})
	release := make(chan struct{})
	worker := newSearchEmbeddedWorker(guard, func(context.Context, context.Context, *protection, model.Profile, map[string]any) (searchCore, *SearchWorkerProbes, error) {
		close(entered)
		<-release
		return instance, nil, nil
	})
	profile, params := embeddedInput(t)
	done := make(chan int, 1)
	go func() { done <- worker.BeginOpenStatus(profile, params, 100) }()
	<-entered
	if status := <-done; status != SearchWorkerCandidateStartTimeout || instance.closes.Load() != 0 {
		close(release)
		t.Fatalf("timeout status=%d closes=%d", status, instance.closes.Load())
	}
	if err := worker.Close(10); err == nil || err.Error() != "worker_cleanup_incomplete" {
		close(release)
		t.Fatal("Close confirmed cleanup while startup was still blocked", err)
	}
	close(release)
	if err := worker.Close(1000); err != nil || instance.closes.Load() != 1 {
		t.Fatal("timed out core was not cleaned exactly once", err)
	}
}

func TestAndroidSearchEmbeddedBeginCapabilityUnavailableIsProvisional(t *testing.T) {
	_, guard := installFakeOwner(t)
	worker := newSearchEmbeddedWorker(guard, func(context.Context, context.Context, *protection, model.Profile, map[string]any) (searchCore, *SearchWorkerProbes, error) {
		return nil, nil, &searchEmbeddedError{SearchWorkerCapabilityUnavailable}
	})
	profile, params := embeddedInput(t)
	if status := worker.BeginOpenStatus(profile, params, 1000); status != SearchWorkerCapabilityUnavailable {
		t.Fatalf("capability status=%d", status)
	}
	if !errors.Is(worker.ctx.Err(), context.Canceled) || !searchCurrent(guard) {
		t.Fatal("capability failure did not cancel work while retaining its owner")
	}
	if err := worker.Close(1000); err != nil {
		t.Fatal("explicit Close failed", err)
	}
}

func TestAndroidSearchEmbeddedBeginDeadlineAndOwnerLossAreDistinct(t *testing.T) {
	t.Run("deadline", func(t *testing.T) {
		_, guard := installFakeOwner(t)
		worker := newSearchEmbeddedWorker(guard, func(context.Context, context.Context, *protection, model.Profile, map[string]any) (searchCore, *SearchWorkerProbes, error) {
			t.Error("expired attempt launched startup")
			return nil, nil, nil
		})
		worker.cancel()
		worker.ctx, worker.cancel = context.WithDeadline(guard.searchContext, time.Unix(0, 0))
		profile, params := embeddedInput(t)
		if status := worker.BeginOpenStatus(profile, params, 1000); status != SearchWorkerDeadline {
			t.Fatalf("deadline status=%d", status)
		}
		if err := worker.Close(1000); err != nil {
			t.Fatal(err)
		}
	})
	t.Run("owner_lost", func(t *testing.T) {
		owner, guard := installFakeOwner(t)
		owner.current = false
		worker := newSearchEmbeddedWorker(guard, func(context.Context, context.Context, *protection, model.Profile, map[string]any) (searchCore, *SearchWorkerProbes, error) {
			t.Error("lost owner launched startup")
			return nil, nil, nil
		})
		profile, params := embeddedInput(t)
		if status := worker.BeginOpenStatus(profile, params, 1000); status != SearchWorkerOwnerLost {
			t.Fatalf("owner status=%d", status)
		}
		if err := worker.Close(1000); err != nil {
			t.Fatal(err)
		}
	})
}

func TestAndroidSearchEmbeddedBeginCoreCloseFailureQuarantines(t *testing.T) {
	_, guard := installFakeOwner(t)
	instance := &fakeSearchRunningCore{closeErr: errors.New("synthetic_private_close")}
	worker := newSearchEmbeddedWorker(guard, func(context.Context, context.Context, *protection, model.Profile, map[string]any) (searchCore, *SearchWorkerProbes, error) {
		return instance, nil, model.Err("core_start_failed", "core")
	})
	profile, params := embeddedInput(t)
	if status := worker.BeginOpenStatus(profile, params, 1000); status != SearchWorkerCandidateStartFailed || instance.closes.Load() != 0 {
		t.Fatalf("candidate status=%d", status)
	}
	if err := worker.Close(1000); err == nil || err.Error() != "worker_cleanup_incomplete" {
		t.Fatal("failed core close was accepted", err)
	}
	if err := worker.Close(1000); err == nil || instance.closes.Load() != 1 {
		t.Fatal("failed core close did not stay quarantined", err)
	}
}

func TestAndroidSearchEmbeddedBeginConcurrentCloseOwnsLateStart(t *testing.T) {
	_, guard := installFakeOwner(t)
	instance := &fakeEmbeddedCore{}
	entered := make(chan struct{})
	release := make(chan struct{})
	worker := newSearchEmbeddedWorker(guard, func(context.Context, context.Context, *protection, model.Profile, map[string]any) (searchCore, *SearchWorkerProbes, error) {
		close(entered)
		<-release
		return instance, nil, nil
	})
	profile, params := embeddedInput(t)
	beginDone := make(chan int, 1)
	go func() { beginDone <- worker.BeginOpenStatus(profile, params, 10000) }()
	<-entered
	closeDone := make(chan error, 1)
	go func() { closeDone <- worker.Close(1000) }()
	<-worker.ctx.Done()
	if status := <-beginDone; status != SearchWorkerCancelled || instance.closes.Load() != 0 {
		close(release)
		<-closeDone
		t.Fatalf("concurrent close status=%d", status)
	}
	close(release)
	if err := <-closeDone; err != nil || instance.closes.Load() != 1 {
		t.Fatal("concurrent Close did not own late core", err)
	}
}

func TestAndroidSearchEmbeddedBeginUnknownCleanupQuarantines(t *testing.T) {
	for _, panicStart := range []bool{false, true} {
		t.Run(fmt.Sprint(panicStart), func(t *testing.T) {
			_, guard := installFakeOwner(t)
			worker := newSearchEmbeddedWorker(guard, func(context.Context, context.Context, *protection, model.Profile, map[string]any) (searchCore, *SearchWorkerProbes, error) {
				if panicStart {
					panic("synthetic_private_data")
				}
				return nil, nil, errSearchCoreCreation
			})
			profile, params := embeddedInput(t)
			if status := worker.BeginOpenStatus(profile, params, 1000); status != SearchWorkerUnavailable {
				t.Fatalf("unknown creation status=%d", status)
			}
			if err := worker.Close(1000); err == nil || err.Error() != "worker_cleanup_incomplete" {
				t.Fatal("unknown creation cleanup was confirmed", err)
			}
			if err := worker.Close(1000); err == nil {
				t.Fatal("unknown cleanup lost quarantine")
			}
		})
	}
}

func TestAndroidSearchEmbeddedBeginLateUnknownCreationRemainsQuarantined(t *testing.T) {
	_, guard := installFakeOwner(t)
	entered := make(chan struct{})
	release := make(chan struct{})
	worker := newSearchEmbeddedWorker(guard, func(context.Context, context.Context, *protection, model.Profile, map[string]any) (searchCore, *SearchWorkerProbes, error) {
		close(entered)
		<-release
		return nil, nil, errSearchCoreCreation
	})
	profile, params := embeddedInput(t)
	done := make(chan int, 1)
	go func() { done <- worker.BeginOpenStatus(profile, params, 10000) }()
	<-entered
	worker.Cancel()
	if status := <-done; status != SearchWorkerCancelled {
		close(release)
		t.Fatalf("cancel status=%d", status)
	}
	if err := worker.Close(10); err == nil || err.Error() != "worker_cleanup_incomplete" {
		close(release)
		t.Fatal("Close confirmed a blocked creation", err)
	}
	close(release)
	if err := worker.Close(1000); err == nil || err.Error() != "worker_cleanup_incomplete" {
		t.Fatal("late creation failure lost quarantine", err)
	}
}
