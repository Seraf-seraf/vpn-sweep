package search

import (
	"bufio"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/internal/clock"
	"github.com/Seraf-seraf/vpn-sweep/internal/media"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/probe"
	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
	"github.com/Seraf-seraf/vpn-sweep/internal/space"
	"github.com/Seraf-seraf/vpn-sweep/internal/state"
)

type testClock struct {
	mu                  sync.Mutex
	now                 time.Time
	step                time.Duration
	timers              []*testTimer
	waitReady           chan struct{}
	waitElapsed         chan struct{}
	firstCancelComplete chan struct{}
}

type testTimer struct {
	clock  *testClock
	at     time.Time
	f      func()
	active bool
}

func (c *testClock) Now() time.Time {
	c.mu.Lock()
	now, step := c.now, c.step
	c.mu.Unlock()
	if step > 0 {
		c.advance(step)
	}
	return now
}

func (c *testClock) advance(d time.Duration) {
	c.mu.Lock()
	target := c.now.Add(d)
	for {
		var next *testTimer
		for _, timer := range c.timers {
			if timer.active && !timer.at.After(target) && (next == nil || timer.at.Before(next.at)) {
				next = timer
			}
		}
		if next == nil {
			c.now = target
			c.mu.Unlock()
			return
		}
		if next.at.After(c.now) {
			c.now = next.at
		}
		next.active = false
		f := next.f
		c.mu.Unlock()
		f()
		c.mu.Lock()
	}
}

func (c *testClock) AfterFunc(d time.Duration, f func()) clock.Timer {
	c.mu.Lock()
	defer c.mu.Unlock()
	timer := &testTimer{clock: c, at: c.now.Add(d), f: f, active: true}
	c.timers = append(c.timers, timer)
	return timer
}

func (t *testTimer) Stop() bool {
	t.clock.mu.Lock()
	defer t.clock.mu.Unlock()
	active := t.active
	t.active = false
	return active
}

func (t *testTimer) Reset(d time.Duration) bool {
	t.clock.mu.Lock()
	defer t.clock.mu.Unlock()
	active := t.active
	t.at = t.clock.now.Add(d)
	t.active = true
	return active
}

func (c *testClock) Wait(ctx context.Context, d time.Duration) {
	if d <= 0 {
		return
	}
	done := make(chan struct{})
	timer := c.AfterFunc(d, func() {
		if c.waitElapsed != nil {
			close(c.waitElapsed)
		}
		close(done)
	})
	defer timer.Stop()
	if c.waitReady != nil {
		close(c.waitReady)
	}
	select {
	case <-ctx.Done():
	case <-done:
	}
}

type testDeadline struct {
	context.Context
	mu             sync.Mutex
	deadline       time.Time
	done           chan struct{}
	err            error
	timer          clock.Timer
	stopParent     func() bool
	cancelComplete chan struct{}
}

func (ctx *testDeadline) Deadline() (time.Time, bool) { return ctx.deadline, true }
func (ctx *testDeadline) Done() <-chan struct{}       { return ctx.done }
func (ctx *testDeadline) Err() error {
	ctx.mu.Lock()
	defer ctx.mu.Unlock()
	return ctx.err
}

func (ctx *testDeadline) cancel(err error) {
	ctx.mu.Lock()
	if ctx.err != nil {
		ctx.mu.Unlock()
		return
	}
	ctx.err = err
	close(ctx.done)
	timer, stopParent := ctx.timer, ctx.stopParent
	ctx.mu.Unlock()
	if timer != nil {
		timer.Stop()
	}
	if stopParent != nil {
		stopParent()
	}
	if ctx.cancelComplete != nil {
		close(ctx.cancelComplete)
	}
}

func (c *testClock) WithTimeout(parent context.Context, d time.Duration) (context.Context, context.CancelFunc) {
	c.mu.Lock()
	deadline := c.now.Add(d)
	cancelComplete := c.firstCancelComplete
	c.firstCancelComplete = nil
	c.mu.Unlock()
	parentDeadline, hasParentDeadline := parent.Deadline()
	inherited := hasParentDeadline && parentDeadline.Before(deadline)
	if inherited {
		deadline = parentDeadline
	}
	ctx := &testDeadline{Context: parent, deadline: deadline, done: make(chan struct{}), cancelComplete: cancelComplete}
	cancel := func() { ctx.cancel(context.Canceled) }
	if err := parent.Err(); err != nil {
		ctx.cancel(err)
		return ctx, cancel
	}
	if d <= 0 && !inherited {
		ctx.cancel(context.DeadlineExceeded)
		return ctx, cancel
	}
	if !inherited {
		ctx.timer = c.AfterFunc(d, func() { ctx.cancel(context.DeadlineExceeded) })
	}
	stopParent := context.AfterFunc(parent, func() { ctx.cancel(parent.Err()) })
	ctx.mu.Lock()
	if ctx.err == nil {
		ctx.stopParent = stopParent
		ctx.mu.Unlock()
	} else {
		ctx.mu.Unlock()
		stopParent()
	}
	return ctx, cancel
}

func (c *testClock) activeTimers() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	count := 0
	for _, timer := range c.timers {
		if timer.active {
			count++
		}
	}
	return count
}

func clockStore(t *testing.T, c clock.Clock) (model.Manifest, []model.Profile, *space.Generator, *state.Store) {
	t.Helper()
	m, profiles, g, original := setup(t)
	dir := original.Dir
	if err := original.Close(); err != nil {
		t.Fatal(err)
	}
	s, err := state.OpenWithClock(dir, 0, c)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return m, profiles, g, s
}

func journalEvents(t *testing.T, s *state.Store) []state.Event {
	t.Helper()
	f, err := os.Open(filepath.Join(s.Dir, "journal.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	var events []state.Event
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		var r struct {
			Event state.Event `json:"event"`
		}
		if err := json.Unmarshal(scanner.Bytes(), &r); err != nil {
			t.Fatal(err)
		}
		events = append(events, r.Event)
	}
	if err := scanner.Err(); err != nil {
		t.Fatal(err)
	}
	return events
}

func TestSearchUsesOneClockForAttemptsEventsAndProbes(t *testing.T) {
	const step = 17 * time.Millisecond
	start := time.Date(2030, 1, 2, 3, 4, 5, 0, time.FixedZone("test", 3*60*60))
	c := &testClock{now: start, step: step}
	m, profiles, g, s := clockStore(t, c)
	m.Budgets.SessionSeconds = 100
	m.Budgets.AttemptSeconds = 50
	m.Policy.UDP = []model.UDPProbe{{ID: "udp", Address: "192.0.2.1:9", TimeoutSeconds: 1}}
	closed := 0
	e := Engine{
		Open: func(context.Context, model.Profile, map[string]any) (Tunnel, error) {
			return tunnel{&closed}, nil
		},
		Check: func(ctx context.Context, ep socks.Endpoint, p model.Probe, round int) model.ProbeResult {
			measured := (probe.Client{}).Check(ctx, ep, model.Probe{}, round)
			if measured.Outcome != "invalid_probe" || measured.DurationMS != step.Milliseconds() {
				t.Fatalf("HTTPS validation duration: %+v", measured)
			}
			result := pass(ctx, ep, p, round)
			result.DurationMS = measured.DurationMS
			return result
		},
		UDP: func(ctx context.Context, ep socks.Endpoint, p model.UDPProbe, round int) model.ProbeResult {
			result := probe.UDPEcho(ctx, ep, p.ID, p.Address, round)
			dns := probe.UDPDNS(ctx, ep, p.ID, p.Address, round)
			if result.Outcome != "invalid_proxy_handle" || dns.Outcome != "invalid_proxy_handle" || result.DurationMS != step.Milliseconds() || dns.DurationMS != step.Milliseconds() {
				t.Fatalf("UDP durations: echo=%+v DNS=%+v", result, dns)
			}
			result.Outcome = "pass"
			return result
		},
		Export: func(model.Attempt) error { return nil },
	}
	status, err := e.Run(context.Background(), m, profiles, make([]byte, 32), g, s)
	if err != nil || status != "success" || closed != m.Policy.Rounds {
		t.Fatalf("status=%s closed=%d err=%v", status, closed, err)
	}
	events := journalEvents(t, s)
	if len(events) == 0 || events[0].Kind != "begin" || !events[0].Attempt.Started.Equal(start) || !events[0].At.Equal(start.Add(step)) {
		t.Fatalf("begin event: %+v", events)
	}
	var last time.Time
	probes := 0
	for _, event := range events {
		if event.At.Location() != time.UTC || (!last.IsZero() && !event.At.After(last)) {
			t.Fatalf("journal time is not ordered UTC: %+v", event)
		}
		last = event.At
		if event.Probe != nil {
			probes++
			if event.Probe.DurationMS != step.Milliseconds() {
				t.Fatalf("probe duration was not persisted: %+v", event.Probe)
			}
		}
	}
	if probes != 5 || c.activeTimers() != 0 {
		t.Fatalf("probes=%d active timers=%d", probes, c.activeTimers())
	}
}

func TestMediaRoundUsesSearchClock(t *testing.T) {
	const step = 23 * time.Millisecond
	c := &testClock{now: time.Date(2030, 1, 1, 0, 0, 0, 0, time.UTC), step: step}
	m, profiles, g, s := clockStore(t, c)
	m.Budgets.SessionSeconds = 100
	m.Budgets.AttemptSeconds = 50
	m.Policy = model.Policy{Version: media.Version, Rounds: 2, Video: &model.VideoPolicy{
		Required: []string{media.Transfer}, Samples: 1, MinMbps: 1, MaxReadGapMS: 1000, SampleBytes: 65536, TimeoutSeconds: 1,
		Reference: model.MediaReference{URL: "https://synthetic.invalid/video", Bytes: 65536, Provenance: "test", License: "test", Status: "unverified"},
	}}
	closed := 0
	e := Engine{
		Open: func(context.Context, model.Profile, map[string]any) (Tunnel, error) {
			return tunnel{&closed}, nil
		},
		Media: (media.Client{}).Round,
	}
	status, err := e.Run(context.Background(), m, profiles, make([]byte, 32), g, s)
	if err != nil || status != "capability_blocked" || closed != 1 {
		t.Fatalf("status=%s closed=%d err=%v", status, closed, err)
	}
	for _, event := range journalEvents(t, s) {
		if event.Probe != nil {
			if event.Probe.Outcome != "reference_unverified" || event.Probe.DurationMS != step.Milliseconds() {
				t.Fatalf("media result: %+v", event.Probe)
			}
			if c.activeTimers() != 0 {
				t.Fatalf("active timers: %d", c.activeTimers())
			}
			return
		}
	}
	t.Fatal("missing media result")
}

func TestClockDeadlinesKeepTimeoutOutcomesAndRetry(t *testing.T) {
	for _, tc := range []struct {
		name           string
		sessionSeconds int
		attemptSeconds int
		deadline       time.Duration
		wantRetry      bool
		wantEnd        string
	}{
		{name: "session", sessionSeconds: 3, attemptSeconds: 5, deadline: 3 * time.Second, wantRetry: true, wantEnd: "interrupted"},
		{name: "attempt", sessionSeconds: 5, attemptSeconds: 3, deadline: 3 * time.Second, wantEnd: "attempt_timeout"},
		{name: "same deadline", sessionSeconds: 3, attemptSeconds: 3, deadline: 3 * time.Second, wantRetry: true, wantEnd: "interrupted"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			start := time.Date(2030, 1, 1, 0, 0, 0, 0, time.UTC)
			c := &testClock{now: start}
			m, profiles, g, s := clockStore(t, c)
			m.Budgets.SessionSeconds = tc.sessionSeconds
			m.Budgets.AttemptSeconds = tc.attemptSeconds
			m.Budgets.Attempts = 1
			var candidateID string
			e := Engine{Open: func(ctx context.Context, _ model.Profile, _ map[string]any) (Tunnel, error) {
				candidateID = s.View().Pending.Candidate.ID
				deadline, ok := ctx.Deadline()
				if !ok || !deadline.Equal(start.Add(tc.deadline)) {
					t.Fatalf("deadline=%s ok=%v", deadline, ok)
				}
				c.advance(tc.deadline - time.Nanosecond)
				if ctx.Err() != nil {
					t.Fatalf("expired early: %v", ctx.Err())
				}
				c.advance(time.Nanosecond)
				<-ctx.Done()
				if ctx.Err() != context.DeadlineExceeded {
					t.Fatalf("deadline error: %v", ctx.Err())
				}
				c.advance(time.Nanosecond)
				if ctx.Err() != context.DeadlineExceeded {
					t.Fatalf("deadline error changed: %v", ctx.Err())
				}
				return nil, ctx.Err()
			}}
			status, err := e.Run(context.Background(), m, profiles, make([]byte, 32), g, s)
			v := s.View()
			if err != nil || status != "paused_budget" || (v.Retry != nil) != tc.wantRetry || s.Seen(candidateID) == tc.wantRetry {
				t.Fatalf("status=%s err=%v view=%+v", status, err, v)
			}
			var end string
			for _, event := range journalEvents(t, s) {
				if event.Kind == "end" {
					end = event.Status
				}
			}
			if end != tc.wantEnd || c.activeTimers() != 0 {
				t.Fatalf("end=%s active timers=%d", end, c.activeTimers())
			}
		})
	}
}

func TestSystemClockNestedExpiredAndCanceledContexts(t *testing.T) {
	deadline := time.Date(2000, 1, 1, 0, 0, 0, 0, time.UTC)
	expired, cancelExpired := context.WithDeadline(context.Background(), deadline)
	defer cancelExpired()
	child, stop := (clock.System{}).WithTimeout(expired, time.Hour)
	defer stop()
	if child.Err() != context.DeadlineExceeded {
		t.Fatalf("expired parent: %v", child.Err())
	}
	if got, ok := child.Deadline(); !ok || !got.Equal(deadline) {
		t.Fatalf("expired parent deadline: %s, %v", got, ok)
	}
	parent, cancelParent := context.WithCancel(context.Background())
	cancelParent()
	canceled, stopCanceled := (clock.System{}).WithTimeout(parent, time.Hour)
	defer stopCanceled()
	if canceled.Err() != context.Canceled {
		t.Fatalf("canceled parent: %v", canceled.Err())
	}
}

func TestClockPreservesParentDeadlineAndCancellation(t *testing.T) {
	c := &testClock{now: time.Now()}
	m, profiles, g, s := clockStore(t, c)
	m.Budgets.SessionSeconds = 3600
	m.Budgets.AttemptSeconds = 7200
	deadline := time.Now().Add(30 * time.Minute)
	parent, cancel := context.WithDeadline(context.Background(), deadline)
	defer cancel()
	e := Engine{Open: func(ctx context.Context, _ model.Profile, _ map[string]any) (Tunnel, error) {
		got, ok := ctx.Deadline()
		if !ok || !got.Equal(deadline) {
			t.Fatalf("parent deadline lost: %s, %v", got, ok)
		}
		cancel()
		<-ctx.Done()
		if ctx.Err() != context.Canceled {
			t.Fatalf("parent cancellation lost: %v", ctx.Err())
		}
		return nil, ctx.Err()
	}}
	status, err := e.Run(parent, m, profiles, make([]byte, 32), g, s)
	if err != nil || status != "interrupted" || s.View().Retry == nil || c.activeTimers() != 0 {
		t.Fatalf("status=%s err=%v view=%+v active timers=%d", status, err, s.View(), c.activeTimers())
	}
}

func TestSessionExpiryBeforeAttemptContextKeepsRetry(t *testing.T) {
	c := &testClock{now: time.Date(2030, 1, 1, 0, 0, 0, 0, time.UTC)}
	m, profiles, g, s := clockStore(t, c)
	m.Budgets.SessionSeconds = 1
	m.Budgets.AttemptSeconds = 2
	e := Engine{
		Progress: func(p Progress) {
			if p.Event == "attempt_started" {
				c.advance(time.Second)
			}
		},
		Open: func(context.Context, model.Profile, map[string]any) (Tunnel, error) {
			t.Fatal("opened tunnel after session expiry")
			return nil, nil
		},
	}
	status, err := e.Run(context.Background(), m, profiles, make([]byte, 32), g, s)
	if err != nil || status != "paused_budget" || s.View().Retry == nil || c.activeTimers() != 0 {
		t.Fatalf("status=%s err=%v view=%+v active timers=%d", status, err, s.View(), c.activeTimers())
	}
}

func TestClockWaitHonorsIntervalAndCancellation(t *testing.T) {
	for _, tc := range []struct {
		name       string
		intervalMS int
		action     string
		wantStatus string
	}{
		{name: "interval", intervalMS: 500, action: "interval", wantStatus: "paused_budget"},
		{name: "session deadline", intervalMS: 1500, action: "session", wantStatus: "paused_budget"},
		{name: "parent cancellation", intervalMS: 1500, action: "cancel", wantStatus: "interrupted"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			cancelComplete := make(chan struct{})
			c := &testClock{
				now:                 time.Date(2030, 1, 1, 0, 0, 0, 0, time.UTC),
				waitReady:           make(chan struct{}),
				waitElapsed:         make(chan struct{}),
				firstCancelComplete: cancelComplete,
			}
			m, profiles, g, s := clockStore(t, c)
			m.Budgets.SessionSeconds = 1
			m.Budgets.AttemptSeconds = 2
			m.Budgets.IntervalMS = tc.intervalMS
			m.Budgets.Attempts = 1
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			type result struct {
				status string
				err    error
			}
			done := make(chan result, 1)
			e := Engine{Open: func(context.Context, model.Profile, map[string]any) (Tunnel, error) {
				return nil, model.Err("core_start_failed", "core")
			}}
			go func() {
				status, err := e.Run(ctx, m, profiles, make([]byte, 32), g, s)
				done <- result{status: status, err: err}
			}()
			select {
			case <-c.waitReady:
			case got := <-done:
				t.Fatalf("search ended before interval wait: %+v", got)
			}
			c.advance(499 * time.Millisecond)
			select {
			case <-c.waitElapsed:
				t.Fatal("interval timer fired early")
			default:
			}
			select {
			case got := <-done:
				t.Fatalf("interval ended early: %+v", got)
			default:
			}
			switch tc.action {
			case "interval":
				c.advance(time.Millisecond)
				select {
				case <-c.waitElapsed:
				default:
					t.Fatal("interval timer did not fire at the boundary")
				}
			case "session":
				c.advance(501 * time.Millisecond)
			case "cancel":
				cancel()
			}
			got := <-done
			<-cancelComplete
			if got.err != nil || got.status != tc.wantStatus || c.activeTimers() != 0 {
				t.Fatalf("result=%+v active timers=%d", got, c.activeTimers())
			}
		})
	}
}
