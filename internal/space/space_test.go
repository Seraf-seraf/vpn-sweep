package space

import (
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"math/big"
	"testing"
)

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
