package subscriptionbridge

import (
	"context"
	"errors"
	"math"
	"net"
	"net/netip"
	"reflect"
	"sync"
	"syscall"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/subscription"
)

type Route interface {
	Current() bool
	QueryDNS(packet []byte, remainingMillis int64) ([]byte, error)
	VerifyPublicDestination(address string, remainingMillis int64) bool
	AuthorizeSocket(fd int64, network, address string, remainingMillis int64) bool
}

type Request struct {
	mu      sync.Mutex
	route   Route
	ctx     context.Context
	cancel  context.CancelFunc
	timeout time.Duration
	started bool
}

func NewRequest(route Route, timeoutMillis int64) (*Request, error) {
	if nilRoute(route) || timeoutMillis <= 0 || timeoutMillis > math.MaxInt64/int64(time.Millisecond) {
		return nil, errInput
	}
	ctx, cancel := context.WithCancel(context.Background())
	return &Request{route: route, ctx: ctx, cancel: cancel, timeout: time.Duration(timeoutMillis) * time.Millisecond}, nil
}

func (r *Request) Fetch(rawURL string) (*Snapshot, error) {
	if r == nil {
		return nil, errInput
	}
	r.mu.Lock()
	if r.started {
		r.mu.Unlock()
		return nil, errInput
	}
	r.started = true
	r.mu.Unlock()
	ctx, cancel := context.WithTimeout(r.ctx, r.timeout)
	defer cancel()
	defer r.cancel()
	network := &requestNetwork{route: r.route}
	if !network.current(ctx) {
		return nil, requestError(ctx)
	}
	fetcher := subscription.PublicFetcher{
		Network: network,
		Limits: subscription.FetchLimits{MaxTransportBytes: importer.MaxInputBytes,
			MaxDecodedBytes: importer.MaxInputBytes, Timeout: r.timeout},
	}
	raw, err := fetcher.Fetch(ctx, rawURL)
	if err != nil {
		return nil, safeError(err)
	}
	if !network.current(ctx) {
		return nil, requestError(ctx)
	}
	snapshot, err := parseSnapshot(ctx, raw)
	if err != nil {
		return nil, err
	}
	if !network.current(ctx) {
		snapshot.Close()
		return nil, requestError(ctx)
	}
	return snapshot, nil
}

func requestError(ctx context.Context) error {
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return errors.New("subscription_timeout")
	}
	if errors.Is(ctx.Err(), context.Canceled) {
		return errors.New("cancelled")
	}
	return errors.New("destination_rejected")
}

func (r *Request) Cancel() {
	if r != nil {
		r.cancel()
	}
}

func nilRoute(route Route) bool {
	if route == nil {
		return true
	}
	v := reflect.ValueOf(route)
	switch v.Kind() {
	case reflect.Chan, reflect.Func, reflect.Interface, reflect.Map, reflect.Pointer, reflect.Slice:
		return v.IsNil()
	}
	return false
}

type requestNetwork struct{ route Route }

func (n *requestNetwork) current(ctx context.Context) (current bool) {
	if ctx.Err() != nil || nilRoute(n.route) {
		return false
	}
	defer func() {
		if recover() != nil {
			current = false
		}
	}()
	return n.route.Current() && ctx.Err() == nil
}

func remainingMillis(ctx context.Context) int64 {
	deadline, ok := ctx.Deadline()
	if !ok || ctx.Err() != nil {
		return 0
	}
	remaining := time.Until(deadline) / time.Millisecond
	if remaining <= 0 {
		return 0
	}
	return int64(remaining)
}

func (n *requestNetwork) LookupNetIP(ctx context.Context, network, host string) ([]netip.Addr, error) {
	if !n.current(ctx) {
		return nil, errInput
	}
	resolver := net.Resolver{PreferGo: true, StrictErrors: true, Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
		if !n.current(ctx) {
			return nil, errInput
		}
		return &dnsConn{ctx: ctx, owner: n}, nil
	}}
	addresses, err := resolver.LookupNetIP(ctx, network, host)
	if err != nil || !n.current(ctx) {
		return nil, errInput
	}
	return addresses, nil
}

func (n *requestNetwork) VerifyPublicDestination(ctx context.Context, address netip.Addr) (err error) {
	defer func() {
		if recover() != nil {
			err = errInput
		}
	}()
	if !n.current(ctx) {
		return errInput
	}
	remaining := remainingMillis(ctx)
	if remaining == 0 || !n.route.VerifyPublicDestination(address.String(), remaining) || !n.current(ctx) {
		return errInput
	}
	return nil
}

func (n *requestNetwork) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	if !n.current(ctx) {
		return nil, errInput
	}
	dialer := net.Dialer{ControlContext: func(ctx context.Context, network, address string, raw syscall.RawConn) error {
		if !n.current(ctx) {
			return errInput
		}
		allowed := false
		err := raw.Control(func(fd uintptr) {
			allowed = n.authorize(ctx, int64(fd), network, address)
		})
		if err != nil || !allowed || !n.current(ctx) {
			return errInput
		}
		return nil
	}}
	conn, err := dialer.DialContext(ctx, network, address)
	if err != nil {
		return nil, errInput
	}
	if !n.current(ctx) {
		conn.Close()
		return nil, errInput
	}
	return &guardedConn{Conn: conn, ctx: ctx, owner: n}, nil
}

func (n *requestNetwork) authorize(ctx context.Context, fd int64, network, address string) (allowed bool) {
	defer func() {
		if recover() != nil {
			allowed = false
		}
	}()
	if !n.current(ctx) {
		return false
	}
	remaining := remainingMillis(ctx)
	return remaining > 0 && n.route.AuthorizeSocket(fd, network, address, remaining) && n.current(ctx)
}

type guardedConn struct {
	net.Conn
	ctx   context.Context
	owner *requestNetwork
}

func (c *guardedConn) Read(b []byte) (int, error) {
	if !c.owner.current(c.ctx) {
		return 0, errInput
	}
	n, err := c.Conn.Read(b)
	if !c.owner.current(c.ctx) {
		return 0, errInput
	}
	return n, err
}

func (c *guardedConn) Write(b []byte) (int, error) {
	if !c.owner.current(c.ctx) {
		return 0, errInput
	}
	n, err := c.Conn.Write(b)
	if !c.owner.current(c.ctx) {
		return 0, errInput
	}
	return n, err
}
