package media

import (
	"net/url"
	"strings"
)

func YouTubeURL(raw string) bool {
	if !HTTPS(raw) {
		return false
	}
	u, e := url.Parse(raw)
	return e == nil && strings.HasSuffix(strings.ToLower(u.Hostname()), ".googlevideo.com") && (u.Port() == "" || u.Port() == "443") && u.Path == "/videoplayback"
}
