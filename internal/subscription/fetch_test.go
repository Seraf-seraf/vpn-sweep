package subscription

import (
	"context"
	"crypto/x509"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestFetchContract(t *testing.T) {
	s := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/large":
			w.Write([]byte(strings.Repeat("x", 33)))
		case "/redirect":
			http.Redirect(w, r, "https://else.invalid/secret-marker", 302)
		case "/loop":
			http.Redirect(w, r, "/loop", 302)
		case "/slow":
			time.Sleep(50 * time.Millisecond)
		default:
			w.Write([]byte("socks5://test.invalid:1"))
		}
	}))
	defer s.Close()
	roots := x509.NewCertPool()
	roots.AddCert(s.Certificate())
	f := Fetcher{Roots: roots, MaxBytes: 32}
	b, e := f.Fetch(context.Background(), s.URL)
	if e != nil || string(b) != "socks5://test.invalid:1" {
		t.Fatal(e)
	}
	for _, path := range []string{"/large", "/redirect", "/loop"} {
		if _, e = f.Fetch(context.Background(), s.URL+path); e == nil || strings.Contains(e.Error(), "secret-marker") {
			t.Fatal("unsafe fetch result")
		}
	}
	if _, e = (Fetcher{}).Fetch(context.Background(), s.URL); e == nil {
		t.Fatal("untrusted TLS accepted")
	}
	f.Timeout = time.Millisecond
	if _, e = f.Fetch(context.Background(), s.URL+"/slow"); e == nil {
		t.Fatal("deadline ignored")
	}
	if _, e = f.Fetch(context.Background(), "http://example.invalid/token"); e == nil {
		t.Fatal("cleartext accepted")
	}
}
