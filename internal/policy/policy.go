package policy

import (
	"github.com/Seraf-seraf/vpn-sweep/internal/media"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/probe"
	"net"
	"net/url"
	"strconv"
)

func Validate(p model.Policy) error {
	if p.Version == GoogleYouTube {
		if err := validateBuiltinWeb(pagePolicy(p)); err != nil {
			return err
		}
		return media.Validate(MediaPolicy(p))
	}
	if p.Version == media.Version {
		return media.Validate(p)
	}
	if p.Video != nil {
		return model.Err("video_requires_separate_profile", "policy")
	}
	if p.Version == GoogleYouTubeWebNoise {
		return validateGoogleYouTubeWebNoise(p)
	}
	for _, s := range p.UDP {
		if s.Kind != "" || s.NoiseOnly {
			return model.Err("unsupported_udp_probe", "policy")
		}
	}
	if p.Version == builtinWeb {
		return validateBuiltinWeb(p)
	}
	if p.Version == GoogleYouTubeWeb {
		return validateGoogleYouTube(p)
	}
	if p.Version != "web-basic-v1" && p.Version != "custom-v1" {
		return model.Err("unsupported_policy_version", "policy")
	}
	if p.Rounds < 2 || p.Rounds > 10 || p.MinOperators < 1 || p.Downloads < 1 || p.MinDownloadBytes < 1 || len(p.Probes) > 32 {
		return model.Err("invalid_policy", "policy")
	}
	if p.Version == "web-basic-v1" && (p.Rounds != 2 || p.MinOperators < 3 || p.Downloads < 2 || p.MinDownloadBytes < 1<<20) {
		return model.Err("weakened_basic_policy", "policy")
	}
	ids := map[string]bool{}
	ops := map[string]bool{}
	downloads := map[string]bool{}
	hostOwners := map[string]string{}
	for _, s := range p.Probes {
		if e := probe.Validate(s); e != nil {
			return e
		}
		if ids[s.ID] {
			return model.Err("duplicate_probe", "policy")
		}
		ids[s.ID] = true
		u, _ := url.Parse(s.URL)
		host := u.Hostname()
		if owner, ok := hostOwners[host]; ok && owner != s.Operator {
			return model.Err("conflicting_resource_operator", "policy")
		}
		hostOwners[host] = s.Operator
		if s.Kind == "page" {
			ops[s.Operator] = true
		}
		if s.Kind == "download" && s.Bytes >= p.MinDownloadBytes {
			downloads[s.Operator] = true
		}
	}
	if len(ops) < p.MinOperators || len(downloads) < p.Downloads {
		return model.Err("insufficient_independent_resources", "policy")
	}
	for _, s := range p.UDP {
		host, port, e := net.SplitHostPort(s.Address)
		pn, x := strconv.Atoi(port)
		if e != nil || x != nil || host == "" || pn < 1 || pn > 65535 || s.ID == "" || ids[s.ID] || s.TimeoutSeconds < 1 || s.TimeoutSeconds > 30 {
			return model.Err("invalid_udp_policy", "policy")
		}
		ids[s.ID] = true
	}
	if p.RequireUDP && len(p.UDP) == 0 {
		return model.Err("missing_udp_probes", "policy")
	}
	if p.RequireQUIC {
		return model.Err("quic_probe_unsupported", "policy")
	}
	return nil
}
func Evaluate(p model.Policy, a model.Attempt) string {
	if p.Version == GoogleYouTube {
		return evaluateCombined(p, a)
	}
	if p.Version == media.Version {
		return media.Evaluate(p, a)
	}
	if Validate(p) != nil {
		return "invalid_policy"
	}
	if a.Restarts < p.Rounds-1 {
		return "partial"
	}
	seen := map[string]bool{}
	inconclusive := false
	for _, r := range a.Results {
		key := strconv.Itoa(r.Round) + ":" + r.Probe
		if seen[key] {
			return "partial"
		}
		seen[key] = true
		if r.Outcome != "pass" {
			switch r.Outcome {
			case "site_consent", "site_forbidden", "site_rate_limited", "site_restricted", "site_challenge", "unexpected_http_status", "unsupported":
				inconclusive = true
			default:
				return "failed"
			}
			continue
		}
		valid := false
		for _, s := range p.Probes {
			if r.Probe == s.ID {
				if !r.TLSVerified || r.Transport != "https-tcp" || r.HTTPStatus != s.Status {
					return "failed"
				}
				if (p.Version == GoogleYouTubeWeb || p.Version == GoogleYouTubeWebNoise || p.Version == builtinWeb) && (!r.ContentVerified || r.MIME != "text/html" || r.Bytes < s.MinBytes || r.Bytes > probe.MaxPageBodyBytes) {
					return "failed"
				}
				if s.Kind == "download" && r.Bytes != s.Bytes {
					return "failed"
				}
				valid = true
			}
		}
		for _, s := range p.UDP {
			if s.NoiseOnly && a.Candidate.Params["noise"] != true {
				continue
			}
			if r.Probe == s.ID {
				if r.Transport != "udp-socks5" {
					return "failed"
				}
				valid = true
			}
		}
		if !valid || r.Round < 0 || r.Round >= p.Rounds {
			return "failed"
		}
	}
	for round := 0; round < p.Rounds; round++ {
		for _, s := range p.Probes {
			if s.Kind == "download" && round > 0 {
				continue
			}
			if !seen[strconv.Itoa(round)+":"+s.ID] {
				return "partial"
			}
		}
		for _, s := range p.UDP {
			if s.NoiseOnly && a.Candidate.Params["noise"] != true {
				continue
			}
			if !seen[strconv.Itoa(round)+":"+s.ID] {
				return "partial"
			}
		}
	}
	if inconclusive {
		return "inconclusive"
	}
	return "pass"
}
