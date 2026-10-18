package policy

import (
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/probe"
)

const GoogleYouTubeWeb = "google-youtube-web-v1"
const MinWebBodyBytes int64 = 1024

// This profile measures two pages owned by Google, never independent Internet access.
func validateGoogleYouTube(p model.Policy) error {
	if p.Rounds != 2 || p.MinOperators != 1 || p.Downloads != 0 || p.MinDownloadBytes != 0 || p.RequireUDP || p.RequireQUIC || len(p.UDP) != 0 || len(p.Probes) != 2 {
		return model.Err("invalid_google_youtube_web_scope", "policy")
	}
	targets := map[string]string{"google-home": "https://www.google.com/?hl=en", "youtube-home": "https://www.youtube.com/?hl=en&gl=US"}
	seen := map[string]bool{}
	for _, s := range p.Probes {
		if targets[s.ID] == "" || targets[s.ID] != s.URL || seen[s.ID] || s.Operator != "google" || s.Kind != "page" || s.Status != 200 || s.MIME != "text/html" || s.MinBytes < MinWebBodyBytes || s.Bytes != 0 || s.SHA256 != "" || s.Contains != "" || len(s.ContainsAll) < 2 || len(s.RedirectHosts) != 0 {
			return model.Err("invalid_google_youtube_web_probe", "policy")
		}
		seen[s.ID] = true
		markers := map[string]bool{}
		for _, m := range s.ContainsAll {
			if markers[m] {
				return model.Err("duplicate_composite_marker", "policy")
			}
			markers[m] = true
		}
		if err := probe.Validate(s); err != nil {
			return err
		}
	}
	return nil
}

func Scope(p model.Policy) string {
	if p.Version == GoogleYouTubeWeb {
		return "google_youtube_web_pages_only_not_video_or_general_internet"
	}
	return "declared_policy_resources_only"
}
func PresetStatus(p model.Policy) string {
	if p.Version == GoogleYouTubeWeb {
		return "live_unverified"
	}
	return "user_supplied"
}
