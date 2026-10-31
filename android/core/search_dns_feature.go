package mobile

import (
	"context"
	"errors"
	"net"
	"net/netip"

	"github.com/Seraf-seraf/vpn-sweep/android/core/searchdns"
	"github.com/xtls/xray-core/common"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/features/dns"
)

type searchDNSContextKey struct{}

type searchDNSClient struct {
	ctx   context.Context
	owner *protection
}

func init() {
	if err := common.RegisterConfig((*searchdns.Config)(nil), func(ctx context.Context, _ any) (any, error) {
		owner, ok := ctx.Value(searchDNSContextKey{}).(*protection)
		if !ok || !searchCurrent(owner) {
			return nil, errors.New("search_network_unavailable")
		}
		return &searchDNSClient{ctx: ctx, owner: owner}, nil
	}); err != nil {
		panic("search_dns_registration_failed")
	}
}

func (*searchDNSClient) Type() any    { return dns.ClientType() }
func (*searchDNSClient) Start() error { return nil }
func (*searchDNSClient) Close() error { return nil }

func (r *searchDNSClient) LookupIP(host string, option dns.IPOption) (result []xnet.IP, ttl uint32, err error) {
	defer func() {
		if recover() != nil {
			r.owner.revokeSearch()
			result, ttl, err = nil, 0, errors.New("search_network_unavailable")
		}
	}()
	if err := r.ctx.Err(); err != nil {
		return nil, 0, err
	}
	if !searchCurrent(r.owner) {
		return nil, 0, errors.New("search_network_unavailable")
	}
	if !option.IPv4Enable && !option.IPv6Enable {
		return nil, 0, dns.ErrEmptyResponse
	}
	network := "ip"
	if option.IPv4Enable && !option.IPv6Enable {
		network = "ip4"
	} else if !option.IPv4Enable && option.IPv6Enable {
		network = "ip6"
	}
	resolver := net.Resolver{PreferGo: true, Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
		return searchDNSConnection(ctx, r.owner)
	}}
	addresses, resolveErr := resolver.LookupIP(r.ctx, network, host)
	if err := r.ctx.Err(); err != nil {
		return nil, 0, err
	}
	if !searchCurrent(r.owner) {
		return nil, 0, errors.New("search_network_unavailable")
	}
	if resolveErr != nil {
		return nil, 0, errors.New("search_dns_lookup_failed")
	}
	for _, value := range addresses {
		if err := r.ctx.Err(); err != nil {
			return nil, 0, err
		}
		address, ok := netip.AddrFromSlice(value)
		if !ok {
			r.owner.revokeSearch()
			return nil, 0, errors.New("search_network_unavailable")
		}
		address = address.Unmap()
		if address.Is4() && option.IPv4Enable || address.Is6() && option.IPv6Enable {
			result = append(result, xnet.IP(address.AsSlice()))
		}
	}
	if !searchCurrent(r.owner) {
		return nil, 0, errors.New("search_network_unavailable")
	}
	if len(result) == 0 {
		return nil, 0, dns.ErrEmptyResponse
	}
	return result, dns.DefaultTTL, nil
}
