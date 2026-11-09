package probe

import (
	"testing"

	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

func TestIsSuccessfulUserInterstitial(t *testing.T) {
	spec := model.Probe{ID: "google-home", Kind: "page", Status: 200}
	result := model.ProbeResult{
		Probe: "google-home", Outcome: "site_consent", Transport: "https-tcp", HTTPStatus: 200, TLSVerified: true,
	}
	if !IsSuccessfulUserInterstitial(spec, result) {
		t.Fatal("verified HTTP 200 consent response must establish connectivity")
	}
	result.Outcome = "site_challenge"
	if !IsSuccessfulUserInterstitial(spec, result) {
		t.Fatal("verified HTTP 200 challenge response must establish connectivity")
	}

	for name, mutate := range map[string]func(*model.Probe, *model.ProbeResult){
		"unexpected outcome":      func(_ *model.Probe, result *model.ProbeResult) { result.Outcome = "unexpected_content" },
		"wrong probe":             func(_ *model.Probe, result *model.ProbeResult) { result.Probe = "youtube-home" },
		"wrong status":            func(_ *model.Probe, result *model.ProbeResult) { result.HTTPStatus = 302 },
		"unverified TLS":          func(_ *model.Probe, result *model.ProbeResult) { result.TLSVerified = false },
		"wrong transport":         func(_ *model.Probe, result *model.ProbeResult) { result.Transport = "udp-socks5" },
		"not a page":              func(spec *model.Probe, _ *model.ProbeResult) { spec.Kind = "download" },
		"expected status differs": func(spec *model.Probe, _ *model.ProbeResult) { spec.Status = 204 },
	} {
		t.Run(name, func(t *testing.T) {
			mutatedSpec, mutatedResult := spec, result
			mutate(&mutatedSpec, &mutatedResult)
			if IsSuccessfulUserInterstitial(mutatedSpec, mutatedResult) {
				t.Fatal("invalid interstitial was accepted")
			}
		})
	}
}
