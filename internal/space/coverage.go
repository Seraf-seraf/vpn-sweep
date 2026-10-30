package space

import (
	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"math/big"
	"sort"
)

func (g *Generator) prepareCoverage() {
	if g.total.Sign() == 0 {
		return
	}
	baseline := g.complete(map[string]any{})
	seen := map[string]bool{}
	add := func(params map[string]any) {
		rank := g.rank(g.complete(params))
		key := rank.String()
		if !seen[key] {
			seen[key] = true
			g.coverage = append(g.coverage, rank)
		}
	}
	add(baseline)
	for _, d := range g.Spec.Parameters {
		indices := []int64{}
		switch {
		case d.Preset != "":
			variants := len(noiseRandomPackets) + len(noiseFixedTypes)*len(noiseSizes)*len(noiseContent)
			block := d.count() / int64(variants)
			for i := int64(0); i < block; i++ {
				indices = append(indices, i)
			}
			for i := 0; i < variants; i++ {
				indices = append(indices, int64(i)*block)
			}
		case d.Pair:
			indices = append(indices, 0, d.count()-1)
			if d.Min != nil && d.Max != nil && *d.Min <= 10 && *d.Max >= 50 {
				idx := d.index("10-50")
				if capability.Normalize(d.Name, d.value(idx)) == "10-50" {
					indices = append(indices, idx)
				}
			}
		default:
			for i := int64(0); i < d.count(); i++ {
				indices = append(indices, i)
			}
		}
		for _, index := range indices {
			p := map[string]any{}
			for name, value := range baseline {
				p[name] = value
			}
			if parent := activationParent(d.Name); parent != "" {
				if !g.canEnable(parent) {
					continue
				}
				p[parent] = true
			}
			if value := d.value(index); value != nil {
				p[d.Name] = value
			} else {
				delete(p, d.Name)
			}
			add(p)
		}
	}
	if len(g.coverage) == 0 || g.total.Cmp(big.NewInt(1)) <= 0 {
		return
	}
	inverse := new(big.Int).ModInverse(g.stride, g.total)
	for _, rank := range g.coverage {
		position := new(big.Int).Sub(rank, g.offset)
		position.Mul(position, inverse).Mod(position, g.total)
		g.excluded = append(g.excluded, position)
	}
	sort.Slice(g.excluded, func(i, j int) bool { return g.excluded[i].Cmp(g.excluded[j]) < 0 })
}

func activationParent(name string) string {
	switch name {
	case "packets", "length", "interval", "maxSplit":
		return "fragment"
	case "tcpConcurrency", "xudpConcurrency", "quic":
		return "mux"
	case "noises":
		return "noise"
	}
	return ""
}

func (g *Generator) canEnable(name string) bool {
	for _, d := range g.Spec.Parameters {
		if d.Name == name {
			for _, value := range d.Values {
				if value == true {
					return true
				}
			}
		}
	}
	return false
}

func (g *Generator) complete(params map[string]any) map[string]any {
	p := make(map[string]any, len(g.Spec.Parameters))
	for name, value := range params {
		p[name] = value
	}
	for _, d := range g.Spec.Parameters {
		if active(d.Name, p) && p[d.Name] == nil {
			if value := d.value(0); value != nil {
				p[d.Name] = value
			}
		}
	}
	return capability.Canonical(p)
}

func (g *Generator) rank(params map[string]any) *big.Int {
	rank := new(big.Int)
	selected := map[string]any{}
	for i, d := range g.Spec.Parameters {
		if !active(d.Name, selected) {
			continue
		}
		index := d.index(params[d.Name])
		if d.Name == "fragment" || d.Name == "mux" || d.Name == "noise" {
			for j := int64(0); j < index; j++ {
				selected[d.Name] = d.value(j)
				rank.Add(rank, g.count(i+1, selected))
			}
			selected[d.Name] = d.value(index)
		} else {
			count := g.count(i+1, selected)
			rank.Add(rank, new(big.Int).Mul(big.NewInt(index), count))
			if value := params[d.Name]; value != nil {
				selected[d.Name] = value
			}
		}
	}
	return rank
}
