package mobile

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

type changingDNSOwner struct {
	current    atomic.Bool
	calls      atomic.Int32
	loseOnCall bool
}

type fakeRemainingContext struct {
	context.Context
	millis atomic.Int64
}

func (c *fakeRemainingContext) Deadline() (time.Time, bool) {
	return time.Now().Add(time.Duration(c.millis.Load()) * time.Millisecond), true
}

type blockedBudgetDNSOwner struct {
	block   atomic.Bool
	entered chan struct{}
	release chan struct{}
	budget  chan int64
}

func (o *blockedBudgetDNSOwner) Current() bool {
	if o.block.Swap(false) {
		close(o.entered)
		<-o.release
	}
	return true
}
func (*blockedBudgetDNSOwner) AllowSocket(int, int, string, string) bool { return false }
func (o *blockedBudgetDNSOwner) QueryDNS(_ []byte, remaining int64) ([]byte, error) {
	o.budget <- remaining
	return nil, nil
}

func TestAndroidSearchDNSRecomputesRemainingAfterBlockedEpochCheck(t *testing.T) {
	owner := &blockedBudgetDNSOwner{entered: make(chan struct{}), release: make(chan struct{}), budget: make(chan int64, 1)}
	ownerCtx, cancel := context.WithCancel(context.Background())
	defer cancel()
	round, stop := context.WithTimeout(ownerCtx, time.Minute)
	defer stop()
	guard := &protection{search: owner, searchContext: ownerCtx, searchCancel: cancel, searchRound: round}
	previous := activeProtection.Swap(guard)
	defer activeProtection.Store(previous)
	raw, err := searchDNSConnection(round, guard)
	if err != nil {
		t.Fatal(err)
	}
	defer raw.Close()
	conn := raw.(*searchDNSConn)
	clock := &fakeRemainingContext{Context: conn.ctx}
	clock.millis.Store(60000)
	conn.ctx = clock
	owner.block.Store(true)
	done := make(chan error, 1)
	go func() { _, err := conn.query([]byte{1}); done <- err }()
	<-owner.entered
	clock.millis.Store(10000)
	close(owner.release)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if remaining := <-owner.budget; remaining < 9000 || remaining > 10000 {
		t.Fatalf("callback received stale budget: %d", remaining)
	}
}

func (o *changingDNSOwner) Current() bool                           { return o.current.Load() }
func (*changingDNSOwner) AllowSocket(int, int, string, string) bool { return false }
func (o *changingDNSOwner) QueryDNS([]byte, int64) ([]byte, error) {
	o.calls.Add(1)
	if o.loseOnCall {
		o.current.Store(false)
	}
	return nil, nil
}

func TestAndroidSearchDNSRechecksEpochAroundCallback(t *testing.T) {
	for _, loseOnCall := range []bool{false, true} {
		t.Run(fmt.Sprint(loseOnCall), func(t *testing.T) {
			owner := &changingDNSOwner{loseOnCall: loseOnCall}
			owner.current.Store(true)
			ownerCtx, cancel := context.WithCancel(context.Background())
			defer cancel()
			round, stop := context.WithTimeout(ownerCtx, time.Second)
			defer stop()
			guard := &protection{search: owner, searchContext: ownerCtx, searchCancel: cancel, searchRound: round}
			previous := activeProtection.Swap(guard)
			defer activeProtection.Store(previous)
			conn, err := searchDNSConnection(round, guard)
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			if !loseOnCall {
				owner.current.Store(false)
			}
			if _, err := conn.(*searchDNSConn).query([]byte{1}); err == nil {
				t.Fatal("stale epoch accepted")
			}
			want := int32(0)
			if loseOnCall {
				want = 1
			}
			if owner.calls.Load() != want || guard.current() {
				t.Fatal("callback used a stale epoch or failed to revoke it")
			}
		})
	}
}

type fakeRawDNSOwner struct {
	mu        sync.Mutex
	questions []dnsmessage.Type
	remaining []int64
	badReply  bool
	wireBytes int
	rcode     dnsmessage.RCode
}

func (*fakeRawDNSOwner) Current() bool                             { return true }
func (*fakeRawDNSOwner) AllowSocket(int, int, string, string) bool { return false }
func (o *fakeRawDNSOwner) QueryDNS(data []byte, remainingMillis int64) ([]byte, error) {
	if remainingMillis <= 0 {
		return nil, errors.New("deadline_missing")
	}
	var query dnsmessage.Message
	if query.Unpack(data) != nil || len(query.Questions) != 1 {
		return nil, errors.New("invalid_test_query")
	}
	o.mu.Lock()
	o.questions = append(o.questions, query.Questions[0].Type)
	o.remaining = append(o.remaining, remainingMillis)
	badReply := o.badReply
	wireBytes := o.wireBytes
	rcode := o.rcode
	o.mu.Unlock()
	if badReply {
		return []byte("invalid"), nil
	}
	question := query.Questions[0]
	answer := dnsmessage.Message{
		Header:    dnsmessage.Header{ID: query.Header.ID, Response: true, RecursionAvailable: true, RCode: rcode},
		Questions: []dnsmessage.Question{question},
	}
	if rcode != dnsmessage.RCodeSuccess {
		return answer.Pack()
	}
	if wireBytes > 0 {
		unknown := &dnsmessage.UnknownResource{Type: dnsmessage.Type(65000)}
		answer.Answers = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: question.Name, Type: unknown.Type, Class: dnsmessage.ClassINET}, Body: unknown}}
		empty, err := answer.Pack()
		if err != nil || wireBytes < len(empty) {
			return nil, errors.New("invalid_test_size")
		}
		unknown.Data = make([]byte, wireBytes-len(empty))
		return answer.Pack()
	}
	resource := dnsmessage.Resource{Header: dnsmessage.ResourceHeader{Name: question.Name, Type: question.Type, Class: dnsmessage.ClassINET, TTL: 60}}
	switch question.Type {
	case dnsmessage.TypeA:
		resource.Body = &dnsmessage.AResource{A: [4]byte{192, 0, 2, 5}}
	case dnsmessage.TypeAAAA:
		resource.Body = &dnsmessage.AAAAResource{AAAA: [16]byte{0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}}
	case dnsmessage.TypeTXT:
		resource.Body = &dnsmessage.TXTResource{TXT: []string{"synthetic"}}
	case dnsmessage.TypeSRV:
		resource.Body = &dnsmessage.SRVResource{Priority: 1, Weight: 2, Port: 443, Target: dnsmessage.MustNewName("target.invalid.")}
	default:
		return answer.Pack()
	}
	answer.Answers = []dnsmessage.Resource{resource}
	return answer.Pack()
}

func TestAndroidSearchDNSUsesShorterConnectionContext(t *testing.T) {
	owner := &fakeRawDNSOwner{}
	guard := installRawDNSOwner(t, owner)
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	conn, err := searchDNSConnection(ctx, guard)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	query, err := (&dnsmessage.Message{Header: dnsmessage.Header{ID: 17}, Questions: []dnsmessage.Question{{Name: dnsmessage.MustNewName("synthetic.invalid."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}}}).Pack()
	if err != nil {
		t.Fatal(err)
	}
	frame := make([]byte, 2, len(query)+2)
	binary.BigEndian.PutUint16(frame, uint16(len(query)))
	frame = append(frame, query...)
	if _, err := conn.Write(frame); err != nil {
		t.Fatal(err)
	}
	owner.mu.Lock()
	defer owner.mu.Unlock()
	if len(owner.remaining) != 1 || owner.remaining[0] <= 0 || owner.remaining[0] > 300 {
		t.Fatalf("connection deadline not propagated: %v", owner.remaining)
	}
}

type blockedRawDNSOwner struct {
	entered chan struct{}
	release chan struct{}
}

func (*blockedRawDNSOwner) Current() bool                             { return true }
func (*blockedRawDNSOwner) AllowSocket(int, int, string, string) bool { return false }
func (o *blockedRawDNSOwner) QueryDNS([]byte, int64) ([]byte, error) {
	close(o.entered)
	<-o.release
	return nil, errors.New("callback_released")
}

func TestAndroidSearchDNSRevokeDoesNotWaitForBlockedCallback(t *testing.T) {
	owner := &blockedRawDNSOwner{entered: make(chan struct{}), release: make(chan struct{})}
	policyCtx, policyCancel := context.WithCancel(context.Background())
	roundCtx, roundCancel := context.WithTimeout(policyCtx, 5*time.Second)
	guard := &protection{search: owner, searchContext: policyCtx, searchCancel: policyCancel, searchRound: roundCtx}
	previous := activeProtection.Swap(guard)
	defer func() { roundCancel(); policyCancel(); activeProtection.Store(previous) }()
	conn, err := searchDNSConnection(roundCtx, guard)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	query, err := (&dnsmessage.Message{Header: dnsmessage.Header{ID: 18}, Questions: []dnsmessage.Question{{Name: dnsmessage.MustNewName("synthetic.invalid."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}}}).Pack()
	if err != nil {
		t.Fatal(err)
	}
	frame := make([]byte, 2, len(query)+2)
	binary.BigEndian.PutUint16(frame, uint16(len(query)))
	frame = append(frame, query...)
	done := make(chan error, 1)
	go func() { _, err := conn.Write(frame); done <- err }()
	select {
	case <-owner.entered:
	case <-time.After(time.Second):
		close(owner.release)
		t.Fatal("callback did not begin")
	}
	revoked := make(chan struct{})
	go func() { guard.revokeSearch(); close(revoked) }()
	select {
	case <-revoked:
	case <-time.After(time.Second):
		close(owner.release)
		t.Fatal("revoke waited for callback")
	}
	close(owner.release)
	if err := <-done; !errors.Is(err, context.Canceled) {
		t.Fatalf("callback result after revoke: %v", err)
	}
}

func TestAndroidSearchDNSWireResponseBoundary(t *testing.T) {
	for _, wireBytes := range []int{maxDNSWireMessageBytes - 1, maxDNSWireMessageBytes, maxDNSWireMessageBytes + 1} {
		t.Run(fmt.Sprint(wireBytes), func(t *testing.T) {
			owner := &fakeRawDNSOwner{wireBytes: wireBytes}
			guard := installRawDNSOwner(t, owner)
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			conn, err := searchDNSConnection(ctx, guard)
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			query, err := (&dnsmessage.Message{Header: dnsmessage.Header{ID: 31}, Questions: []dnsmessage.Question{{Name: dnsmessage.MustNewName("synthetic.invalid."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}}}).Pack()
			if err != nil {
				t.Fatal(err)
			}
			frame := make([]byte, 2, len(query)+2)
			binary.BigEndian.PutUint16(frame, uint16(len(query)))
			frame = append(frame, query...)
			_, err = conn.Write(frame)
			if wireBytes <= maxDNSWireMessageBytes && err != nil || wireBytes > maxDNSWireMessageBytes && err == nil {
				t.Fatalf("wireBytes=%d error=%v", wireBytes, err)
			}
		})
	}
}

func TestAndroidSearchDNSNegativeAnswerDoesNotRevokeOwner(t *testing.T) {
	owner := &fakeRawDNSOwner{rcode: dnsmessage.RCodeNameError}
	guard := installRawDNSOwner(t, owner)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if _, err := net.DefaultResolver.LookupTXT(ctx, "synthetic.invalid."); err == nil {
		t.Fatal("negative DNS answer accepted")
	}
	if !guard.current() {
		t.Fatal("ordinary negative DNS answer revoked owner")
	}
}

func installRawDNSOwner(t *testing.T, owner *fakeRawDNSOwner) *protection {
	t.Helper()
	policyCtx, policyCancel := context.WithCancel(context.Background())
	roundCtx, roundCancel := context.WithTimeout(policyCtx, time.Second)
	guard := &protection{search: owner, searchContext: policyCtx, searchCancel: policyCancel, searchRound: roundCtx}
	previous := activeProtection.Swap(guard)
	t.Cleanup(func() { roundCancel(); policyCancel(); activeProtection.Store(previous) })
	return guard
}

func TestAndroidSearchDefaultResolverForwardsWireWithoutSockets(t *testing.T) {
	owner := &fakeRawDNSOwner{}
	installRawDNSOwner(t, owner)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	addresses, err := net.DefaultResolver.LookupIPAddr(ctx, "synthetic.invalid.")
	if err != nil || len(addresses) != 2 {
		t.Fatalf("A/AAAA lookup failed: %v", err)
	}
	texts, err := net.DefaultResolver.LookupTXT(ctx, "synthetic.invalid.")
	if err != nil || len(texts) != 1 || texts[0] != "synthetic" {
		t.Fatalf("TXT lookup failed: %v", err)
	}
	_, records, err := net.DefaultResolver.LookupSRV(ctx, "test", "tcp", "synthetic.invalid.")
	if err != nil || len(records) != 1 || records[0].Target != "target.invalid." || records[0].Port != 443 {
		t.Fatalf("SRV lookup failed: %v", err)
	}
	owner.mu.Lock()
	defer owner.mu.Unlock()
	seen := make(map[dnsmessage.Type]bool)
	for _, q := range owner.questions {
		seen[q] = true
	}
	for _, q := range []dnsmessage.Type{dnsmessage.TypeA, dnsmessage.TypeAAAA, dnsmessage.TypeTXT, dnsmessage.TypeSRV} {
		if !seen[q] {
			t.Fatal("resolver did not forward a query type through the owner")
		}
	}
}

func TestAndroidSearchDefaultResolverRejectsInvalidCallbackAndRevokes(t *testing.T) {
	owner := &fakeRawDNSOwner{badReply: true}
	guard := installRawDNSOwner(t, owner)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if _, err := net.DefaultResolver.LookupTXT(ctx, "synthetic.invalid."); err == nil {
		t.Fatal("invalid DNS response accepted")
	}
	if guard.current() || guard.searchContext.Err() == nil {
		t.Fatal("invalid callback did not revoke owner")
	}
}

func TestAndroidSearchDefaultResolverRequiresLiveWorker(t *testing.T) {
	_, guard := installFakeOwner(t)
	if _, err := searchDNSConnection(context.Background(), guard); err == nil {
		t.Fatal("resolver without worker deadline accepted")
	}
}

func TestAndroidSearchUDPResolutionUsesOwnerBeforeXrayControl(t *testing.T) {
	owner := &fakeRawDNSOwner{}
	installRawDNSOwner(t, owner)
	address, err := net.ResolveUDPAddr("udp", "synthetic.invalid.:53")
	if err != nil || address == nil || address.IP == nil {
		t.Fatalf("synthetic UDP resolution failed: %v", err)
	}
	owner.mu.Lock()
	defer owner.mu.Unlock()
	if len(owner.questions) == 0 {
		t.Fatal("UDP name resolution did not reach the DNS owner")
	}
}
