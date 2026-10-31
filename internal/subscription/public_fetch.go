package subscription

import (
	"context"
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"reflect"
	"strconv"
	"strings"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

type Network interface {
	LookupNetIP(context.Context, string, string) ([]netip.Addr, error)
	// VerifyPublicDestination must prove the address is public and the selected route has no unsupported translation.
	VerifyPublicDestination(context.Context, netip.Addr) error
	DialContext(context.Context, string, string) (net.Conn, error)
}
type FetchLimits struct {
	MaxTransportBytes, MaxDecodedBytes int64
	Timeout                            time.Duration
}
type PublicFetcher struct {
	Network     Network
	Limits      FetchLimits
	withTimeout func(context.Context, time.Duration) (context.Context, context.CancelFunc)
}

func subscriptionURL(raw string) (*url.URL, error) {
	if strings.Contains(raw, "#") {
		return nil, model.Err("invalid_subscription_url", "subscription")
	}
	u, e := url.Parse(raw)
	if e != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.Fragment != "" || u.Opaque != "" {
		return nil, model.Err("invalid_subscription_url", "subscription")
	}
	if port := u.Port(); port != "" {
		p, e := strconv.Atoi(port)
		if e != nil || p < 1 || p > 65535 {
			return nil, model.Err("invalid_subscription_url", "subscription")
		}
	} else if strings.HasSuffix(u.Host, ":") {
		return nil, model.Err("invalid_subscription_url", "subscription")
	}
	return u, nil
}

func (f PublicFetcher) Fetch(ctx context.Context, raw string) ([]byte, error) {
	if nilNetwork(f.Network) || f.Limits.Timeout <= 0 || f.Limits.MaxTransportBytes <= 0 || f.Limits.MaxDecodedBytes <= 0 || f.Limits.MaxTransportBytes > importer.MaxInputBytes || f.Limits.MaxDecodedBytes > importer.MaxInputBytes {
		return nil, model.Err("invalid_limits", "subscription")
	}
	u, err := subscriptionURL(raw)
	if err != nil {
		return nil, err
	}
	timeout := f.withTimeout
	if timeout == nil {
		timeout = context.WithTimeout
	}
	ctx, cancel := timeout(ctx, f.Limits.Timeout)
	defer cancel()
	tr := &http.Transport{Proxy: nil, DisableKeepAlives: true, DisableCompression: true, DialTLSContext: func(_ context.Context, network, address string) (net.Conn, error) {
		return f.dialTLS(ctx, network, address)
	}}
	defer tr.CloseIdleConnections()
	return f.fetch(ctx, u, tr)
}
func (f PublicFetcher) fetch(ctx context.Context, u *url.URL, transport http.RoundTripper) ([]byte, error) {
	originalHost := u.Host
	for hops := 0; ; hops++ {
		if ctx.Err() != nil {
			return nil, model.Err(contextErrorCode(ctx), "subscription")
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
		if err != nil {
			return nil, model.Err("invalid_subscription_url", "subscription")
		}
		req.Header.Set("Accept-Encoding", "identity")
		resp, err := transport.RoundTrip(req)
		if err != nil {
			if ctx.Err() != nil {
				return nil, model.Err(contextErrorCode(ctx), "subscription")
			}
			code := model.Code(err)
			switch code {
			case "destination_rejected", "resolution_failed", "network_failed", "tls_failed":
				return nil, model.Err(code, "subscription")
			}
			return nil, model.Err("network_failed", "subscription")
		}
		if resp.StatusCode == 301 || resp.StatusCode == 302 || resp.StatusCode == 303 || resp.StatusCode == 307 || resp.StatusCode == 308 {
			location := resp.Header.Get("Location")
			_ = resp.Body.Close()
			if ctx.Err() != nil {
				return nil, model.Err(contextErrorCode(ctx), "subscription")
			}
			if hops >= 4 || location == "" || strings.Contains(location, "#") {
				return nil, model.Err("redirect_rejected", "subscription")
			}
			ref, err := url.Parse(location)
			if err != nil {
				return nil, model.Err("redirect_rejected", "subscription")
			}
			target := u.ResolveReference(ref)
			if target.Host != originalHost {
				return nil, model.Err("redirect_rejected", "subscription")
			}
			u, err = subscriptionURL(target.String())
			if err != nil {
				return nil, model.Err("redirect_rejected", "subscription")
			}
			continue
		}
		if resp.StatusCode != http.StatusOK {
			_ = resp.Body.Close()
			return nil, model.Err("subscription_http_status", "subscription")
		}
		for _, value := range resp.Header.Values("Content-Encoding") {
			for _, enc := range strings.Split(value, ",") {
				if !strings.EqualFold(strings.TrimSpace(enc), "identity") {
					_ = resp.Body.Close()
					return nil, model.Err("unsupported_content_encoding", "subscription")
				}
			}
		}
		max := f.Limits.MaxTransportBytes
		if f.Limits.MaxDecodedBytes < max {
			max = f.Limits.MaxDecodedBytes
		}
		b, err := io.ReadAll(io.LimitReader(resp.Body, max+1))
		_ = resp.Body.Close()
		if ctx.Err() != nil {
			return nil, model.Err(contextErrorCode(ctx), "subscription")
		}
		if err != nil {
			return nil, model.Err("subscription_read_failed", "subscription")
		}
		if int64(len(b)) > max {
			return nil, model.Err("subscription_too_large", "subscription")
		}
		return b, nil
	}
}

func (f PublicFetcher) dial(ctx context.Context, network, address string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return nil, model.Err("destination_rejected", "subscription")
	}
	var addresses []netip.Addr
	if a, e := netip.ParseAddr(host); e == nil {
		addresses = []netip.Addr{a}
	} else {
		addresses, err = f.Network.LookupNetIP(ctx, "ip", host)
		if err != nil {
			return nil, model.Err("resolution_failed", "subscription")
		}
	}
	if len(addresses) == 0 {
		return nil, model.Err("destination_rejected", "subscription")
	}
	for _, a := range addresses {
		a = a.Unmap()
		if !publicAddress(a) || f.Network.VerifyPublicDestination(ctx, a) != nil {
			return nil, model.Err("destination_rejected", "subscription")
		}
	}
	conn, err := f.Network.DialContext(ctx, network, net.JoinHostPort(addresses[0].Unmap().String(), port))
	if err != nil || conn == nil {
		return nil, model.Err("network_failed", "subscription")
	}
	return conn, nil
}

var nonPublicPrefixes = func() []netip.Prefix {
	cidrs := []string{"0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4", "::/128", "::1/128", "64:ff9b::/96", "64:ff9b:1::/48", "100::/64", "2001::/23", "2001:db8::/32", "2002::/16", "3fff::/20", "fc00::/7", "fe80::/10", "ff00::/8"}
	out := make([]netip.Prefix, 0, len(cidrs))
	for _, s := range cidrs {
		out = append(out, netip.MustParsePrefix(s))
	}
	return out
}()

func publicAddress(a netip.Addr) bool {
	if !a.IsValid() || a.Zone() != "" || !a.IsGlobalUnicast() {
		return false
	}
	if a.Is6() && !netip.MustParsePrefix("2000::/3").Contains(a) {
		return false
	}
	for _, p := range nonPublicPrefixes {
		if p.Contains(a) {
			return false
		}
	}
	return true
}

func (f PublicFetcher) dialTLS(ctx context.Context, network, address string) (net.Conn, error) {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return nil, model.Err("destination_rejected", "subscription")
	}
	conn, err := f.dial(ctx, network, address)
	if err != nil {
		return nil, err
	}
	tlsConn := tls.Client(conn, &tls.Config{MinVersion: tls.VersionTLS12, ServerName: host})
	if err := tlsConn.HandshakeContext(ctx); err != nil {
		_ = conn.Close()
		return nil, model.Err("tls_failed", "subscription")
	}
	return tlsConn, nil
}

func nilNetwork(n Network) bool {
	if n == nil {
		return true
	}
	v := reflect.ValueOf(n)
	switch v.Kind() {
	case reflect.Chan, reflect.Func, reflect.Interface, reflect.Map, reflect.Pointer, reflect.Slice:
		return v.IsNil()
	}
	return false
}

func contextErrorCode(ctx context.Context) string {
	if ctx.Err() == context.DeadlineExceeded {
		return "subscription_timeout"
	}
	return "cancelled"
}
