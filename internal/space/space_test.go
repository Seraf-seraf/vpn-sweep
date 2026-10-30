package space

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"math/big"
	"reflect"
	"strings"
	"testing"
)

func TestDefaultIncludesAllNoiseAndFingerprintFields(t *testing.T) {
	s := Default()
	domains := make(map[string]Domain)
	for _, d := range s.Parameters {
		domains[d.Name] = d
	}
	noise, ok := domains["noise"]
	if !ok || !reflect.DeepEqual(noise.Values, []any{false, true}) {
		t.Fatal("default space must include noise on and off")
	}
	fingerprint, ok := domains["fingerprint"]
	if !ok || !reflect.DeepEqual(fingerprint.Values, []any{"chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized"}) {
		t.Fatal("default fingerprint values are incomplete")
	}
	noises, ok := domains["noises"]
	if !ok {
		t.Fatal("default space must include noise packet settings")
	}
	if noises.count() != 756 {
		t.Fatalf("got %d noise settings, want 756", noises.count())
	}
	seen := map[string]bool{}
	types := map[string]bool{}
	delays := map[string]bool{}
	families := map[string]bool{}
	counts := map[int]bool{}
	packets := map[string]map[string]bool{}
	for i := int64(0); i < noises.count(); i++ {
		value := noises.value(i)
		if got := noises.index(value); got != i {
			t.Fatalf("noise position: got %d, want %d", got, i)
		}
		if err := capability.ValidateValue("noises", value); err != nil {
			t.Fatalf("invalid noise %d: %v", i, err)
		}
		id := model.Digest(value)
		if seen[id] {
			t.Fatalf("duplicate noise setting %d", i)
		}
		seen[id] = true
		items := value.([]any)
		counts[len(items)] = true
		item := items[0].(map[string]any)
		for _, other := range items[1:] {
			if !reflect.DeepEqual(item, other) {
				t.Fatal("a noise setting must repeat the same packet")
			}
		}
		typ := item["type"].(string)
		packet := item["packet"].(string)
		types[typ] = true
		delays[item["delay"].(string)] = true
		families[item["applyTo"].(string)] = true
		if packets[typ] == nil {
			packets[typ] = map[string]bool{}
		}
		switch typ {
		case "rand":
			packets[typ][packet] = true
		case "str", "hex", "base64":
			b := []byte(packet)
			var err error
			if typ == "hex" {
				b, err = hex.DecodeString(packet)
			} else if typ == "base64" {
				b, err = base64.StdEncoding.DecodeString(packet)
			}
			if err != nil || len(b) == 0 {
				t.Fatalf("invalid %s content", typ)
			}
			for _, c := range b {
				if c != b[0] {
					t.Fatal("content must repeat the selected byte")
				}
			}
			packets[typ][string(b)] = true
		default:
			t.Fatalf("unexpected type %q", typ)
		}
	}
	if len(types) != 4 || len(delays) != 3 || !delays["0"] || !delays["10-16"] || !delays["1000"] || len(families) != 3 || !families["ip"] || !families["ipv4"] || !families["ipv6"] || len(counts) != 4 {
		t.Fatal("missing noise types, delays, address families or packet counts")
	}
	if len(packets["rand"]) != 3 || !packets["rand"]["1"] || !packets["rand"]["50-150"] || !packets["rand"]["1200"] {
		t.Fatal("missing random packet lengths")
	}
	for _, typ := range []string{"str", "hex", "base64"} {
		for _, c := range []byte{'A', 'Z'} {
			for _, n := range []int{1, 64, 1200} {
				b := make([]byte, n)
				for i := range b {
					b[i] = c
				}
				if !packets[typ][string(b)] {
					t.Fatalf("missing %s content %q of length %d", typ, c, n)
				}
			}
		}
	}
	// Omission keeps the imported fingerprint; noise settings are absent when disabled.
	s.Strategy = "exhaustive"
	s.Parameters = []Domain{noise, noises, fingerprint}
	g, err := New(s)
	if err != nil {
		t.Fatal(err)
	}
	if g.Count().Int64() != 11*757 {
		t.Fatalf("unexpected conditional count %s", g.Count())
	}
	seenBaseline := false
	for i := int64(0); i < g.Count().Int64(); i++ {
		p, ok := g.At(big.NewInt(i))
		if !ok {
			t.Fatalf("missing candidate %d", i)
		}
		if p["noise"] == false && p["noises"] != nil {
			t.Fatal("disabled noise settings were retained")
		}
		if _, ok := p["fingerprint"]; !ok {
			seenBaseline = true
		}
	}
	if !seenBaseline {
		t.Fatal("the source fingerprint cannot be preserved")
	}
}

func TestDefaultKeepsStandardDomains(t *testing.T) {
	domains := map[string]Domain{}
	for _, d := range Default().Parameters {
		domains[d.Name] = d
	}
	values := map[string][]any{
		"fragment":        {false, true},
		"mux":             {false, true},
		"packets":         {"tlshello", "1-1", "1-3"},
		"tcpConcurrency":  {float64(-1), float64(0), float64(1), float64(8), float64(32), float64(128)},
		"xudpConcurrency": {float64(-1), float64(0), float64(8), float64(64), float64(1024)},
		"quic":            {"reject", "allow", "skip"},
	}
	for name, want := range values {
		if !reflect.DeepEqual(domains[name].Values, want) {
			t.Fatalf("%s changed: %v", name, domains[name].Values)
		}
	}
	for name, bounds := range map[string][2]int{"length": {1, 512}, "interval": {0, 50}, "maxSplit": {1, 256}} {
		d := domains[name]
		if d.Min == nil || d.Max == nil || *d.Min != bounds[0] || *d.Max != bounds[1] || d.Step != 1 || !d.Pair {
			t.Fatalf("%s bounds changed: %+v", name, d)
		}
		if got := d.value(d.index("10-50")); got != "10-50" {
			t.Fatalf("%s does not enumerate ranges: %v", name, got)
		}
	}
}

func TestDefaultSpaceRestoresFromCompactSpec(t *testing.T) {
	original, err := New(Default())
	if err != nil {
		t.Fatal(err)
	}
	raw, err := json.Marshal(original.Spec)
	if err != nil {
		t.Fatal(err)
	}
	if len(raw) > 4096 || strings.Contains(string(raw), strings.Repeat("A", 64)) {
		t.Fatal("noise payloads were materialized in the saved space")
	}
	var spec Spec
	if err := model.Decode(raw, &spec); err != nil {
		t.Fatal(err)
	}
	restored, err := New(spec)
	if err != nil || original.Count().Cmp(restored.Count()) != 0 {
		t.Fatal("space changed after restore", err)
	}
	for _, pos := range []*big.Int{big.NewInt(0), big.NewInt(1), new(big.Int).Sub(original.Count(), big.NewInt(1))} {
		a, okA := original.At(pos)
		b, okB := restored.At(pos)
		if !okA || !okB || !reflect.DeepEqual(a, b) {
			t.Fatalf("candidate %s changed after restore", pos)
		}
	}
}

func TestLegacyBroadSequenceIsStable(t *testing.T) {
	lo, hi := 1, 3
	s := Spec{Version: 1, Strategy: "broad", Seed: 42, Parameters: []Domain{
		{Name: "fragment", Values: []any{false, true}},
		{Name: "length", Min: &lo, Max: &hi, Step: 1, Pair: true},
		{Name: "mux", Values: []any{false, true}},
		{Name: "tcpConcurrency", Values: []any{float64(0), float64(8)}},
	}}
	g, err := New(s)
	if err != nil {
		t.Fatal(err)
	}
	var sequence []any
	for i := int64(0); i < g.Count().Int64(); i++ {
		p, ok := g.At(big.NewInt(i))
		if !ok {
			t.Fatal("legacy candidate missing", i)
		}
		sequence = append(sequence, p)
	}
	const original = "1570a23ee2955f3ebcd1aaf490f0654e4b3e2f96dcd80fb2d6c15b860ac07231"
	if got := model.Digest(sequence); got != original {
		t.Fatalf("legacy resume sequence changed: %s", got)
	}
}

func TestVersion2BroadVisitsSmallSpaceExactlyOnce(t *testing.T) {
	lo, hi := 1, 3
	s := Spec{Version: 2, Strategy: "exhaustive", Seed: 42, Parameters: []Domain{
		{Name: "fragment", Values: []any{false, true}},
		{Name: "length", Min: &lo, Max: &hi, Step: 1, Pair: true},
		{Name: "mux", Values: []any{false, true}},
		{Name: "tcpConcurrency", Values: []any{float64(0), float64(8)}},
		{Name: "fingerprint", Optional: true, Values: []any{"chrome", "firefox"}},
	}}
	exhaustive, err := New(s)
	if err != nil {
		t.Fatal(err)
	}
	s.Strategy = "broad"
	broad, err := New(s)
	if err != nil {
		t.Fatal(err)
	}
	restored, err := New(s)
	if err != nil {
		t.Fatal(err)
	}
	if exhaustive.Count().Cmp(broad.Count()) != 0 {
		t.Fatal("coverage changed the size of the space")
	}
	want, got := map[string]bool{}, map[string]bool{}
	for i := int64(0); i < exhaustive.Count().Int64(); i++ {
		pos := big.NewInt(i)
		e, ok := exhaustive.At(pos)
		if !ok {
			t.Fatal("missing exhaustive candidate", i)
		}
		b, ok := broad.At(pos)
		if !ok {
			t.Fatal("missing broad candidate", i)
		}
		r, ok := restored.At(pos)
		if !ok || !reflect.DeepEqual(b, r) {
			t.Fatal("broad sequence is not reproducible", i)
		}
		want[model.Digest(e)] = true
		id := model.Digest(b)
		if got[id] {
			t.Fatal("coverage repeated a candidate", i)
		}
		got[id] = true
	}
	if !reflect.DeepEqual(want, got) {
		t.Fatal("coverage changed the enumerated combinations")
	}
	if _, ok := broad.At(broad.Count()); ok {
		t.Fatal("broad search crossed its finite boundary")
	}
}

func TestVersion2NoiseCoverageKeepsEveryCombination(t *testing.T) {
	for _, preset := range []string{noisePresetIP, noisePresetIPv4, noisePresetIPv6} {
		t.Run(preset, func(t *testing.T) {
			s := Spec{Version: 2, Strategy: "exhaustive", Seed: 42, Parameters: []Domain{
				{Name: "noise", Values: []any{false, true}},
				{Name: "noises", Preset: preset},
			}}
			exhaustive, err := New(s)
			if err != nil {
				t.Fatal(err)
			}
			s.Strategy = "broad"
			broad, err := New(s)
			if err != nil {
				t.Fatal(err)
			}
			want, got := map[string]bool{}, map[string]bool{}
			for i := int64(0); i < exhaustive.Count().Int64(); i++ {
				pos := big.NewInt(i)
				e, _ := exhaustive.At(pos)
				b, _ := broad.At(pos)
				want[model.Digest(e)] = true
				id := model.Digest(b)
				if got[id] {
					t.Fatal("noise candidate repeated", i)
				}
				got[id] = true
			}
			if !reflect.DeepEqual(want, got) {
				t.Fatal("noise candidates lost during coverage")
			}
		})
	}
}

func TestCompleteConditionalGridAndSeed(t *testing.T) {
	lo, hi := 1, 3
	s := Spec{Version: 1, Strategy: "exhaustive", Parameters: []Domain{{Name: "fragment", Values: []any{false, true}}, {Name: "length", Min: &lo, Max: &hi, Step: 1, Pair: true}}}
	g, e := New(s)
	if e != nil {
		t.Fatal(e)
	}
	if g.Count().Int64() != 7 {
		t.Fatal(g.Count())
	}
	seen := map[string]bool{}
	for i := int64(0); i < 7; i++ {
		p, ok := g.At(big.NewInt(i))
		if !ok {
			t.Fatal("missing")
		}
		id := model.Digest(p)
		if seen[id] {
			t.Fatal("duplicate")
		}
		seen[id] = true
	}
	if _, ok := g.At(big.NewInt(7)); ok {
		t.Fatal("overflow")
	}
	s.Strategy = "broad"
	s.Seed = 42
	a, _ := New(s)
	b, _ := New(s)
	seen2 := map[string]bool{}
	for i := int64(0); i < 7; i++ {
		x, _ := a.At(big.NewInt(i))
		y, _ := b.At(big.NewInt(i))
		if model.Digest(x) != model.Digest(y) {
			t.Fatal("nondeterministic resume")
		}
		seen2[model.Digest(x)] = true
	}
	if len(seen2) != len(seen) {
		t.Fatal("not bijection")
	}
}
func TestPairOrderingAndHugeSpace(t *testing.T) {
	lo, hi := 1, 512
	d := Domain{Name: "length", Min: &lo, Max: &hi, Step: 1, Pair: true}
	if d.count() != 131328 || d.value(0) != "1-1" || d.value(d.count()-1) != "512-512" {
		t.Fatal("triangle")
	}
	g, e := New(Default())
	if e != nil {
		t.Fatal(e)
	}
	if g.Count().Cmp(big.NewInt(1000000)) < 0 {
		t.Fatal("small fixed grid")
	}
	for _, i := range []*big.Int{big.NewInt(0), new(big.Int).Sub(g.Count(), big.NewInt(1))} {
		if _, ok := g.At(i); !ok {
			t.Fatal("lazy boundary")
		}
	}
}

func TestRefinementBoundedAndDeterministic(t *testing.T) {
	g, e := New(Default())
	if e != nil {
		t.Fatal(e)
	}
	a := g.Neighbors(map[string]any{"fragment": true, "length": "50-100", "interval": "10-20", "maxSplit": "100-200", "packets": "tlshello", "mux": false})
	b := g.Neighbors(map[string]any{"fragment": true, "length": "50-100", "interval": "10-20", "maxSplit": "100-200", "packets": "tlshello", "mux": false})
	if len(a) > 8 || len(a) == 0 || model.Digest(a) != model.Digest(b) {
		t.Fatal("unbounded/nonreproducible")
	}
}
