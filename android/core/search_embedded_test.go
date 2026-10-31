package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"sync/atomic"
	"testing"

	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

type fakeEmbeddedCore struct {
	closes       atomic.Int32
	closeEntered chan struct{}
	closeRelease chan struct{}
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
	worker := newSearchEmbeddedWorker(guard, func(_ context.Context, _ *protection, _ model.Profile, _ map[string]any) (searchCore, *SearchWorkerProbes, error) {
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
	worker := newSearchEmbeddedWorker(guard, func(context.Context, *protection, model.Profile, map[string]any) (searchCore, *SearchWorkerProbes, error) {
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
	worker := newSearchEmbeddedWorker(guard, func(context.Context, *protection, model.Profile, map[string]any) (searchCore, *SearchWorkerProbes, error) {
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
	worker := newSearchEmbeddedWorker(guard, func(ctx context.Context, _ *protection, _ model.Profile, _ map[string]any) (searchCore, *SearchWorkerProbes, error) {
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
	worker := newSearchEmbeddedWorker(guard, func(_ context.Context, _ *protection, _ model.Profile, _ map[string]any) (searchCore, *SearchWorkerProbes, error) {
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

func TestAndroidSearchEmbeddedProductionStartFailsClosedWithoutResolver(t *testing.T) {
	owner, guard := installFakeOwner(t)
	worker := newSearchEmbeddedWorker(guard, startSearchEmbedded)
	profile, params := embeddedInput(t)
	if err := worker.Open(profile, params, 1000, 1000); err == nil || err.Error() != "search_resolver_unavailable" {
		t.Fatalf("unavailable resolver was accepted: %v", err)
	}
	if len(owner.roles) != 0 {
		t.Fatal("disabled start created a socket")
	}
}
