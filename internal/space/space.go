package space

import (
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"math/big"
	"sort"
	"strconv"
)

type Domain struct {
	Name     string `json:"name"`
	Values   []any  `json:"values,omitempty"`
	Min      *int   `json:"min,omitempty"`
	Max      *int   `json:"max,omitempty"`
	Step     int    `json:"step,omitempty"`
	Pair     bool   `json:"pair,omitempty"`
	Preset   string `json:"preset,omitempty"`
	Optional bool   `json:"optional,omitempty"`
}
type Spec struct {
	Version    int      `json:"version"`
	Strategy   string   `json:"strategy"`
	Seed       uint64   `json:"seed"`
	Parameters []Domain `json:"parameters"`
}
type Generator struct {
	Spec     Spec
	total    *big.Int
	stride   *big.Int
	offset   *big.Int
	coverage []*big.Int
	excluded []*big.Int
}

func Default() Spec {
	lo, hi, z, delay, split := 1, 512, 0, 50, 256
	return Spec{Version: 2, Strategy: "broad", Seed: 1, Parameters: []Domain{
		{Name: "fragment", Values: []any{false, true}},
		{Name: "mux", Values: []any{false, true}},
		{Name: "noise", Values: []any{false, true}},
		{Name: "noises", Preset: noisePreset},
		{Name: "fingerprint", Optional: true, Values: []any{"chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized"}},
		{Name: "packets", Values: []any{"tlshello", "1-1", "1-3"}},
		{Name: "length", Min: &lo, Max: &hi, Step: 1, Pair: true},
		{Name: "interval", Min: &z, Max: &delay, Step: 1, Pair: true},
		{Name: "maxSplit", Min: &lo, Max: &split, Step: 1, Pair: true},
		{Name: "tcpConcurrency", Values: []any{float64(-1), float64(0), float64(1), float64(8), float64(32), float64(128)}},
		{Name: "xudpConcurrency", Values: []any{float64(-1), float64(0), float64(8), float64(64), float64(1024)}},
		{Name: "quic", Values: []any{"reject", "allow", "skip"}},
	}}
}
func New(s Spec) (*Generator, error) {
	if (s.Version != 1 && s.Version != 2) || (s.Strategy != "exhaustive" && s.Strategy != "broad") {
		return nil, model.Err("unsupported_space_version_or_strategy", "space")
	}
	seen := map[string]bool{}
	for _, d := range s.Parameters {
		if seen[d.Name] {
			return nil, model.Err("duplicate_parameter", "space")
		}
		seen[d.Name] = true
		if d.Optional && (s.Version != 2 || d.Name != "fingerprint" || len(d.Values) == 0) {
			return nil, model.Err("invalid_domain", "space")
		}
		if d.Preset != "" {
			_, supported := presetFamilies(d.Preset)
			if s.Version != 2 || d.Name != "noises" || !supported || d.Optional || len(d.Values) != 0 || d.Min != nil || d.Max != nil || d.Step != 0 || d.Pair {
				return nil, model.Err("invalid_domain", "space")
			}
			continue
		}
		if len(d.Values) > 0 {
			if d.Min != nil || d.Max != nil || d.Pair {
				return nil, model.Err("ambiguous_domain", "space")
			}
			vals := map[string]bool{}
			for _, v := range d.Values {
				if e := capability.ValidateValue(d.Name, v); e != nil {
					return nil, e
				}
				key := model.Digest(capability.Normalize(d.Name, v))
				if vals[key] {
					return nil, model.Err("duplicate_domain_value", "space")
				}
				vals[key] = true
			}
		} else {
			if d.Min == nil || d.Max == nil || *d.Min > *d.Max || d.Step < 1 || int64(*d.Max)-int64(*d.Min) > 1000000 {
				return nil, model.Err("invalid_domain", "space")
			}
			for _, v := range []any{d.value(0), d.value(d.count() - 1)} {
				if e := capability.ValidateValue(d.Name, v); e != nil {
					return nil, e
				}
			}
		}
	}
	for _, name := range []string{"packets", "length", "interval", "maxSplit"} {
		if seen[name] && !seen["fragment"] {
			return nil, model.Err("missing_activation_parameter", "space")
		}
	}
	for _, name := range []string{"tcpConcurrency", "xudpConcurrency", "quic"} {
		if seen[name] && !seen["mux"] {
			return nil, model.Err("missing_activation_parameter", "space")
		}
	}
	if seen["noises"] && !seen["noise"] {
		return nil, model.Err("missing_activation_parameter", "space")
	}
	// Activation fields precede their dependent fields; all other names have a stable order.
	sort.SliceStable(s.Parameters, func(i, j int) bool {
		rank := func(n string) string {
			if n == "fragment" {
				return "0"
			}
			if n == "mux" {
				return "1"
			}
			if n == "noise" {
				return "2"
			}
			return "3" + n
		}
		return rank(s.Parameters[i].Name) < rank(s.Parameters[j].Name)
	})
	g := &Generator{Spec: s}
	g.total = g.count(0, map[string]any{})
	g.stride = big.NewInt(1)
	g.offset = big.NewInt(0)
	if s.Strategy == "broad" && g.total.Sign() > 0 {
		h := sha256.Sum256([]byte(strconv.FormatUint(s.Seed, 10)))
		g.offset.SetUint64(binary.BigEndian.Uint64(h[:8]))
		g.offset.Mod(g.offset, g.total)
		g.stride.SetUint64(binary.BigEndian.Uint64(h[8:16]) | 1)
		g.stride.Mod(g.stride, g.total)
		if g.stride.Sign() == 0 {
			g.stride.SetInt64(1)
		}
		for new(big.Int).GCD(nil, nil, g.stride, g.total).Cmp(big.NewInt(1)) != 0 {
			g.stride.Add(g.stride, big.NewInt(1))
		}
	}
	if s.Version == 2 && s.Strategy == "broad" {
		g.prepareCoverage()
	}
	return g, nil
}
func (d Domain) count() int64 {
	if d.Preset != "" {
		return noiseCount(d.Preset)
	}
	if len(d.Values) > 0 {
		n := int64(len(d.Values))
		if d.Optional {
			n++
		}
		return n
	}
	n := int64((*d.Max-*d.Min)/d.Step + 1)
	if d.Pair {
		return n * (n + 1) / 2
	}
	return n
}
func (d Domain) value(index int64) any {
	if d.Preset != "" {
		return noiseValue(d.Preset, index)
	}
	if len(d.Values) > 0 {
		if d.Optional {
			if index == 0 {
				return nil
			}
			index--
		}
		return d.Values[index]
	}
	n := int64((*d.Max-*d.Min)/d.Step + 1)
	if !d.Pair {
		return float64(*d.Min + int(index)*d.Step)
	} // row i has n-i values
	lo, hi := int64(0), n
	for lo < hi {
		mid := (lo + hi) / 2
		start := mid*n - mid*(mid-1)/2
		if start <= index {
			lo = mid + 1
		} else {
			hi = mid
		}
	}
	row := lo - 1
	start := row*n - row*(row-1)/2
	col := row + index - start
	return fmt.Sprintf("%d-%d", *d.Min+int(row)*d.Step, *d.Min+int(col)*d.Step)
}
func active(name string, p map[string]any) bool {
	switch name {
	case "noises":
		return p["noise"] == true
	case "packets", "length", "interval", "maxSplit":
		return p["fragment"] == true
	case "tcpConcurrency", "xudpConcurrency", "quic":
		return p["mux"] == true
	}
	return true
}
func (g *Generator) count(i int, p map[string]any) *big.Int {
	if i == len(g.Spec.Parameters) {
		return big.NewInt(1)
	}
	d := g.Spec.Parameters[i]
	if !active(d.Name, p) {
		return g.count(i+1, p)
	}
	if d.Name == "fragment" || d.Name == "mux" || d.Name == "noise" {
		sum := new(big.Int)
		for j := int64(0); j < d.count(); j++ {
			p[d.Name] = d.value(j)
			sum.Add(sum, g.count(i+1, p))
		}
		delete(p, d.Name)
		return sum
	}
	return new(big.Int).Mul(big.NewInt(d.count()), g.count(i+1, p))
}
func (g *Generator) Count() *big.Int { return new(big.Int).Set(g.total) }
func (g *Generator) At(position *big.Int) (map[string]any, bool) {
	if position.Sign() < 0 || position.Cmp(g.total) >= 0 {
		return nil, false
	}
	rank := new(big.Int).Set(position)
	fromCoverage := false
	if len(g.coverage) > 0 {
		prefix := big.NewInt(int64(len(g.coverage)))
		if rank.Cmp(prefix) < 0 {
			rank.Set(g.coverage[rank.Int64()])
			fromCoverage = true
		} else {
			rank.Sub(rank, prefix)
			for _, skipped := range g.excluded {
				if skipped.Cmp(rank) > 0 {
					break
				}
				rank.Add(rank, big.NewInt(1))
			}
		}
	}
	if g.Spec.Strategy == "broad" && !fromCoverage {
		rank.Mul(rank, g.stride).Add(rank, g.offset).Mod(rank, g.total)
	}
	p := map[string]any{}
	for i, d := range g.Spec.Parameters {
		if !active(d.Name, p) {
			continue
		}
		if d.Name == "fragment" || d.Name == "mux" || d.Name == "noise" {
			for j := int64(0); j < d.count(); j++ {
				p[d.Name] = d.value(j)
				c := g.count(i+1, p)
				if rank.Cmp(c) < 0 {
					break
				}
				rank.Sub(rank, c)
			}
		} else {
			c := g.count(i+1, p)
			q, r := new(big.Int), new(big.Int)
			q.QuoRem(rank, c, r)
			if value := d.value(q.Int64()); value != nil {
				p[d.Name] = value
			}
			rank = r
		}
	}
	return capability.Canonical(p), true
}
