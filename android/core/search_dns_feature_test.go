package mobile

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/android/core/searchdns"
	"github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/dns"
	"golang.org/x/net/dns/dnsmessage"
)

func TestAndroidSearchDNSFeatureSelectedBeforeXrayFallback(t *testing.T) {
	owner := &fakeRawDNSOwner{}
	guard := installRawDNSOwner(t, owner)
	ctx := context.WithValue(context.Background(), searchDNSContextKey{}, guard)
	config := &core.Config{App: []*serial.TypedMessage{serial.ToTypedMessage(&searchdns.Config{})}}
	instance, err := core.NewWithContext(ctx, config)
	if err != nil {
		t.Fatal("could not construct Xray feature", err)
	}
	t.Cleanup(func() { _ = instance.Close() })
	client, ok := instance.GetFeature(dns.ClientType()).(*searchDNSClient)
	if !ok {
		t.Fatal("Xray selected its fallback DNS client")
	}
	answers, ttl, err := client.LookupIP("synthetic.invalid.", dns.IPOption{IPv4Enable: true, IPv6Enable: true})
	if err != nil || ttl != dns.DefaultTTL || len(answers) != 2 {
		t.Fatalf("unexpected DNS result, ttl=%d error=%v", ttl, err)
	}
	seen := make(map[string]bool)
	for _, answer := range answers {
		seen[answer.String()] = true
	}
	if !seen["192.0.2.5"] || !seen["2001:db8::1"] {
		t.Fatal("A/AAAA answers were not preserved")
	}
	answers, _, err = client.LookupIP("synthetic.invalid.", dns.IPOption{IPv6Enable: true})
	if err != nil || len(answers) != 1 || answers[0].String() != "2001:db8::1" {
		t.Fatal("IPv6-only DNS filtering failed")
	}
	owner.mu.Lock()
	defer owner.mu.Unlock()
	if len(owner.questions) < 3 {
		t.Fatal("DNS did not pass through the Android owner")
	}
}

type blockedSearchResolver struct {
	started chan struct{}
	release chan struct{}
	exited  chan struct{}
}

func (*blockedSearchResolver) Current() bool                             { return true }
func (*blockedSearchResolver) AllowSocket(int, int, string, string) bool { return false }
func (r *blockedSearchResolver) QueryDNS(data []byte, _ int64) ([]byte, error) {
	close(r.started)
	<-r.release
	defer close(r.exited)
	var query dnsmessage.Message
	if query.Unpack(data) != nil {
		return nil, errors.New("invalid_query")
	}
	answer := dnsmessage.Message{Header: dnsmessage.Header{ID: query.Header.ID, Response: true, RecursionAvailable: true}, Questions: query.Questions}
	return answer.Pack()
}

func TestAndroidSearchDNSCallbackRequiresLifecycleCleanupOnCancellation(t *testing.T) {
	resolver := &blockedSearchResolver{started: make(chan struct{}), release: make(chan struct{}), exited: make(chan struct{})}
	ownerContext, ownerCancel := context.WithCancel(context.Background())
	defer ownerCancel()
	roundContext, roundCancel := context.WithTimeout(ownerContext, time.Second)
	defer roundCancel()
	guard := &protection{search: resolver, searchContext: ownerContext, searchCancel: ownerCancel, searchRound: roundContext}
	previous := activeProtection.Swap(guard)
	defer activeProtection.Store(previous)
	ctx, cancel := context.WithCancel(context.Background())
	client := &searchDNSClient{ctx: ctx, owner: guard}
	done := make(chan error, 1)
	go func() {
		_, _, err := client.LookupIP("synthetic.invalid.", dns.IPOption{IPv4Enable: true})
		done <- err
	}()
	<-resolver.started
	cancel()
	close(resolver.release)
	if err := <-done; !errors.Is(err, context.Canceled) {
		t.Fatal("callback completion ignored cancellation")
	}
	<-resolver.exited
}

func TestAndroidSearchDNSFeatureRequiresOwnerAndRevokesOnMalformedReply(t *testing.T) {
	config := &core.Config{App: []*serial.TypedMessage{serial.ToTypedMessage(&searchdns.Config{})}}
	if instance, err := core.NewWithContext(context.Background(), config); err == nil {
		_ = instance.Close()
		t.Fatal("feature accepted missing owner")
	}
	owner := &fakeRawDNSOwner{badReply: true}
	guard := installRawDNSOwner(t, owner)
	client := &searchDNSClient{ctx: context.Background(), owner: guard}
	if _, _, err := client.LookupIP("synthetic.invalid.", dns.IPOption{IPv4Enable: true}); err == nil {
		t.Fatal("invalid resolver response accepted")
	}
	if guard.current() || guard.searchContext.Err() == nil {
		t.Fatal("invalid resolver response did not revoke policy")
	}
}

func TestAndroidSearchDNSFeatureHonorsWorkerCancellation(t *testing.T) {
	owner := &fakeRawDNSOwner{}
	guard := installRawDNSOwner(t, owner)
	ctx, cancel := context.WithCancel(context.Background())
	client := &searchDNSClient{ctx: ctx, owner: guard}
	cancel()
	if _, _, err := client.LookupIP("synthetic.invalid.", dns.IPOption{IPv4Enable: true}); !errors.Is(err, context.Canceled) {
		t.Fatal("cancelled worker resolved a name")
	}
	owner.mu.Lock()
	defer owner.mu.Unlock()
	if len(owner.questions) != 0 || !guard.current() {
		t.Fatal("cancellation called resolver or revoked valid owner")
	}
}
