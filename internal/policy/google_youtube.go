package policy

import (
	"github.com/Seraf-seraf/vpn-sweep/internal/media"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/probe"
)

const GoogleYouTubeWeb = "google-youtube-web-v1"
const MinWebBodyBytes int64 = 1024

const googleYouTubeWebTimeoutSeconds = 45
const googleYouTubeWebMinBytes int64 = 4096

// GoogleYouTubeWebDefault checks only the Google and YouTube home pages.
func GoogleYouTubeWebDefault() model.Policy {
	return model.Policy{
		Version:      GoogleYouTubeWeb,
		Rounds:       2,
		MinOperators: 1,
		Probes: []model.Probe{
			{
				ID: "google-home", Operator: "google", URL: "https://www.google.com/?hl=en", Kind: "page",
				ContainsAll: []string{"<title>Google</title>", "name=\"q\"", "action=\"/search\""},
				MinBytes:    googleYouTubeWebMinBytes, MIME: "text/html", Status: 200, TimeoutSeconds: googleYouTubeWebTimeoutSeconds,
			},
			{
				ID: "youtube-home", Operator: "google", URL: "https://www.youtube.com/?hl=en&gl=US", Kind: "page",
				ContainsAll: []string{"ytInitialData", "ytcfg.set", "\"richGridRenderer\""},
				MinBytes:    googleYouTubeWebMinBytes, MIME: "text/html", Status: 200, TimeoutSeconds: googleYouTubeWebTimeoutSeconds,
			},
		},
	}
}

// IsSuccessfulGoogleYouTubeInterstitial applies the built-in Google/YouTube
// web policy's explicit user-interstitial success condition. It confirms
// HTTPS reachability but does not claim that page content was verified.
func IsSuccessfulGoogleYouTubeInterstitial(p model.Policy, result model.ProbeResult) bool {
	if (p.Version != GoogleYouTubeWeb && p.Version != GoogleYouTubeWebNoise) || Validate(p) != nil {
		return false
	}
	for _, spec := range p.Probes {
		if probe.IsVerifiedUserInterstitialConnection(spec, result) {
			return true
		}
	}
	return false
}

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
	if p.Version == GoogleWeb {
		return "google_homepage_only_not_general_internet"
	}
	if p.Version == WebConnectivity {
		return "google_youtube_github_https_connectivity_only"
	}
	if p.Version == GoogleYouTubeWebNoise {
		return "google_youtube_web_pages_and_udp_dns_for_noise_only"
	}
	if p.Version == GoogleYouTube {
		return "declared_web_pages_and_youtube_media_delivery_not_playback_or_general_internet"
	}
	if p.Version == media.Version {
		return "declared_video_capabilities_only_not_playback_or_general_internet"
	}
	if p.Version == GoogleYouTubeWeb {
		return "google_youtube_web_pages_only_not_video_or_general_internet"
	}
	return "declared_policy_resources_only"
}
func PresetStatus(p model.Policy) string {
	if p.Version == GoogleWeb {
		return "live_unverified"
	}
	if p.Version == WebConnectivity || p.Version == GoogleYouTubeWebNoise {
		return "live_unverified"
	}
	if p.Version == GoogleYouTube {
		return "live_unverified"
	}
	if p.Version == media.Version {
		return "reference_and_device_acceptance_required"
	}
	if p.Version == GoogleYouTubeWeb {
		return "live_unverified"
	}
	return "user_supplied"
}
