package mobile

import (
	"context"
	"errors"
	"sync"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

const (
	maxSearchStartupMillis = 300 * 1000
	maxSearchCleanupMillis = 60 * 1000
)

type searchCore interface{ Close() error }
type searchStarter func(context.Context, *protection, model.Profile, map[string]any) (searchCore, *SearchWorkerProbes, error)

var errSearchCoreCreation = errors.New("search_core_initialization_failed")

type SearchEmbeddedWorker struct {
	mu                 sync.Mutex
	owner              *protection
	ctx                context.Context
	cancel             context.CancelFunc
	start              searchStarter
	started            bool
	closing            bool
	ready              bool
	openDone           chan struct{}
	closeDone          chan struct{}
	core               searchCore
	probes             *SearchWorkerProbes
	closeErr           error
	unknownCoreCleanup bool
}

func NewSearchEmbeddedWorker(attemptRemainingMillis int64) (*SearchEmbeddedWorker, error) {
	runtimeState.Lock()
	defer runtimeState.Unlock()
	owner := activeProtection.Load()
	if !runtimeState.searchInitialized || runtimeState.searchWorkerCreated || !searchCurrent(owner) || owner.searchContext == nil ||
		attemptRemainingMillis < 1 || attemptRemainingMillis > int64(86400*time.Second/time.Millisecond) {
		return nil, errors.New("search_admission_failed")
	}
	worker := newSearchEmbeddedWorker(owner, startSearchEmbedded)
	worker.cancel()
	worker.ctx, worker.cancel = context.WithTimeout(owner.searchContext, time.Duration(attemptRemainingMillis)*time.Millisecond)
	owner.searchRoundMu.Lock()
	owner.searchRound = worker.ctx
	owner.searchRoundMu.Unlock()
	runtimeState.searchWorkerCreated = true
	return worker, nil
}

func newSearchEmbeddedWorker(owner *protection, start searchStarter) *SearchEmbeddedWorker {
	ctx, cancel := context.WithCancel(owner.searchContext)
	return &SearchEmbeddedWorker{owner: owner, ctx: ctx, cancel: cancel, start: start, openDone: make(chan struct{}), closeDone: make(chan struct{})}
}

func (w *SearchEmbeddedWorker) Open(profileBytes, parameterBytes []byte, startupMillis, cleanupMillis int64) error {
	w.mu.Lock()
	if w.started || w.closing {
		w.mu.Unlock()
		return errors.New("search_worker_already_used")
	}
	w.started = true
	w.mu.Unlock()

	var profile model.Profile
	var params map[string]any
	var instance searchCore
	var probes *SearchWorkerProbes
	var err error
	if len(profileBytes) == 0 || len(profileBytes) > importer.MaxInputBytes ||
		model.Decode(profileBytes, &profile) != nil || model.Decode(parameterBytes, &params) != nil ||
		params == nil || importer.Validate(profile) != nil || capability.Check(profile, params) != nil ||
		startupMillis < 1 || startupMillis > maxSearchStartupMillis || cleanupMillis < 1 || cleanupMillis > maxSearchCleanupMillis {
		err = errors.New("invalid_search_input")
	} else if !searchCurrent(w.owner) {
		err = errors.New("search_owner_lost")
	} else {
		ctx, cancel := context.WithTimeout(w.ctx, time.Duration(startupMillis)*time.Millisecond)
		instance, probes, err = w.start(ctx, w.owner, profile, params)
		if err == nil && ctx.Err() != nil {
			err = errors.New("search_start_interrupted")
		}
		cancel()
	}
	current := searchCurrent(w.owner)
	w.mu.Lock()
	w.core, w.probes = instance, probes
	w.unknownCoreCleanup = errors.Is(err, errSearchCoreCreation)
	if err == nil && (instance == nil || probes == nil || w.closing || w.ctx.Err() != nil || !current) {
		err = errors.New("search_start_interrupted")
	}
	if err == nil {
		w.ready = true
	}
	close(w.openDone)
	w.mu.Unlock()
	if err != nil {
		if closeErr := w.Close(cleanupMillis); closeErr != nil {
			return closeErr
		}
		return err
	}
	return nil
}

func (w *SearchEmbeddedWorker) Probes() (*SearchWorkerProbes, error) {
	w.mu.Lock()
	if !w.ready || w.closing {
		w.mu.Unlock()
		return nil, errors.New("search_worker_not_ready")
	}
	probes := w.probes
	w.mu.Unlock()
	if !searchCurrent(w.owner) {
		return nil, errors.New("search_worker_not_ready")
	}
	return probes, nil
}

func (w *SearchEmbeddedWorker) Cancel() {
	w.mu.Lock()
	w.ready = false
	probes := w.probes
	w.mu.Unlock()
	w.cancel()
	if probes != nil {
		probes.Cancel()
	}
}

func (w *SearchEmbeddedWorker) WaitStopped() bool {
	<-w.ctx.Done()
	return !searchCurrent(w.owner)
}

// A successful close confirms in-process cleanup only; the coordinator must
// still wait for death of this worker process before starting another round.
func (w *SearchEmbeddedWorker) Close(cleanupMillis int64) error {
	if cleanupMillis < 1 || cleanupMillis > maxSearchCleanupMillis {
		return errors.New("invalid_cleanup_budget")
	}
	w.mu.Lock()
	if !w.closing {
		w.closing = true
		w.ready = false
		w.cancel()
		if !w.started {
			close(w.openDone)
		}
		go w.closeResources()
	}
	done := w.closeDone
	w.mu.Unlock()
	timer := time.NewTimer(time.Duration(cleanupMillis) * time.Millisecond)
	defer timer.Stop()
	select {
	case <-done:
		w.mu.Lock()
		defer w.mu.Unlock()
		return w.closeErr
	case <-timer.C:
		return errors.New("worker_cleanup_incomplete")
	}
}

func (w *SearchEmbeddedWorker) closeResources() {
	<-w.openDone
	w.mu.Lock()
	instance, probes, unknown := w.core, w.probes, w.unknownCoreCleanup
	w.mu.Unlock()
	if probes != nil {
		probes.Cancel()
	}
	var err error
	if unknown {
		err = errors.New("worker_cleanup_incomplete")
	}
	if instance != nil && instance.Close() != nil {
		err = errors.New("worker_cleanup_incomplete")
	}
	w.mu.Lock()
	w.closeErr = err
	close(w.closeDone)
	w.mu.Unlock()
}

func startSearchEmbedded(context.Context, *protection, model.Profile, map[string]any) (searchCore, *SearchWorkerProbes, error) {
	return nil, nil, errors.New("search_resolver_unavailable")
}
