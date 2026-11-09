package probe

import (
	"net/http"

	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

// IsVerifiedUserInterstitialConnection reports HTTPS reachability when a page
// responds with a verified HTTP 200 consent or human-challenge page. It does
// not verify page content or decide whether a policy accepts the response.
func IsVerifiedUserInterstitialConnection(spec model.Probe, result model.ProbeResult) bool {
	if spec.Kind != "page" || spec.Status != http.StatusOK || result.Probe != spec.ID ||
		result.HTTPStatus != http.StatusOK || !result.TLSVerified || result.Transport != "https-tcp" || result.ContentVerified {
		return false
	}
	return result.Outcome == "site_consent" || result.Outcome == "site_challenge"
}
