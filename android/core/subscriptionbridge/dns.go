package subscriptionbridge

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"net/netip"
	"strings"

	"golang.org/x/net/dns/dnsmessage"
	"golang.org/x/net/idna"
)

const (
	maxDNSBytes  = 1<<16 - 1
	maxCNAMEHops = 16
)

func (n *requestNetwork) lookupNetIP(ctx context.Context, network, host string) ([]netip.Addr, error) {
	name, err := dnsName(host)
	if err != nil || !n.current(ctx) {
		return nil, errInput
	}
	var kinds []dnsmessage.Type
	switch network {
	case "ip":
		kinds = []dnsmessage.Type{dnsmessage.TypeA, dnsmessage.TypeAAAA}
	case "ip4":
		kinds = []dnsmessage.Type{dnsmessage.TypeA}
	case "ip6":
		kinds = []dnsmessage.Type{dnsmessage.TypeAAAA}
	default:
		return nil, errInput
	}
	var addresses []netip.Addr
	for _, kind := range kinds {
		family, err := n.lookupFamily(ctx, name, kind)
		if err != nil || !n.current(ctx) {
			return nil, errInput
		}
		addresses = append(addresses, family...)
	}
	return addresses, nil
}

func dnsName(host string) (dnsmessage.Name, error) {
	for _, character := range host {
		if character > 127 {
			ascii, err := idna.Lookup.ToASCII(host)
			if err != nil {
				return dnsmessage.Name{}, errInput
			}
			host = ascii
			break
		}
	}
	if !strings.HasSuffix(host, ".") {
		host += "."
	}
	if len(host) > 254 || len(host) < 2 {
		return dnsmessage.Name{}, errInput
	}
	nonNumeric := false
	for _, label := range strings.Split(host[:len(host)-1], ".") {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return dnsmessage.Name{}, errInput
		}
		for i := 0; i < len(label); i++ {
			c := label[i]
			switch {
			case c >= 'a' && c <= 'z', c >= 'A' && c <= 'Z', c == '_', c == '-':
				nonNumeric = true
			case c >= '0' && c <= '9':
			default:
				return dnsmessage.Name{}, errInput
			}
		}
	}
	if !nonNumeric {
		return dnsmessage.Name{}, errInput
	}
	name, err := dnsmessage.NewName(host)
	if err != nil {
		return dnsmessage.Name{}, errInput
	}
	return name, nil
}

func (n *requestNetwork) lookupFamily(ctx context.Context, name dnsmessage.Name, kind dnsmessage.Type) ([]netip.Addr, error) {
	current := name
	seen := map[string]struct{}{strings.ToLower(current.String()): {}}
	for hops := 0; ; {
		question := dnsmessage.Question{Name: current, Type: kind, Class: dnsmessage.ClassINET}
		answer, err := n.queryDNS(ctx, question)
		if err != nil {
			return nil, errInput
		}
		queried := current
		for {
			var addresses []netip.Addr
			var target dnsmessage.Name
			hasTarget := false
			for _, record := range answer.Answers {
				if !strings.EqualFold(record.Header.Name.String(), current.String()) {
					continue
				}
				if record.Header.Type != kind && record.Header.Type != dnsmessage.TypeCNAME {
					continue
				}
				if record.Header.Class != dnsmessage.ClassINET {
					return nil, errInput
				}
				switch record.Header.Type {
				case dnsmessage.TypeCNAME:
					alias, ok := record.Body.(*dnsmessage.CNAMEResource)
					if !ok || (hasTarget && !strings.EqualFold(target.String(), alias.CNAME.String())) {
						return nil, errInput
					}
					target, hasTarget = alias.CNAME, true
				case dnsmessage.TypeA:
					address, ok := record.Body.(*dnsmessage.AResource)
					if !ok {
						return nil, errInput
					}
					addresses = append(addresses, netip.AddrFrom4(address.A))
				case dnsmessage.TypeAAAA:
					address, ok := record.Body.(*dnsmessage.AAAAResource)
					if !ok {
						return nil, errInput
					}
					addresses = append(addresses, netip.AddrFrom16(address.AAAA))
				}
			}
			if hasTarget && len(addresses) != 0 {
				return nil, errInput
			}
			if !hasTarget {
				if len(addresses) > 0 {
					return addresses, nil
				}
				if strings.EqualFold(queried.String(), current.String()) {
					if !validNoData(answer) {
						return nil, errInput
					}
					return nil, nil
				}
				break
			}
			if hops >= maxCNAMEHops {
				return nil, errInput
			}
			next, err := dnsName(target.String())
			if err != nil {
				return nil, errInput
			}
			key := strings.ToLower(next.String())
			if _, exists := seen[key]; exists {
				return nil, errInput
			}
			seen[key] = struct{}{}
			current = next
			hops++
		}
	}
}

func validNoData(answer dnsmessage.Message) bool {
	hasNameserver, hasSOA := false, false
	for _, record := range answer.Authorities {
		if record.Header.Class != dnsmessage.ClassINET {
			continue
		}
		switch record.Header.Type {
		case dnsmessage.TypeNS:
			hasNameserver = true
		case dnsmessage.TypeSOA:
			hasSOA = true
		}
	}
	return (!hasNameserver || hasSOA) && (answer.RecursionAvailable || answer.Authoritative || hasSOA)
}

func (n *requestNetwork) queryDNS(ctx context.Context, question dnsmessage.Question) (answer dnsmessage.Message, err error) {
	defer func() {
		if recover() != nil {
			answer, err = dnsmessage.Message{}, errInput
		}
	}()
	var id [2]byte
	if _, err := rand.Read(id[:]); err != nil {
		return dnsmessage.Message{}, errInput
	}
	query := dnsmessage.Message{
		Header:    dnsmessage.Header{ID: binary.BigEndian.Uint16(id[:]), RecursionDesired: true},
		Questions: []dnsmessage.Question{question},
	}
	packet, err := query.Pack()
	if err != nil || !n.current(ctx) {
		return dnsmessage.Message{}, errInput
	}
	remaining := remainingMillis(ctx)
	if remaining == 0 {
		return dnsmessage.Message{}, errInput
	}
	response, err := n.route.QueryDNS(packet, remaining)
	if err != nil || !n.current(ctx) || len(response) == 0 || len(response) > maxDNSBytes || answer.Unpack(response) != nil {
		return dnsmessage.Message{}, errInput
	}
	if !answer.Response || answer.OpCode != 0 || answer.ID != query.ID || answer.Truncated ||
		answer.RCode != dnsmessage.RCodeSuccess || len(answer.Questions) != 1 ||
		answer.Questions[0].Type != question.Type || answer.Questions[0].Class != question.Class ||
		!strings.EqualFold(answer.Questions[0].Name.String(), question.Name.String()) {
		return dnsmessage.Message{}, errInput
	}
	for _, record := range answer.Additionals {
		if record.Header.Type == dnsmessage.TypeOPT && record.Header.TTL>>24 != 0 {
			return dnsmessage.Message{}, errInput
		}
	}
	return answer, nil
}
