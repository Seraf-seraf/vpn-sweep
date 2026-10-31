package capability

import (
	"encoding/json"
	"reflect"
	"testing"

	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

func fragmentTestProfile(t *testing.T) model.Profile {
	t.Helper()
	r, err := importer.Parse([]byte("vless://synthetic@vpn.invalid:443?type=ws&security=tls&sni=tls.invalid"), "uri")
	if err != nil || len(r.Profiles) != 1 {
		t.Fatal("synthetic profile rejected", err)
	}
	return r.Profiles[0]
}

func renderedFragment(t *testing.T, p model.Profile, params map[string]any) map[string]any {
	t.Helper()
	b, err := Render(p, params, "127.0.0.1:10801", "local-user", "local-pass")
	if err != nil {
		t.Fatal("render failed", err)
	}
	var cfg struct {
		Outbounds []map[string]any `json:"outbounds"`
	}
	if err := json.Unmarshal(b, &cfg); err != nil {
		t.Fatal(err)
	}
	if len(cfg.Outbounds) == 1 {
		return nil
	}
	if len(cfg.Outbounds) != 2 || cfg.Outbounds[1]["protocol"] != "freedom" {
		t.Fatal("unexpected outbound layout")
	}
	settings, ok := cfg.Outbounds[1]["settings"].(map[string]any)
	if !ok {
		t.Fatal("missing freedom settings")
	}
	fragment, ok := settings["fragment"].(map[string]any)
	if !ok {
		t.Fatal("missing fragment")
	}
	return fragment
}

func TestSourceFragmentBaselineAndOverrides(t *testing.T) {
	p := fragmentTestProfile(t)
	p.Fragment = &model.FragmentBaseline{Length: "100-200", Interval: "10-20", Packets: "tlshello"}
	baseline := map[string]any{"length": "100-200", "interval": "10-20", "packets": "tlshello"}
	if got := renderedFragment(t, p, nil); !reflect.DeepEqual(got, baseline) {
		t.Fatal("source fragment did not reach renderer", got)
	}
	effective := Effective(p, nil)
	if effective["fragment"] != true || effective["length"] != "100-200" || effective["interval"] != "10-20" || effective["packets"] != "tlshello" {
		t.Fatal("source fragment did not reach effective parameters", effective)
	}
	if _, exists := effective["maxSplit"]; exists {
		t.Fatal("absent maxSplit was invented")
	}
	if got := renderedFragment(t, p, map[string]any{"fragment": false}); got != nil {
		t.Fatal("explicit disable ignored")
	}
	override := map[string]any{"fragment": true, "length": "3-5", "interval": "1-2", "packets": "1-1", "maxSplit": "4"}
	want := map[string]any{"length": "3-5", "interval": "1-2", "packets": "1-1", "maxSplit": "4"}
	if got := renderedFragment(t, p, override); !reflect.DeepEqual(got, want) {
		t.Fatal("explicit override did not replace baseline", got)
	}
	if err := Check(p, map[string]any{"fragment": true, "length": "3"}); err == nil {
		t.Fatal("incomplete override inherited source fields")
	}
}

func TestSourceFragmentCloneAndValidation(t *testing.T) {
	p := fragmentTestProfile(t)
	legacy, err := json.Marshal(p)
	if err != nil {
		t.Fatal(err)
	}
	var legacyShape map[string]any
	if json.Unmarshal(legacy, &legacyShape) != nil || len(legacyShape) != 1 || legacyShape["outbound"] == nil {
		t.Fatal("legacy profile shape changed")
	}
	limit := "4-8"
	p.Fragment = &model.FragmentBaseline{Length: "2-6", Interval: "0-4", Packets: "tlshello", MaxSplit: &limit}
	clone := importer.Clone(p)
	if !reflect.DeepEqual(clone.Fragment, p.Fragment) || clone.Fragment == p.Fragment || clone.Fragment.MaxSplit == p.Fragment.MaxSplit {
		t.Fatal("clone did not preserve an independent baseline")
	}
	for _, invalid := range []*model.FragmentBaseline{
		{Length: "0", Interval: "1", Packets: "tlshello"},
		{Length: "1", Interval: "1001", Packets: "tlshello"},
		{Length: "1", Interval: "0", Packets: "unknown"},
		{Length: "1", Interval: "0", Packets: "tlshello", MaxSplit: func() *string { s := "0"; return &s }()},
	} {
		p.Fragment = invalid
		if Check(p, map[string]any{"fragment": false}) == nil {
			t.Fatal("invalid source baseline accepted")
		}
	}
}

func TestSourceMuxTargetDefaults(t *testing.T) {
	p := fragmentTestProfile(t)
	p.Outbound["mux"] = map[string]any{"enabled": true, "concurrency": float64(8)}
	effective := Effective(p, nil)
	if effective["mux"] != true || effective["tcpConcurrency"] != float64(8) || effective["xudpConcurrency"] != float64(0) || effective["quic"] != "reject" {
		t.Fatal("target mux defaults changed", effective)
	}
	b, err := Render(p, nil, "127.0.0.1:10801", "local-user", "local-pass")
	if err != nil {
		t.Fatal(err)
	}
	var cfg struct {
		Outbounds []map[string]any `json:"outbounds"`
	}
	if json.Unmarshal(b, &cfg) != nil || len(cfg.Outbounds) != 1 {
		t.Fatal("invalid rendered output")
	}
	mux, ok := cfg.Outbounds[0]["mux"].(map[string]any)
	if !ok || mux["enabled"] != true || mux["concurrency"] != float64(8) || mux["xudpConcurrency"] != float64(0) || mux["xudpProxyUDP443"] != "reject" {
		t.Fatal("rendered mux target differs", mux)
	}
}
