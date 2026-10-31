package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"path/filepath"
	"sync"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/android/core/searchbridge"
	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/export"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/policy"
	"github.com/Seraf-seraf/vpn-sweep/internal/search"
	"github.com/Seraf-seraf/vpn-sweep/internal/space"
	"github.com/Seraf-seraf/vpn-sweep/internal/state"
)

const (
	SearchWorkerReady = iota
	SearchWorkerBusy
	SearchWorkerOwnerLost
	SearchWorkerDeliveryUnknown
	SearchWorkerDied
	SearchWorkerCleanupIncomplete
	SearchWorkerUnavailable
	SearchWorkerCapabilityUnavailable
	SearchWorkerCandidateConfigRejected
	SearchWorkerCandidateStartFailed
	SearchWorkerCandidateStartTimeout
	SearchWorkerCandidateExitedBeforeReady
	SearchWorkerCancelled
	SearchWorkerDeadline
)

const SearchWorkerRetryableFlag = 1 << 8

const (
	SearchWorkerCheck = iota + 1
	SearchWorkerUDP
	SearchWorkerMedia
)

// Android transports private profile and operation bytes using bounded FDs.
// Close reports success only after cleanup and confirmed worker death.
type SearchEngineWorker interface {
	Open(session int64, attempt int64, round int, generation int64, profile, params []byte, attemptRemainingMillis, startupRemainingMillis int64) int
	Call(generation int64, operation int, request []byte, round int, remainingMillis int64) []byte
	Failure(generation int64) int
	Close(generation int64, cleanupMillis int64) int
	Cancel(generation int64)
	Progress(kind int, outcome int, attempt int64, profile, round, probe int, bytes, durationMillis int64)
}

type SearchSession struct {
	mu            sync.Mutex
	runner        *searchbridge.Runner
	store         *state.Store
	stagingDir    string
	cancel        context.CancelFunc
	phaseEpoch    int64
	phaseContext  context.Context
	phaseCancel   context.CancelFunc
	closed        bool
	running       bool
	resultReady   bool
	resultClaimed bool
	openingResult bool
}

// NewSearchSession accepts selected normalized profiles, not subscription JSON.
// The caller supplies fresh app-private state and staging directories.
func NewSearchSession(generation int64, manifestBytes, profileBytes []byte, stateDir, stagingDir string, worker SearchEngineWorker) (*SearchSession, error) {
	if worker == nil || len(profileBytes) == 0 || len(profileBytes) > importer.MaxInputBytes {
		return nil, errors.New("invalid_search_input")
	}
	var manifest model.Manifest
	var profiles []model.Profile
	if model.Decode(manifestBytes, &manifest) != nil || model.Decode(profileBytes, &profiles) != nil ||
		len(profiles) == 0 || len(profiles) > 10000 || !model.SupportsProfileSchemaVersion(manifest.Version, profiles) ||
		(manifest.SearchVersion != model.SearchVersion && manifest.SearchVersion != model.LegacySearchVersion) ||
		(manifest.Environment != "unverified" && (manifest.Environment != "user_attested" || manifest.NetworkLabel == "")) ||
		!validSearchBudgets(manifest.Budgets) || capability.Gate(manifest.Mode, manifest.Core.Commit) != nil ||
		policy.Validate(manifest.Policy) != nil {
		return nil, errors.New("invalid_search_input")
	}
	for _, profile := range profiles {
		if importer.Validate(profile) != nil {
			return nil, errors.New("invalid_search_input")
		}
	}
	var spec space.Spec
	if model.Decode(manifest.Space, &spec) != nil {
		return nil, errors.New("invalid_search_space")
	}
	generator, err := space.New(spec)
	if err != nil {
		return nil, errors.New("invalid_search_space")
	}
	for _, profile := range profiles {
		serialized, err := json.Marshal(profile)
		if err != nil {
			return nil, errors.New("invalid_search_input")
		}
		if len(serialized) > importer.MaxInputBytes {
			return nil, errors.New("search_profile_size")
		}
	}
	if _, err = export.SafeDirectory(stagingDir); err != nil {
		return nil, errors.New("search_staging_unavailable")
	}
	saved, err := export.SavedCandidates(stagingDir)
	if err != nil {
		return nil, errors.New("search_staging_unavailable")
	}
	store, err := state.Open(stateDir, manifest.Budgets.JournalBytes)
	if err != nil {
		return nil, errors.New("search_state_unavailable")
	}
	key, err := state.IdentityKey(filepath.Dir(store.Dir))
	if err != nil {
		_ = store.Close()
		return nil, errors.New("search_identity_unavailable")
	}
	manifest.InputID = model.PrivateID(key, profiles)
	if err = store.SaveManifest(manifest, profiles, key); err != nil {
		_ = store.Close()
		return nil, errors.New("search_state_unavailable")
	}
	backend := &androidSearchWorker{callback: worker, startup: time.Duration(manifest.Budgets.StartupSeconds) * time.Second,
		cleanupMillis: int64(manifest.Budgets.CleanupSeconds) * 1000}
	runner := &searchbridge.Runner{
		SessionGeneration: generation, Manifest: manifest, Profiles: profiles, Key: key,
		Generator: generator, Store: store, Worker: backend, Saved: saved,
		Progress: func(p search.Progress) { emitSearchProgress(worker, p) },
		Export:   func(a model.Attempt) error { return export.Save(stagingDir, manifest, profiles, a) },
	}
	return &SearchSession{runner: runner, store: store, stagingDir: stagingDir}, nil
}

func (s *SearchSession) Run() (string, error) {
	s.mu.Lock()
	if s.closed || s.running || s.phaseContext != nil || s.openingResult || s.resultReady || s.resultClaimed {
		s.mu.Unlock()
		return "busy", errors.New("search_busy")
	}
	s.running = true
	ctx, cancel := context.WithCancel(context.Background())
	s.cancel = cancel
	s.mu.Unlock()
	return s.runContext(ctx, cancel, false)
}

func (s *SearchSession) PreparePhase(epoch int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if epoch <= 0 || epoch <= s.phaseEpoch || s.closed || s.running || s.phaseContext != nil ||
		s.openingResult || s.resultReady || s.resultClaimed {
		return errors.New("search_busy")
	}
	s.phaseEpoch = epoch
	s.phaseContext, s.phaseCancel = context.WithCancel(context.Background())
	return nil
}

func (s *SearchSession) RunPhase(epoch int64) (string, error) {
	s.mu.Lock()
	if s.closed || s.running || s.phaseContext == nil || s.phaseEpoch != epoch ||
		s.openingResult || s.resultReady || s.resultClaimed {
		s.mu.Unlock()
		return "busy", errors.New("search_busy")
	}
	ctx, cancel := s.phaseContext, s.phaseCancel
	s.running = true
	s.cancel = cancel
	s.mu.Unlock()
	return s.runContext(ctx, cancel, true)
}

func (s *SearchSession) CancelPhase(epoch int64) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.phaseContext == nil || s.phaseEpoch != epoch {
		return false
	}
	s.phaseCancel()
	return true
}

func (s *SearchSession) runContext(ctx context.Context, cancel context.CancelFunc, phase bool) (string, error) {
	status, err := s.runner.Run(ctx)
	cancel()
	s.mu.Lock()
	s.running = false
	s.cancel = nil
	if phase {
		s.phaseContext = nil
		s.phaseCancel = nil
	}
	s.resultReady = err == nil && status == "success"
	s.mu.Unlock()
	if err != nil {
		return status, safeSessionError(err)
	}
	return status, nil
}

func (s *SearchSession) Cancel() {
	s.mu.Lock()
	cancel := s.cancel
	s.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

func (s *SearchSession) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.running || s.phaseContext != nil || s.openingResult {
		return errors.New("search_still_running")
	}
	if s.closed {
		return nil
	}
	s.closed = true
	if err := s.store.Close(); err != nil {
		return errors.New("search_state_close_failed")
	}
	return nil
}

func validSearchBudgets(b model.Budgets) bool {
	return b.SessionSeconds >= 0 && b.SessionSeconds <= 365*24*3600 && b.PayloadBytes >= 0 && b.JournalBytes >= 0 &&
		b.AttemptSeconds >= 1 && b.AttemptSeconds <= 86400 && b.StartupSeconds >= 1 && b.StartupSeconds <= 300 &&
		b.CleanupSeconds >= 1 && b.CleanupSeconds <= 60 && b.IntervalMS >= 0 && b.IntervalMS <= 60000
}

func safeSessionError(err error) error {
	for _, fault := range []error{searchbridge.ErrBusy, searchbridge.ErrOwnerLost, searchbridge.ErrUnknownDelivery,
		searchbridge.ErrWorkerDied, searchbridge.ErrCleanupIncomplete, searchbridge.ErrWorkerUnavailable, searchbridge.ErrCapabilityUnavailable} {
		if errors.Is(err, fault) {
			return fault
		}
	}
	return errors.New(model.Code(err))
}

type androidSearchWorker struct {
	callback      SearchEngineWorker
	startup       time.Duration
	cleanupMillis int64
}

func (w *androidSearchWorker) Open(ctx context.Context, id searchbridge.Identity, profile model.Profile, params map[string]any) (searchbridge.Round, error) {
	attempt := ctx
	ctx, cancel := context.WithTimeout(attempt, w.startup)
	defer cancel()
	profileBytes, profileErr := json.Marshal(profile)
	paramBytes, paramErr := json.Marshal(params)
	if profileErr != nil || paramErr != nil {
		return nil, searchbridge.ErrWorkerUnavailable
	}
	generation := int64(id.WorkerGeneration)
	stop := context.AfterFunc(ctx, func() { w.callback.Cancel(generation) })
	code := w.callback.Open(id.SessionGeneration, int64(id.Attempt), id.Round, generation, profileBytes, paramBytes, remainingMillis(attempt), remainingMillis(ctx))
	stop()
	if err := searchWorkerOpenError(code, attempt, ctx); err != nil {
		return nil, err
	}
	round := &androidSearchRound{callback: w.callback, generation: generation, cleanupMillis: w.cleanupMillis}
	if err := ctx.Err(); err != nil {
		if closeErr := round.Close(); closeErr != nil {
			return nil, closeErr
		}
		if attempt.Err() != nil {
			return nil, attempt.Err()
		}
		return nil, &searchbridge.CandidateOpenError{Reason: searchbridge.CandidateStartTimeout}
	}
	return round, nil
}

type androidSearchRound struct {
	callback      SearchEngineWorker
	generation    int64
	cleanupMillis int64
}

type probeReply struct {
	Fault  *int               `json:"fault"`
	Result *model.ProbeResult `json:"result"`
}

type mediaReply struct {
	Fault  *int                 `json:"fault"`
	Result *[]model.ProbeResult `json:"result"`
}

func (r *androidSearchRound) Check(ctx context.Context, p model.Probe, round int) (model.ProbeResult, error) {
	var reply probeReply
	if err := r.call(ctx, SearchWorkerCheck, p, round, &reply); err != nil {
		return model.ProbeResult{}, err
	}
	if reply.Fault == nil {
		return model.ProbeResult{}, searchbridge.ErrWorkerUnavailable
	}
	if err := searchWorkerCallError(*reply.Fault, ctx); err != nil {
		return model.ProbeResult{}, err
	}
	if err := ctx.Err(); err != nil {
		return model.ProbeResult{}, err
	}
	if reply.Result == nil {
		return model.ProbeResult{}, searchbridge.ErrWorkerUnavailable
	}
	if reply.Result.Probe != p.ID || reply.Result.Round != round || reply.Result.Bytes < 0 || reply.Result.DurationMS < 0 || reply.Result.Outcome == "" {
		return model.ProbeResult{}, searchbridge.ErrWorkerUnavailable
	}
	return *reply.Result, nil
}

func (r *androidSearchRound) UDP(ctx context.Context, p model.UDPProbe, round int) (model.ProbeResult, error) {
	var reply probeReply
	if err := r.call(ctx, SearchWorkerUDP, p, round, &reply); err != nil {
		return model.ProbeResult{}, err
	}
	if reply.Fault == nil {
		return model.ProbeResult{}, searchbridge.ErrWorkerUnavailable
	}
	if err := searchWorkerCallError(*reply.Fault, ctx); err != nil {
		return model.ProbeResult{}, err
	}
	if err := ctx.Err(); err != nil {
		return model.ProbeResult{}, err
	}
	if reply.Result == nil {
		return model.ProbeResult{}, searchbridge.ErrWorkerUnavailable
	}
	if reply.Result.Probe != p.ID || reply.Result.Round != round || reply.Result.Bytes < 0 || reply.Result.DurationMS < 0 || reply.Result.Outcome == "" {
		return model.ProbeResult{}, searchbridge.ErrWorkerUnavailable
	}
	return *reply.Result, nil
}

func (r *androidSearchRound) Media(ctx context.Context, p model.Policy, round int) ([]model.ProbeResult, error) {
	var reply mediaReply
	if err := r.call(ctx, SearchWorkerMedia, p, round, &reply); err != nil {
		return nil, err
	}
	if reply.Fault == nil {
		return nil, searchbridge.ErrWorkerUnavailable
	}
	if err := searchWorkerCallError(*reply.Fault, ctx); err != nil {
		return nil, err
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if reply.Result == nil {
		return nil, searchbridge.ErrWorkerUnavailable
	}
	for _, result := range *reply.Result {
		if result.Round != round || result.Bytes < 0 || result.DurationMS < 0 || result.Outcome == "" {
			return nil, searchbridge.ErrWorkerUnavailable
		}
	}
	return *reply.Result, nil
}

func (r *androidSearchRound) call(ctx context.Context, operation int, request any, round int, reply any) error {
	data, err := json.Marshal(request)
	if err != nil {
		return searchbridge.ErrWorkerUnavailable
	}
	if err = ctx.Err(); err != nil {
		return err
	}
	stop := context.AfterFunc(ctx, func() { r.callback.Cancel(r.generation) })
	response := r.callback.Call(r.generation, operation, data, round, remainingMillis(ctx))
	stop()
	if model.Decode(response, reply) != nil {
		return searchbridge.ErrWorkerUnavailable
	}
	return nil
}

func (r *androidSearchRound) Failure() error {
	return searchWorkerError(r.callback.Failure(r.generation))
}
func (r *androidSearchRound) Close() error {
	return searchWorkerError(r.callback.Close(r.generation, r.cleanupMillis))
}

func searchWorkerCallError(code int, ctx context.Context) error {
	if code == SearchWorkerCancelled || code == SearchWorkerDeadline {
		if err := ctx.Err(); err != nil {
			return err
		}
		return searchbridge.ErrWorkerUnavailable
	}
	return searchWorkerError(code)
}

func searchWorkerError(code int) error {
	switch code {
	case SearchWorkerReady:
		return nil
	case SearchWorkerBusy:
		return searchbridge.ErrBusy
	case SearchWorkerOwnerLost:
		return searchbridge.ErrOwnerLost
	case SearchWorkerDeliveryUnknown:
		return searchbridge.ErrUnknownDelivery
	case SearchWorkerDied:
		return searchbridge.ErrWorkerDied
	case SearchWorkerCleanupIncomplete:
		return searchbridge.ErrCleanupIncomplete
	case SearchWorkerCapabilityUnavailable:
		return searchbridge.ErrCapabilityUnavailable
	default:
		return searchbridge.ErrWorkerUnavailable
	}
}

func searchWorkerOpenError(code int, attempt, startup context.Context) error {
	retryable := code&SearchWorkerRetryableFlag != 0
	base := code &^ SearchWorkerRetryableFlag
	var reason searchbridge.CandidateOpenReason
	switch base {
	case SearchWorkerCandidateConfigRejected:
		reason = searchbridge.CandidateConfigRejected
	case SearchWorkerCandidateStartFailed:
		reason = searchbridge.CandidateStartFailed
	case SearchWorkerCandidateStartTimeout:
		reason = searchbridge.CandidateStartTimeout
	case SearchWorkerCandidateExitedBeforeReady:
		reason = searchbridge.CandidateExitedBeforeReady
	case SearchWorkerCancelled, SearchWorkerDeadline:
		if retryable {
			return searchbridge.ErrWorkerUnavailable
		}
		if err := attempt.Err(); err != nil {
			return err
		}
		if errors.Is(startup.Err(), context.DeadlineExceeded) {
			return &searchbridge.CandidateOpenError{Reason: searchbridge.CandidateStartTimeout}
		}
		return searchbridge.ErrWorkerUnavailable
	default:
		if retryable {
			return searchbridge.ErrWorkerUnavailable
		}
		return searchWorkerError(base)
	}
	return &searchbridge.CandidateOpenError{Reason: reason, Retryable: retryable}
}

func remainingMillis(ctx context.Context) int64 {
	if deadline, ok := ctx.Deadline(); ok {
		duration := time.Until(deadline)
		if duration <= 0 {
			return 0
		}
		return duration.Milliseconds()
	}
	return 0
}

func emitSearchProgress(worker SearchEngineWorker, progress search.Progress) {
	kinds := map[string]int{"attempt_started": 1, "attempt_finished": 2, "candidate_skipped": 3, "core_starting": 4,
		"core_ready": 5, "core_stopping": 6, "core_stopped": 7, "probe_started": 8, "probe_finished": 9,
		"core_failed": 9, "media_started": 10, "budget_reached": 11, "saved_result_skipped": 12}
	kind := kinds[progress.Event]
	if kind == 0 {
		return
	}
	outcomes := map[string]int{"": 0, "running": 1, "pass": 2, "failed": 3, "inconclusive": 4,
		"capability_blocked": 5, "duplicate": 6, "session_budget": 7, "payload_budget": 7, "attempt_budget": 7, "journal_budget": 7}
	worker.Progress(kind, outcomes[progress.Outcome], int64(progress.Attempt), progress.Profile, progress.Round, progress.Probe, progress.Bytes, progress.DurationMS)
}
