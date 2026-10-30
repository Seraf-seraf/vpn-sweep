package search

import (
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/space"
	"math/big"
)

type candidateSchedule struct {
	profiles []model.Profile
	key      []byte
	base     *space.Generator
	spaces   []*space.Generator
	counts   []*big.Int
	min      *big.Int
	max      *big.Int
	total    *big.Int
}

func newCandidateSchedule(profiles []model.Profile, key []byte, g *space.Generator) (*candidateSchedule, error) {
	s := &candidateSchedule{profiles: profiles, key: key, base: g, min: new(big.Int), max: new(big.Int), total: big.NewInt(int64(len(profiles)))}
	for i, p := range profiles {
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
	c.ID = model.PrivateID(s.key, []any{"candidate-v2", s.profiles[profile], Effective(s.profiles[profile], params)})
	return c, new(big.Int).Add(pos, big.NewInt(1)), true
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
