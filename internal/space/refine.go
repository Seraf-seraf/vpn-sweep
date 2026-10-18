package space

import (
	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

func (d Domain) index(v any) int64 {
	if len(d.Values) > 0 {
		for i, x := range d.Values {
			if model.Digest(x) == model.Digest(v) {
				return int64(i)
			}
		}
		return 0
	}
	if d.Pair {
		s, ok := v.(string)
		if !ok {
			return 0
		}
		a, b, e := capability.Range(s)
		if e != nil || a < *d.Min || b > *d.Max || (a-*d.Min)%d.Step != 0 || (b-*d.Min)%d.Step != 0 {
			return 0
		}
		n := int64((*d.Max-*d.Min)/d.Step + 1)
		row, col := int64((a-*d.Min)/d.Step), int64((b-*d.Min)/d.Step)
		return row*n - row*(row-1)/2 + col - row
	}
	x, ok := v.(float64)
	if !ok || int(x) < *d.Min || int(x) > *d.Max {
		return 0
	}
	return int64((int(x) - *d.Min) / d.Step)
}

// Neighbors makes bounded joint changes. Global exploration owns at least 3/4 slots.
func (g *Generator) Neighbors(params map[string]any) []map[string]any {
	out := []map[string]any{}
	seen := map[string]bool{}
	dims := g.Spec.Parameters
	if len(dims) == 0 {
		return out
	}
	for k := 0; k < 8; k++ {
		p := map[string]any{}
		for name, v := range params {
			p[name] = v
		}
		for _, d := range dims {
			if active(d.Name, p) && p[d.Name] == nil {
				p[d.Name] = d.value(0)
			}
		}
		for j := 0; j < 2 && j < len(dims); j++ {
			d := dims[(k+j*3)%len(dims)]
			if !active(d.Name, p) {
				continue
			}
			idx := (d.index(p[d.Name]) + int64(1+k/len(dims))) % d.count()
			p[d.Name] = d.value(idx)
		}
		for _, d := range dims {
			if active(d.Name, p) && p[d.Name] == nil {
				p[d.Name] = d.value(0)
			}
		}
		p = capability.Canonical(p)
		id := model.Digest(p)
		if !seen[id] {
			out = append(out, p)
			seen[id] = true
		}
	}
	return out
}
