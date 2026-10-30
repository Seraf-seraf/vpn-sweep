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
		if p.Video != nil {
			r := model.ProbeResult{Probe: media.YouTube, Round: round, Transport: "https-tcp", Outcome: "pass", TLSVerified: true, ContentVerified: true, Media: &model.MediaEvidence{Version: 1, Capability: media.YouTube, FormatID: "137", Height: 1080}}
			for i := 0; i < p.Video.Samples; i++ {
				r.Media.Samples = append(r.Media.Samples, model.MediaSample{Bytes: p.Video.SampleBytes, DurationNS: 1000000, MaxReadGapNS: 1000, SHA256: strings.Repeat("1", 64), ContainerVerified: true, TLSVerified: true, Status: 206})
			}
			a.Results = append(a.Results, r)
		}
	}
	return a
}

func legacyCombinedPolicy() model.Policy {
	p := Default()
	p.Version = GoogleYouTube
	p.UDP = nil
	p.Video = &model.VideoPolicy{Required: []string{media.YouTube}, Samples: 3, MinMbps: 10, MaxReadGapMS: 3000, SampleBytes: 4 << 20, TimeoutSeconds: 30, YouTube: &model.YouTubeReference{VideoID: "BaW_jenozKc", Permission: "synthetic", MinHeight: 1080}}
	return p
}

func TestDefaultRequiresPagesAndFreshRoundsWithoutVideo(t *testing.T) {
	p := Default()
	if p.Version != GoogleYouTubeWebNoise || p.Video != nil || len(p.Probes) != 2 || p.Rounds != 2 {
		t.Fatalf("unexpected default policy: %+v", p)
	}
	if err := Validate(p); err != nil {
		t.Fatal(err)
	}
	if result := Evaluate(p, builtinAttempt(p)); result != "pass" {
		t.Fatal(result)
	}
	for _, missing := range []string{"google-home", "youtube-home"} {
		a := builtinAttempt(p)
		var results []model.ProbeResult
		for _, r := range a.Results {
			if r.Probe != missing {
				results = append(results, r)
			}
		}
		a.Results = results
		if Evaluate(p, a) == "pass" {
			t.Fatal("missing page accepted", missing)
		}
	}
	a := builtinAttempt(p)
	a.Restarts = 0
	if Evaluate(p, a) == "pass" {
		t.Fatal("missing restart accepted")
	}
}

func TestDefaultNoiseRequiresUDPInEveryRound(t *testing.T) {
	p := Default()
	if len(p.UDP) != 1 || p.UDP[0].Address != "8.8.8.8:53" || p.UDP[0].Kind != "dns-a" || !p.UDP[0].NoiseOnly || p.UDP[0].TimeoutSeconds != 5 {
		t.Fatal("missing conditional DNS probe", p.UDP)
	}
	a := builtinAttempt(p)
	a.Candidate.Params = map[string]any{"noise": true}
	if result := Evaluate(p, a); result == "pass" {
		t.Fatal("noise was accepted without UDP evidence")
	}
	for round := 0; round < p.Rounds; round++ {
		a.Results = append(a.Results, model.ProbeResult{Probe: p.UDP[0].ID, Round: round, Transport: "udp-socks5", Outcome: "pass"})
	}
	if result := Evaluate(p, a); result != "pass" {
		t.Fatal("valid noise evidence rejected", result)
	}
	for _, mutate := range []func(*model.Attempt){
		func(a *model.Attempt) { a.Results = a.Results[:len(a.Results)-1] },
		func(a *model.Attempt) { a.Results[len(a.Results)-1].Outcome = "udp_timeout_or_read_failed" },
		func(a *model.Attempt) { a.Results[len(a.Results)-1].Transport = "https-tcp" },
	} {
		b := a
		b.Results = append([]model.ProbeResult(nil), a.Results...)
		mutate(&b)
		if Evaluate(p, b) == "pass" {
			t.Fatal("incomplete or invalid UDP evidence accepted")
		}
	}
	plain := builtinAttempt(p)
	if result := Evaluate(p, plain); result != "pass" {
		t.Fatal("web-only candidate was forced to use UDP", result)
	}
	plain.Results = append(plain.Results, a.Results[len(a.Results)-1])
	if Evaluate(p, plain) == "pass" {
		t.Fatal("unexpected noise UDP evidence accepted for web-only candidate")
	}
	invalid := p
	invalid.UDP = append([]model.UDPProbe(nil), p.UDP...)
	invalid.UDP[0].Address = "203.0.113.1:53"
	if Validate(invalid) == nil {
		t.Fatal("unverified default UDP endpoint accepted")
	}
}

func TestLegacyBuiltinRequiresPagesVideoAndFreshRounds(t *testing.T) {
	p := legacyCombinedPolicy()
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
	p := legacyCombinedPolicy()
	p.Probes = append(p.Probes, model.Probe{ID: "additional-site", Operator: "other", URL: "https://additional.invalid/", Kind: "page", ContainsAll: []string{"expected-title", "expected-form"}, MinBytes: 4096, MIME: "text/html", Status: 200, TimeoutSeconds: 45})
	if err := Validate(p); err != nil {
		t.Fatal(err)
	}
	if Evaluate(p, builtinAttempt(legacyCombinedPolicy())) == "pass" {
		t.Fatal("additional site ignored")
	}
	if result := Evaluate(p, builtinAttempt(p)); result != "pass" {
		t.Fatal(result)
	}
}
