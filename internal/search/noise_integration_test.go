package search

import (
	"bytes"
	"context"
	"encoding/json"
	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/core"
	"github.com/Seraf-seraf/vpn-sweep/internal/export"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/policy"
	"github.com/Seraf-seraf/vpn-sweep/internal/probe"
	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
	"github.com/Seraf-seraf/vpn-sweep/internal/space"
	"github.com/Seraf-seraf/vpn-sweep/internal/state"
	"github.com/Seraf-seraf/vpn-sweep/internal/testkit"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestNoiseCandidateRunsDNSOverSOCKSInBothRounds(t *testing.T) {
	dns, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer dns.Close()
	go func() {
		buf := make([]byte, 512)
		for {
			n, peer, err := dns.ReadFrom(buf)
			if err != nil {
				return
			}
			if n < 12 {
				continue
			}
			reply := append([]byte(nil), buf[:n]...)
			reply[2], reply[3], reply[6], reply[7] = 0x81, 0x80, 0, 1
			reply = append(reply, 0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 203, 0, 113, 10)
			_, _ = dns.WriteTo(reply, peer)
		}
	}()
	binary, identity := testkit.FakeCore(t)
	m, _, _, store := setup(t)
	m.Policy = policy.Default()
	m.Core = identity
	m.Budgets.SessionSeconds = 20
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
	key := make([]byte, 32)
	m.InputID = model.PrivateID(key, profile.Profiles)
	m.SeriesID = model.ID()
	m.TrustID = "system"
	m.Space, err = json.Marshal(g.Spec)
	if err != nil {
		t.Fatal(err)
	}
	if err = store.SaveManifest(m, profile.Profiles, key); err != nil {
		t.Fatal(err)
	}
	manifestPath := filepath.Join(store.Dir, "manifest.json")
	frozen, err := os.ReadFile(manifestPath)
	if err != nil {
		t.Fatal(err)
	}
	runner := core.Runner{Binary: binary, Identity: identity, Directory: t.TempDir(), Startup: 5 * time.Second, Cleanup: 5 * time.Second}
	udpCalls, exports := 0, 0
	output := t.TempDir()
	e := Engine{
		Open: func(ctx context.Context, p model.Profile, params map[string]any) (Tunnel, error) {
			if params["noise"] != true {
				return nil, model.Err("core_start_failed", "core")
			}
			addr, err := core.ReserveAddress()
			if err != nil {
				return nil, err
			}
			cfg, err := capability.Render(p, params, addr, "u", "p")
			if err != nil {
				return nil, err
			}
			var rendered map[string]any
			if err := json.Unmarshal(cfg, &rendered); err != nil {
				return nil, err
			}
			outbounds := rendered["outbounds"].([]any)
			if len(outbounds) != 2 || outbounds[1].(map[string]any)["settings"].(map[string]any)["noises"] == nil {
				t.Fatal("noise was not rendered into the candidate")
			}
			return runner.Start(ctx, cfg, socks.Endpoint{Address: addr, User: "u", Pass: "p"})
		},
		Check: func(_ context.Context, _ socks.Endpoint, p model.Probe, round int) model.ProbeResult {
			return model.ProbeResult{ID: model.ID(), Probe: p.ID, Round: round, Outcome: "pass", Transport: "https-tcp", HTTPStatus: 200, TLSVerified: true, ContentVerified: true, MIME: "text/html", Bytes: p.MinBytes}
		},
		UDP: func(ctx context.Context, endpoint socks.Endpoint, p model.UDPProbe, round int) model.ProbeResult {
			if p.Address != policy.NoiseDNSProbeAddress || p.Kind != "dns-a" {
				t.Fatal("wrong frozen UDP policy")
			}
			udpCalls++
			return probe.UDPDNS(ctx, endpoint, p.ID, dns.LocalAddr().String(), round)
		},
		Export: func(a model.Attempt) error { exports++; return export.Save(output, m, profile.Profiles, a) },
	}
	status, err := e.Run(context.Background(), m, profile.Profiles, key, g, store)
	if err != nil || status != "success" || udpCalls != 2 || exports != 1 {
		t.Fatal(status, err, udpCalls, exports)
	}
	winner := store.View().Winner
	if winner == nil || winner.Candidate.Params["noise"] != true {
		t.Fatal("verified noise candidate was not preserved")
	}
	for round := 0; round < 2; round++ {
		found := false
		for _, result := range winner.Results {
			if result.Probe == policy.NoiseDNSProbeID && result.Round == round && result.Transport == "udp-socks5" && result.Outcome == "pass" {
				found = true
			}
		}
		if !found {
			t.Fatal("missing DNS evidence", round)
		}
	}
	report, err := export.ReadReport(output)
	if err != nil || report.CandidateID != winner.Candidate.ID || report.VerificationScope != policy.Scope(m.Policy) {
		t.Fatal("noise result was not exported with its scope", err)
	}
	dir := store.Dir
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	restored, err := state.Open(dir, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer restored.Close()
	loaded, profiles, loadedKey, err := restored.LoadManifest()
	if err != nil || loaded.Policy.Version != policy.GoogleYouTubeWebNoise || len(loaded.Policy.UDP) != 1 || !loaded.Policy.UDP[0].NoiseOnly || loaded.Policy.UDP[0].Kind != "dns-a" {
		t.Fatal("noise policy was not restored", err)
	}
	if restored.View().Winner == nil || policy.Evaluate(loaded.Policy, *restored.View().Winner) != "pass" {
		t.Fatal("noise evidence was not restored")
	}
	var savedSpace space.Spec
	if err = model.Decode(loaded.Space, &savedSpace); err != nil {
		t.Fatal(err)
	}
	restoredGenerator, err := space.New(savedSpace)
	if err != nil {
		t.Fatal(err)
	}
	e.Saved, err = export.SavedCandidates(output)
	if err != nil {
		t.Fatal(err)
	}
	status, err = e.Run(context.Background(), loaded, profiles, loadedKey, restoredGenerator, restored)
	if err != nil || status != "space_exhausted" || udpCalls != 2 || exports != 1 || restored.View().LastSaved == nil || restored.View().LastSaved.Candidate.ID != winner.Candidate.ID {
		t.Fatal("resume repeated or lost a verified noise candidate", status, err, udpCalls, exports)
	}
	after, err := os.ReadFile(manifestPath)
	if err != nil || !bytes.Equal(frozen, after) {
		t.Fatal("resume changed the frozen policy or space", err)
	}
}
