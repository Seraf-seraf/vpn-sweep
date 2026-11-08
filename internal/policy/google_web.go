package policy

import (
	"reflect"

	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/probe"
)

const GoogleWeb = "google-web-v1"
const googleWebTimeoutSeconds = 45
const googleWebMinBytes int64 = 4096

// GoogleWebDefault checks only Google's home page, not general Internet access.
func GoogleWebDefault() model.Policy {
	return model.Policy{
		Version:      GoogleWeb,
		Rounds:       2,
		MinOperators: 1,
		Probes: []model.Probe{{
			ID: "google-home", Operator: "google", URL: "https://www.google.com/?hl=en", Kind: "page",
			ContainsAll: []string{"<title>Google</title>", "name=\"q\"", "action=\"/search\""},
			MinBytes:    googleWebMinBytes, MIME: "text/html", Status: 200, TimeoutSeconds: googleWebTimeoutSeconds,
		}},
	}
}

func validateGoogleWeb(p model.Policy) error {
	want := GoogleWebDefault()
	if !reflect.DeepEqual(p, want) {
		return model.Err("invalid_google_web_policy", "policy")
	}
	if err := probe.Validate(p.Probes[0]); err != nil {
		return err
	}
	return nil
}
