package mobile

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/android/core/searchbridge"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

type interruptedCallWorker struct {
	fakeEngineWorker
	entered chan struct{}
	release chan struct{}
	fault   int
}

type triggeredDeadline struct{ done chan struct{} }

func (*triggeredDeadline) Deadline() (time.Time, bool) { return time.Time{}, false }
func (c *triggeredDeadline) Done() <-chan struct{}     { return c.done }
func (c *triggeredDeadline) Err() error {
	select {
	case <-c.done:
		return context.DeadlineExceeded
	default:
		return nil
	}
}
func (*triggeredDeadline) Value(any) any { return nil }

func (w *interruptedCallWorker) Call(_ int64, _ int, _ []byte, _ int, _ int64) []byte {
	close(w.entered)
	<-w.release
	return []byte(fmt.Sprintf(`{"fault":%d}`, w.fault))
}

func TestAndroidSearchCallInterruptionPreservesUnknownDelivery(t *testing.T) {
	operations := []struct {
		name string
		call func(*androidSearchRound, context.Context) error
	}{
		{"check", func(r *androidSearchRound, ctx context.Context) error {
			_, err := r.Check(ctx, model.Probe{ID: "synthetic"}, 0)
			return err
		}},
		{"udp", func(r *androidSearchRound, ctx context.Context) error {
			_, err := r.UDP(ctx, model.UDPProbe{ID: "synthetic"}, 0)
			return err
		}},
		{"media", func(r *androidSearchRound, ctx context.Context) error {
			_, err := r.Media(ctx, model.Policy{}, 0)
			return err
		}},
	}
	for _, operation := range operations {
		for _, tc := range []struct {
			name  string
			fault int
			want  error
		}{
			{"cancelled", SearchWorkerCancelled, context.Canceled},
			{"unknown", SearchWorkerDeliveryUnknown, searchbridge.ErrUnknownDelivery},
		} {
			t.Run(operation.name+"/"+tc.name, func(t *testing.T) {
				ctx, cancel := context.WithCancel(context.Background())
				defer cancel()
				worker := &interruptedCallWorker{entered: make(chan struct{}), release: make(chan struct{}), fault: tc.fault}
				round := &androidSearchRound{callback: worker, generation: 1}
				result := make(chan error, 1)
				go func() { result <- operation.call(round, ctx) }()
				<-worker.entered
				cancel()
				close(worker.release)
				if err := <-result; !errors.Is(err, tc.want) {
					t.Fatalf("got %v, want %v", err, tc.want)
				}
			})
		}
	}
}

func TestAndroidSearchCallDeadlineNeedsEndedContext(t *testing.T) {
	ctx := &triggeredDeadline{done: make(chan struct{})}
	worker := &interruptedCallWorker{entered: make(chan struct{}), release: make(chan struct{}), fault: SearchWorkerDeadline}
	round := &androidSearchRound{callback: worker, generation: 1}
	result := make(chan error, 1)
	go func() { _, err := round.Check(ctx, model.Probe{ID: "synthetic"}, 0); result <- err }()
	<-worker.entered
	close(ctx.done)
	close(worker.release)
	if err := <-result; !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("got %v, want deadline", err)
	}

	worker = &interruptedCallWorker{entered: make(chan struct{}), release: make(chan struct{}), fault: SearchWorkerCancelled}
	round = &androidSearchRound{callback: worker, generation: 2}
	result = make(chan error, 1)
	go func() { _, err := round.Check(context.Background(), model.Probe{ID: "synthetic"}, 0); result <- err }()
	<-worker.entered
	close(worker.release)
	if err := <-result; !errors.Is(err, searchbridge.ErrWorkerUnavailable) {
		t.Fatalf("unsolicited interruption should be fatal, got %v", err)
	}
}
