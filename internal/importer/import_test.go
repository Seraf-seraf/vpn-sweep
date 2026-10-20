package importer

import (
	"encoding/base64"
	"encoding/json"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"strings"
	"testing"
)

func TestURIContracts(t *testing.T) {
	for _, u := range []string{"vless://synthetic-id@vpn.invalid:443?type=ws&security=tls&sni=tls.invalid&host=ws.invalid&path=%2Fcontract&encryption=none", "trojan://synthetic-password@vpn.invalid:443?security=tls&sni=tls.invalid", "ss://" + base64.RawURLEncoding.EncodeToString([]byte("aes-128-gcm:synthetic-password")) + "@vpn.invalid:8388", "socks5://test-user:test-password@127.0.0.1:1080"} {
		r, e := Parse([]byte(u), "auto")
		if e != nil {
			t.Fatal(e)
		}
		if len(r.Profiles) != 1 {
			t.Fatal("missing profile")
		}
		b, _ := json.Marshal(r.Profiles[0])
		if !strings.Contains(string(b), "443") && !strings.Contains(string(b), "8388") && !strings.Contains(string(b), "1080") {
			t.Fatal("lost port")
		}
	}
}
func TestFullJSONPreservesServer(t *testing.T) {
	src := `{"dns":{"servers":["localhost"]},"routing":{"rules":[]},"outbounds":[{"protocol":"socks","tag":"source","settings":{"servers":[{"address":"vpn.invalid","port":1080,"users":[{"user":"synthetic","pass":"secret-marker"}]}]}},{"protocol":"freedom"}]}`
	r, e := Parse([]byte(src), "json")
	if e != nil {
		t.Fatal(e)
	}
	if Endpoint(r.Profiles[0]) != "vpn.invalid:1080" {
		t.Fatal("endpoint changed")
	}
	if len(r.Notices) != 3 {
		t.Fatal(r.Notices)
	}
	b, _ := json.Marshal(r)
	if strings.Contains(string(b), "secret-marker") {
		t.Fatal("result leaked profile")
	}
}
func TestRejectUnsafeAndUnknown(t *testing.T) {
	for _, src := range []string{`{"env":{},"outbounds":[]}`, `{"outbounds":[{"protocol":"socks","proxySettings":{"tag":"chain"},"settings":{}}]}`, `{"outbounds":[{"protocol":"socks","settings":{"servers":[{"address":"a","port":1}]},"streamSettings":{"security":"tls","tlsSettings":{"allowInsecure":true}}}]}`, "vless://x@a:443?unknown=secret-marker", "vless://x@a:443?type=ws&type=tcp", "hy2://secret-marker@a:443", "unrecognised-secret-marker", `{"outbounds":[],"outbounds":[]}`} {
		_, e := Parse([]byte(src), "auto")
		if e == nil {
			t.Fatal("unsafe accepted")
		}
		if strings.Contains(e.Error(), "secret-marker") {
			t.Fatal("secret error")
		}
		if model.Code(e) == "internal_error" {
			t.Fatal("unclassified")
		}
	}
}
func TestBase64EnvelopeRejectedAndBounds(t *testing.T) {
	u := "socks5://a:1"
	encoded := base64.StdEncoding.EncodeToString([]byte(u))
	if _, e := Parse([]byte(encoded), "auto"); e == nil {
		t.Fatal("implicit conversion")
	}
	if _, e := Parse([]byte(encoded), "base64"); model.Code(e) != "unsupported_format" {
		t.Fatal("removed envelope accepted", e)
	}
	if _, e := Parse(make([]byte, MaxInputBytes+1), "json"); e == nil {
		t.Fatal("unbounded")
	}
}
