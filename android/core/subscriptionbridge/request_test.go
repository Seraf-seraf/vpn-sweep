package subscriptionbridge

import (
	"context"
	"errors"
	"net/netip"
	"sync"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

type syntheticRoute struct {
	mu        sync.Mutex
	current   bool
	allow     bool
	badAnswer bool
	changeDNS bool
	queries   int
}

func (r *syntheticRoute) Current() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.current
}

func (r *syntheticRoute) QueryDNS(packet []byte, remainingMillis int64) ([]byte, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if remainingMillis <= 0 {
		return nil, errors.New("invalid_test_budget")
	}
	r.queries++
	if r.changeDNS {
		r.current = false
	}
	if r.badAnswer {
		return []byte{0}, nil
	}
	var question dnsmessage.Message
	if question.Unpack(packet) != nil || len(question.Questions) != 1 {
		return nil, errors.New("invalid_test_request")
	}
	q := question.Questions[0]
	reply := dnsmessage.Message{
		Header:    dnsmessage.Header{ID: question.Header.ID, Response: true, RecursionAvailable: true},
		Questions: []dnsmessage.Question{q},
	}
	if q.Type == dnsmessage.TypeA {
		reply.Answers = []dnsmessage.Resource{{
			Header: dnsmessage.ResourceHeader{Name: q.Name, Type: q.Type, Class: dnsmessage.ClassINET},
			Body:   &dnsmessage.AResource{A: [4]byte{8, 8, 8, 8}},
		}}
	}
	return reply.Pack()
}

func (r *syntheticRoute) VerifyPublicDestination(_ string, remainingMillis int64) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.allow && remainingMillis > 0
}

func (r *syntheticRoute) AuthorizeSocket(_ int64, _, _ string, _ int64) bool { return false }

func TestRequestDNSStaysOnSelectedRoute(t *testing.T) {
	for _, tc := range []struct {
		name      string
		badAnswer bool
		changeDNS bool
		wantError bool
	}{
		{name: "selected"},
		{name: "malformed_answer", badAnswer: true, wantError: true},
		{name: "changed_during_query", changeDNS: true, wantError: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			route := &syntheticRoute{current: true, allow: true, badAnswer: tc.badAnswer, changeDNS: tc.changeDNS}
			network := &requestNetwork{route: route}
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			addresses, err := network.LookupNetIP(ctx, "ip", "synthetic.invalid")
			if (err != nil) != tc.wantError {
				t.Fatalf("lookup error: %v", err)
			}
			if !tc.wantError && (len(addresses) != 1 || addresses[0] != netip.MustParseAddr("8.8.8.8")) {
				t.Fatal("synthetic DNS result changed")
			}
		})
	}
}

func TestRequestRejectsDeniedOrExpiredRouteWithoutDial(t *testing.T) {
	route := &syntheticRoute{current: true}
	network := &requestNetwork{route: route}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := network.VerifyPublicDestination(ctx, netip.MustParseAddr("8.8.8.8")); err == nil {
		t.Fatal("destination denial ignored")
	}
	route.mu.Lock()
	route.current = false
	route.mu.Unlock()
	if _, err := network.LookupNetIP(ctx, "ip", "synthetic.invalid"); err == nil {
		t.Fatal("stale route accepted")
	}
	if route.queries != 0 {
		t.Fatal("DNS used after route changed")
	}
}

func TestRequestRejectsInvalidInputsAndCancellation(t *testing.T) {
	const syntheticURL = "https" + "://" + "synthetic.invalid"
	if _, err := NewRequest(nil, 1); err != errInput {
		t.Fatal("nil route accepted")
	}
	if _, err := NewRequest(&syntheticRoute{}, 0); err != errInput {
		t.Fatal("zero timeout accepted")
	}
	req, err := NewRequest(&syntheticRoute{current: true}, 1000)
	if err != nil {
		t.Fatal(err)
	}
	req.Cancel()
	if _, err := req.Fetch(syntheticURL); err == nil || err.Error() != "cancelled" {
		t.Fatalf("cancel result: %v", err)
	}
	if _, err := req.Fetch(syntheticURL); err != errInput {
		t.Fatal("request reused")
	}
}
