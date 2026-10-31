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
type searchStarter func(context.Context, context.Context, *protection, model.Profile, map[string]any) (searchCore, *SearchWorkerProbes, error)

var (
	errSearchCoreCreation    = errors.New("search_core_initialization_failed")
	errSearchResourceCleanup = errors.New("worker_cleanup_incomplete")
	errInvalidSearchInput    = errors.New("invalid_search_input")
)

type searchEmbeddedError struct{ status int }

func (e *searchEmbeddedError) Error() string {
	switch e.status &^ SearchWorkerRetryableFlag {
	case SearchWorkerBusy:
		return "search_worker_already_used"
	case SearchWorkerOwnerLost:
		return "search_owner_lost"
	case SearchWorkerCleanupIncomplete:
		return "worker_cleanup_incomplete"
	case SearchWorkerCapabilityUnavailable:
		return "worker_capability_unavailable"
	case SearchWorkerCandidateConfigRejected:
		return "core_config_rejected"
	case SearchWorkerCandidateStartFailed:
		return "core_start_failed"
	case SearchWorkerCandidateStartTimeout:
		return "core_start_timeout"
	case SearchWorkerCandidateExitedBeforeReady:
		return "core_exited_before_ready"
	case SearchWorkerCancelled:
		return "search_start_interrupted"
	case SearchWorkerDeadline:
		return "search_attempt_deadline"
	default:
		return "search_worker_unavailable"
	}
}

func searchEmbeddedStatus(err error) int {
	if err == nil {
		return SearchWorkerReady
	}
	var result *searchEmbeddedError
	if errors.As(err, &result) {
		return result.status
	}
	return SearchWorkerUnavailable
}

func sanitizeSearchEmbeddedError(err error) error {
	var failure *model.Error
	if errors.As(err, &failure) && failure != nil {
		var status int
		switch failure.Code {
		case "core_config_rejected":
			status = SearchWorkerCandidateConfigRejected
		case "core_start_failed":
			status = SearchWorkerCandidateStartFailed
		case "core_start_timeout":
			status = SearchWorkerCandidateStartTimeout
		case "core_exited_before_ready":
			status = SearchWorkerCandidateExitedBeforeReady
		default:
			return &searchEmbeddedError{SearchWorkerUnavailable}
		}
		if failure.Retryable {
			status |= SearchWorkerRetryableFlag
		}
		return &searchEmbeddedError{status}
	}
	var result *searchEmbeddedError
	if errors.As(err, &result) && result != nil {
		return result
	}
	return &searchEmbeddedError{SearchWorkerUnavailable}
}

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
	startErr           error
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
	return w.open(profileBytes, parameterBytes, startupMillis, cleanupMillis)
}

func (w *SearchEmbeddedWorker) OpenStatus(profileBytes, parameterBytes []byte, startupMillis, cleanupMillis int64) int {
	return searchEmbeddedStatus(w.open(profileBytes, parameterBytes, startupMillis, cleanupMillis))
}

func (w *SearchEmbeddedWorker) open(profileBytes, parameterBytes []byte, startupMillis, cleanupMillis int64) error {
	w.mu.Lock()
	if w.started || w.closing {
		w.mu.Unlock()
		return &searchEmbeddedError{SearchWorkerBusy}
	}
	w.started = true
	w.mu.Unlock()
	startedAt := time.Now()

	var profile model.Profile
	var params map[string]any
	var err error
	launched := false
	if len(profileBytes) == 0 || len(profileBytes) > importer.MaxInputBytes ||
		model.Decode(profileBytes, &profile) != nil || model.Decode(parameterBytes, &params) != nil ||
		params == nil || importer.Validate(profile) != nil || capability.Check(profile, params) != nil ||
		startupMillis < 1 || startupMillis > maxSearchStartupMillis || cleanupMillis < 1 || cleanupMillis > maxSearchCleanupMillis {
		err = errInvalidSearchInput
	} else if searchEmbeddedOwnerLost(w.owner) {
		err = &searchEmbeddedError{SearchWorkerOwnerLost}
	} else {
		ctx, cancel := context.WithDeadline(w.ctx, startedAt.Add(time.Duration(startupMillis)*time.Millisecond))
		if ctx.Err() == nil {
			launched = true
			go w.startCore(ctx, profile, params)
			select {
			case <-w.openDone:
			case <-ctx.Done():
			}
		}
		if searchEmbeddedOwnerLost(w.owner) {
			err = &searchEmbeddedError{SearchWorkerOwnerLost}
		} else {
			w.mu.Lock()
			if errors.Is(w.ctx.Err(), context.DeadlineExceeded) {
				err = &searchEmbeddedError{SearchWorkerDeadline}
			} else if w.ctx.Err() != nil {
				err = &searchEmbeddedError{SearchWorkerCancelled}
			} else if errors.Is(ctx.Err(), context.DeadlineExceeded) {
				err = &searchEmbeddedError{SearchWorkerCandidateStartTimeout}
			} else if w.startErr != nil {
				err = sanitizeSearchEmbeddedError(w.startErr)
			} else if w.core == nil || w.probes == nil || w.closing {
				err = &searchEmbeddedError{SearchWorkerUnavailable}
			} else {
				w.ready = true
			}
			w.mu.Unlock()
		}
		cancel()
	}
	if err != nil {
		if !launched {
			w.mu.Lock()
			close(w.openDone)
			w.mu.Unlock()
		}
		if cleanupMillis < 1 || cleanupMillis > maxSearchCleanupMillis {
			w.cancel()
			return err
		}
		if closeErr := w.Close(cleanupMillis); closeErr != nil {
			return &searchEmbeddedError{SearchWorkerCleanupIncomplete}
		}
		return err
	}
	return nil
}

func searchEmbeddedOwnerLost(owner *protection) bool {
	if owner == nil || owner.search == nil {
		return true
	}
	if activeProtection.Load() != owner {
		owner.revokeSearch()
		return true
	}
	return owner.searchRevoked.Load()
}

func (w *SearchEmbeddedWorker) startCore(ctx context.Context, profile model.Profile, params map[string]any) {
	var instance searchCore
	var probes *SearchWorkerProbes
	var err error
	defer func() {
		if recover() != nil {
			instance, probes, err = nil, nil, errSearchCoreCreation
		}
		w.mu.Lock()
		w.core, w.probes, w.startErr = instance, probes, err
		w.unknownCoreCleanup = errors.Is(err, errSearchCoreCreation) || errors.Is(err, errSearchResourceCleanup)
		if probes != nil {
			context.AfterFunc(w.ctx, probes.Cancel)
		}
		close(w.openDone)
		w.mu.Unlock()
	}()
	if err = ctx.Err(); err != nil {
		return
	}
	if !searchCurrent(w.owner) {
		err = &searchEmbeddedError{SearchWorkerOwnerLost}
		return
	}
	if err = ctx.Err(); err != nil {
		return
	}
	instance, probes, err = w.start(ctx, w.ctx, w.owner, profile, params)
	if err == nil && ctx.Err() == nil && !searchCurrent(w.owner) {
		err = &searchEmbeddedError{SearchWorkerOwnerLost}
	}
}

func (w *SearchEmbeddedWorker) Probes() (*SearchWorkerProbes, error) {
	w.mu.Lock()
	if !w.ready || w.closing || w.ctx.Err() != nil {
		w.mu.Unlock()
		return nil, errors.New("search_worker_not_ready")
	}
	probes := w.probes
	w.mu.Unlock()
	if !searchCurrent(w.owner) || w.ctx.Err() != nil {
		return nil, errors.New("search_worker_not_ready")
	}
	return probes, nil
}

func (w *SearchEmbeddedWorker) Cancel() {
	w.mu.Lock()
	w.ready = false
	probes := w.probes
	w.cancel()
	w.mu.Unlock()
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
	var err error
	defer func() {
		if recover() != nil {
			err = errors.New("worker_cleanup_incomplete")
		}
		w.mu.Lock()
		w.closeErr = err
		close(w.closeDone)
		w.mu.Unlock()
	}()
	if probes != nil {
		probes.Cancel()
	}
	if unknown {
		err = errors.New("worker_cleanup_incomplete")
	}
	if instance != nil && instance.Close() != nil {
		err = errors.New("worker_cleanup_incomplete")
	}
}
