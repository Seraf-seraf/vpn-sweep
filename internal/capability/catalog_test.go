package capability

import (
	"encoding/json"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"reflect"
	"testing"
)

func TestRenderPreservesContract(t *testing.T) {
	r, e := importer.Parse([]byte("vless://synthetic@vpn.invalid:443?type=ws&security=tls&sni=tls.invalid&host=ws.invalid&path=%2Fcontract"), "uri")
	if e != nil {
		t.Fatal(e)
	}
	p := r.Profiles[0]
	params := map[string]any{"fragment": true, "length": "50-100", "interval": "10-20", "packets": "tlshello", "maxSplit": "100-200", "mux": true, "tcpConcurrency": float64(8)}
	b, e := Render(p, params, "127.0.0.1:10801", "local-user", "local-pass")
	if e != nil {
		t.Fatal(e)
	}
	var cfg map[string]any
	json.Unmarshal(b, &cfg)
	o := cfg["outbounds"].([]any)[0].(map[string]any)
	if !reflect.DeepEqual(o["settings"], p.Outbound["settings"]) {
		t.Fatal("server credentials changed")
	}
	s := o["streamSettings"].(map[string]any)
	src := p.Outbound["streamSettings"].(map[string]any)
	for _, k := range []string{"wsSettings", "tlsSettings", "network", "security"} {
		if !reflect.DeepEqual(s[k], src[k]) {
			t.Fatal("contract changed", k)
		}
	}
	if len(cfg["outbounds"].([]any)) != 2 {
		t.Fatal("missing dialer")
	}
}
func TestGatesAndRange(t *testing.T) {
	if Gate("happ", model.TargetCommit) == nil || Gate("core", "latest") == nil || Gate("other", model.TargetCommit) == nil {
		t.Fatal("gate bypass")
	}
	for _, v := range []string{"0-1", "2-1", "-1", "1-99999999999999"} {
		if ValidateValue("length", v) == nil {
			t.Fatal("bad range accepted")
		}
	}
	if ValidateValue("tcpConcurrency", float64(129)) == nil {
		t.Fatal("UI limit trusted")
	}
	if ValidateValue("xudpConcurrency", float64(1024)) != nil {
		t.Fatal("wrong UDP limit")
	}
	if ValidateValue("noises", true) == nil {
		t.Fatal("guessed noise mapping")
	}
}

func TestCanonicalEquivalentRangesAndDisabledNoise(t *testing.T) {
	a := Canonical(map[string]any{"fragment": true, "length": "01-01", "noise": false, "noises": []any{"inactive"}})
	b := Canonical(map[string]any{"fragment": true, "length": "1", "noise": false})
	if !reflect.DeepEqual(a, b) {
		t.Fatal("equivalent fields not canonical")
	}
}
