package capability

import (
	"encoding/json"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"testing"
)

func TestNativeUDPNoiseOnly(t *testing.T) {
	ss, e := importer.Parse([]byte("ss://YWVzLTEyOC1nY206c3ludGhldGlj@vpn.invalid:8388"), "uri")
	if e != nil {
		t.Fatal(e)
	}
	params := map[string]any{"noise": true, "noises": []any{map[string]any{"type": "rand", "packet": "50-150", "delay": "10-16", "applyTo": "ip"}}}
	b, e := Render(ss.Profiles[0], params, "127.0.0.1:10808", "u", "p")
	if e != nil {
		t.Fatal(e)
	}
	var cfg map[string]any
	json.Unmarshal(b, &cfg)
	settings := cfg["outbounds"].([]any)[1].(map[string]any)["settings"].(map[string]any)
	if settings["noises"] == nil {
		t.Fatal("noise lost")
	}
	ws, _ := importer.Parse([]byte("vless://synthetic@vpn.invalid:443?type=ws&security=tls"), "uri")
	if Check(ws.Profiles[0], params) == nil {
		t.Fatal("inner UDP treated as external UDP")
	}
	for _, noise := range []any{[]any{map[string]any{"type": "array", "packet": "1A,2B"}}, []any{map[string]any{"type": "rand", "packet": "1-8192", "RandRange": "0-255"}}, []any{map[string]any{"type": "hex", "packet": "badhex"}}} {
		if ValidateValue("noises", noise) == nil {
			t.Fatal("unsupported noise mapping")
		}
	}
}
