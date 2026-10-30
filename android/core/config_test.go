package mobile

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"
)

func TestPreserveCompleteOutbounds(t *testing.T) {
	source := []byte(`{"inbounds":[],"log":{"loglevel":"none"},"routing":{},"outbounds":[{"tag":"candidate","protocol":"vless","settings":{"vnext":[{"address":"example.invalid","port":443,"users":[{"id":"11111111-1111-4111-8111-111111111111","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"example.invalid","fingerprint":"chrome"},"sockopt":{"dialerProxy":"endpoint-fragment"}},"mux":{"enabled":true,"concurrency":8,"xudpConcurrency":64,"xudpProxyUDP443":"reject"}},{"tag":"endpoint-fragment","protocol":"freedom","settings":{"fragment":{"packets":"tlshello","length":"50-100","interval":"10-20","maxSplit":"100-200"}}}]}`)
	prepared, err := PrepareConfig(source)
	if err != nil {
		t.Fatal(err)
	}
	if err := ValidateConfig(source); err != nil {
		t.Fatal("Xray rejected representative result", err)
	}
	var before, after map[string]any
	_ = json.Unmarshal(source, &before)
	_ = json.Unmarshal(prepared, &after)
	if !reflect.DeepEqual(before["outbounds"], after["outbounds"]) {
		t.Fatal("saved outbounds changed")
	}
	inbound := after["inbounds"].([]any)[0].(map[string]any)
	if inbound["protocol"] != "tun" || strings.Contains(string(prepared), "probe-in") {
		t.Fatal("Android input not installed")
	}
	a, _ := OutboundDigest(source)
	b, _ := OutboundDigest(prepared)
	if a == "" || a != b {
		t.Fatal("outbound digest changed")
	}
	for _, dns := range []string{"192.0.2.53", "2001:db8::53"} {
		runtime, err := runtimeConfig(source, dns)
		if err != nil {
			t.Fatal(err)
		}
		var final map[string]any
		_ = json.Unmarshal(runtime, &final)
		if !reflect.DeepEqual(before["outbounds"], final["outbounds"]) || final["dns"] == nil {
			t.Fatal("runtime bootstrap changed outbounds or is absent")
		}
	}
}

func TestRejectInvalidResults(t *testing.T) {
	for _, source := range []string{"", "null", "[]", `{}`, `{"outbounds":[{"tag":"other"}]}`, `{"outbounds":[{"tag":"candidate"}],"api":{}}`, `{"outbounds":[{"tag":"candidate"}]} trailing`} {
		if _, err := PrepareConfig([]byte(source)); err == nil {
			t.Fatalf("accepted invalid result %q", source)
		}
	}
	if ValidateConfig([]byte(`{"outbounds":[{"tag":"candidate","protocol":"does-not-exist"}]}`)) == nil {
		t.Fatal("invalid Xray config accepted")
	}
}
