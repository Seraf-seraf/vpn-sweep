package subscription

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"io"
	"net/http"
	"net/url"
	"time"
)

type Fetcher struct {
	Roots    *x509.CertPool
	Timeout  time.Duration
	MaxBytes int64
}

func (f Fetcher) Fetch(ctx context.Context, raw string) ([]byte, error) {
	u, e := url.Parse(raw)
	if e != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.Fragment != "" {
		return nil, model.Err("invalid_subscription_url", "subscription")
	}
	max := f.MaxBytes
	if max == 0 {
		max = importer.MaxInputBytes
	}
	timeout := f.Timeout
	if timeout == 0 {
		timeout = 30 * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	tr := &http.Transport{Proxy: nil, TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12, RootCAs: f.Roots}, DisableKeepAlives: true}
	defer tr.CloseIdleConnections()
	client := &http.Client{Transport: tr, CheckRedirect: func(r *http.Request, via []*http.Request) error {
		if len(via) >= 5 || r.URL.Scheme != "https" || r.URL.Host != u.Host || r.URL.User != nil {
			return model.Err("redirect_rejected", "subscription")
		}
		return nil
	}}
	req, e := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if e != nil {
		return nil, model.Err("invalid_subscription_url", "subscription")
	}
	req.Header.Set("Accept", "application/json, text/plain")
	resp, e := client.Do(req)
	if e != nil {
		return nil, model.Err("subscription_request_failed", "subscription")
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, model.Err("subscription_http_status", "subscription")
	}
	b, e := io.ReadAll(io.LimitReader(resp.Body, max+1))
	if e != nil {
		return nil, model.Err("subscription_read_failed", "subscription")
	}
	if int64(len(b)) > max {
		return nil, model.Err("subscription_too_large", "subscription")
	}
	return b, nil
}
