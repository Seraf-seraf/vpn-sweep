package search

import (
	"context"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/space"
	"math/big"
)

const fingerprintIdentityVersion = 3

type candidateSchedule struct {
	profiles []model.Profile
	key      []byte
	version  int
	base     *space.Generator
	spaces   []*space.Generator
	counts   []*big.Int
	min      *big.Int
	max      *big.Int
	total    *big.Int
}

func newCandidateSchedule(ctx context.Context, profiles []model.Profile, key []byte, g *space.Generator, version int) (*candidateSchedule, error) {
	s := &candidateSchedule{profiles: profiles, key: key, version: version, base: g, min: new(big.Int), max: new(big.Int), total: big.NewInt(int64(len(profiles)))}
	for i, p := range profiles {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		profileSpace, err := g.ForProfile(p)
		if err != nil {
			return nil, err
		}
		count := profileSpace.Count()
		s.spaces = append(s.spaces, profileSpace)
		s.counts = append(s.counts, count)
		s.total.Add(s.total, count)
		if i == 0 || count.Cmp(s.min) < 0 {
			s.min.Set(count)
		}
		if count.Cmp(s.max) > 0 {
			s.max.Set(count)
		}
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return s, nil
}

func (s *candidateSchedule) candidate(pos *big.Int) (model.Candidate, *big.Int, bool) {
	n := big.NewInt(int64(len(s.profiles)))
	if len(s.profiles) == 0 || pos.Sign() < 0 || pos.Cmp(s.total) >= 0 {
		return model.Candidate{}, nil, false
	}
	profile := int(new(big.Int).Mod(pos, n).Int64())
	params := map[string]any{}
	if pos.Cmp(n) >= 0 {
		generated := new(big.Int).Sub(pos, n)
		rank, rem := new(big.Int), new(big.Int)
		rank.QuoRem(generated, n, rem)
		if rank.Cmp(s.min) < 0 {
			profile = int(rem.Int64())
		} else {
			profile, rank = s.position(generated)
		}
		params, _ = s.spaces[profile].At(rank)
	}
	c := model.Candidate{Profile: profile, Params: params}
	c.ID = s.candidateID(profile, params)
	return c, new(big.Int).Add(pos, big.NewInt(1)), true
}

func sourceFingerprint(p model.Profile) string {
	stream, _ := p.Outbound["streamSettings"].(map[string]any)
	settings := "tlsSettings"
	if stream["security"] == "reality" {
		settings = "realitySettings"
	} else if stream["security"] != "tls" {
		return ""
	}
	tls, _ := stream[settings].(map[string]any)
	fingerprint, _ := tls["fingerprint"].(string)
	return fingerprint
}

func (s *candidateSchedule) candidateID(profile int, params map[string]any) string {
	p := s.profiles[profile]
	effective := Effective(p, params)
	if s.version >= fingerprintIdentityVersion {
		if fingerprint := sourceFingerprint(p); fingerprint != "" && effective["fingerprint"] == fingerprint {
			delete(effective, "fingerprint")
		}
	}
	return model.PrivateID(s.key, []any{"candidate-v2", p, effective})
}

func (s *candidateSchedule) isSaved(saved map[string]bool, c model.Candidate) bool {
	if saved[c.ID] {
		return true
	}
	if s.version < fingerprintIdentityVersion {
		return false
	}
	p := s.profiles[c.Profile]
	fingerprint := sourceFingerprint(p)
	if fingerprint == "" || c.Params["fingerprint"] != nil && c.Params["fingerprint"] != fingerprint {
		return false
	}
	effective := Effective(p, c.Params)
	effective["fingerprint"] = fingerprint
	legacyID := model.PrivateID(s.key, []any{"candidate-v2", p, effective})
	return saved[legacyID]
}

func (s *candidateSchedule) position(position *big.Int) (int, *big.Int) {
	lo := new(big.Int)
	hi := new(big.Int).Set(s.max)
	for lo.Cmp(hi) < 0 {
		mid := new(big.Int).Add(lo, hi)
		mid.Add(mid, big.NewInt(1)).Quo(mid, big.NewInt(2))
		if s.before(mid).Cmp(position) <= 0 {
			lo.Set(mid)
		} else {
			hi.Sub(mid, big.NewInt(1))
		}
	}
	remaining := new(big.Int).Sub(position, s.before(lo)).Int64()
	for i, count := range s.counts {
		if count.Cmp(lo) <= 0 {
			continue
		}
		if remaining == 0 {
			return i, lo
		}
		remaining--
	}
	panic("candidate position outside profile spaces")
}

func (s *candidateSchedule) before(round *big.Int) *big.Int {
	total := new(big.Int)
	for _, count := range s.counts {
		if count.Cmp(round) < 0 {
			total.Add(total, count)
		} else {
			total.Add(total, round)
		}
	}
	return total
}
