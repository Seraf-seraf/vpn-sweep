package policy

import (
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"reflect"
)

const WebConnectivity = "web-connectivity-v1"
const connectivityTimeoutSeconds = 45

func Default() model.Policy {
	p := model.Policy{Version: WebConnectivity, Rounds: 2, MinOperators: 2}
	for _, target := range []struct{ id, operator, url string }{
		{"google-home", "google", "https://www.google.com/"},
		{"youtube-home", "google", "https://www.youtube.com/"},
		{"github-home", "github", "https://github.com/"},
	} {
		p.Probes = append(p.Probes, model.Probe{ID: target.id, Operator: target.operator, URL: target.url, Kind: "connectivity", TimeoutSeconds: connectivityTimeoutSeconds})
	}
	p.UDP = []model.UDPProbe{{ID: NoiseDNSProbeID, Address: NoiseDNSProbeAddress, TimeoutSeconds: NoiseDNSProbeTimeoutSeconds, Kind: "dns-a", NoiseOnly: true}}
	return p
}

func validateWebConnectivity(p model.Policy) error {
	// A version names one immutable built-in contract, including its targets.
	want := Default()
	if p.Rounds != want.Rounds || p.MinOperators != want.MinOperators || p.Downloads != 0 || p.MinDownloadBytes != 0 || p.RequireUDP || p.RequireQUIC || p.Video != nil || len(p.Probes) != len(want.Probes) || len(p.UDP) != 1 || p.UDP[0] != want.UDP[0] {
		return model.Err("invalid_connectivity_policy", "policy")
	}
	for i, pr := range p.Probes {
		if !reflect.DeepEqual(pr, want.Probes[i]) {
			return model.Err("invalid_connectivity_probe", "policy")
		}
	}
	return nil
}
