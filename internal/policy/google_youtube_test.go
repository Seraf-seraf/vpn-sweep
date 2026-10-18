package policy

import (
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"os"
	"testing"
)

func webPolicy(t *testing.T) model.Policy {
	t.Helper()
	b, e := os.ReadFile("../../examples/google-youtube-web-v1.json")
	if e != nil {
		t.Fatal(e)
	}
	var p model.Policy
	if e = model.Decode(b, &p); e != nil {
		t.Fatal(e)
	}
	return p
}
func TestGoogleYouTubePolicyScopeAndEvidence(t *testing.T) {
	p := webPolicy(t)
	if err := Validate(p); err != nil {
		t.Fatal(err)
	}
	a := model.Attempt{Restarts: 1}
	for round := 0; round < 2; round++ {
		for _, s := range p.Probes {
			a.Results = append(a.Results, model.ProbeResult{Probe: s.ID, Round: round, Outcome: "pass", TLSVerified: true, ContentVerified: true, MIME: "text/html", HTTPStatus: 200, Transport: "https-tcp", Bytes: s.MinBytes})
		}
	}
	if Evaluate(p, a) != "pass" || PresetStatus(p) != "live_unverified" {
		t.Fatal("full web policy")
	}
	for _, mutate := range []func(*model.Attempt){func(a *model.Attempt) { a.Restarts = 0 }, func(a *model.Attempt) { a.Results = a.Results[:3] }, func(a *model.Attempt) { a.Results[0].TLSVerified = false }, func(a *model.Attempt) { a.Results[0].ContentVerified = false }, func(a *model.Attempt) { a.Results[0].MIME = "text/plain" }, func(a *model.Attempt) { a.Results[0].Bytes-- }} {
		b := a
		b.Results = append([]model.ProbeResult(nil), a.Results...)
		mutate(&b)
		if Evaluate(p, b) == "pass" {
			t.Fatal("partial/invalid evidence accepted")
		}
	}
	a.Results[0].Outcome = "site_consent"
	if Evaluate(p, a) != "inconclusive" {
		t.Fatal("consent accepted or blamed on tunnel")
	}
	for _, mutate := range []func(*model.Policy){func(p *model.Policy) { p.MinOperators = 3 }, func(p *model.Policy) { p.Rounds = 1 }, func(p *model.Policy) { p.Probes[0].Operator = "other" }, func(p *model.Policy) { p.Probes[0].URL = "https://other.invalid" }, func(p *model.Policy) { p.Probes[0].ContainsAll = []string{"only"} }, func(p *model.Policy) { p.Probes[0].ContainsAll = []string{"same", "same"} }, func(p *model.Policy) { p.Probes[0].MinBytes = 1023 }, func(p *model.Policy) { p.RequireQUIC = true }, func(p *model.Policy) { p.Probes[0].RedirectHosts = []string{"consent.google.com"} }} {
		p = webPolicy(t)
		mutate(&p)
		if Validate(p) == nil {
			t.Fatal("weakened web scope accepted", p)
		}
	}
	p = webPolicy(t)
	p.Version = "web-basic-v1"
	if Validate(p) == nil {
		t.Fatal("legacy web-basic weakened")
	}
	p.Version = "custom-v1"
	if Validate(p) == nil {
		t.Fatal("legacy custom transfer requirement weakened")
	}
}
func TestCompositeFieldBounds(t *testing.T) {
	p := webPolicy(t)
	for _, n := range []int64{1024, 4096, 2 << 20} {
		p.Probes[0].MinBytes = n
		if Validate(p) != nil {
			t.Fatal(n)
		}
	}
	p.Probes[0].MinBytes = (2 << 20) + 1
	if Validate(p) == nil {
		t.Fatal("unbounded body accepted")
	}
}
