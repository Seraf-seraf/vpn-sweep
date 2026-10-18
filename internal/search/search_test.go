package search

import (
	"context"
	"encoding/json"
	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/export"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/policy"
	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
	"github.com/Seraf-seraf/vpn-sweep/internal/space"
	"github.com/Seraf-seraf/vpn-sweep/internal/state"
	"math/big"
	"os"
	"reflect"
	"strings"
	"testing"
	"time"
)

type tunnel struct{ closed *int }

func (t tunnel) Endpoint() socks.Endpoint { return socks.Endpoint{} }
func (t tunnel) Close() error             { *t.closed++; return nil }
func setup(t *testing.T) (model.Manifest, []model.Profile, *space.Generator, *state.Store) {
	t.Helper()
	p, _ := importer.Parse([]byte("socks5://synthetic.invalid:1080"), "uri")
	g, e := space.New(space.Spec{Version: 1, Strategy: "exhaustive"})
	if e != nil {
		t.Fatal(e)
	}
	s, e := state.Open(t.TempDir(), 0)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { s.Close() })
	m := model.Manifest{Version: 1, SearchVersion: model.SearchVersion, Mode: "core", Core: model.CoreIdentity{Commit: model.TargetCommit}, Environment: "user_attested", NetworkLabel: "local", Budgets: model.DefaultBudgets(), Policy: model.Policy{Version: "custom-v1", Rounds: 2, MinOperators: 1, Downloads: 1, MinDownloadBytes: 1, Probes: []model.Probe{{ID: "page", Operator: "a", URL: "https://a.invalid", Kind: "page", Contains: "expected", Status: 200, TimeoutSeconds: 1}, {ID: "data", Operator: "b", URL: "https://b.invalid", Kind: "download", SHA256: strings.Repeat("0", 64), Bytes: 1, Status: 200, TimeoutSeconds: 1}}}}
	m.Budgets.IntervalMS = 0
	return m, p.Profiles, g, s
}
func pass(_ context.Context, _ socks.Endpoint, p model.Probe, round int) model.ProbeResult {
	return model.ProbeResult{ID: model.ID(), Probe: p.ID, Round: round, Outcome: "pass", Transport: "https-tcp", HTTPStatus: 200, TLSVerified: true, Bytes: p.Bytes}
}
func TestFirstSuccessStopAndWinnerRecovery(t *testing.T) {
	m, p, g, s := setup(t)
	opens, closed, exports := 0, 0, 0
	e := Engine{Open: func(context.Context, model.Profile, map[string]any) (Tunnel, error) {
		opens++
		return tunnel{&closed}, nil
	}, Check: pass, Export: func(a model.Attempt) error { exports++; return nil }}
	status, err := e.Run(context.Background(), m, p, make([]byte, 32), g, s)
	if err != nil || status != "success" || opens != 2 || closed != 2 || exports != 1 {
		t.Fatal(status, err, opens, closed, exports)
	}
	if s.View().Pending != nil || s.View().Winner == nil {
		t.Fatal("winner not durable")
	}
	e.Open = func(context.Context, model.Profile, map[string]any) (Tunnel, error) {
		t.Fatal("searched after winner")
		return nil, nil
	}
	if status, err = e.Run(context.Background(), m, p, make([]byte, 32), g, s); err != nil || status != "success" || exports != 2 {
		t.Fatal("export recovery", status, err)
	}
}
func TestInterruptRetriesSameCandidateWithNewAttempt(t *testing.T) {
	m, p, g, s := setup(t)
	ctx, cancel := context.WithCancel(context.Background())
	closed := 0
	e := Engine{Open: func(context.Context, model.Profile, map[string]any) (Tunnel, error) { return tunnel{&closed}, nil }, Check: func(ctx context.Context, x socks.Endpoint, p model.Probe, r int) model.ProbeResult {
		cancel()
		return pass(ctx, x, p, r)
	}, Export: func(model.Attempt) error { return nil }}
	status, err := e.Run(ctx, m, p, make([]byte, 32), g, s)
	if err != nil || status != "interrupted" || s.View().Retry == nil {
		t.Fatal(status, err, s.View())
	}
	candidate := s.View().Retry.ID
	e.Check = pass
	if status, err = e.Run(context.Background(), m, p, make([]byte, 32), g, s); err != nil || status != "success" {
		t.Fatal(status, err)
	}
	if s.View().Winner.Candidate.ID != candidate || s.View().Attempts != 2 {
		t.Fatal("lost retry or wrong identity")
	}
}
func TestNoPartialSuccessAndBudget(t *testing.T) {
	m, p, g, s := setup(t)
	m.Budgets.Attempts = 1
	closed := 0
	e := Engine{Open: func(context.Context, model.Profile, map[string]any) (Tunnel, error) { return tunnel{&closed}, nil }, Check: func(_ context.Context, _ socks.Endpoint, p model.Probe, r int) model.ProbeResult {
		return model.ProbeResult{ID: model.ID(), Probe: p.ID, Round: r, Outcome: "unexpected_content"}
	}, Export: func(model.Attempt) error { t.Fatal("partial exported"); return nil }}
	status, err := e.Run(context.Background(), m, p, make([]byte, 32), g, s)
	if err != nil || status != "paused_budget" || s.View().Winner != nil {
		t.Fatal(status, err)
	}
	m.Budgets.Attempts = 0
	if status, err = e.Run(context.Background(), m, p, make([]byte, 32), g, s); err != nil || status != "space_exhausted" {
		t.Fatal(status, err)
	}
	_ = time.Second
}

func TestMuxCandidateIdentityMatchesRenderedSettings(t *testing.T) {
	r, err := importer.Parse([]byte("vless://synthetic@vpn.invalid:443?type=ws&security=tls"), "uri")
	if err != nil {
		t.Fatal(err)
	}
	p := r.Profiles[0]
	p.Outbound["mux"] = map[string]any{"enabled": true, "concurrency": float64(32)}
	g, err := space.New(space.Spec{Version: 1, Strategy: "exhaustive", Parameters: []space.Domain{{Name: "mux", Values: []any{true}}}})
	if err != nil {
		t.Fatal(err)
	}
	key := make([]byte, 32)
	baseline, _, _ := Candidate([]model.Profile{p}, key, g, big.NewInt(0))
	explicit, _, _ := Candidate([]model.Profile{p}, key, g, big.NewInt(1))
	rendered := func(params map[string]any) any {
		t.Helper()
		b, e := capability.Render(p, params, "127.0.0.1:10801", "local-user", "local-pass")
		if e != nil {
			t.Fatal(e)
		}
		var cfg map[string]any
		if e = json.Unmarshal(b, &cfg); e != nil {
			t.Fatal(e)
		}
		return cfg["outbounds"].([]any)[0].(map[string]any)["mux"]
	}
	if baseline.ID == explicit.ID || reflect.DeepEqual(rendered(baseline.Params), rendered(explicit.Params)) {
		t.Fatal("distinct Mux configurations collapsed")
	}
	// A baseline with defaults and explicit zero/default values must collapse;
	// negative concurrency is a distinct sentinel and must remain distinct.
	p.Outbound["mux"] = map[string]any{"enabled": true}
	defaults := Effective(p, map[string]any{})
	for _, v := range []map[string]any{{"mux": true}, {"mux": true, "tcpConcurrency": float64(0)}, {"mux": true, "tcpConcurrency": float64(8), "xudpConcurrency": float64(0), "quic": "reject"}} {
		if !reflect.DeepEqual(defaults, Effective(p, v)) || !reflect.DeepEqual(rendered(map[string]any{}), rendered(v)) {
			t.Fatal("default normalization mismatch", v)
		}
	}
	for _, name := range []string{"tcpConcurrency", "xudpConcurrency"} {
		if reflect.DeepEqual(defaults, Effective(p, map[string]any{"mux": true, name: float64(-1)})) {
			t.Fatal("sentinel collapsed", name)
		}
	}
}

func TestAttemptTimeoutContinuesAndIsDurable(t *testing.T) {
	for _, phase := range []string{"open", "probe", "confirmation"} {
		t.Run(phase, func(t *testing.T) {
			m, p, g, s := setup(t)
			m.Budgets.AttemptSeconds = 1
			m.Budgets.SessionSeconds = 10
			p = append(p, importer.Clone(p[0]))
			p[1].Outbound["settings"].(map[string]any)["servers"].([]any)[0].(map[string]any)["address"] = "second.invalid"
			opens, closed, exports := 0, 0, 0
			var expiredID string
			e := Engine{Open: func(ctx context.Context, _ model.Profile, _ map[string]any) (Tunnel, error) {
				opens++
				if (phase == "open" && opens == 1) || (phase == "confirmation" && opens == 2) {
					expiredID = s.View().Pending.Candidate.ID
					<-ctx.Done()
					return nil, ctx.Err()
				}
				return tunnel{&closed}, nil
			}, Check: func(ctx context.Context, x socks.Endpoint, pr model.Probe, r int) model.ProbeResult {
				if phase == "probe" && opens == 1 {
					expiredID = s.View().Pending.Candidate.ID
					<-ctx.Done()
					return model.ProbeResult{ID: model.ID(), Probe: pr.ID, Round: r, Outcome: "timeout"}
				}
				return pass(ctx, x, pr, r)
			}, Export: func(model.Attempt) error { exports++; return nil }}
			status, err := e.Run(context.Background(), m, p, make([]byte, 32), g, s)
			if err != nil || status != "success" || exports != 1 || s.View().Attempts != 2 || s.View().Retry != nil || !s.Seen(expiredID) || s.View().Winner.Candidate.ID == expiredID {
				t.Fatal(status, err, s.View())
			}
			wantFailedOpens := 1
			if phase == "probe" {
				wantFailedOpens = 0
			}
			if opens-closed != wantFailedOpens {
				t.Fatal("tunnel leak", opens, closed)
			}
			dir := s.Dir
			if err = s.Close(); err != nil {
				t.Fatal(err)
			}
			recovered, err := state.Open(dir, 0)
			if err != nil {
				t.Fatal(err)
			}
			defer recovered.Close()
			if recovered.View().Retry != nil || !recovered.Seen(expiredID) || recovered.View().Winner == nil {
				t.Fatal("timeout recovery lost completion", recovered.View())
			}

		})
	}
}

func TestSessionDeadlineAndCancellationRemainRetryable(t *testing.T) {
	for _, external := range []bool{false, true} {
		t.Run(map[bool]string{false: "session", true: "external"}[external], func(t *testing.T) {
			m, p, g, s := setup(t)
			m.Budgets.AttemptSeconds = 10
			m.Budgets.SessionSeconds = 1
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			e := Engine{Open: func(ctx context.Context, _ model.Profile, _ map[string]any) (Tunnel, error) {
				if external {
					cancel()
				}
				<-ctx.Done()
				return nil, ctx.Err()
			}, Check: pass, Export: func(model.Attempt) error { return nil }}
			status, err := e.Run(ctx, m, p, make([]byte, 32), g, s)
			expected := "paused_budget"
			if external {
				expected = "interrupted"
			}
			if err != nil || status != expected || s.View().Retry == nil || s.Seen(s.View().Retry.ID) {
				t.Fatal(status, err, s.View())
			}

			dir := s.Dir
			if err = s.Close(); err != nil {
				t.Fatal(err)
			}
			s, err = state.Open(dir, 0)
			if err != nil {
				t.Fatal(err)
			}
			defer s.Close()
			retry := *s.View().Retry
			closed := 0
			e.Open = func(context.Context, model.Profile, map[string]any) (Tunnel, error) { return tunnel{&closed}, nil }
			status, err = e.Run(context.Background(), m, p, make([]byte, 32), g, s)
			if err != nil || status != "success" || s.View().Winner.Candidate.ID != retry.ID || s.View().Attempts != 2 {
				t.Fatal(status, err, s.View())
			}
		})
	}
}

func TestLegacySearchSeriesFailsClosed(t *testing.T) {
	m, p, g, s := setup(t)
	m.SearchVersion = 0
	e := Engine{Open: func(context.Context, model.Profile, map[string]any) (Tunnel, error) {
		t.Fatal("legacy series started")
		return nil, nil
	}}
	status, err := e.Run(context.Background(), m, p, make([]byte, 32), g, s)
	if status != "unsupported_search_version" || model.Code(err) != "search_version_requires_new_series" || s.View().Attempts != 0 {
		t.Fatal(status, err)
	}
}

func TestWebProfileRestartResumeAndScopedExport(t *testing.T) {
	m, p, g, s := setup(t)
	raw, err := os.ReadFile("../../examples/google-youtube-web-v1.json")
	if err != nil {
		t.Fatal(err)
	}
	m.Policy = model.Policy{}
	if err = model.Decode(raw, &m.Policy); err != nil {
		t.Fatal(err)
	}
	key := make([]byte, 32)
	m.InputID = model.PrivateID(key, p)
	m.Space, _ = json.Marshal(g.Spec)
	if err = s.SaveManifest(m, p, key); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	opens, closed, exports := 0, 0, 0
	out := t.TempDir()
	e := Engine{Open: func(context.Context, model.Profile, map[string]any) (Tunnel, error) {
		opens++
		return tunnel{&closed}, nil
	}, Check: func(ctx context.Context, x socks.Endpoint, pr model.Probe, r int) model.ProbeResult {
		cancel()
		return pass(ctx, x, pr, r)
	}, Export: func(a model.Attempt) error { exports++; return export.Save(out, m, p, a) }}
	status, err := e.Run(ctx, m, p, key, g, s)
	if err != nil || status != "interrupted" || s.View().Retry == nil || closed != 1 {
		t.Fatal(status, err)
	}
	dir := s.Dir
	if err = s.Close(); err != nil {
		t.Fatal(err)
	}
	s, err = state.Open(dir, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	frozen, profiles, k, err := s.LoadManifest()
	if err != nil {
		t.Fatal(err)
	}
	if model.Digest(frozen.Policy) != model.Digest(m.Policy) {
		t.Fatal("policy changed during resume")
	}
	m = frozen
	p = profiles
	e.Check = func(_ context.Context, _ socks.Endpoint, pr model.Probe, r int) model.ProbeResult {
		return model.ProbeResult{ID: model.ID(), Probe: pr.ID, Round: r, Outcome: "pass", TLSVerified: true, ContentVerified: true, MIME: "text/html", HTTPStatus: 200, Bytes: pr.MinBytes, Transport: "https-tcp"}
	}
	status, err = e.Run(context.Background(), m, p, k, g, s)
	if err != nil || status != "success" || opens != 3 || closed != 3 || exports != 1 || len(s.View().Winner.Results) != 4 {
		t.Fatal(status, err, opens, closed, exports)
	}
	report, err := export.ReadReport(out)
	if err != nil || report.VerificationScope != policy.Scope(m.Policy) || report.PresetStatus != "live_unverified" {
		t.Fatal(report, err)
	}
	e.Open = func(context.Context, model.Profile, map[string]any) (Tunnel, error) {
		t.Fatal("searched after winner")
		return nil, nil
	}
	status, err = e.Run(context.Background(), m, p, k, g, s)
	if err != nil || status != "success" || exports != 2 {
		t.Fatal(status, err)
	}
}
