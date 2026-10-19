package media

import (
	"encoding/hex"
	"math"
	"net/url"
	"regexp"

	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

const Version = "representative-video-v1"
const Transfer = "video_transfer"
const YouTube = "youtube_delivery"
const MaxSampleBytes int64 = 16 << 20
const MaxResolverBytes int64 = (2 << 20) + 1

var videoID = regexp.MustCompile(`^[a-zA-Z0-9_-]{11}$`)

func HTTPS(raw string) bool {
	u, e := url.Parse(raw)
	return e == nil && u.Scheme == "https" && u.Hostname() != "" && u.User == nil && u.Fragment == ""
}
func Validate(p model.Policy) error {
	v := p.Video
	if p.Version != Version || v == nil || p.Rounds != 2 || len(p.Probes) != 0 || len(p.UDP) != 0 || p.RequireUDP || p.RequireQUIC || p.Downloads != 0 || p.MinOperators != 0 || p.MinDownloadBytes != 0 {
		return model.Err("invalid_video_scope", "policy")
	}
	if v.Samples < 1 || v.Samples > 10 || math.IsNaN(v.MinMbps) || math.IsInf(v.MinMbps, 0) || v.MinMbps <= 0 || v.MinMbps > 10000 || v.MaxReadGapMS < 1 || v.MaxReadGapMS > 30000 || v.SampleBytes < 65536 || v.SampleBytes > MaxSampleBytes || v.TimeoutSeconds < 1 || v.TimeoutSeconds > 300 {
		return model.Err("invalid_video_limits", "policy")
	}
	seen := map[string]bool{}
	for _, k := range v.Required {
		if (k != Transfer && k != YouTube) || seen[k] {
			return model.Err("invalid_required_capabilities", "policy")
		}
		seen[k] = true
	}
	if len(seen) == 0 || (seen[YouTube] && v.YouTube == nil) {
		return model.Err("explicit_media_requirements_needed", "policy")
	}
	r := v.Reference
	if !HTTPS(r.URL) || r.Bytes < 65536 || r.Bytes > v.SampleBytes || r.Provenance == "" || len(r.Provenance) > 1024 || r.License == "" || len(r.License) > 128 || (r.Status != "verified_reference" && r.Status != "unverified") {
		return model.Err("invalid_media_reference", "policy")
	}
	h, e := hex.DecodeString(r.SHA256)
	if r.Status == "verified_reference" && (e != nil || len(h) != 32 || r.SHA256 != hex.EncodeToString(h)) {
		return model.Err("verified_reference_requires_sha256", "policy")
	}
	if y := v.YouTube; y != nil && (!videoID.MatchString(y.VideoID) || y.Permission == "" || len(y.Permission) > 1024 || y.MinHeight != 1080) {
		return model.Err("invalid_youtube_reference", "policy")
	}
	return nil
}
func Required(p model.Policy, k string) bool {
	for _, x := range p.Video.Required {
		if x == k {
			return true
		}
	}
	return false
}
func RoundBudget(p model.Policy) int64 {
	n := int64(p.Video.Samples) * p.Video.Reference.Bytes
	if p.Video.YouTube != nil {
		n += int64(p.Video.Samples)*p.Video.SampleBytes + MaxResolverBytes
	}
	return n
}

// Capabilities are evaluated independently; only explicitly required ones govern stop.
func Capabilities(p model.Policy, a model.Attempt) map[string]string {
	out := map[string]string{Transfer: "partial"}
	if p.Video.YouTube != nil {
		out[YouTube] = "partial"
	}
	for k := range out {
		count := 0
		state := "pass"
		seen := map[int]bool{}
		for _, r := range a.Results {
			if r.Probe != k {
				continue
			}
			count++
			if seen[r.Round] || r.Round < 0 || r.Round >= 2 {
				state = "failed"
				break
			}
			seen[r.Round] = true
			if r.Outcome != "pass" {
				if state != "failed" {
					state = r.Outcome
				}
				continue
			}
			ev := r.Media
			if ev == nil || ev.Version != 1 || ev.Capability != k || len(ev.Samples) != p.Video.Samples || !r.TLSVerified || !r.ContentVerified || r.Transport != "https-tcp" {
				state = "failed"
				break
			}
			if k == YouTube && (ev.FormatID == "" || ev.Height < p.Video.YouTube.MinHeight) {
				state = "failed"
				break
			}
			for _, s := range ev.Samples {
				expected := p.Video.Reference.Bytes
				status := 200
				if k == YouTube {
					expected = p.Video.SampleBytes
					status = 206
				}
				h, e := hex.DecodeString(s.SHA256)
				if s.Bytes != expected || s.DurationNS <= 0 || float64(s.Bytes)*8*1000/float64(s.DurationNS) < p.Video.MinMbps || s.MaxReadGapNS < 0 || s.MaxReadGapNS > int64(p.Video.MaxReadGapMS)*1000000 || !s.ContainerVerified || !s.TLSVerified || s.Status != status || e != nil || len(h) != 32 {
					state = "failed"
				}
				if k == Transfer && (!s.ReferenceVerified || s.SHA256 != p.Video.Reference.SHA256 || p.Video.Reference.Status != "verified_reference") {
					state = "failed"
				}
			}
		}
		if state == "pass" && (count != 2 || a.Restarts < 1) {
			state = "partial"
		}
		out[k] = state
	}
	return out
}
func Evaluate(p model.Policy, a model.Attempt) string {
	if Validate(p) != nil {
		return "invalid_policy"
	}
	for _, r := range a.Results {
		if r.Probe != Transfer && (r.Probe != YouTube || p.Video.YouTube == nil) {
			return "failed"
		}
	}
	caps := Capabilities(p, a)
	for _, k := range p.Video.Required {
		if caps[k] != "pass" {
			return "partial"
		}
	}
	return "pass"
}
