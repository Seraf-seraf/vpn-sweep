package mobile

import (
	"encoding/json"
	"strings"
	"testing"
)

func readApplied(t *testing.T, source string) map[string]displayParameter {
	t.Helper()
	data, err := AppliedParametersJSON([]byte(source))
	if err != nil {
		t.Fatal(err)
	}
	for _, secret := range []string{"example.invalid", "11111111-1111-4111-8111-111111111111", "serverName", "outbounds", "vpnSweepSelection"} {
		if strings.Contains(string(data), secret) {
			t.Fatal("private configuration leaked", secret)
		}
	}
	var projection struct {
		Version int
		Values  map[string]displayParameter
	}
	if err := json.Unmarshal(data, &projection); err != nil {
		t.Fatal(err)
	}
	if projection.Version != 1 || len(projection.Values) != 12 {
		t.Fatal("invalid projection", projection)
	}
	return projection.Values
}

func TestAppliedParametersUsesEffectiveOutboundNotSelectionMetadata(t *testing.T) {
	source := `{"vpnSweepSelection":{"fingerprint":"chrome","mux":false},"outbounds":[{"tag":"candidate","protocol":"vless","settings":{"vnext":[{"address":"example.invalid","port":443,"users":[{"id":"11111111-1111-4111-8111-111111111111","encryption":"none"}]}]},"streamSettings":{"security":"tls","tlsSettings":{"serverName":"example.invalid","fingerprint":"firefox"},"sockopt":{"dialerProxy":"fragment"}},"mux":{"enabled":true,"xudpConcurrency":16}},{"tag":"fragment","protocol":"freedom","settings":{"fragment":{"packets":"tlshello","length":"50-100","interval":"10"},"noises":[{"type":"rand","packet":"10-20","delay":"1-2"}]}}]}`
	values := readApplied(t, source)
	for key, want := range map[string]displayParameter{"fingerprint": {"firefox", "value"}, "mux": {"true", "value"}, "tcpConcurrency": {"8", "default"}, "xudpConcurrency": {"16", "value"}, "quic": {"reject", "value"}, "fragment": {"true", "value"}, "packets": {"tlshello", "value"}, "length": {"50-100", "value"}, "interval": {"10", "value"}, "maxSplit": {"", "default"}, "noise": {"true", "value"}, "noises": {"1", "value"}} {
		if values[key] != want {
			t.Errorf("%s=%+v, want %+v", key, values[key], want)
		}
	}
}

func TestAppliedParametersDefaultsAndUnreachableSupportingOutbound(t *testing.T) {
	values := readApplied(t, `{"outbounds":[{"tag":"candidate","protocol":"freedom","streamSettings":{"security":"tls","tlsSettings":{}}},{"tag":"unused","protocol":"freedom","settings":{"fragment":{"packets":"tlshello","length":"50-100","interval":"10"}}}]}`)
	for _, key := range []string{"fragment", "mux", "noise"} {
		if values[key].Value != "false" {
			t.Fatal(key, values[key])
		}
	}
	if values["fingerprint"] != (displayParameter{"chrome", "default"}) || values["packets"].State != "not_applicable" {
		t.Fatal(values)
	}
	for _, source := range []string{`{}`, `{"outbounds":[{"tag":"candidate","protocol":"invalid"}]}`, `{"outbounds":[{"tag":"candidate","protocol":"freedom","streamSettings":{"sockopt":{"dialerProxy":"missing"}}}]}`} {
		if _, err := AppliedParametersJSON([]byte(source)); err == nil {
			t.Fatal("invalid result accepted")
		}
	}
}

func TestAppliedParametersReadsInheritedRealityFingerprint(t *testing.T) {
	values := readApplied(t, `{"outbounds":[{"tag":"candidate","protocol":"vless","settings":{"vnext":[{"address":"example.invalid","port":443,"users":[{"id":"11111111-1111-4111-8111-111111111111","encryption":"none"}]}]},"streamSettings":{"security":"reality","realitySettings":{"serverName":"example.invalid","fingerprint":"safari","publicKey":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","shortId":""}}}]}`)
	if values["fingerprint"] != (displayParameter{"safari", "value"}) {
		t.Fatal(values["fingerprint"])
	}
}
