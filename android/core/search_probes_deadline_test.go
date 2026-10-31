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
