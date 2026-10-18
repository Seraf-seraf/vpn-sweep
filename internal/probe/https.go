package probe

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
	"io"
	"mime"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const MaxPageBodyBytes int64 = 2 << 20

type Client struct{ Roots *x509.CertPool }

func Validate(p model.Probe) error {
	u, e := url.Parse(p.URL)
	if e != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.Fragment != "" || p.ID == "" || p.Operator == "" {
		return model.Err("invalid_probe", "policy")
	}
	if p.Kind != "page" && p.Kind != "download" {
		return model.Err("unsupported_probe_kind", "policy")
	}
	if p.Contains == "" && p.SHA256 == "" && len(p.ContainsAll) == 0 {
		return model.Err("missing_content_validator", "policy")
	}
	if len(p.ContainsAll) > 8 || p.MinBytes < 0 || p.MinBytes > MaxPageBodyBytes || (p.Bytes > 0 && p.MinBytes > p.Bytes) || (p.Kind != "page" && (len(p.ContainsAll) > 0 || p.MinBytes > 0)) {
		return model.Err("invalid_body_contract", "policy")
	}
	for _, marker := range p.ContainsAll {
		if len(marker) < 4 || len(marker) > 1024 {
			return model.Err("invalid_composite_marker", "policy")
		}
	}
	if p.Kind == "download" && (p.SHA256 == "" || p.Bytes < 1) {
		return model.Err("download_requires_hash_and_size", "policy")
	}
	if p.SHA256 != "" {
		b, e := hex.DecodeString(p.SHA256)
		if e != nil || len(b) != 32 {
			return model.Err("invalid_content_hash", "policy")
		}
	}
	if p.Bytes < 0 || p.Bytes > 16<<20 || p.TimeoutSeconds < 1 || p.TimeoutSeconds > 300 || p.Status < 200 || p.Status > 299 {
		return model.Err("invalid_probe_limits", "policy")
	}
	return nil
}
func (c Client) Check(ctx context.Context, proxy socks.Endpoint, p model.Probe, round int) model.ProbeResult {
	started := time.Now()
	r := model.ProbeResult{ID: model.ID(), Probe: p.ID, Round: round, Transport: "https-tcp", Phase: "request"}
	defer func() {}()
	finish := func(outcome, phase string) model.ProbeResult {
		r.Outcome = outcome
		r.Phase = phase
		r.DurationMS = time.Since(started).Milliseconds()
		return r
	}
	if Validate(p) != nil {
		return finish("invalid_probe", "validation")
	}
	ctx, cancel := context.WithTimeout(ctx, time.Duration(p.TimeoutSeconds)*time.Second)
	defer cancel()
	u, _ := url.Parse(p.URL)
	tr := &http.Transport{Proxy: nil, DialContext: proxy.DialContext, DisableKeepAlives: true, ForceAttemptHTTP2: true, TLSClientConfig: &tls.Config{RootCAs: c.Roots, MinVersion: tls.VersionTLS12}, MaxResponseHeaderBytes: 64 << 10}
	defer tr.CloseIdleConnections()
	client := &http.Client{Transport: tr, CheckRedirect: func(req *http.Request, via []*http.Request) error {
		r.Redirects = len(via)
		if len(p.ContainsAll) > 0 && (req.URL.Hostname() == "consent.google.com" || req.URL.Hostname() == "consent.youtube.com") {
			return model.Err("site_consent", "site")
		}
		if len(via) > 5 || req.URL.Scheme != "https" || req.URL.User != nil {
			return model.Err("redirect_rejected", "redirect")
		}
		if req.URL.Host == u.Host {
			return nil
		}
		for _, h := range p.RedirectHosts {
			if req.URL.Host == h {
				return nil
			}
		}
		return model.Err("redirect_rejected", "redirect")
	}}
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, p.URL, nil)
	req.Header.Set("User-Agent", "vpn-sweep/1 connectivity-check")
	req.Header.Set("Accept-Encoding", "identity")
	resp, e := client.Do(req)
	if e != nil {
		var me *model.Error
		if errors.As(e, &me) {
			return finish(me.Code, me.Phase)
		}
		var cert *tls.CertificateVerificationError
		if errors.As(e, &cert) {
			return finish("tls_failure", "tls")
		}
		var ne net.Error
		if errors.As(e, &ne) && ne.Timeout() || ctx.Err() != nil {
			return finish("timeout", "request")
		}
		return finish("request_failed", "request")
	}
	defer resp.Body.Close()
	r.HTTPStatus = resp.StatusCode
	r.TLSVerified = resp.TLS != nil && len(resp.TLS.VerifiedChains) > 0
	if !r.TLSVerified {
		return finish("tls_unverified", "tls")
	}
	if resp.StatusCode != p.Status {
		switch resp.StatusCode {
		case 403:
			return finish("site_forbidden", "site")
		case 429:
			return finish("site_rate_limited", "site")
		case 451:
			return finish("site_restricted", "site")
		}
		return finish("unexpected_http_status", "site")
	}
	max := MaxPageBodyBytes
	if p.Bytes > 0 {
		max = p.Bytes
	}
	b, e := io.ReadAll(io.LimitReader(resp.Body, max+1))
	r.Bytes = int64(len(b))
	if e != nil {
		if ctx.Err() != nil {
			return finish("timeout", "transfer")
		}
		return finish("transfer_failed", "transfer")
	}
	if r.Bytes > max {
		return finish("body_too_large", "content")
	}
	if p.Bytes > 0 && r.Bytes != p.Bytes {
		return finish("wrong_body_size", "content")
	}
	m, _, mimeErr := mime.ParseMediaType(resp.Header.Get("Content-Type"))
	if mimeErr == nil {
		r.MIME = m
	}
	if p.MIME != "" {
		if mimeErr != nil || m != p.MIME {
			return finish("unexpected_mime", "content")
		}
	}
	if r.Bytes < p.MinBytes {
		return finish("body_too_small", "content")
	}
	lower := strings.ToLower(string(b))
	if len(p.ContainsAll) > 0 {
		for _, marker := range []string{"before you continue to google", "before you continue to youtube", `<form action="https://consent.google.com/`, `<form action="https://consent.youtube.com/`} {
			if strings.Contains(lower, marker) {
				return finish("site_consent", "site")
			}
		}
		for _, marker := range []string{"sign in to confirm", "our systems have detected unusual traffic"} {
			if strings.Contains(lower, marker) {
				return finish("site_challenge", "site")
			}
		}
		if strings.HasPrefix(resp.Request.URL.Path, "/sorry/") {
			return finish("site_challenge", "site")
		}
	}
	for _, marker := range []string{"cf-chl-", "g-recaptcha", "verify you are human"} {
		if strings.Contains(lower, marker) {
			return finish("site_challenge", "site")
		}
	}
	if p.Contains != "" && !strings.Contains(string(b), p.Contains) {
		return finish("unexpected_content", "content")
	}
	for _, marker := range p.ContainsAll {
		if !strings.Contains(string(b), marker) {
			return finish("composite_marker_missing", "content")
		}
	}
	if p.SHA256 != "" {
		h := sha256.Sum256(b)
		if hex.EncodeToString(h[:]) != strings.ToLower(p.SHA256) {
			return finish("content_hash_mismatch", "content")
		}
	}
	r.ContentVerified = true
	return finish("pass", "complete")
}
