package probe

import (
	"net/http"

	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

// IsSuccessfulUserInterstitial reports a verified HTTPS 200 response whose
// page requires a user to continue through consent or a human challenge.
func IsSuccessfulUserInterstitial(spec model.Probe, result model.ProbeResult) bool {
	if spec.Kind != "page" || spec.Status != http.StatusOK || result.Probe != spec.ID ||
		result.HTTPStatus != http.StatusOK || !result.TLSVerified || result.Transport != "https-tcp" {
		return false
	}
	return result.Outcome == "site_consent" || result.Outcome == "site_challenge"
}
