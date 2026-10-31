package searchbridge

import (
	"context"
	"errors"
	"sync"

	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/search"
	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
	"github.com/Seraf-seraf/vpn-sweep/internal/space"
	"github.com/Seraf-seraf/vpn-sweep/internal/state"
)

var (
	ErrBusy                  = errors.New("search_busy")
	ErrOwnerLost             = errors.New("search_owner_lost")
	ErrUnknownDelivery       = errors.New("worker_delivery_unknown")
	ErrWorkerDied            = errors.New("worker_died")
	ErrCleanupIncomplete     = errors.New("worker_cleanup_incomplete")
	ErrWorkerUnavailable     = errors.New("worker_unavailable")
	ErrCapabilityUnavailable = errors.New("worker_capability_unavailable")
	ErrInvalidSession        = errors.New("invalid_search_session")
)

type CandidateOpenReason string

const (
	CandidateConfigRejected    CandidateOpenReason = "core_config_rejected"
	CandidateStartFailed       CandidateOpenReason = "core_start_failed"
	CandidateStartTimeout      CandidateOpenReason = "core_start_timeout"
	CandidateExitedBeforeReady CandidateOpenReason = "core_exited_before_ready"
)

// CandidateOpenError is valid only after the worker has been cleaned up and
// its death confirmed, including when Open never returned a handle.
type CandidateOpenError struct {
	Reason    CandidateOpenReason
	Retryable bool
}

func (e *CandidateOpenError) Error() string { return string(e.Reason) }

func candidateOpenError(err error) (*model.Error, bool) {
	var candidate *CandidateOpenError
	if !errors.As(err, &candidate) || candidate == nil {
		return nil, false
	}
	switch candidate.Reason {
	case CandidateConfigRejected, CandidateStartFailed, CandidateStartTimeout, CandidateExitedBeforeReady:
		return &model.Error{Code: string(candidate.Reason), Phase: "core", Retryable: candidate.Retryable}, true
	default:
		return nil, false
	}
}

type Identity struct {
	SessionGeneration int64
	Attempt           uint64
	Round             int
	WorkerGeneration  uint64
}

type Worker interface {
	Open(context.Context, Identity, model.Profile, map[string]any) (Round, error)
}

// Close succeeds only after cleanup and death of the corresponding worker.
// Open owns cleanup or quarantine even when it returns an error.
type Round interface {
	Check(context.Context, model.Probe, int) (model.ProbeResult, error)
	UDP(context.Context, model.UDPProbe, int) (model.ProbeResult, error)
	Media(context.Context, model.Policy, int) ([]model.ProbeResult, error)
	Failure() error
	Close() error
}

type Runner struct {
	Manifest          model.Manifest
	Profiles          []model.Profile
	Key               []byte
	Generator         *space.Generator
	Store             *state.Store
	Worker            Worker
	Saved             map[string]bool
	Export            func(model.Attempt) error
	Progress          func(search.Progress)
	SessionGeneration int64

	mu         sync.Mutex
	running    bool
	generation uint64
	blocked    error
}

func (r *Runner) Run(parent context.Context) (string, error) {
	if parent == nil || r.Worker == nil || r.Store == nil || r.Generator == nil || r.Export == nil {
		return "invalid_input", ErrInvalidSession
	}
	r.mu.Lock()
	if r.blocked != nil {
		fault := r.blocked
		r.mu.Unlock()
		return "interrupted", fault
	}
	if r.running {
		r.mu.Unlock()
		return "busy", ErrBusy
	}
	r.running = true
	r.mu.Unlock()
	defer func() {
		r.mu.Lock()
		r.running = false
		r.mu.Unlock()
	}()

	ctx, cancel := context.WithCancelCause(parent)
	defer cancel(nil)
	var fatal error
	fail := func(err error) error {
		fault := workerFault(err)
		if fatal == nil {
			fatal = fault
		}
		cancel(fault)
		r.mu.Lock()
		r.blocked = fault
		r.mu.Unlock()
		return fault
	}
	var attempt uint64
	var number int
	var active *workerTunnel
	engine := search.Engine{
		Saved: r.Saved,
		Progress: func(p search.Progress) {
			if p.Event == "core_starting" {
				attempt, number = p.Attempt, p.Round
			}
			if r.Progress != nil {
				r.Progress(p)
			}
		},
		Open: func(ctx context.Context, profile model.Profile, params map[string]any) (search.Tunnel, error) {
			if err := ctx.Err(); err != nil {
				return nil, err
			}
			if active != nil || r.generation == ^uint64(0) {
				return nil, fail(ErrCleanupIncomplete)
			}
			r.generation++
			identity := Identity{r.SessionGeneration, attempt, number, r.generation}
			round, err := r.Worker.Open(ctx, identity, profile, params)
			if err != nil {
				if ctx.Err() != nil && errors.Is(err, ctx.Err()) {
					return nil, err
				}
				if candidate, ok := candidateOpenError(err); ok {
					if ctx.Err() != nil {
						return nil, ctx.Err()
					}
					return nil, candidate
				}
				return nil, fail(err)
			}
			if round == nil {
				return nil, fail(ErrWorkerUnavailable)
			}
			active = &workerTunnel{round: round, fail: fail, released: func() { active = nil }}
			return active, nil
		},
		Check: func(ctx context.Context, _ socks.Endpoint, p model.Probe, round int) model.ProbeResult {
			if ctx.Err() != nil {
				return interruptedProbe(p.ID, round)
			}
			if active == nil {
				fail(ErrWorkerUnavailable)
				return interruptedProbe(p.ID, round)
			}
			result, err := active.round.Check(ctx, p, round)
			if err != nil {
				if ctx.Err() == nil || !errors.Is(err, ctx.Err()) {
					fail(err)
				}
				return interruptedProbe(p.ID, round)
			}
			return result
		},
		UDP: func(ctx context.Context, _ socks.Endpoint, p model.UDPProbe, round int) model.ProbeResult {
			if ctx.Err() != nil {
				return interruptedProbe(p.ID, round)
			}
			if active == nil {
				fail(ErrWorkerUnavailable)
				return interruptedProbe(p.ID, round)
			}
			result, err := active.round.UDP(ctx, p, round)
			if err != nil {
				if ctx.Err() == nil || !errors.Is(err, ctx.Err()) {
					fail(err)
				}
				return interruptedProbe(p.ID, round)
			}
			return result
		},
		Media: func(ctx context.Context, _ socks.Endpoint, p model.Policy, round int) []model.ProbeResult {
			if ctx.Err() != nil {
				return nil
			}
			if active == nil {
				fail(ErrWorkerUnavailable)
				return nil
			}
			result, err := active.round.Media(ctx, p, round)
			if err != nil {
				if ctx.Err() == nil || !errors.Is(err, ctx.Err()) {
					fail(err)
				}
				return nil
			}
			return result
		},
		Export: r.Export,
	}
	status, err := engine.Run(ctx, r.Manifest, r.Profiles, r.Key, r.Generator, r.Store)
	if fatal != nil {
		return status, fatal
	}
	return status, err
}

type workerTunnel struct {
	round    Round
	fail     func(error) error
	released func()
}

func (t *workerTunnel) Endpoint() socks.Endpoint { return socks.Endpoint{} }

func (t *workerTunnel) Failure() error {
	if err := t.round.Failure(); err != nil {
		return t.fail(err)
	}
	return nil
}

func (t *workerTunnel) Close() error {
	if err := t.round.Close(); err != nil {
		if workerFault(err) == ErrWorkerUnavailable {
			return t.fail(ErrCleanupIncomplete)
		}
		return t.fail(err)
	}
	t.released()
	return nil
}

func interruptedProbe(id string, round int) model.ProbeResult {
	return model.ProbeResult{ID: model.ID(), Probe: id, Round: round, Transport: "worker", Outcome: "interrupted", Phase: "worker"}
}

func workerFault(err error) error {
	for _, fault := range []error{ErrBusy, ErrOwnerLost, ErrUnknownDelivery, ErrWorkerDied, ErrCleanupIncomplete, ErrWorkerUnavailable, ErrCapabilityUnavailable} {
		if errors.Is(err, fault) {
			return fault
		}
	}
	return ErrWorkerUnavailable
}
