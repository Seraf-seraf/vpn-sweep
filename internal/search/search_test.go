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
	"net"
	"os"
	"reflect"
	"strconv"
	"strings"
	"testing"
	"time"
)

type tunnel struct{ closed *int }

func (t tunnel) Endpoint() socks.Endpoint { return socks.Endpoint{} }
func (t tunnel) Close() error             { *t.closed++; return nil }

type closeErrorTunnel struct{ closed *int }

func (t closeErrorTunnel) Endpoint() socks.Endpoint { return socks.Endpoint{} }
func (t closeErrorTunnel) Close() error {
	*t.closed++
	return model.Err("core_kill_failed", "core")
}
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

func syntheticSS(host string) string {
	return "ss://" + "YWVzLTEyOC1nY206c3ludGhldGlj" + "@" + net.JoinHostPort(host, "8388")
}

func TestDefaultEarlyCoverageUsesApplicableProfiles(t *testing.T) {
	inputs := []string{
		"vless://synthetic@vpn.invalid:443?type=tcp&security=tls&sni=tls.invalid",
		syntheticSS("192.0.2.1"),
		syntheticSS("2001:db8::1"),
		syntheticSS("vpn.invalid"),
		"socks5://vpn.invalid:1080",
	}
	profiles := make([]model.Profile, 0, len(inputs))
	for _, input := range inputs {
		r, err := importer.Parse([]byte(input), "uri")
		if err != nil {
			t.Fatal(err)
		}
		profiles = append(profiles, r.Profiles[0])
	}
	g, err := space.New(space.Default())
	if err != nil {
		t.Fatal(err)
	}
	schedule, err := newCandidateSchedule(profiles, make([]byte, 32), g, model.SearchVersion)
	if err != nil {
		t.Fatal(err)
	}
	seen := make([]map[string]map[string]bool, len(profiles))
	for i := range seen {
		seen[i] = map[string]map[string]bool{}
	}
	for pos := int64(0); pos < int64(len(profiles)*128); pos++ {
		c, next, ok := schedule.candidate(big.NewInt(pos))
		if !ok || next.Int64() != pos+1 {
			t.Fatal("missing candidate", pos)
		}
		if err := capability.Check(profiles[c.Profile], c.Params); err != nil {
			t.Fatalf("inapplicable early candidate %d for profile %d: %v", pos, c.Profile, err)
		}
		for _, name := range []string{"fragment", "mux", "noise", "fingerprint", "packets", "length", "interval", "maxSplit", "tcpConcurrency", "xudpConcurrency", "quic"} {
			if value, exists := c.Params[name]; exists {
				if seen[c.Profile][name] == nil {
					seen[c.Profile][name] = map[string]bool{}
				}
				seen[c.Profile][name][model.Digest(value)] = true
			}
		}
		if c.Params["noise"] == true {
			items := c.Params["noises"].([]any)
			item := items[0].(map[string]any)
			for _, name := range []string{"type", "delay", "applyTo"} {
				if seen[c.Profile][name] == nil {
					seen[c.Profile][name] = map[string]bool{}
				}
				seen[c.Profile][name][model.Digest(item[name])] = true
			}
			packetKey := "packet:" + item["type"].(string)
			if seen[c.Profile][packetKey] == nil {
				seen[c.Profile][packetKey] = map[string]bool{}
			}
			seen[c.Profile][packetKey][model.Digest(item["packet"])] = true
			if seen[c.Profile]["count"] == nil {
				seen[c.Profile]["count"] = map[string]bool{}
			}
			seen[c.Profile]["count"][model.Digest(len(items))] = true
			family := item["applyTo"]
			if c.Profile == 1 && family == "ipv6" || c.Profile == 2 && family == "ipv4" || c.Profile == 3 && family != "ip" {
				t.Fatalf("noise family %v does not match profile %d", family, c.Profile)
			}
		}
	}
	for name, count := range map[string]int{"fragment": 2, "mux": 2, "fingerprint": 10, "packets": 3, "tcpConcurrency": 6, "xudpConcurrency": 5, "quic": 3} {
		if got := len(seen[0][name]); got != count {
			t.Fatalf("TLS profile covers %d %s values, want %d", got, name, count)
		}
	}
	for name, values := range map[string][]string{"length": {"1", "10-50", "512"}, "interval": {"0", "10-50", "50"}, "maxSplit": {"1", "10-50", "256"}} {
		for _, value := range values {
			if !seen[0][name][model.Digest(value)] {
				t.Fatalf("TLS profile did not cover %s=%s", name, value)
			}
		}
	}
	for profile := 1; profile <= 3; profile++ {
		for name, count := range map[string]int{"noise": 2, "type": 4, "delay": 3, "count": 4, "packet:rand": 3, "packet:str": 6, "packet:hex": 6, "packet:base64": 6} {
			if got := len(seen[profile][name]); got != count {
				t.Fatalf("profile %d covers %d %s values, want %d", profile, got, name, count)
			}
		}
	}
	if len(seen[1]["applyTo"]) != 2 || len(seen[2]["applyTo"]) != 2 || len(seen[3]["applyTo"]) != 1 {
		t.Fatal("missing applicable address families")
	}
}

func TestProfileSpacesFinishInRoundRobinOrder(t *testing.T) {
	tls, err := importer.Parse([]byte("vless://synthetic@vpn.invalid:443?type=tcp&security=tls&sni=tls.invalid"), "uri")
	if err != nil {
		t.Fatal(err)
	}
	plain, err := importer.Parse([]byte("socks5://vpn.invalid:1080"), "uri")
	if err != nil {
		t.Fatal(err)
	}
	profiles := []model.Profile{tls.Profiles[0], plain.Profiles[0]}
	g, err := space.New(space.Spec{Version: 2, Strategy: "broad", Seed: 42, Parameters: []space.Domain{{Name: "fingerprint", Optional: true, Values: []any{"chrome", "firefox"}}}})
	if err != nil {
		t.Fatal(err)
	}
	order := []int{0, 1, 0, 1, 0, 0}
	for pos, want := range order {
		c, _, ok := Candidate(profiles, make([]byte, 32), g, big.NewInt(int64(pos)))
		if !ok || c.Profile != want || capability.Check(profiles[c.Profile], c.Params) != nil {
			t.Fatal("invalid profile schedule", pos, c)
		}
	}
	if _, _, ok := Candidate(profiles, make([]byte, 32), g, big.NewInt(int64(len(order)))); ok {
		t.Fatal("profile scheduler did not stop at the finite boundary")
	}
}

func TestFingerprintCandidateIdentityMatchesRenderedSettings(t *testing.T) {
	for _, tc := range []struct {
		name  string
		uri   string
		equal bool
	}{
		{name: "TLS source fingerprint", uri: "vless://synthetic@vpn.invalid:443?type=tcp&security=tls&fp=chrome", equal: true},
		{name: "REALITY source fingerprint", uri: "vless://synthetic@vpn.invalid:443?type=tcp&security=reality&sni=tls.invalid&pbk=synthetic&fp=chrome", equal: true},
		{name: "no source fingerprint", uri: "vless://synthetic@vpn.invalid:443?type=tcp&security=tls"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r, err := importer.Parse([]byte(tc.uri), "uri")
			if err != nil {
				t.Fatal(err)
			}
			g, err := space.New(space.Spec{Version: 2, Strategy: "exhaustive", Parameters: []space.Domain{{Name: "fingerprint", Optional: true, Values: []any{"chrome", "firefox"}}}})
			if err != nil {
				t.Fatal(err)
			}
			key := make([]byte, 32)
			base, _, _ := Candidate(r.Profiles, key, g, big.NewInt(0))
			chrome, _, _ := Candidate(r.Profiles, key, g, big.NewInt(2))
			firefox, _, _ := Candidate(r.Profiles, key, g, big.NewInt(3))
			baseConfig, err := capability.Render(r.Profiles[0], base.Params, "127.0.0.1:10801", "local-user", "local-pass")
			if err != nil {
				t.Fatal(err)
			}
			chromeConfig, err := capability.Render(r.Profiles[0], chrome.Params, "127.0.0.1:10801", "local-user", "local-pass")
			if err != nil {
				t.Fatal(err)
			}
			if (string(baseConfig) == string(chromeConfig)) != tc.equal || (base.ID == chrome.ID) != tc.equal || base.ID == firefox.ID {
				t.Fatalf("rendered equality and candidate identity disagree: base=%s chrome=%s firefox=%s", base.ID, chrome.ID, firefox.ID)
			}
		})
	}
}

func TestVersionTwoKeepsFingerprintCandidateIdentity(t *testing.T) {
	m, _, _, s := setup(t)
	m.SearchVersion = 2
	r, err := importer.Parse([]byte("vless://synthetic@vpn.invalid:443?type=tcp&security=tls&fp=chrome"), "uri")
	if err != nil {
		t.Fatal(err)
	}
	g, err := space.New(space.Spec{Version: 2, Strategy: "exhaustive", Parameters: []space.Domain{{Name: "fingerprint", Optional: true, Values: []any{"chrome", "firefox"}}}})
	if err != nil {
		t.Fatal(err)
	}
	key := make([]byte, 32)
	m.InputID = model.PrivateID(key, r.Profiles)
	m.Space, err = json.Marshal(g.Spec)
	if err != nil {
		t.Fatal(err)
	}
	if err = s.SaveManifest(m, r.Profiles, key); err != nil {
		t.Fatal(err)
	}
	var seen []string
	e := Engine{Open: func(_ context.Context, _ model.Profile, _ map[string]any) (Tunnel, error) {
		seen = append(seen, s.View().Pending.Candidate.ID)
		return nil, model.Err("core_start_failed", "core")
	}}
	m.Budgets.Attempts = 1
	status, err := e.Run(context.Background(), m, r.Profiles, key, g, s)
	if err != nil || status != "paused_budget" || len(seen) != 1 {
		t.Fatalf("legacy initial session: status=%s err=%v candidates=%v", status, err, seen)
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
	loaded, profiles, loadedKey, err := s.LoadManifest()
	if err != nil || loaded.SearchVersion != 2 {
		t.Fatal("legacy manifest was not restored", err)
	}
	var spec space.Spec
	if err = model.Decode(loaded.Space, &spec); err != nil {
		t.Fatal(err)
	}
	g, err = space.New(spec)
	if err != nil {
		t.Fatal(err)
	}
	loaded.Budgets.Attempts = 0
	status, err = e.Run(context.Background(), loaded, profiles, loadedKey, g, s)
	if err != nil || status != "space_exhausted" || len(seen) != 3 {
		t.Fatalf("legacy series: status=%s err=%v candidates=%v", status, err, seen)
	}
	for i, want := range []string{
		"8f5e876de08ee17a3bfe68c171831bbb313604cc25ca7d368dc2dbe8a20ecf4e",
		"4b64d9d5c66252d4096628a3a721334a082af49e0bf5fac3090b191f363e6947",
		"ba1020577e5482867d48d5b2025e192312e2ae2d65b270d0f5c32c365ecc49e9",
	} {
		if seen[i] != want {
			t.Fatalf("legacy candidate %d: got %s, want %s", i, seen[i], want)
		}
	}
}

func TestPreviouslySavedLegacyFingerprintSkipsEquivalentCandidate(t *testing.T) {
	m, _, _, s := setup(t)
	r, err := importer.Parse([]byte("vless://synthetic@vpn.invalid:443?type=tcp&security=tls&fp=chrome"), "uri")
	if err != nil {
		t.Fatal(err)
	}
	g, err := space.New(space.Spec{Version: 2, Strategy: "exhaustive", Parameters: []space.Domain{{Name: "fingerprint", Optional: true, Values: []any{"chrome"}}}})
	if err != nil {
		t.Fatal(err)
	}
	key := make([]byte, 32)
	legacyID := model.PrivateID(key, []any{"candidate-v2", r.Profiles[0], Effective(r.Profiles[0], map[string]any{"fingerprint": "chrome"})})
	opens := 0
	e := Engine{Saved: map[string]bool{legacyID: true}, Open: func(context.Context, model.Profile, map[string]any) (Tunnel, error) {
		opens++
		return nil, model.Err("core_start_failed", "core")
	}}
	status, err := e.Run(context.Background(), m, r.Profiles, key, g, s)
	if err != nil || status != "space_exhausted" || opens != 0 {
		t.Fatalf("legacy saved candidate: status=%s err=%v opens=%d", status, err, opens)
	}
}

func TestNoiseUDPIsConditionalAndRequired(t *testing.T) {
	for _, tc := range []struct {
		name       string
		noise      bool
		udpOutcome string
		wantStatus string
		wantUDP    int
	}{
		{name: "web only", wantStatus: "success"},
		{name: "noise succeeds", noise: true, udpOutcome: "pass", wantStatus: "success", wantUDP: 2},
		{name: "noise UDP fails", noise: true, udpOutcome: "udp_timeout_or_read_failed", wantStatus: "space_exhausted", wantUDP: 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			m, _, _, s := setup(t)
			m.Policy = policy.Default()
			m.Budgets.SessionSeconds = 10
			profile, err := importer.Parse([]byte(syntheticSS("192.0.2.1")), "uri")
			if err != nil {
				t.Fatal(err)
			}
			noise := []any{map[string]any{"type": "rand", "packet": "1", "delay": "0", "applyTo": "ip"}}
			g, err := space.New(space.Spec{Version: 2, Strategy: "exhaustive", Parameters: []space.Domain{
				{Name: "mux", Values: []any{false}},
				{Name: "noise", Values: []any{false, true}},
				{Name: "noises", Values: []any{noise}},
			}})
			if err != nil {
				t.Fatal(err)
			}
			closed, udpCalls, exports := 0, 0, 0
			e := Engine{
				Open: func(_ context.Context, _ model.Profile, params map[string]any) (Tunnel, error) {
					if (params["noise"] == true) != tc.noise {
						return nil, model.Err("core_start_failed", "core")
					}
					return tunnel{&closed}, nil
				},
				Check: func(_ context.Context, _ socks.Endpoint, p model.Probe, round int) model.ProbeResult {
					return model.ProbeResult{ID: model.ID(), Probe: p.ID, Round: round, Outcome: "pass", Transport: "https-tcp", HTTPStatus: 200, TLSVerified: true, ContentVerified: true, MIME: "text/html", Bytes: p.MinBytes}
				},
				UDP: func(_ context.Context, _ socks.Endpoint, p model.UDPProbe, round int) model.ProbeResult {
					udpCalls++
					if p.ID != policy.NoiseDNSProbeID || p.Address != policy.NoiseDNSProbeAddress || p.Kind != "dns-a" {
						t.Fatal("unexpected DNS probe")
					}
					return model.ProbeResult{ID: model.ID(), Probe: p.ID, Round: round, Outcome: tc.udpOutcome, Transport: "udp-socks5"}
				},
				Export: func(model.Attempt) error { exports++; return nil },
			}
			status, err := e.Run(context.Background(), m, profile.Profiles, make([]byte, 32), g, s)
			if err != nil || status != tc.wantStatus || udpCalls != tc.wantUDP {
				t.Fatalf("status %s, UDP %d, err %v", status, udpCalls, err)
			}
			if tc.wantStatus == "success" && exports != 1 || tc.wantStatus != "success" && exports != 0 {
				t.Fatal("wrong export count", exports)
			}
		})
	}
}

func TestNoiseDNSRespectsPayloadBudgetBeforeSending(t *testing.T) {
	for _, remaining := range []int64{511, 512, 513} {
		t.Run(strconv.FormatInt(remaining, 10), func(t *testing.T) {
			m, profiles, _, s := setup(t)
			m.Policy = policy.Default()
			m.Budgets.PayloadBytes = 4 << 20
			c := model.Candidate{Profile: 0, ID: model.ID(), Params: map[string]any{"noise": true}}
			if err := s.Begin(model.Attempt{ID: model.ID(), Candidate: c}, "1"); err != nil {
				t.Fatal(err)
			}
			closed, pages, udpCalls := 0, 0, 0
			e := Engine{
				Open: func(context.Context, model.Profile, map[string]any) (Tunnel, error) { return tunnel{&closed}, nil },
				Check: func(_ context.Context, _ socks.Endpoint, p model.Probe, round int) model.ProbeResult {
					pages++
					bytes := int64(2 << 20)
					if pages == 2 {
						bytes -= remaining
					}
					return model.ProbeResult{ID: model.ID(), Probe: p.ID, Round: round, Outcome: "pass", Transport: "https-tcp", HTTPStatus: 200, TLSVerified: true, ContentVerified: true, MIME: "text/html", Bytes: bytes}
				},
				UDP: func(_ context.Context, _ socks.Endpoint, p model.UDPProbe, round int) model.ProbeResult {
					udpCalls++
					return model.ProbeResult{ID: model.ID(), Probe: p.ID, Round: round, Outcome: "pass", Transport: "udp-socks5", Bytes: 512}
				},
			}
			outcome, err := e.attempt(context.Background(), m, profiles[0], c, s, 0)
			wantUDP, wantClosed := 0, 1
			if remaining >= 512 {
				wantUDP, wantClosed = 1, 2
			}
			if err != nil || outcome != "interrupted" || pages != 2 || udpCalls != wantUDP || closed != wantClosed {
				t.Fatalf("outcome %s, pages %d, UDP %d, closed %d, err %v", outcome, pages, udpCalls, closed, err)
			}
			if got := s.View().PayloadBytes; got > m.Budgets.PayloadBytes {
				t.Fatalf("payload budget exceeded: %d", got)
			}
		})
	}
}

func TestFailedNoiseProbeCountsTowardPayloadBudget(t *testing.T) {
	m, _, _, s := setup(t)
	m.Policy = policy.Default()
	m.Budgets.PayloadBytes = 4 << 20
	profile, err := importer.Parse([]byte(syntheticSS("192.0.2.1")), "uri")
	if err != nil {
		t.Fatal(err)
	}
	noise := []any{map[string]any{"type": "rand", "packet": "1", "delay": "0", "applyTo": "ip"}}
	g, err := space.New(space.Spec{Version: 2, Strategy: "exhaustive", Parameters: []space.Domain{
		{Name: "mux", Values: []any{false}},
		{Name: "noise", Values: []any{true}},
		{Name: "noises", Values: []any{noise}},
	}})
	if err != nil {
		t.Fatal(err)
	}
	closed, pages, udpCalls := 0, 0, 0
	e := Engine{
		Open: func(_ context.Context, _ model.Profile, params map[string]any) (Tunnel, error) {
			if params["noise"] != true {
				return nil, model.Err("core_start_failed", "core")
			}
			return tunnel{&closed}, nil
		},
		Check: func(_ context.Context, _ socks.Endpoint, p model.Probe, round int) model.ProbeResult {
			pages++
			bytes := int64(2 << 20)
			if pages == 2 {
				bytes -= 512
			}
			return model.ProbeResult{ID: model.ID(), Probe: p.ID, Round: round, Outcome: "pass", Transport: "https-tcp", Bytes: bytes}
		},
		UDP: func(_ context.Context, _ socks.Endpoint, p model.UDPProbe, round int) model.ProbeResult {
			udpCalls++
			return model.ProbeResult{ID: model.ID(), Probe: p.ID, Round: round, Transport: "udp-socks5", Outcome: "udp_reply_too_large", Bytes: 513}
		},
	}
	status, err := e.Run(context.Background(), m, profile.Profiles, make([]byte, 32), g, s)
	if err != nil || status != "paused_budget" || pages != 2 || udpCalls != 1 || closed != 1 || s.View().PayloadBytes != m.Budgets.PayloadBytes+1 {
		t.Fatalf("failed UDP accounting: status=%s err=%v pages=%d udp=%d closed=%d bytes=%d", status, err, pages, udpCalls, closed, s.View().PayloadBytes)
	}
}

func TestMissingNoiseProbeStopsAndPreservesCleanupError(t *testing.T) {
	for _, failClose := range []bool{false, true} {
		t.Run(strconv.FormatBool(failClose), func(t *testing.T) {
			m, profiles, _, s := setup(t)
			m.Policy = policy.Default()
			c := model.Candidate{Profile: 0, ID: model.ID(), Params: map[string]any{"noise": true}}
			if err := s.Begin(model.Attempt{ID: model.ID(), Candidate: c}, "1"); err != nil {
				t.Fatal(err)
			}
			closed := 0
			e := Engine{
				Open: func(context.Context, model.Profile, map[string]any) (Tunnel, error) {
					if failClose {
						return closeErrorTunnel{&closed}, nil
					}
					return tunnel{&closed}, nil
				},
				Check: func(_ context.Context, _ socks.Endpoint, p model.Probe, round int) model.ProbeResult {
					return model.ProbeResult{ID: model.ID(), Probe: p.ID, Round: round, Outcome: "pass", Transport: "https-tcp", HTTPStatus: 200, TLSVerified: true, ContentVerified: true, MIME: "text/html", Bytes: p.MinBytes}
				},
			}
			outcome, err := e.attempt(context.Background(), m, profiles[0], c, s, 0)
			if closed != 1 {
				t.Fatal("core was not closed", closed)
			}
			if failClose {
				if model.Code(err) != "core_kill_failed" {
					t.Fatal("cleanup failure was lost", err)
				}
			} else if err != nil || outcome != "capability_blocked" {
				t.Fatal("missing UDP probe did not block the candidate", outcome, err)
			}
		})
	}
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

func TestSavedWinnerContinuesFromCheckpointWithoutRepeating(t *testing.T) {
	m, p, _, s := setup(t)
	input, err := importer.Parse([]byte("vless://synthetic-id@vpn.invalid:443?encryption=none"), "uri")
	if err != nil {
		t.Fatal(err)
	}
	p = input.Profiles
	g, err := space.New(space.Spec{Version: 1, Strategy: "exhaustive", Parameters: []space.Domain{{Name: "mux", Values: []any{false, true}}}})
	if err != nil {
		t.Fatal(err)
	}
	key := make([]byte, 32)
	closed, opens, exports := 0, 0, 0
	e := Engine{Open: func(context.Context, model.Profile, map[string]any) (Tunnel, error) {
		opens++
		return tunnel{&closed}, nil
	}, Check: pass, Export: func(model.Attempt) error { exports++; return nil }}
	if status, err := e.Run(context.Background(), m, p, key, g, s); err != nil || status != "success" {
		t.Fatal(status, err)
	}
	first := s.View().Winner.Candidate.ID
	e.Saved = map[string]bool{first: true}
	dir := s.Dir
	s.Close()
	s, err = state.Open(dir, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if status, err := e.Run(context.Background(), m, p, key, g, s); err != nil || status != "success" {
		t.Fatal(status, err)
	}
	v := s.View()
	if exports != 2 || opens != 4 || closed != 4 || v.Winner.Candidate.ID == first || v.LastSaved == nil || v.LastSaved.Candidate.ID != first || v.Next != "3" || !s.Seen(first) {
		t.Fatal("saved winner repeated or checkpoint lost", exports, opens, closed, v)
	}
	e.Saved[v.Winner.Candidate.ID] = true
	e.Open = func(context.Context, model.Profile, map[string]any) (Tunnel, error) {
		t.Fatal("saved result tested again")
		return nil, nil
	}
	if status, err := e.Run(context.Background(), m, p, key, g, s); err != nil || status != "space_exhausted" || exports != 2 {
		t.Fatal(status, err, exports)
	}
}

func TestPreviouslySavedCandidateSkipsNetworkInNewSeries(t *testing.T) {
	m, p, _, s := setup(t)
	input, err := importer.Parse([]byte("vless://synthetic-id@vpn.invalid:443?encryption=none"), "uri")
	if err != nil {
		t.Fatal(err)
	}
	p = input.Profiles
	g, err := space.New(space.Spec{Version: 1, Strategy: "exhaustive", Parameters: []space.Domain{{Name: "mux", Values: []any{true}}}})
	if err != nil {
		t.Fatal(err)
	}
	key := make([]byte, 32)
	c, _, _ := Candidate(p, key, g, big.NewInt(0))
	closed := 0
	e := Engine{Saved: map[string]bool{c.ID: true}, Open: func(_ context.Context, _ model.Profile, params map[string]any) (Tunnel, error) {
		if params["mux"] != true {
			t.Fatal("saved baseline tested again")
		}
		return tunnel{&closed}, nil
	}, Check: pass, Export: func(model.Attempt) error { return nil }}
	if status, err := e.Run(context.Background(), m, p, key, g, s); err != nil || status != "success" || closed != 2 || s.View().Winner.Candidate.ID == c.ID {
		t.Fatal(status, err, closed)
	}
}

func TestExportFailureRetainsWinnerForRecovery(t *testing.T) {
	m, p, g, s := setup(t)
	closed := 0
	e := Engine{Open: func(context.Context, model.Profile, map[string]any) (Tunnel, error) { return tunnel{&closed}, nil }, Check: pass, Export: func(model.Attempt) error { return model.Err("write_failed", "export") }}
	if status, err := e.Run(context.Background(), m, p, make([]byte, 32), g, s); status != "result_save_failed" || model.Code(err) != "write_failed" || s.View().Winner == nil {
		t.Fatal(status, err)
	}
	e.Open = func(context.Context, model.Profile, map[string]any) (Tunnel, error) {
		t.Fatal("searched before recovering export")
		return nil, nil
	}
	e.Export = func(model.Attempt) error { return nil }
	if status, err := e.Run(context.Background(), m, p, make([]byte, 32), g, s); err != nil || status != "success" || s.View().Attempts != 1 {
		t.Fatal(status, err)
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

func TestProgressTracksRunningChecksAndFailures(t *testing.T) {
	for _, outcome := range []string{"pass", "request_failed", "interrupted", "core_start_failed"} {
		t.Run(outcome, func(t *testing.T) {
			m, p, g, s := setup(t)
			m.Budgets.Attempts = 1
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			var events []Progress
			closed := 0
			e := Engine{Progress: func(p Progress) { events = append(events, p) }, Open: func(context.Context, model.Profile, map[string]any) (Tunnel, error) {
				if events[len(events)-1].Event != "core_starting" {
					t.Fatal("core started before progress was reported")
				}
				if outcome == "core_start_failed" {
					return nil, model.Err(outcome, "core")
				}
				return tunnel{&closed}, nil
			}, Check: func(ctx context.Context, proxy socks.Endpoint, pr model.Probe, round int) model.ProbeResult {
				if events[len(events)-1].Event != "probe_started" {
					t.Fatal("probe started before progress was reported")
				}
				r := pass(ctx, proxy, pr, round)
				if outcome == "request_failed" {
					r.Outcome = outcome
				}
				if outcome == "interrupted" {
					cancel()
				}
				return r
			}, Export: func(model.Attempt) error { return nil }}
			status, err := e.Run(ctx, m, p, make([]byte, 32), g, s)
			wantStatus, wantOutcome := "paused_budget", "failed"
			if outcome == "pass" {
				wantStatus, wantOutcome = "success", "pass"
			} else if outcome == "interrupted" {
				wantStatus, wantOutcome = "interrupted", "interrupted"
			}
			if err != nil || status != wantStatus {
				t.Fatal(status, err)
			}
			if len(events) == 0 || events[0].Event != "attempt_started" {
				t.Fatal("attempt start missing", events)
			}
			var result Progress
			for _, event := range events {
				if event.Event == "attempt_finished" {
					result = event
				}
			}
			if result.Outcome != wantOutcome {
				t.Fatal("attempt result missing", events)
			}
			foundFailure := false
			for _, event := range events {
				if event.Attempt != 1 || event.Event != "budget_reached" && event.Profile != 1 {
					t.Fatal("wrong attempt context", event)
				}
				if (event.Event == "probe_finished" || event.Event == "core_failed") && event.Outcome == outcome {
					foundFailure = true
				}
			}
			if (outcome == "request_failed" || outcome == "core_start_failed") && !foundFailure {
				t.Fatal("failure reason missing", events)
			}
			if s.View().Pending != nil || (outcome == "interrupted") != (s.View().Retry != nil) {
				t.Fatal("progress changed recovery", s.View())
			}
		})
	}
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
