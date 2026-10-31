package mobile

import (
	"context"
	"errors"
	"net"
	"syscall"

	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
)

const (
	SearchRoleXrayDial = iota + 1
	SearchRoleXrayListener
	SearchRoleDNS
	SearchRoleSOCKSControl
	SearchRoleSOCKSRelay
	SearchRoleSOCKSReservation
)

// AndroidNetwork is immutable for a worker generation. Each callback must
// recheck the worker lease and the selected default network identity/version.
type AndroidNetwork interface {
	Current() bool
	QueryDNS(packet []byte, remainingMillis int64) ([]byte, error)
	AllowSocket(fd int, role int, network string, address string) bool
}

func InitializeSearch(owner AndroidNetwork) error {
	runtimeState.Lock()
	defer runtimeState.Unlock()
	if owner == nil || runtimeState.instance != nil || runtimeState.searchInitialized || activeProtection.Load() != nil {
		return errors.New("search_admission_failed")
	}
	ctx, cancel := context.WithCancel(context.Background())
	guard := &protection{search: owner, searchContext: ctx, searchCancel: cancel}
	if !guard.current() {
		cancel()
		return errors.New("search_admission_failed")
	}
	runtimeState.searchInitialized = true
	activeProtection.Store(guard)
	return nil
}

func (p *protection) revokeSearch() {
	if p == nil || p.search == nil {
		return
	}
	p.searchRevoked.Store(true)
	if p.searchCancel != nil {
		p.searchCancel()
	}
}

func (p *protection) current() (ok bool) {
	if p == nil || p.search == nil || p.searchRevoked.Load() {
		return false
	}
	defer func() {
		if recover() != nil {
			ok = false
		}
		if !ok {
			p.revokeSearch()
		}
	}()
	return p.search.Current()
}

func (p *protection) allowSocket(fd int, role int, network, address string) (ok bool) {
	if p == nil {
		return false
	}
	defer func() {
		if recover() != nil {
			ok = false
		}
		if !ok {
			p.revokeSearch()
		}
	}()
	if p.search != nil {
		return p.current() && p.search.AllowSocket(fd, role, network, address) && p.current()
	}
	return p.callback != nil && p.callback.Protect(fd)
}

func searchCurrent(owner *protection) bool {
	if owner == nil || owner.search == nil {
		return false
	}
	if activeProtection.Load() != owner {
		owner.revokeSearch()
		return false
	}
	return owner.current()
}

func searchControl(owner *protection, role int) func(string, string, syscall.RawConn) error {
	return func(network, address string, raw syscall.RawConn) error {
		return controlSocket(owner, role, network, address, raw, invalidateSocket)
	}
}

type socketFactory interface {
	DialContext(context.Context, string, string, func(string, string, syscall.RawConn) error) (net.Conn, error)
}

type systemSocketFactory struct{}

func (systemSocketFactory) DialContext(ctx context.Context, network, address string, control func(string, string, syscall.RawConn) error) (net.Conn, error) {
	dialer := net.Dialer{Control: control}
	return dialer.DialContext(ctx, network, address)
}

type searchSocketDialer struct {
	owner   *protection
	factory socketFactory
}

func (d searchSocketDialer) DialTCP(ctx context.Context, address string) (net.Conn, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	host, _, err := net.SplitHostPort(address)
	ip := net.ParseIP(host)
	if err != nil || ip == nil || !ip.IsLoopback() || d.owner == nil || d.factory == nil ||
		!searchCurrent(d.owner) {
		return nil, errors.New("search_socket_denied")
	}
	return d.factory.DialContext(ctx, "tcp", address, searchControl(d.owner, SearchRoleSOCKSControl))
}

func (d searchSocketDialer) DialUDP(ctx context.Context, relay *net.UDPAddr) (socks.UDPConn, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if relay == nil || !relay.IP.IsLoopback() || d.owner == nil || d.factory == nil ||
		!searchCurrent(d.owner) {
		return nil, errors.New("search_socket_denied")
	}
	conn, err := d.factory.DialContext(ctx, "udp", relay.String(), searchControl(d.owner, SearchRoleSOCKSRelay))
	if err != nil {
		return nil, err
	}
	udp, ok := conn.(socks.UDPConn)
	if !ok {
		if conn != nil {
			_ = conn.Close()
		}
		return nil, errors.New("search_socket_unavailable")
	}
	return udp, nil
}

func searchProxy(owner *protection, address, user, password string) (socks.Endpoint, error) {
	if !searchCurrent(owner) {
		return socks.Endpoint{}, errors.New("search_network_unavailable")
	}
	return socks.Endpoint{Address: address, User: user, Pass: password, SocketDialer: searchSocketDialer{owner: owner, factory: systemSocketFactory{}}}, nil
}
