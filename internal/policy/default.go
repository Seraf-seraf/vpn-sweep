package policy

import (
	"github.com/Seraf-seraf/vpn-sweep/internal/media"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/probe"
)

const GoogleYouTube = "google-youtube-v1"
const builtinWeb = "builtin-web-v1"

func Default() model.Policy {
	return model.Policy{
		Version: GoogleYouTube, Rounds: 2, MinOperators: 1,
		Probes: []model.Probe{
			{ID: "google-home", Operator: "google", URL: "https://www.google.com/?hl=en", Kind: "page", ContainsAll: []string{"<title>Google</title>", "name=\"q\"", "action=\"/search\""}, MinBytes: 4096, MIME: "text/html", Status: 200, TimeoutSeconds: 45},
			{ID: "youtube-home", Operator: "google", URL: "https://www.youtube.com/?hl=en&gl=US", Kind: "page", ContainsAll: []string{"ytInitialData", "ytcfg.set", "\"richGridRenderer\""}, MinBytes: 4096, MIME: "text/html", Status: 200, TimeoutSeconds: 45},
		},
		Video: &model.VideoPolicy{
			Required: []string{media.YouTube}, Samples: 3, MinMbps: 10,
			MaxReadGapMS: 3000, SampleBytes: 4 << 20, TimeoutSeconds: 30,
			YouTube: &model.YouTubeReference{VideoID: "BaW_jenozKc", Permission: "Public yt-dlp extractor test video; no account or cookies", MinHeight: 1080},
		},
	}
}

func HasVideo(p model.Policy) bool {
	return p.Version == GoogleYouTube || p.Version == media.Version
}

func MediaPolicy(p model.Policy) model.Policy {
	if p.Version == GoogleYouTube {
		return model.Policy{Version: media.YouTubeVersion, Rounds: p.Rounds, Video: p.Video}
	}
	return p
}

func pagePolicy(p model.Policy) model.Policy {
	p.Version = builtinWeb
	p.Video = nil
	return p
}

func validateBuiltinWeb(p model.Policy) error {
	if p.Rounds != 2 || p.MinOperators != 1 || p.Downloads != 0 || p.MinDownloadBytes != 0 || p.RequireUDP || p.RequireQUIC || len(p.UDP) != 0 || p.Video != nil || len(p.Probes) < 2 || len(p.Probes) > 32 {
		return model.Err("invalid_builtin_web_scope", "policy")
	}
	base := p
	base.Version = GoogleYouTubeWeb
	base.Probes = nil
	seen := map[string]bool{}
	for _, pr := range p.Probes {
		if seen[pr.ID] || pr.ID == media.YouTube {
			return model.Err("duplicate_probe", "policy")
		}
		seen[pr.ID] = true
		if err := probe.Validate(pr); err != nil {
			return err
		}
		if pr.Kind != "page" || pr.MIME != "text/html" || pr.MinBytes < MinWebBodyBytes || len(pr.ContainsAll) < 2 {
			return model.Err("invalid_builtin_web_probe", "policy")
		}
		if pr.ID == "google-home" || pr.ID == "youtube-home" {
			base.Probes = append(base.Probes, pr)
		}
	}
	return validateGoogleYouTube(base)
}

func evaluateCombined(p model.Policy, a model.Attempt) string {
	if Validate(p) != nil {
		return "invalid_policy"
	}
	web, video := a, a
	web.Results = nil
	video.Results = nil
	for _, r := range a.Results {
		if r.Probe == media.YouTube {
			video.Results = append(video.Results, r)
		} else {
			web.Results = append(web.Results, r)
		}
	}
	if result := Evaluate(pagePolicy(p), web); result != "pass" {
		return result
	}
	return media.Evaluate(MediaPolicy(p), video)
}
