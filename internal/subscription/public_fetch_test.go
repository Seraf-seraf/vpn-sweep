package subscription

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

type fakeNetwork struct {
	addresses []netip.Addr
	dialed    string
}

func (n *fakeNetwork) LookupNetIP(_ context.Context, _, _ string) ([]netip.Addr, error) {
	return n.addresses, nil
}
func (n *fakeNetwork) VerifyPublicDestination(_ context.Context, _ netip.Addr) error { return nil }
func (n *fakeNetwork) DialContext(_ context.Context, _, address string) (net.Conn, error) {
	n.dialed = address
	return nil, io.EOF
}

type fakeRoundTrip func(*http.Request) (*http.Response, error)

func (f fakeRoundTrip) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func TestPublicDialPure(t *testing.T) {
	for _, tc := range []struct {
		addr    string
		allowed bool
	}{{"8.8.8.8", true}, {"::ffff:8.8.8.8", true}, {"127.0.0.1", false}, {"::ffff:127.0.0.1", false}, {"::ffff:10.0.0.1", false}, {"64:ff9b::808:808", false}, {"2001:db8::1", false}, {"2606:4700:4700::1111", true}} {
		n := &fakeNetwork{addresses: []netip.Addr{netip.MustParseAddr(tc.addr)}}
		f := PublicFetcher{Network: n}
		_, _ = f.dial(context.Background(), "tcp", "node.invalid:443")
		if (n.dialed != "") != tc.allowed {
			t.Fatal("destination classification", tc.addr)
		}
		if strings.Contains(n.dialed, "node.invalid") {
			t.Fatal("non numeric dial")
		}
	}
	n := &fakeNetwork{addresses: []netip.Addr{netip.MustParseAddr("8.8.8.8"), netip.MustParseAddr("10.0.0.1")}}
	_, _ = (PublicFetcher{Network: n}).dial(context.Background(), "tcp", "node.invalid:443")
	if n.dialed != "" {
		t.Fatal("mixed resolution allowed")
	}
}
func TestPublicFetchHTTPPure(t *testing.T) {
	f := PublicFetcher{Limits: FetchLimits{MaxTransportBytes: 3, MaxDecodedBytes: 3, Timeout: time.Second}}
	u, _ := url.Parse("https://node.invalid/start")
	for _, tc := range []struct {
		body    string
		success bool
	}{{"ab", true}, {"abc", true}, {"abcd", false}} {
		tr := fakeRoundTrip(func(*http.Request) (*http.Response, error) {
			return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(tc.body)), Header: make(http.Header)}, nil
		})
		_, err := f.fetch(context.Background(), u, tr)
		if (err == nil) != tc.success {
			t.Fatal("body boundary", err)
		}
	}
	for _, loc := range []string{"https://NODE.invalid/x", "https://node.invalid:443/x", "https://else.invalid/x"} {
		tr := fakeRoundTrip(func(*http.Request) (*http.Response, error) {
			return &http.Response{StatusCode: 302, Body: io.NopCloser(strings.NewReader("")), Header: http.Header{"Location": []string{loc}}}, nil
		})
		if _, err := f.fetch(context.Background(), u, tr); err == nil {
			t.Fatal("unsafe redirect")
		}
	}
}

type memoryConn struct {
	written bytes.Buffer
	closed  bool
}

func (c *memoryConn) Read([]byte) (int, error)         { return 0, io.EOF }
func (c *memoryConn) Write(p []byte) (int, error)      { return c.written.Write(p) }
func (c *memoryConn) Close() error                     { c.closed = true; return nil }
func (c *memoryConn) LocalAddr() net.Addr              { return nil }
func (c *memoryConn) RemoteAddr() net.Addr             { return nil }
func (c *memoryConn) SetDeadline(time.Time) error      { return nil }
func (c *memoryConn) SetReadDeadline(time.Time) error  { return nil }
func (c *memoryConn) SetWriteDeadline(time.Time) error { return nil }

type tlsNetwork struct {
	conn     *memoryConn
	routeErr error
	dialed   string
}

func (n *tlsNetwork) LookupNetIP(context.Context, string, string) ([]netip.Addr, error) {
	return []netip.Addr{netip.MustParseAddr("8.8.8.8")}, nil
}
func (n *tlsNetwork) VerifyPublicDestination(context.Context, netip.Addr) error { return n.routeErr }
func (n *tlsNetwork) DialContext(_ context.Context, _, address string) (net.Conn, error) {
	n.dialed = address
	return n.conn, nil
}
func TestPublicTLSAndRedactionPure(t *testing.T) {
	n := &tlsNetwork{conn: &memoryConn{}}
	f := PublicFetcher{Network: n}
	_, err := f.dialTLS(context.Background(), "tcp", "tls.invalid:443")
	if err == nil || !n.conn.closed || n.dialed != "8.8.8.8:443" || !bytes.Contains(n.conn.written.Bytes(), []byte("tls.invalid")) {
		t.Fatal("TLS SNI or dial contract")
	}
	n = &tlsNetwork{conn: &memoryConn{}, routeErr: errors.New("synthetic-secret")}
	f.Network = n
	_, err = f.dialTLS(context.Background(), "tcp", "tls.invalid:443")
	if err == nil || n.dialed != "" {
		t.Fatal("unverified route accepted")
	}
	b, _ := json.Marshal(err)
	for _, s := range []string{err.Error(), fmt.Sprintf("%+v", err), fmt.Sprintf("%#v", err), string(b)} {
		if strings.Contains(s, "synthetic-secret") || strings.Contains(s, "tls.invalid") {
			t.Fatal("unsafe error")
		}
	}
}

func TestPublicRedirectAndEncodingPure(t *testing.T) {
	u, _ := url.Parse("https://node.invalid/start")
	for _, hops := range []int{4, 5} {
		calls := 0
		tr := fakeRoundTrip(func(r *http.Request) (*http.Response, error) {
			calls++
			if calls <= hops {
				return &http.Response{StatusCode: 302, Header: http.Header{"Location": []string{"/next"}}, Body: io.NopCloser(strings.NewReader(""))}, nil
			}
			return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader("ok"))}, nil
		})
		f := PublicFetcher{Limits: FetchLimits{MaxTransportBytes: 5, MaxDecodedBytes: 5}}
		_, err := f.fetch(context.Background(), u, tr)
		if (err == nil) != (hops == 4) {
			t.Fatal("redirect boundary", hops)
		}
	}
	tr := fakeRoundTrip(func(*http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: 200, Header: http.Header{"Content-Encoding": []string{"gzip"}}, Body: io.NopCloser(strings.NewReader(""))}, nil
	})
	f := PublicFetcher{Limits: FetchLimits{MaxTransportBytes: 5, MaxDecodedBytes: 5}}
	if _, err := f.fetch(context.Background(), u, tr); err == nil {
		t.Fatal("compressed content accepted")
	}
}

func TestPublicFailureClassificationPure(t *testing.T) {
	u, _ := url.Parse("https://node.invalid/token")
	f := PublicFetcher{Limits: FetchLimits{MaxTransportBytes: 5, MaxDecodedBytes: 5}}
	for _, code := range []string{"tls_failed", "network_failed", "resolution_failed", "destination_rejected"} {
		tr := fakeRoundTrip(func(*http.Request) (*http.Response, error) { return nil, model.Err(code, "subscription") })
		_, err := f.fetch(context.Background(), u, tr)
		if model.Code(err) != code {
			t.Fatal("classification")
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	tr := fakeRoundTrip(func(r *http.Request) (*http.Response, error) { return nil, r.Context().Err() })
	_, err := f.fetch(ctx, u, tr)
	if model.Code(err) != "cancelled" {
		t.Fatal("cancellation")
	}
	for _, raw := range []string{"http://node.invalid/token", "https://user:synthetic-secret@node.invalid/x", "https://node.invalid/x#secret", "https://node.invalid:0/x"} {
		if _, err := subscriptionURL(raw); err == nil {
			t.Fatal("invalid URL accepted")
		}
	}
	var n *fakeNetwork
	if _, err := (PublicFetcher{Network: n, Limits: FetchLimits{Timeout: time.Second, MaxTransportBytes: 1, MaxDecodedBytes: 1}}).Fetch(context.Background(), "https://node.invalid"); model.Code(err) != "invalid_limits" {
		t.Fatal("nil network accepted")
	}
}

type rebindNetwork struct{ resolves, dials int }

func (n *rebindNetwork) LookupNetIP(context.Context, string, string) ([]netip.Addr, error) {
	n.resolves++
	if n.resolves == 1 {
		return []netip.Addr{netip.MustParseAddr("8.8.8.8")}, nil
	}
	return []netip.Addr{netip.MustParseAddr("127.0.0.1")}, nil
}
func (n *rebindNetwork) VerifyPublicDestination(context.Context, netip.Addr) error { return nil }
func (n *rebindNetwork) DialContext(context.Context, string, string) (net.Conn, error) {
	n.dials++
	return &memoryConn{}, nil
}
func TestPublicRedirectRebindingPure(t *testing.T) {
	n := &rebindNetwork{}
	f := PublicFetcher{Network: n, Limits: FetchLimits{MaxTransportBytes: 5, MaxDecodedBytes: 5}}
	u, _ := url.Parse("https://node.invalid/start")
	calls := 0
	tr := fakeRoundTrip(func(r *http.Request) (*http.Response, error) {
		calls++
		conn, err := f.dial(r.Context(), "tcp", net.JoinHostPort(r.URL.Hostname(), "443"))
		if err != nil {
			return nil, err
		}
		_ = conn.Close()
		return &http.Response{StatusCode: 302, Header: http.Header{"Location": []string{"/next"}}, Body: io.NopCloser(strings.NewReader(""))}, nil
	})
	_, err := f.fetch(context.Background(), u, tr)
	if model.Code(err) != "destination_rejected" || n.resolves != 2 || n.dials != 1 || calls != 2 {
		t.Fatal("rebinding not rejected")
	}
}

type waitNetwork struct {
	phase   string
	entered chan struct{}
}

func (n waitNetwork) LookupNetIP(ctx context.Context, _ string, _ string) ([]netip.Addr, error) {
	if n.phase == "resolve" {
		close(n.entered)
		<-ctx.Done()
		return nil, ctx.Err()
	}
	return []netip.Addr{netip.MustParseAddr("8.8.8.8")}, nil
}
func (n waitNetwork) VerifyPublicDestination(ctx context.Context, _ netip.Addr) error {
	if n.phase == "route" {
		close(n.entered)
		<-ctx.Done()
		return ctx.Err()
	}
	return nil
}
func (n waitNetwork) DialContext(ctx context.Context, _ string, _ string) (net.Conn, error) {
	close(n.entered)
	<-ctx.Done()
	return nil, ctx.Err()
}

type cancelBody struct {
	cancel context.CancelFunc
	ctx    context.Context
	closed bool
}

func (b *cancelBody) Read([]byte) (int, error) { b.cancel(); return 0, b.ctx.Err() }
func (b *cancelBody) Close() error             { b.closed = true; return nil }
func TestPublicCancellationPhasesPure(t *testing.T) {
	for _, phase := range []string{"resolve", "route", "dial"} {
		ctx, cancel := context.WithCancel(context.Background())
		entered := make(chan struct{})
		result := make(chan error, 1)
		f := PublicFetcher{Network: waitNetwork{phase: phase, entered: entered}}
		go func() { _, err := f.dial(ctx, "tcp", "node.invalid:443"); result <- err }()
		<-entered
		cancel()
		err := <-result
		if err == nil || ctx.Err() != context.Canceled {
			t.Fatal("phase cancellation", phase)
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	body := &cancelBody{ctx: ctx, cancel: cancel}
	u, _ := url.Parse("https://node.invalid/x")
	f := PublicFetcher{Limits: FetchLimits{MaxTransportBytes: 5, MaxDecodedBytes: 5}}
	tr := fakeRoundTrip(func(*http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: body}, nil
	})
	_, err := f.fetch(ctx, u, tr)
	if model.Code(err) != "cancelled" || !body.closed {
		t.Fatal("read cancellation or cleanup")
	}
}
func TestPublicContentEncodingAllValuesPure(t *testing.T) {
	f := PublicFetcher{Limits: FetchLimits{MaxTransportBytes: 5, MaxDecodedBytes: 5}}
	u, _ := url.Parse("https://node.invalid/x")
	for _, values := range [][]string{{"identity", "gzip"}, {"identity, gzip"}, {"gzip", "identity"}} {
		tr := fakeRoundTrip(func(*http.Request) (*http.Response, error) {
			return &http.Response{StatusCode: 200, Header: http.Header{"Content-Encoding": values}, Body: io.NopCloser(strings.NewReader(""))}, nil
		})
		if _, err := f.fetch(context.Background(), u, tr); model.Code(err) != "unsupported_content_encoding" {
			t.Fatal("unsupported encoding accepted")
		}
	}
}

type blockedTLSConn struct {
	done     chan struct{}
	entered  chan struct{}
	readOnce sync.Once
	once     sync.Once
}

func (c *blockedTLSConn) Read([]byte) (int, error) {
	c.readOnce.Do(func() { close(c.entered) })
	<-c.done
	return 0, io.EOF
}
func (c *blockedTLSConn) Write(p []byte) (int, error)      { return len(p), nil }
func (c *blockedTLSConn) Close() error                     { c.once.Do(func() { close(c.done) }); return nil }
func (c *blockedTLSConn) LocalAddr() net.Addr              { return nil }
func (c *blockedTLSConn) RemoteAddr() net.Addr             { return nil }
func (c *blockedTLSConn) SetDeadline(time.Time) error      { return nil }
func (c *blockedTLSConn) SetReadDeadline(time.Time) error  { return nil }
func (c *blockedTLSConn) SetWriteDeadline(time.Time) error { return nil }

type blockedTLSNetwork struct{ conn *blockedTLSConn }

func (n blockedTLSNetwork) LookupNetIP(context.Context, string, string) ([]netip.Addr, error) {
	return []netip.Addr{netip.MustParseAddr("8.8.8.8")}, nil
}
func (n blockedTLSNetwork) VerifyPublicDestination(context.Context, netip.Addr) error { return nil }
func (n blockedTLSNetwork) DialContext(context.Context, string, string) (net.Conn, error) {
	return n.conn, nil
}
func TestPublicTLSCancellationPure(t *testing.T) {
	conn := &blockedTLSConn{done: make(chan struct{}), entered: make(chan struct{})}
	f := PublicFetcher{Network: blockedTLSNetwork{conn: conn}}
	ctx, cancel := context.WithCancel(context.Background())
	result := make(chan error, 1)
	go func() { _, err := f.dialTLS(ctx, "tcp", "node.invalid:443"); result <- err }()
	<-conn.entered
	cancel()
	err := <-result
	if err == nil || ctx.Err() != context.Canceled {
		t.Fatal("TLS cancellation")
	}
	select {
	case <-conn.done:
	default:
		t.Fatal("TLS connection not closed")
	}
}
func TestPublicRedirectCancellationPure(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	u, _ := url.Parse("https://node.invalid/x")
	f := PublicFetcher{Limits: FetchLimits{MaxTransportBytes: 5, MaxDecodedBytes: 5}}
	tr := fakeRoundTrip(func(*http.Request) (*http.Response, error) {
		cancel()
		return &http.Response{StatusCode: 302, Header: http.Header{"Location": []string{"/next"}}, Body: io.NopCloser(strings.NewReader(""))}, nil
	})
	_, err := f.fetch(ctx, u, tr)
	if model.Code(err) != "cancelled" {
		t.Fatal("redirect cancellation")
	}
}

type controlledDeadline struct{ context.Context }

func (c controlledDeadline) Deadline() (time.Time, bool) { return time.Unix(1, 0), true }
func (c controlledDeadline) Err() error {
	if c.Context.Err() != nil {
		return context.DeadlineExceeded
	}
	return nil
}
func TestPublicDeadlinePhasesPure(t *testing.T) {
	for _, phase := range []string{"resolve", "route", "dial"} {
		base, expire := context.WithCancel(context.Background())
		ctx := controlledDeadline{base}
		entered := make(chan struct{})
		result := make(chan error, 1)
		f := PublicFetcher{Network: waitNetwork{phase: phase, entered: entered}}
		go func() { _, err := f.dial(ctx, "tcp", "node.invalid:443"); result <- err }()
		<-entered
		expire()
		if err := <-result; err == nil || ctx.Err() != context.DeadlineExceeded {
			t.Fatal("deadline phase", phase)
		}
	}
}

type deadlineNetwork struct{ observed chan bool }

func (n deadlineNetwork) LookupNetIP(ctx context.Context, _, _ string) ([]netip.Addr, error) {
	_, ok := ctx.Deadline()
	n.observed <- ok
	<-ctx.Done()
	return nil, ctx.Err()
}
func (n deadlineNetwork) VerifyPublicDestination(context.Context, netip.Addr) error { return nil }
func (n deadlineNetwork) DialContext(context.Context, string, string) (net.Conn, error) {
	return nil, io.EOF
}
func TestPublicFetchDeadlinePropagationPure(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	observed := make(chan bool, 1)
	result := make(chan error, 1)
	f := PublicFetcher{Network: deadlineNetwork{observed}, Limits: FetchLimits{MaxTransportBytes: 1, MaxDecodedBytes: 1, Timeout: time.Hour}}
	go func() { _, err := f.Fetch(ctx, "https://node.invalid/x"); result <- err }()
	if !<-observed {
		t.Fatal("network did not receive deadline")
	}
	cancel()
	if model.Code(<-result) != "cancelled" {
		t.Fatal("fetch cancellation")
	}
}

type countedBody struct {
	reads  int
	closed bool
}

func (b *countedBody) Read([]byte) (int, error) { b.reads++; return 0, io.EOF }
func (b *countedBody) Close() error             { b.closed = true; return nil }
func TestPublicRedirectRawAndBudgetPure(t *testing.T) {
	f := PublicFetcher{Limits: FetchLimits{MaxTransportBytes: 1, MaxDecodedBytes: 1}}
	u, _ := url.Parse("https://node.invalid/x")
	if _, err := subscriptionURL("https://node.invalid/x#"); err == nil {
		t.Fatal("empty fragment accepted")
	}
	for _, location := range []string{"#", "/next#", "http://[::1", "%zz"} {
		body := &countedBody{}
		tr := fakeRoundTrip(func(*http.Request) (*http.Response, error) {
			return &http.Response{StatusCode: 302, Header: http.Header{"Location": []string{location}}, Body: body}, nil
		})
		_, err := f.fetch(context.Background(), u, tr)
		if model.Code(err) != "redirect_rejected" || body.reads != 0 || !body.closed {
			t.Fatal("redirect classification or body read")
		}
	}
	body := &countedBody{}
	calls := 0
	tr := fakeRoundTrip(func(*http.Request) (*http.Response, error) {
		calls++
		if calls == 1 {
			return &http.Response{StatusCode: 302, Header: http.Header{"Location": []string{"/next"}}, Body: body}, nil
		}
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader("x"))}, nil
	})
	if _, err := f.fetch(context.Background(), u, tr); err != nil || body.reads != 0 || !body.closed {
		t.Fatal("redirect body budget")
	}
}
func TestPublicIndependentLimitsPure(t *testing.T) {
	u, _ := url.Parse("https://node.invalid/x")
	for _, limits := range []FetchLimits{{MaxTransportBytes: 2, MaxDecodedBytes: 5}, {MaxTransportBytes: 5, MaxDecodedBytes: 2}} {
		for _, size := range []int{1, 2, 3} {
			f := PublicFetcher{Limits: limits}
			tr := fakeRoundTrip(func(*http.Request) (*http.Response, error) {
				return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(strings.Repeat("x", size)))}, nil
			})
			_, err := f.fetch(context.Background(), u, tr)
			if (err == nil) != (size <= 2) {
				t.Fatal("independent limit", size)
			}
		}
	}
}

type checkedNetwork struct {
	addresses    []netip.Addr
	checked      []netip.Addr
	dialed       string
	reject       bool
	rejectPrefix netip.Prefix
}

func (n *checkedNetwork) LookupNetIP(context.Context, string, string) ([]netip.Addr, error) {
	return n.addresses, nil
}
func (n *checkedNetwork) VerifyPublicDestination(_ context.Context, a netip.Addr) error {
	n.checked = append(n.checked, a)
	if n.reject || n.rejectPrefix.IsValid() && n.rejectPrefix.Contains(a) {
		return io.EOF
	}
	return nil
}
func (n *checkedNetwork) DialContext(_ context.Context, _, address string) (net.Conn, error) {
	n.dialed = address
	return nil, io.EOF
}
func TestPublicVerifiedNumericBindingPure(t *testing.T) {
	for _, tc := range []struct {
		addresses       []netip.Addr
		reject, allowed bool
	}{
		{[]netip.Addr{netip.MustParseAddr("::ffff:8.8.8.8")}, false, true},
		{[]netip.Addr{netip.MustParseAddr("8.8.8.8"), netip.MustParseAddr("10.0.0.1")}, false, false},
		{[]netip.Addr{netip.MustParseAddr("2606:4700:4700::1111")}, true, false},
	} {
		n := &checkedNetwork{addresses: tc.addresses, reject: tc.reject}
		_, _ = (PublicFetcher{Network: n}).dial(context.Background(), "tcp", "node.invalid:443")
		if (n.dialed != "") != tc.allowed {
			t.Fatal("destination binding")
		}
		if tc.allowed && (len(n.checked) != 1 || n.checked[0] != tc.addresses[0].Unmap() || net.JoinHostPort(tc.addresses[0].Unmap().String(), "443") != n.dialed) {
			t.Fatal("checked address differs from dial")
		}
	}
}

type fakeTimedContext struct {
	context.Context
	mu       sync.Mutex
	done     chan struct{}
	err      error
	deadline time.Time
}

func (c *fakeTimedContext) Deadline() (time.Time, bool) { return c.deadline, true }
func (c *fakeTimedContext) Done() <-chan struct{}       { return c.done }
func (c *fakeTimedContext) Err() error                  { c.mu.Lock(); defer c.mu.Unlock(); return c.err }
func (c *fakeTimedContext) finish(err error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.err == nil {
		c.err = err
		close(c.done)
	}
}

type fakeTimeoutClock struct {
	ctx            *fakeTimedContext
	limit, elapsed time.Duration
}

func (c *fakeTimeoutClock) timeout(parent context.Context, limit time.Duration) (context.Context, context.CancelFunc) {
	c.limit = limit
	c.ctx = &fakeTimedContext{Context: parent, done: make(chan struct{}), deadline: time.Date(2100, 1, 1, 0, 0, 0, 0, time.UTC)}
	return c.ctx, func() { c.ctx.finish(context.Canceled) }
}
func (c *fakeTimeoutClock) advance(d time.Duration) {
	c.elapsed += d
	if c.elapsed >= c.limit {
		c.ctx.finish(context.DeadlineExceeded)
	}
}
func TestPublicFetchTimeoutClockPure(t *testing.T) {
	clock := &fakeTimeoutClock{}
	observed := make(chan bool, 1)
	result := make(chan error, 1)
	f := PublicFetcher{Network: deadlineNetwork{observed}, Limits: FetchLimits{MaxTransportBytes: 1, MaxDecodedBytes: 1, Timeout: time.Minute}, withTimeout: clock.timeout}
	go func() { _, err := f.Fetch(context.Background(), "https://node.invalid/x"); result <- err }()
	if !<-observed || clock.limit != time.Minute {
		t.Fatal("deadline not passed to network")
	}
	clock.advance(time.Minute - time.Nanosecond)
	if clock.ctx.Err() != nil {
		t.Fatal("expired below deadline")
	}
	select {
	case <-result:
		t.Fatal("fetch returned early")
	default:
	}
	clock.advance(time.Nanosecond)
	if err := <-result; model.Code(err) != "subscription_timeout" {
		t.Fatal("timeout classification")
	}
}

func TestPublicCustomNAT64PrefixPure(t *testing.T) {
	prefix := netip.MustParsePrefix("2606:4700:64::/96")
	address := netip.MustParseAddr("2606:4700:64::808:808")
	n := &checkedNetwork{addresses: []netip.Addr{address}, rejectPrefix: prefix}
	_, err := (PublicFetcher{Network: n}).dial(context.Background(), "tcp", "node.invalid:443")
	if model.Code(err) != "destination_rejected" || len(n.checked) != 1 || n.checked[0] != address || n.dialed != "" {
		t.Fatal("custom NAT64 verifier not enforced")
	}
}
