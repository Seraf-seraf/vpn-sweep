package policy

import (
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"strings"
	"testing"
)

func validPolicy() model.Policy {
	return model.Policy{Version: "custom-v1", Rounds: 2, MinOperators: 1, Downloads: 1, MinDownloadBytes: 1, Probes: []model.Probe{{ID: "page", Operator: "a", URL: "https://a.invalid", Kind: "page", Contains: "expected", Status: 200, TimeoutSeconds: 45}, {ID: "data", Operator: "b", URL: "https://b.invalid", Kind: "download", SHA256: strings.Repeat("0", 64), Bytes: 1, Status: 200, TimeoutSeconds: 90}}}
}
func TestPolicyCompleteOnly(t *testing.T) {
	p := validPolicy()
	a := model.Attempt{Restarts: 1, Results: []model.ProbeResult{{Probe: "page", Round: 0, Outcome: "pass", TLSVerified: true, HTTPStatus: 200, Transport: "https-tcp"}, {Probe: "data", Round: 0, Outcome: "pass", TLSVerified: true, HTTPStatus: 200, Transport: "https-tcp", Bytes: 1}, {Probe: "page", Round: 1, Outcome: "pass", TLSVerified: true, HTTPStatus: 200, Transport: "https-tcp"}}}
	if Validate(p) != nil || Evaluate(p, a) != "pass" {
		t.Fatal("full policy")
	}
	a.Restarts = 0
	if Evaluate(p, a) == "pass" {
		t.Fatal("no restart")
	}
	a.Restarts = 1
	a.Results = a.Results[:2]
	if Evaluate(p, a) == "pass" {
		t.Fatal("partial")
	}
	a.Results = append(a.Results, model.ProbeResult{Probe: "page", Round: 1, Outcome: "site_rate_limited"})
	if Evaluate(p, a) != "inconclusive" {
		t.Fatal("site blamed on tunnel")
	}
	p.RequireQUIC = true
	if Validate(p) == nil {
		t.Fatal("unsupported QUIC")
	}
}
