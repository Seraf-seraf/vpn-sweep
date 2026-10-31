package subscriptionbridge

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/subscription"
	"golang.org/x/net/dns/dnsmessage"
)

type syntheticDNS struct {
	answer         func(dnsmessage.Question, *dnsmessage.Message) ([]byte, error)
	verified       []string
	authorizations int
	onVerify       func()
	onAuthorize    func()
}

func (*syntheticDNS) Current() bool { return true }

func (d *syntheticDNS) QueryDNS(packet []byte, remainingMillis int64) ([]byte, error) {
	if remainingMillis <= 0 {
		return nil, errors.New("invalid_test_budget")
	}
	var query dnsmessage.Message
	if query.Unpack(packet) != nil || len(query.Questions) != 1 {
		return nil, errors.New("invalid_test_query")
	}
	reply := &dnsmessage.Message{
		Header:    dnsmessage.Header{ID: query.ID, Response: true, RecursionAvailable: true},
		Questions: query.Questions,
	}
	return d.answer(query.Questions[0], reply)
}

func (d *syntheticDNS) VerifyPublicDestination(address string, remainingMillis int64) bool {
	d.verified = append(d.verified, address)
	if d.onVerify != nil {
		d.onVerify()
	}
	return remainingMillis > 0
}

func (d *syntheticDNS) AuthorizeSocket(int64, string, string, int64) bool {
	d.authorizations++
	if d.onAuthorize != nil {
		d.onAuthorize()
		return true
	}
	return false
}

type syntheticDialNetwork struct {
	*requestNetwork
	dials int
}

func (n *syntheticDialNetwork) DialContext(context.Context, string, string) (net.Conn, error) {
	n.dials++
	return nil, errors.New("synthetic_dial")
}

func aRecord(name dnsmessage.Name, address [4]byte) dnsmessage.Resource {
	return dnsmessage.Resource{Header: dnsmessage.ResourceHeader{Name: name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}, Body: &dnsmessage.AResource{A: address}}
}

func aaaaRecord(name dnsmessage.Name, address [16]byte) dnsmessage.Resource {
	return dnsmessage.Resource{Header: dnsmessage.ResourceHeader{Name: name, Type: dnsmessage.TypeAAAA, Class: dnsmessage.ClassINET}, Body: &dnsmessage.AAAAResource{AAAA: address}}
}

func cnameRecord(name, target dnsmessage.Name) dnsmessage.Resource {
	return dnsmessage.Resource{Header: dnsmessage.ResourceHeader{Name: name, Type: dnsmessage.TypeCNAME, Class: dnsmessage.ClassINET}, Body: &dnsmessage.CNAMEResource{CNAME: target}}
}

func TestPublicFetcherChecksEveryDNSAddressBeforeAnyDial(t *testing.T) {
	const syntheticURL = "https" + "://" + "synthetic.invalid"
	for _, scenario := range []string{"public", "mixed_v4", "mixed_families"} {
		route := &syntheticDNS{answer: func(question dnsmessage.Question, reply *dnsmessage.Message) ([]byte, error) {
			if question.Type == dnsmessage.TypeA {
				reply.Answers = append(reply.Answers, aRecord(question.Name, [4]byte{8, 8, 8, 8}))
				if scenario == "mixed_v4" {
					reply.Answers = append(reply.Answers, aRecord(question.Name, [4]byte{10, 0, 0, 1}))
				} else if scenario == "public" {
					reply.Answers = append(reply.Answers, aRecord(question.Name, [4]byte{1, 1, 1, 1}))
				}
			} else if scenario == "mixed_families" {
				reply.Answers = []dnsmessage.Resource{aaaaRecord(question.Name, [16]byte{0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1})}
			}
			return reply.Pack()
		}}
		network := &syntheticDialNetwork{requestNetwork: &requestNetwork{route: route}}
		fetcher := subscription.PublicFetcher{Network: network, Limits: subscription.FetchLimits{
			MaxTransportBytes: importer.MaxInputBytes, MaxDecodedBytes: importer.MaxInputBytes, Timeout: time.Second,
		}}
		_, err := fetcher.Fetch(context.Background(), syntheticURL)
		if scenario != "public" {
			if model.Code(err) != "destination_rejected" || network.dials != 0 || len(route.verified) != 1 {
				t.Fatalf("mixed DNS reached dial: code=%s dials=%d verified=%d", model.Code(err), network.dials, len(route.verified))
			}
		} else if model.Code(err) != "network_failed" || network.dials != 1 || len(route.verified) != 2 {
			t.Fatalf("public DNS was not fully checked: code=%s dials=%d verified=%d", model.Code(err), network.dials, len(route.verified))
		}
		if route.authorizations != 0 {
			t.Fatal("DNS attempted socket authorization")
		}
	}
}

func TestDNSRejectsTruncatedOrMalformedFamilyInsteadOfPartialResult(t *testing.T) {
	for _, invalid := range []string{"truncated", "malformed", "oversized", "wrong_question", "wrong_id", "server_failure", "incomplete"} {
		t.Run(invalid, func(t *testing.T) {
			route := &syntheticDNS{answer: func(question dnsmessage.Question, reply *dnsmessage.Message) ([]byte, error) {
				if question.Type == dnsmessage.TypeA {
					reply.Answers = []dnsmessage.Resource{aRecord(question.Name, [4]byte{8, 8, 8, 8})}
					return reply.Pack()
				}
				switch invalid {
				case "truncated":
					reply.Truncated = true
				case "malformed":
					return []byte{0}, nil
				case "oversized":
					return make([]byte, maxDNSBytes+1), nil
				case "wrong_question":
					reply.Questions[0].Type = dnsmessage.TypeA
				case "wrong_id":
					reply.ID++
				case "server_failure":
					reply.RCode = dnsmessage.RCodeServerFailure
				case "incomplete":
					reply.RecursionAvailable = false
				}
				return reply.Pack()
			}}
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			addresses, err := (&requestNetwork{route: route}).LookupNetIP(ctx, "ip", "synthetic.invalid")
			if err == nil || len(addresses) != 0 || route.authorizations != 0 {
				t.Fatal("incomplete DNS result accepted")
			}
		})
	}
}

func TestDNSFollowsCNAMEAndAcceptsZeroTTLWithoutCaching(t *testing.T) {
	target := dnsmessage.MustNewName("alias.invalid.")
	var calls int
	route := &syntheticDNS{answer: func(question dnsmessage.Question, reply *dnsmessage.Message) ([]byte, error) {
		calls++
		if question.Type == dnsmessage.TypeA {
			if question.Name == target {
				reply.Answers = []dnsmessage.Resource{aRecord(target, [4]byte{8, 8, 8, 8})}
			} else {
				reply.Answers = []dnsmessage.Resource{cnameRecord(question.Name, target)}
			}
		} else {
			reply.Answers = []dnsmessage.Resource{cnameRecord(question.Name, target), aaaaRecord(target, [16]byte{0x26, 0x06, 0x47, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1})}
		}
		return reply.Pack()
	}}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	addresses, err := (&requestNetwork{route: route}).LookupNetIP(ctx, "ip", "synthetic.invalid")
	if err != nil || len(addresses) != 2 || addresses[0] != netip.MustParseAddr("8.8.8.8") || !addresses[1].Is6() || calls != 3 {
		t.Fatalf("CNAME resolution: count=%d calls=%d error=%v", len(addresses), calls, err)
	}
	if _, err := (&requestNetwork{route: route}).LookupNetIP(ctx, "ip", "synthetic.invalid"); err != nil || calls != 6 {
		t.Fatal("zero-TTL response was cached")
	}
}

func TestDNSRejectsCNAMELoop(t *testing.T) {
	route := &syntheticDNS{answer: func(question dnsmessage.Question, reply *dnsmessage.Message) ([]byte, error) {
		reply.Answers = []dnsmessage.Resource{cnameRecord(question.Name, question.Name)}
		return reply.Pack()
	}}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if addresses, err := (&requestNetwork{route: route}).LookupNetIP(ctx, "ip", "synthetic.invalid"); err == nil || len(addresses) != 0 {
		t.Fatal("CNAME loop accepted")
	}
}

func TestDNSBoundsCNAMEChain(t *testing.T) {
	for _, hops := range []int{maxCNAMEHops - 1, maxCNAMEHops, maxCNAMEHops + 1} {
		route := &syntheticDNS{answer: func(question dnsmessage.Question, reply *dnsmessage.Message) ([]byte, error) {
			if question.Type == dnsmessage.TypeA {
				label, _, _ := strings.Cut(question.Name.String(), ".")
				index, err := strconv.Atoi(strings.TrimPrefix(label, "node"))
				if err != nil {
					return nil, err
				}
				if index < hops {
					target := dnsmessage.MustNewName("node" + strconv.Itoa(index+1) + ".invalid.")
					reply.Answers = []dnsmessage.Resource{cnameRecord(question.Name, target)}
				} else {
					reply.Answers = []dnsmessage.Resource{aRecord(question.Name, [4]byte{8, 8, 8, 8})}
				}
			}
			return reply.Pack()
		}}
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		addresses, err := (&requestNetwork{route: route}).LookupNetIP(ctx, "ip", "node0.invalid")
		cancel()
		if hops <= maxCNAMEHops {
			if err != nil || len(addresses) != 1 {
				t.Fatalf("valid CNAME length %d rejected", hops)
			}
		} else if err == nil || len(addresses) != 0 {
			t.Fatal("oversized CNAME chain accepted")
		}
	}
}

func TestDNSResponseSizeBoundary(t *testing.T) {
	for _, size := range []int{maxDNSBytes - 1, maxDNSBytes, maxDNSBytes + 1} {
		route := &syntheticDNS{answer: func(_ dnsmessage.Question, reply *dnsmessage.Message) ([]byte, error) {
			unknown := &dnsmessage.UnknownResource{Type: dnsmessage.Type(65000)}
			reply.Additionals = []dnsmessage.Resource{{
				Header: dnsmessage.ResourceHeader{Name: dnsmessage.MustNewName("."), Class: dnsmessage.ClassINET},
				Body:   unknown,
			}}
			base, err := reply.Pack()
			if err != nil {
				return nil, err
			}
			payloadSize := size - len(base)
			if size > maxDNSBytes {
				payloadSize--
			}
			unknown.Data = make([]byte, payloadSize)
			response, err := reply.Pack()
			if size > maxDNSBytes {
				response = append(response, 0)
			}
			return response, err
		}}
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		addresses, err := (&requestNetwork{route: route}).LookupNetIP(ctx, "ip4", "synthetic.invalid")
		cancel()
		if len(addresses) != 0 || (err != nil) != (size > maxDNSBytes) {
			t.Fatalf("DNS size boundary %d: %v", size, err)
		}
	}
}

func TestDNSNameLabelBoundary(t *testing.T) {
	for _, length := range []int{62, 63, 64} {
		_, err := dnsName(strings.Repeat("a", length) + ".invalid")
		if (err != nil) != (length > 63) {
			t.Fatalf("label length %d: %v", length, err)
		}
	}
	for _, name := range []string{"", ".", "bad..invalid", "-bad.invalid", "bad-.invalid"} {
		if _, err := dnsName(name); err == nil {
			t.Fatal("invalid DNS name accepted")
		}
	}
}

type fixedDeadlineContext struct {
	context.Context
	deadline time.Time
}

func (c fixedDeadlineContext) Deadline() (time.Time, bool) { return c.deadline, true }

func TestRemainingMillisRoundsUpWithoutLosingSubmillisecondBudget(t *testing.T) {
	now := time.Date(2030, 1, 1, 0, 0, 0, 0, time.UTC)
	for _, tc := range []struct {
		remaining time.Duration
		want      int64
	}{{-1, 0}, {0, 0}, {1, 1}, {time.Millisecond - 1, 1}, {time.Millisecond, 1}, {time.Millisecond + 1, 2}} {
		ctx := fixedDeadlineContext{Context: context.Background(), deadline: now.Add(tc.remaining)}
		if got := remainingMillisAt(ctx, now); got != tc.want {
			t.Fatalf("remaining=%s got=%d want=%d", tc.remaining, got, tc.want)
		}
	}
}

func TestRouteCallbacksCannotAuthorizeAfterContextCancellation(t *testing.T) {
	t.Run("verify", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		route := &syntheticDNS{onVerify: cancel}
		if err := (&requestNetwork{route: route}).VerifyPublicDestination(ctx, netip.MustParseAddr("8.8.8.8")); err == nil {
			t.Fatal("destination accepted after cancellation")
		}
	})
	t.Run("authorize", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		route := &syntheticDNS{onAuthorize: cancel}
		if (&requestNetwork{route: route}).authorize(ctx, 123, "tcp4", "synthetic") {
			t.Fatal("socket accepted after cancellation")
		}
	})
	t.Run("dns", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		route := &syntheticDNS{answer: func(question dnsmessage.Question, reply *dnsmessage.Message) ([]byte, error) {
			cancel()
			return reply.Pack()
		}}
		if _, err := (&requestNetwork{route: route}).LookupNetIP(ctx, "ip", "synthetic.invalid"); err == nil {
			t.Fatal("DNS answer accepted after cancellation")
		}
	})
}
