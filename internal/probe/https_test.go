package probe

import (
	"context"
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/core"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
	"github.com/Seraf-seraf/vpn-sweep/internal/testkit"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func TestHTTPSContentTLSAndNoFallback(t *testing.T) {
	var hits atomic.Int64
	payload := []byte("expected synthetic payload")
	s := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		switch r.URL.Path {
		case "/stub":
			w.Write([]byte("access denied"))
		case "/403":
			w.WriteHeader(403)
		case "/429":
			w.WriteHeader(429)
		case "/challenge":
			w.Write([]byte("verify you are human"))
		case "/redirect":
			http.Redirect(w, r, "https://else.invalid/", 302)
		default:
			w.Header().Set("Content-Type", "application/octet-stream")
			w.Write(payload)
		}
	}))
	defer s.Close()
	roots := x509.NewCertPool()
	roots.AddCert(s.Certificate())
	p := model.Probe{ID: "page", Operator: "test", URL: s.URL, Kind: "page", Contains: "expected synthetic", Status: 200, TimeoutSeconds: 2}
	binary, id := testkit.FakeCore(t)
	addr, _ := core.ReserveAddress()
	proxy := socks.Endpoint{Address: addr, User: "user", Pass: "pass"}
	imp, _ := importer.Parse([]byte("socks5://127.0.0.1:9"), "uri")
	cfg, e := capability.Render(imp.Profiles[0], nil, addr, "user", "pass")
	if e != nil {
		t.Fatal(e)
	}
	runner := core.Runner{Binary: binary, Identity: id, Directory: t.TempDir(), Startup: time.Second}
	h, e := runner.Start(context.Background(), cfg, proxy)
	if e != nil {
		t.Fatal(e)
	}
	defer h.Close()
	c := Client{Roots: roots}
	if r := c.Check(context.Background(), proxy, p, 0); r.Outcome != "pass" || !r.TLSVerified {
		t.Fatal(r)
	}
	for path, want := range map[string]string{"/stub": "unexpected_content", "/403": "site_forbidden", "/429": "site_rate_limited", "/challenge": "site_challenge", "/redirect": "redirect_rejected"} {
		p.URL = s.URL + path
		if r := c.Check(context.Background(), proxy, p, 0); r.Outcome != want {
			t.Fatal(path, r)
		}
	}
	p.URL = s.URL
	if r := (Client{}).Check(context.Background(), proxy, p, 0); r.Outcome != "tls_failure" {
		t.Fatal("untrusted accepted", r)
	}
	hash := sha256.Sum256(payload)
	p.Kind = "download"
	p.Contains = ""
	p.Bytes = int64(len(payload))
	p.SHA256 = hex.EncodeToString(hash[:])
	if r := c.Check(context.Background(), proxy, p, 0); r.Outcome != "pass" {
		t.Fatal(r)
	}
	p.SHA256 = hex.EncodeToString(make([]byte, 32))
	if r := c.Check(context.Background(), proxy, p, 0); r.Outcome != "content_hash_mismatch" {
		t.Fatal(r)
	}
	h.Close()
	before := hits.Load()
	if r := c.Check(context.Background(), proxy, p, 0); r.Outcome == "pass" {
		t.Fatal("direct fallback")
	}
	if hits.Load() != before {
		t.Fatal("direct request reached target")
	}
}
