package mobile

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/android/core/searchbridge"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

type controlledWorkerDeadline struct {
	deadline    time.Time
	done        chan struct{}
	observed    chan struct{}
	checked     chan struct{}
	returned    *atomic.Bool
	once        sync.Once
	checkedOnce sync.Once
	mu          sync.Mutex
	err         error
}

func (c *controlledWorkerDeadline) Deadline() (time.Time, bool) {
	if c.returned != nil && c.returned.Load() {
		c.checkedOnce.Do(func() { close(c.checked) })
	}
	return c.deadline, true
}
func (c *controlledWorkerDeadline) Done() <-chan struct{} {
	c.once.Do(func() { close(c.observed) })
	return c.done
}
func (c *controlledWorkerDeadline) Err() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.err
}
func (*controlledWorkerDeadline) Value(any) any { return nil }
func (c *controlledWorkerDeadline) finish(err error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.err == nil {
		c.err = err
		close(c.done)
	}
}

type deadlineReplyWorker struct {
	fakeEngineWorker
	calls    atomic.Int32
	returned atomic.Bool
	until    time.Time
}

func (w *deadlineReplyWorker) Call(_ int64, _ int, _ []byte, _ int, _ int64) []byte {
	w.calls.Add(1)
	if !w.until.IsZero() {
		timer := time.NewTimer(time.Until(w.until))
		defer timer.Stop()
		<-timer.C
	}
	w.returned.Store(true)
	return []byte(`{"fault":13}`)
}

func TestAndroidSearchWorkerCallDeadlineBeforeContext(t *testing.T) {
	operations := []struct {
		name string
		call func(*androidSearchRound, context.Context) error
	}{
		{name: "check", call: func(round *androidSearchRound, ctx context.Context) error {
			_, err := round.Check(ctx, model.Probe{ID: "synthetic"}, 0)
			return err
		}},
		{name: "udp", call: func(round *androidSearchRound, ctx context.Context) error {
			_, err := round.UDP(ctx, model.UDPProbe{ID: "synthetic"}, 0)
			return err
		}},
		{name: "media", call: func(round *androidSearchRound, ctx context.Context) error {
			_, err := round.Media(ctx, model.Policy{}, 0)
			return err
		}},
	}
	for _, operation := range operations {
		for _, cause := range []error{context.DeadlineExceeded, context.Canceled} {
			t.Run(operation.name+"/"+cause.Error(), func(t *testing.T) {
				deadline := time.Now().Add(100 * time.Millisecond)
				worker := &deadlineReplyWorker{until: deadline.Add(-time.Millisecond)}
				ctx := &controlledWorkerDeadline{deadline: deadline, done: make(chan struct{}), observed: make(chan struct{}), checked: make(chan struct{}), returned: &worker.returned}
				defer ctx.finish(context.Canceled)
				round := &androidSearchRound{callback: worker, generation: 1}
				done := make(chan error, 1)
				go func() { done <- operation.call(round, ctx) }()
				select {
				case <-ctx.checked:
				case err := <-done:
					t.Fatalf("deadline became fatal while the context was live: %v", err)
				case <-time.After(2 * time.Second):
					t.Fatal("fake deadline reply was not processed")
				}
				ctx.finish(cause)
				if err := <-done; !errors.Is(err, cause) || !errors.Is(err, ctx.Err()) {
					t.Fatalf("context cause was not preserved: %v", err)
				}
				if worker.calls.Load() != 1 {
					t.Fatal("fake worker was not called exactly once")
				}
			})
		}
	}
}

func TestAndroidSearchWorkerZeroMillisDoesNotDispatch(t *testing.T) {
	ctx := &controlledWorkerDeadline{deadline: time.Now().Add(-time.Millisecond), done: make(chan struct{}), observed: make(chan struct{}), checked: make(chan struct{})}
	defer ctx.finish(context.Canceled)
	worker := &deadlineReplyWorker{}
	round := &androidSearchRound{callback: worker, generation: 1}
	done := make(chan error, 1)
	go func() {
		var reply probeReply
		done <- round.call(ctx, SearchWorkerCheck, model.Probe{ID: "synthetic"}, 0, &reply)
	}()
	select {
	case <-ctx.observed:
	case <-time.After(2 * time.Second):
		t.Fatal("context was not observed")
	}
	ctx.finish(context.DeadlineExceeded)
	if err := <-done; !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("zero-millisecond budget lost context deadline: %v", err)
	}
	if worker.calls.Load() != 0 {
		t.Fatal("worker called with a zero-millisecond budget")
	}
}

func TestAndroidSearchWorkerEarlyDeadlineRemainsUnknown(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	if err := searchWorkerCallError(SearchWorkerDeadline, ctx); !errors.Is(err, searchbridge.ErrWorkerUnavailable) {
		t.Fatalf("unexpected early worker deadline accepted: %v", err)
	}
	if err := searchWorkerCallError(SearchWorkerDeadline, context.Background()); !errors.Is(err, searchbridge.ErrWorkerUnavailable) {
		t.Fatalf("worker deadline accepted without a context deadline: %v", err)
	}
	if err := searchWorkerCallError(SearchWorkerCancelled, ctx); !errors.Is(err, searchbridge.ErrWorkerUnavailable) {
		t.Fatalf("unexpected cancellation accepted: %v", err)
	}
	cancel()
	if err := searchWorkerCallError(SearchWorkerDeadline, ctx); !errors.Is(err, context.Canceled) {
		t.Fatalf("parent cancellation was lost: %v", err)
	}
	if err := searchWorkerCallError(SearchWorkerCleanupIncomplete, ctx); !errors.Is(err, searchbridge.ErrCleanupIncomplete) {
		t.Fatalf("unknown cleanup was hidden: %v", err)
	}
}
