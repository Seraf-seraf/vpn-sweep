package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
)

type blockedProbeOwner struct {
	entered chan struct{}
	release chan struct{}
	calls   atomic.Int32
}

func (o *blockedProbeOwner) Current() bool {
	if o.calls.Add(1) == 1 {
		close(o.entered)
		<-o.release
	}
	return true
}
func (*blockedProbeOwner) QueryDNS([]byte, int64) ([]byte, error) {
	return nil, errors.New("unexpected_dns")
}
func (*blockedProbeOwner) AllowSocket(int, int, string, string) bool { return false }

type countedProbeClient struct{ calls atomic.Int32 }

func (c *countedProbeClient) Check(context.Context, socks.Endpoint, model.Probe, int) model.ProbeResult {
	c.calls.Add(1)
	return model.ProbeResult{ID: "synthetic", Probe: "page", Outcome: "pass"}
}

type blockingProbeErrContext struct {
	context.Context
	entered chan struct{}
	release chan struct{}
}

func (c *blockingProbeErrContext) Err() error {
	close(c.entered)
	<-c.release
	return c.Context.Err()
}

func TestAndroidSearchProbeDeadlineIncludesBlockedOwnerCheck(t *testing.T) {
	owner := &blockedProbeOwner{entered: make(chan struct{}), release: make(chan struct{})}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	guard := &protection{search: owner, searchContext: ctx, searchCancel: cancel}
	previous := activeProtection.Swap(guard)
	defer activeProtection.Store(previous)
	checker := &countedProbeClient{}
	worker := &SearchWorkerProbes{ctx: ctx, cancel: cancel, owner: guard, check: checker}
	probe := model.Probe{ID: "page", Operator: "test", URL: "https://test.invalid", Kind: "page", Contains: "synthetic", Status: 200, TimeoutSeconds: 1}
	source, err := json.Marshal(probe)
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { _, err := worker.Check(source, 0, 1); done <- err }()
	<-owner.entered
	<-time.After(30 * time.Millisecond)
	close(owner.release)
	if err := <-done; !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("blocked owner extended probe deadline: %v", err)
	}
	if checker.calls.Load() != 0 {
		t.Fatal("expired probe dispatched after owner check")
	}
}

func TestAndroidSearchProbeRevokedOwnerIsNotOrdinaryCancellation(t *testing.T) {
	owner := &blockedProbeOwner{entered: make(chan struct{}), release: make(chan struct{})}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	guard := &protection{search: owner, searchContext: ctx, searchCancel: cancel}
	previous := activeProtection.Swap(guard)
	defer activeProtection.Store(previous)
	worker := &SearchWorkerProbes{ctx: ctx, cancel: cancel, owner: guard}
	guard.revokeSearch()

	for name, call := range map[string]func() error{
		"authenticate": func() error { return worker.Authenticate(1000) },
		"check":        func() error { _, err := worker.Check(nil, 0, 1000); return err },
		"udp":          func() error { _, err := worker.UDP(nil, 0, 1000); return err },
		"media":        func() error { _, err := worker.Media(nil, 0, 1000); return err },
	} {
		t.Run(name, func(t *testing.T) {
			if err := call(); err == nil || err.Error() != "search_owner_lost" {
				t.Fatalf("revoked owner reported as %v", err)
			}
		})
	}
	if owner.calls.Load() != 0 {
		t.Fatal("revoked owner callback was called")
	}
}

func TestAndroidSearchProbeCancelRetainsCancellation(t *testing.T) {
	owner := &blockedProbeOwner{entered: make(chan struct{}), release: make(chan struct{})}
	ownerCtx, ownerCancel := context.WithCancel(context.Background())
	defer ownerCancel()
	guard := &protection{search: owner, searchContext: ownerCtx, searchCancel: ownerCancel}
	previous := activeProtection.Swap(guard)
	defer activeProtection.Store(previous)
	ctx, cancel := context.WithCancel(ownerCtx)
	worker := &SearchWorkerProbes{ctx: ctx, cancel: cancel, owner: guard}
	worker.Cancel()
	if _, err := worker.Check(nil, 0, 1000); !errors.Is(err, context.Canceled) {
		t.Fatalf("explicit cancellation reported as %v", err)
	}
	if owner.calls.Load() != 0 {
		t.Fatal("cancelled probe called owner")
	}
}

func TestAndroidSearchProbeRevokedDuringContextError(t *testing.T) {
	owner := &fakeAndroidNetwork{current: true, allow: true}
	ownerCtx, ownerCancel := context.WithCancel(context.Background())
	defer ownerCancel()
	guard := &protection{search: owner, searchContext: ownerCtx, searchCancel: ownerCancel}
	previous := activeProtection.Swap(guard)
	defer activeProtection.Store(previous)
	child, childCancel := context.WithCancel(ownerCtx)
	defer childCancel()
	controlled := &blockingProbeErrContext{Context: child, entered: make(chan struct{}), release: make(chan struct{})}
	worker := &SearchWorkerProbes{ctx: controlled, cancel: childCancel, owner: guard}
	done := make(chan error, 1)
	go func() { _, err := worker.Check(nil, 0, 1000); done <- err }()
	select {
	case <-controlled.entered:
	case <-time.After(time.Second):
		close(controlled.release)
		t.Fatal("context error check not reached")
	}
	guard.revokeSearch()
	close(controlled.release)
	if err := <-done; err == nil || err.Error() != "search_owner_lost" {
		t.Fatalf("concurrent owner revocation reported as %v", err)
	}
}
