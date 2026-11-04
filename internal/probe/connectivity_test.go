package probe

import (
	"context"
	"crypto/x509"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/core"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
	"github.com/Seraf-seraf/vpn-sweep/internal/testkit"
)

func TestConnectivityVerifiedResponseWithoutContentOrRedirect(t *testing.T) {
	var status atomic.Int64
	var hits atomic.Int64
	status.Store(200)
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		if r.Method != http.MethodGet || r.URL.RequestURI() != "/" {
			t.Error("unexpected request", r.Method, r.URL)
		}
		w.Header().Set("Location", "/must-not-follow")
		w.Header().Set("Content-Length", "999999") // Body completion is not part of connectivity.
		w.WriteHeader(int(status.Load()))
		w.(http.Flusher).Flush()
	}))
	defer server.Close()
	roots := x509.NewCertPool()
	roots.AddCert(server.Certificate())
	binary, id := testkit.FakeCore(t)
	addr, err := core.ReserveAddress()
	if err != nil {
		t.Fatal(err)
	}
	proxy := socks.Endpoint{Address: addr, User: "test", Pass: "test"}
	imported, err := importer.Parse([]byte("socks5://127.0.0.1:9"), "uri")
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := capability.Render(imported.Profiles[0], nil, addr, proxy.User, proxy.Pass)
	if err != nil {
		t.Fatal(err)
	}
	runner := core.Runner{Binary: binary, Identity: id, Directory: t.TempDir(), Startup: time.Second}
	handle, err := runner.Start(context.Background(), cfg, proxy)
	if err != nil {
		t.Fatal(err)
	}
	defer handle.Close()
	probe := model.Probe{ID: "test", Operator: "test", Kind: "connectivity", URL: server.URL + "/", TimeoutSeconds: 2}
	for _, code := range []int{200, 204, 302, 403, 429, 500, 599} {
		status.Store(int64(code))
		before := hits.Load()
		result := (Client{Roots: roots}).Check(context.Background(), proxy, probe, 0)
		if result.Outcome != "pass" || result.HTTPStatus != code || !result.TLSVerified || result.Bytes != 0 || hits.Load() != before+1 {
			t.Fatalf("HTTP %d: %+v, hits=%d", code, result, hits.Load()-before)
		}
	}
	if result := (Client{}).Check(context.Background(), proxy, probe, 0); result.Outcome != "tls_failure" {
		t.Fatal("untrusted certificate accepted", result)
	}
	handle.Close()
	before := hits.Load()
	if result := (Client{Roots: roots}).Check(context.Background(), proxy, probe, 0); result.Outcome == "pass" || hits.Load() != before {
		t.Fatal("direct fallback", result)
	}
}

func TestConnectivityRejectsQueryContentAndRedirectContracts(t *testing.T) {
	base := model.Probe{ID: "test", Operator: "test", Kind: "connectivity", URL: "https://example.test/", TimeoutSeconds: 2}
	for _, mutate := range []func(*model.Probe){
		func(p *model.Probe) { p.URL += "?hl=en" }, func(p *model.Probe) { p.URL += "?" }, func(p *model.Probe) { p.URL += "path" },
		func(p *model.Probe) { p.Status = 200 }, func(p *model.Probe) { p.Contains = "body" }, func(p *model.Probe) { p.RedirectHosts = []string{"example.test"} },
	} {
		p := base
		mutate(&p)
		if Validate(p) == nil {
			t.Fatal("accepted invalid contract", p)
		}
	}
}
