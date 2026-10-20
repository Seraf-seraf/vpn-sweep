package policy

import (
	"github.com/Seraf-seraf/vpn-sweep/internal/media"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"strings"
	"testing"
)

func builtinAttempt(p model.Policy) model.Attempt {
	a := model.Attempt{Restarts: 1}
	for round := 0; round < p.Rounds; round++ {
		for _, pr := range p.Probes {
			a.Results = append(a.Results, model.ProbeResult{Probe: pr.ID, Round: round, Transport: "https-tcp", Outcome: "pass", HTTPStatus: 200, Bytes: pr.MinBytes, MIME: "text/html", TLSVerified: true, ContentVerified: true})
		}
		r := model.ProbeResult{Probe: media.YouTube, Round: round, Transport: "https-tcp", Outcome: "pass", TLSVerified: true, ContentVerified: true, Media: &model.MediaEvidence{Version: 1, Capability: media.YouTube, FormatID: "137", Height: 1080}}
		for i := 0; i < p.Video.Samples; i++ {
			r.Media.Samples = append(r.Media.Samples, model.MediaSample{Bytes: p.Video.SampleBytes, DurationNS: 1000000, MaxReadGapNS: 1000, SHA256: strings.Repeat("1", 64), ContainerVerified: true, TLSVerified: true, Status: 206})
		}
		a.Results = append(a.Results, r)
	}
	return a
}

func TestBuiltinRequiresPagesVideoAndFreshRounds(t *testing.T) {
	p := Default()
	if err := Validate(p); err != nil {
		t.Fatal(err)
	}
	if result := Evaluate(p, builtinAttempt(p)); result != "pass" {
		t.Fatal(result)
	}
	for _, missing := range []string{"google-home", "youtube-home", media.YouTube} {
		a := builtinAttempt(p)
		var results []model.ProbeResult
		for _, r := range a.Results {
			if r.Probe != missing {
				results = append(results, r)
			}
		}
		a.Results = results
		if Evaluate(p, a) == "pass" {
			t.Fatal("missing required check accepted", missing)
		}
	}
	for _, change := range []func(*model.Attempt){
		func(a *model.Attempt) { a.Restarts = 0 },
		func(a *model.Attempt) { a.Results[0].ContentVerified = false },
		func(a *model.Attempt) { a.Results[2].Media.Height = 720 },
		func(a *model.Attempt) { a.Results[2].Media.Samples[0].ContainerVerified = false },
		func(a *model.Attempt) { a.Results[2].Outcome = "resolver_blocked" },
	} {
		a := builtinAttempt(p)
		change(&a)
		if Evaluate(p, a) == "pass" {
			t.Fatal("incomplete evidence accepted")
		}
	}
}

func TestAdditionalBuiltinSitesBecomeRequired(t *testing.T) {
	p := Default()
	p.Probes = append(p.Probes, model.Probe{ID: "additional-site", Operator: "other", URL: "https://additional.invalid/", Kind: "page", ContainsAll: []string{"expected-title", "expected-form"}, MinBytes: 4096, MIME: "text/html", Status: 200, TimeoutSeconds: 45})
	if err := Validate(p); err != nil {
		t.Fatal(err)
	}
	if Evaluate(p, builtinAttempt(Default())) == "pass" {
		t.Fatal("additional site ignored")
	}
	if result := Evaluate(p, builtinAttempt(p)); result != "pass" {
		t.Fatal(result)
	}
}
