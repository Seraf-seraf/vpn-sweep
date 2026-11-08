package policy

import (
	"testing"

	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

func TestGoogleWebPolicyScopeAndEvidence(t *testing.T) {
	policy := GoogleWebDefault()
	if err := Validate(policy); err != nil {
		t.Fatal(err)
	}
	if policy.Version != GoogleWeb || policy.Rounds != 2 || policy.MinOperators != 1 ||
		len(policy.Probes) != 1 || policy.Probes[0].URL != "https://www.google.com/?hl=en" ||
		len(policy.UDP) != 0 || Scope(policy) != "google_homepage_only_not_general_internet" ||
		PresetStatus(policy) != "live_unverified" {
		t.Fatal("Google-only policy scope differs from its contract")
	}

	attempt := model.Attempt{Restarts: policy.Rounds - 1}
	for round := 0; round < policy.Rounds; round++ {
		for _, probe := range policy.Probes {
			attempt.Results = append(attempt.Results, model.ProbeResult{
				Probe: probe.ID, Round: round, Outcome: "pass", TLSVerified: true,
				ContentVerified: true, MIME: "text/html", HTTPStatus: 200,
				Transport: "https-tcp", Bytes: probe.MinBytes,
			})
		}
	}
	if Evaluate(policy, attempt) != "pass" {
		t.Fatal("complete Google homepage evidence was rejected")
	}

	invalidPolicies := []func(*model.Policy){
		func(p *model.Policy) { p.Probes[0].URL = "https://github.com/" },
		func(p *model.Policy) {
			p.Probes = append(p.Probes, model.Probe{ID: "youtube-home", URL: "https://www.youtube.com/"})
		},
		func(p *model.Policy) { p.Probes[0].MinBytes = 0 },
		func(p *model.Policy) { p.Rounds = 1 },
		func(p *model.Policy) { p.UDP = []model.UDPProbe{{ID: "noise-dns", Address: "8.8.8.8:53"}} },
	}
	for _, mutate := range invalidPolicies {
		invalid := GoogleWebDefault()
		mutate(&invalid)
		if Validate(invalid) == nil {
			t.Fatal("policy outside Google-only scope was accepted")
		}
	}

	attempt.Results[0].ContentVerified = false
	if Evaluate(policy, attempt) == "pass" {
		t.Fatal("page without verified content was accepted")
	}
}
