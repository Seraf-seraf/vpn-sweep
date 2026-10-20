package search

import (
	"context"
	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/media"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/policy"
	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
	"github.com/Seraf-seraf/vpn-sweep/internal/space"
	"github.com/Seraf-seraf/vpn-sweep/internal/state"
	"math/big"
	"time"
)

type Tunnel interface {
	Endpoint() socks.Endpoint
	Close() error
}
type Engine struct {
	Progress func(Progress)
	Media    func(context.Context, socks.Endpoint, model.Policy, int) []model.ProbeResult
	Open     func(context.Context, model.Profile, map[string]any) (Tunnel, error)
	Check    func(context.Context, socks.Endpoint, model.Probe, int) model.ProbeResult
	UDP      func(context.Context, socks.Endpoint, string, string, int) model.ProbeResult
	Export   func(model.Attempt) error
}

type Progress struct {
	Event      string
	Attempt    uint64
	Profile    int
	Round      int
	Probe      int
	Outcome    string
	Bytes      int64
	DurationMS int64
}

func (e Engine) notify(s *state.Store, p Progress) {
	if e.Progress == nil {
		return
	}
	v := s.View()
	p.Attempt = v.Attempts
	if v.Pending != nil {
		p.Profile = v.Pending.Candidate.Profile + 1
	}
	e.Progress(p)
}

func (e Engine) recordProbe(s *state.Store, r model.ProbeResult, index int) error {
	if err := s.Probe(r); err != nil {
		return err
	}
	event := "probe_finished"
	if r.Transport == "core" {
		event = "core_failed"
	}
	e.notify(s, Progress{Event: event, Round: r.Round + 1, Probe: index, Outcome: r.Outcome, Bytes: r.Bytes, DurationMS: r.DurationMS})
	return nil
}

func Effective(p model.Profile, params map[string]any) map[string]any {
	return capability.Effective(p, params)
}
func Candidate(profiles []model.Profile, key []byte, g *space.Generator, pos *big.Int) (model.Candidate, *big.Int, bool) {
	n := big.NewInt(int64(len(profiles)))
	total := new(big.Int).Mul(new(big.Int).Add(g.Count(), big.NewInt(1)), n)
	if len(profiles) == 0 || pos.Sign() < 0 || pos.Cmp(total) >= 0 {
		return model.Candidate{}, nil, false
	}
	params := map[string]any{}
	profile := int(new(big.Int).Mod(pos, n).Int64())
	if pos.Cmp(n) >= 0 {
		rank := new(big.Int).Quo(pos, n)
		rank.Sub(rank, big.NewInt(1))
		params, _ = g.At(rank)
	}
	c := model.Candidate{Profile: profile, Params: params}
	c.ID = model.PrivateID(key, []any{"candidate-v2", profiles[profile], Effective(profiles[profile], params)})
	return c, new(big.Int).Add(pos, big.NewInt(1)), true
}
func (e Engine) Run(ctx context.Context, m model.Manifest, profiles []model.Profile, key []byte, g *space.Generator, s *state.Store) (string, error) {
	if m.SearchVersion != model.SearchVersion {
		return "unsupported_search_version", model.Err("search_version_requires_new_series", "search")
	}
	if m.Environment != "unverified" && (m.Environment != "user_attested" || m.NetworkLabel == "") {
		return "environment_blocked", model.Err("environment_unverified", "search")
	}
	if err := policy.Validate(m.Policy); err != nil {
		return "invalid_policy", err
	}
	if len(profiles) == 0 {
		return "invalid_input", model.Err("no_profiles", "search")
	}
	for _, p := range profiles {
		if err := capability.Gate(m.Mode, m.Core.Commit); err != nil {
			return "unsupported", err
		}
		_ = p
	}
	b := m.Budgets
	if b.AttemptSeconds < 1 {
		return "invalid_budget", model.Err("invalid_budget", "search")
	}
	session := ctx
	cancel := func() {}
	if b.SessionSeconds > 0 {
		session, cancel = context.WithTimeout(ctx, time.Duration(b.SessionSeconds)*time.Second)
	}
	defer cancel()
	initial := s.View()
	usedAttempts := uint64(0)
	status := func(v string) (string, error) { return v, s.Status(v) }
	if initial.Pending != nil {
		if err := s.End("interrupted"); err != nil {
			return "state_error", err
		}
	}
	if winner := s.View().Winner; winner != nil {
		if err := e.Export(*winner); err != nil {
			return "result_save_failed", err
		}
		return status("success")
	}
	for {
		view := s.View()
		if session.Err() != nil {
			if ctx.Err() != nil {
				return status("interrupted")
			}
			e.notify(s, Progress{Event: "budget_reached", Outcome: "session_budget"})
			return status("paused_budget")
		}
		if b.Attempts > 0 && usedAttempts >= b.Attempts || b.PayloadBytes > 0 && view.PayloadBytes-initial.PayloadBytes >= b.PayloadBytes {
			reason := "payload_budget"
			if b.Attempts > 0 && usedAttempts >= b.Attempts {
				reason = "attempt_budget"
			}
			e.notify(s, Progress{Event: "budget_reached", Outcome: reason})
			return status("paused_budget")
		}
		var c model.Candidate
		next := view.Next
		if view.Retry != nil {
			c = *view.Retry
		} else if g.Spec.Strategy == "broad" && view.Attempts%4 == 3 && len(view.Refinements) > 0 {
			c = view.Refinements[0]
		} else {
			pos, ok := new(big.Int).SetString(view.Next, 10)
			if !ok {
				return "state_error", model.Err("invalid_checkpoint", "search")
			}
			var n *big.Int
			var exists bool
			c, n, exists = Candidate(profiles, key, g, pos)
			if !exists {
				return status("space_exhausted")
			}
			next = n.String()
		}
		a := model.Attempt{ID: model.ID(), Candidate: c, Started: time.Now().UTC(), Outcome: "running"}
		if err := s.Begin(a, next); err != nil {
			if model.Code(err) == "journal_budget" {
				e.notify(s, Progress{Event: "budget_reached", Outcome: "journal_budget"})
				return "paused_budget", nil
			}
			return "state_error", err
		}
		if s.Seen(c.ID) {
			e.notify(s, Progress{Event: "candidate_skipped", Outcome: "duplicate"})
			if err := s.End("duplicate"); err != nil {
				return "state_error", err
			}
			continue
		}
		if err := capability.Check(profiles[c.Profile], c.Params); err != nil {
			e.notify(s, Progress{Event: "candidate_skipped", Outcome: model.Code(err)})
			if err = s.End(model.Code(err)); err != nil {
				return "state_error", err
			}
			continue
		}
		if c.Params["noise"] == true && len(m.Policy.UDP) == 0 {
			e.notify(s, Progress{Event: "candidate_skipped", Outcome: "noise_udp_probe_required"})
			if err := s.End("noise_udp_probe_required"); err != nil {
				return "state_error", err
			}
			continue
		}
		usedAttempts++
		e.notify(s, Progress{Event: "attempt_started"})
		attemptCtx, stop := context.WithTimeout(session, time.Duration(b.AttemptSeconds)*time.Second)
		outcome, err := e.attempt(attemptCtx, m, profiles[c.Profile], c, s, initial.PayloadBytes)

		// Parent cancellation wins over a child deadline: only parent interruption
		// preserves Retry. An expired candidate is a completed failed attempt.
		if session.Err() != nil {
			outcome = "interrupted"
		} else if attemptCtx.Err() == context.DeadlineExceeded {
			outcome = "attempt_timeout"
		}
		stop()
		e.notify(s, Progress{Event: "attempt_finished", Outcome: outcome})
		if err != nil {
			return "attempt_error", err
		}
		if outcome == "interrupted" {
			if err = s.End("interrupted"); err != nil {
				return "state_error", err
			}
			if ctx.Err() != nil {
				return status("interrupted")
			}
			if session.Err() != nil {
				e.notify(s, Progress{Event: "budget_reached", Outcome: "session_budget"})
				return status("paused_budget")
			}
			return status("paused_budget")
		}
		if outcome == "capability_blocked" {
			if err = s.End("interrupted"); err != nil {
				return "state_error", err
			}
			return status("capability_blocked")
		}
		if outcome == "pass" {
			if err = s.End("verified_pending_export"); err != nil {
				return "state_error", err
			}
			winner := s.View().Winner
			if err = e.Export(*winner); err != nil {
				_ = s.Status("result_save_failed")
				return "result_save_failed", err
			}
			return status("success")
		}
		pending := s.View().Pending
		if err = s.End(outcome); err != nil {
			return "state_error", err
		}
		if g.Spec.Strategy == "broad" && pending != nil {
			progress := false
			for _, r := range pending.Results {
				if r.Outcome == "pass" && r.TLSVerified {
					progress = true
				}
			}
			if progress {
				neighbors := []model.Candidate{}
				for _, params := range g.Neighbors(c.Params) {
					if capability.Check(profiles[c.Profile], params) != nil {
						continue
					}
					n := model.Candidate{Profile: c.Profile, Params: params}
					n.ID = model.PrivateID(key, []any{"candidate-v2", profiles[c.Profile], Effective(profiles[c.Profile], params)})
					neighbors = append(neighbors, n)
				}
				if err = s.Refine(neighbors); err != nil {
					return "state_error", err
				}
			}
		}
		timer := time.NewTimer(time.Duration(b.IntervalMS) * time.Millisecond)
		select {
		case <-session.Done():
			timer.Stop()
		case <-timer.C:
		}
	}
}
func (e Engine) attempt(ctx context.Context, m model.Manifest, p model.Profile, c model.Candidate, s *state.Store, initialBytes int64) (string, error) {
	for round := 0; round < m.Policy.Rounds; round++ {
		if ctx.Err() != nil {
			return "interrupted", nil
		}
		e.notify(s, Progress{Event: "core_starting", Round: round + 1})
		h, err := e.Open(ctx, p, c.Params)
		if err != nil {
			if ctx.Err() != nil {
				return "interrupted", nil
			}
			if er := e.recordProbe(s, model.ProbeResult{ID: model.ID(), Probe: "core-start", Round: round, Transport: "core", Outcome: model.Code(err), Phase: "core"}, 0); er != nil {
				return "", er
			}
			return "failed", nil
		}
		e.notify(s, Progress{Event: "core_ready", Round: round + 1})
		closeTunnel := func() error {
			e.notify(s, Progress{Event: "core_stopping", Round: round + 1})
			err := h.Close()
			outcome := "pass"
			if err != nil {
				outcome = model.Code(err)
			}
			e.notify(s, Progress{Event: "core_stopped", Round: round + 1, Outcome: outcome})
			return err
		}
		if round > 0 {
			if err = s.Restart(); err != nil {
				closeTunnel()
				return "", err
			}
		}

		if m.Policy.Version == media.Version {
			if e.Media == nil {
				closeTunnel()
				return "capability_blocked", nil
			}
			if m.Budgets.PayloadBytes > 0 && s.View().PayloadBytes-initialBytes+media.RoundBudget(m.Policy) > m.Budgets.PayloadBytes {
				e.notify(s, Progress{Event: "budget_reached", Outcome: "payload_budget"})
				if err = closeTunnel(); err != nil {
					return "", err
				}
				return "interrupted", nil
			}
			e.notify(s, Progress{Event: "media_started", Round: round + 1})
			results := e.Media(ctx, h.Endpoint(), m.Policy, round)
			blocked := false
			passed := true
			for i, r := range results {
				if err = e.recordProbe(s, r, i+1); err != nil {
					closeTunnel()
					return "", err
				}
				if media.Required(m.Policy, r.Probe) && r.Outcome != "pass" {
					passed = false
					if media.Blocked(r.Outcome) {
						blocked = true
					}
				}
			}
			if f, ok := h.(interface{ Failure() error }); ok && f.Failure() != nil {
				passed = false
			}
			if err = closeTunnel(); err != nil {
				return "", err
			}
			if ctx.Err() != nil {
				return "interrupted", nil
			}
			if blocked {
				return "capability_blocked", nil
			}
			if !passed {
				return "failed", nil
			}
			continue
		}
		failed := false
		inconclusive := false
		for i, pr := range m.Policy.Probes {
			if pr.Kind == "download" && round > 0 {
				continue
			}
			limit := pr.Bytes
			if limit == 0 {
				limit = 2 << 20
			}
			if m.Budgets.PayloadBytes > 0 && s.View().PayloadBytes-initialBytes+limit > m.Budgets.PayloadBytes {
				e.notify(s, Progress{Event: "budget_reached", Outcome: "payload_budget"})
				if err = closeTunnel(); err != nil {
					return "", err
				}
				return "interrupted", nil
			}
			e.notify(s, Progress{Event: "probe_started", Round: round + 1, Probe: i + 1})
			r := e.Check(ctx, h.Endpoint(), pr, round)
			if err = e.recordProbe(s, r, i+1); err != nil {
				closeTunnel()
				return "", err
			}
			if r.Outcome != "pass" {
				switch r.Outcome {
				case "site_consent", "site_forbidden", "site_rate_limited", "site_restricted", "site_challenge", "unexpected_http_status", "unsupported":
					inconclusive = true
				default:
					failed = true
				}
			}
		}
		for i, pr := range m.Policy.UDP {
			e.notify(s, Progress{Event: "probe_started", Round: round + 1, Probe: len(m.Policy.Probes) + i + 1})
			udpCtx, cancel := context.WithTimeout(ctx, time.Duration(pr.TimeoutSeconds)*time.Second)
			r := e.UDP(udpCtx, h.Endpoint(), pr.ID, pr.Address, round)
			cancel()
			if err = e.recordProbe(s, r, len(m.Policy.Probes)+i+1); err != nil {
				closeTunnel()
				return "", err
			}
			if r.Outcome != "pass" {
				failed = true
			}
		}
		mediaOutcome := "pass"
		if m.Policy.Version == policy.GoogleYouTube && !failed && !inconclusive {
			mediaOutcome, err = e.checkMedia(ctx, m, h, s, round, initialBytes)
			if err != nil {
				closeTunnel()
				return "", err
			}
		}
		if f, ok := h.(interface{ Failure() error }); ok {
			if failure := f.Failure(); failure != nil {
				failed = true
				if er := e.recordProbe(s, model.ProbeResult{ID: model.ID(), Probe: "core-exit", Round: round, Transport: "core", Outcome: model.Code(failure), Phase: "core"}, 0); er != nil {
					closeTunnel()
					return "", er
				}
			}
		}
		if err = closeTunnel(); err != nil {
			return "", err
		}
		if ctx.Err() != nil {
			return "interrupted", nil
		}
		if failed {
			return "failed", nil
		}
		if inconclusive {
			return "inconclusive", nil
		}
		if mediaOutcome != "pass" {
			return mediaOutcome, nil
		}
	}
	return policy.Evaluate(m.Policy, *s.View().Pending), nil
}

func (e Engine) checkMedia(ctx context.Context, m model.Manifest, h Tunnel, s *state.Store, round int, initialBytes int64) (string, error) {
	if e.Media == nil {
		return "capability_blocked", nil
	}
	p := policy.MediaPolicy(m.Policy)
	if m.Budgets.PayloadBytes > 0 && s.View().PayloadBytes-initialBytes+media.RoundBudget(p) > m.Budgets.PayloadBytes {
		e.notify(s, Progress{Event: "budget_reached", Outcome: "payload_budget"})
		return "interrupted", nil
	}
	e.notify(s, Progress{Event: "media_started", Round: round + 1})
	failed, blocked := false, false
	for i, r := range e.Media(ctx, h.Endpoint(), p, round) {
		if err := e.recordProbe(s, r, len(m.Policy.Probes)+i+1); err != nil {
			return "", err
		}
		if media.Required(p, r.Probe) && r.Outcome != "pass" {
			failed = true
			blocked = blocked || media.Blocked(r.Outcome)
		}
	}
	if blocked {
		return "capability_blocked", nil
	}
	if failed {
		return "failed", nil
	}
	return "pass", nil
}
