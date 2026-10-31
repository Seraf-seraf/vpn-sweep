package mobile

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/policy"
	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
)

type fakeAuthSocket struct {
	input    *bytes.Reader
	output   bytes.Buffer
	closed   bool
	deadline time.Time
}

func (c *fakeAuthSocket) Read(p []byte) (int, error)     { return c.input.Read(p) }
func (c *fakeAuthSocket) Write(p []byte) (int, error)    { return c.output.Write(p) }
func (c *fakeAuthSocket) Close() error                   { c.closed = true; return nil }
func (*fakeAuthSocket) LocalAddr() net.Addr              { return nil }
func (*fakeAuthSocket) RemoteAddr() net.Addr             { return nil }
func (c *fakeAuthSocket) SetDeadline(d time.Time) error  { c.deadline = d; return nil }
func (*fakeAuthSocket) SetReadDeadline(time.Time) error  { return nil }
func (*fakeAuthSocket) SetWriteDeadline(time.Time) error { return nil }

type fakeCheckClient struct {
	called  bool
	onCheck func()
}

func (c *fakeCheckClient) Check(ctx context.Context, endpoint socks.Endpoint, p model.Probe, round int) model.ProbeResult {
	c.called = endpoint.SocketDialer != nil && ctx.Err() == nil
	if c.onCheck != nil {
		c.onCheck()
	}
	return model.ProbeResult{ID: "test", Probe: p.ID, Round: round, Outcome: "pass"}
}

type fakeMediaClient struct{ called bool }

func (c *fakeMediaClient) Round(ctx context.Context, endpoint socks.Endpoint, _ model.Policy, round int) []model.ProbeResult {
	c.called = endpoint.SocketDialer != nil && ctx.Err() == nil
	return []model.ProbeResult{{ID: "test", Round: round, Outcome: "pass"}}
}

func TestAndroidSearchWorkerAuthenticatesWithOwnerAndCloses(t *testing.T) {
	owner, guard := installFakeOwner(t)
	worker, err := newSearchWorkerProbes("127.0.0.1:1080", "user", "password")
	if err != nil {
		t.Fatal(err)
	}
	defer worker.Cancel()
	connection := &fakeAuthSocket{input: bytes.NewReader([]byte{5, 2, 1, 0})}
	factory := &fakeSocketFactory{conn: connection}
	worker.proxy.SocketDialer = searchSocketDialer{owner: guard, factory: factory}
	before := time.Now()
	if err := worker.Authenticate(1000); err != nil {
		t.Fatal(err)
	}
	if !connection.closed || connection.deadline.Before(before) || connection.deadline.After(before.Add(1100*time.Millisecond)) ||
		len(owner.roles) != 1 || owner.roles[0] != SearchRoleSOCKSControl || !bytes.Equal(connection.output.Bytes()[:3], []byte{5, 1, 2}) {
		t.Fatal("authenticated readiness bypassed owner, deadline, or close")
	}
}

func TestAndroidSearchWorkerDispatchesThroughGuardedProxy(t *testing.T) {
	_, _ = installFakeOwner(t)
	worker, err := newSearchWorkerProbes("127.0.0.1:1080", "user", "password")
	if err != nil {
		t.Fatal(err)
	}
	defer worker.Cancel()
	checker := &fakeCheckClient{}
	media := &fakeMediaClient{}
	worker.check = checker
	worker.media = media
	udpCalled := false
	worker.udp = func(ctx context.Context, endpoint socks.Endpoint, p model.UDPProbe, round int) model.ProbeResult {
		udpCalled = endpoint.SocketDialer != nil && ctx.Err() == nil
		return model.ProbeResult{ID: "test", Probe: p.ID, Round: round, Outcome: "pass"}
	}
	page := model.Probe{ID: "p", Operator: "a", URL: "https://a.invalid", Kind: "page", Contains: "expected", Status: 200, TimeoutSeconds: 1}
	encoded, _ := json.Marshal(page)
	if _, err := worker.Check(encoded, 0, 1000); err != nil || !checker.called {
		t.Fatal("check did not receive guarded proxy", err)
	}
	udp, _ := json.Marshal(model.UDPProbe{ID: "u", Address: "192.0.2.1:53", TimeoutSeconds: 1, Kind: "dns-a"})
	if _, err := worker.UDP(udp, 0, 1000); err != nil || !udpCalled {
		t.Fatal("UDP did not receive guarded proxy", err)
	}
	settings, _ := json.Marshal(policy.Default())
	if _, err := worker.Media(settings, 0, 1000); err != nil || !media.called {
		t.Fatal("media did not receive guarded proxy", err)
	}
	if _, err := worker.Check(encoded, 0, 0); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatal("expired deadline dispatched check")
	}
	worker.Cancel()
	if _, err := worker.Check(encoded, 0, 1000); !errors.Is(err, context.Canceled) {
		t.Fatal("cancelled worker dispatched check")
	}
}

func TestAndroidSearchWorkerRejectsInvalidInputsWithoutNetwork(t *testing.T) {
	_, _ = installFakeOwner(t)
	if _, err := newSearchWorkerProbes("192.0.2.1:1080", "user", "password"); err == nil {
		t.Fatal("non-loopback endpoint accepted")
	}
	worker, err := newSearchWorkerProbes("127.0.0.1:1080", "user", "password")
	if err != nil {
		t.Fatal(err)
	}
	defer worker.Cancel()
	for _, input := range []string{"", "{}", `{"id":"x","id":"y"}`, strings.Repeat("x", 10)} {
		if _, err := worker.Check([]byte(input), 0, 1000); err == nil {
			t.Fatal("invalid request accepted")
		}
	}
	if _, err := worker.UDP([]byte(`{"id":"u","address":"192.0.2.1:53","timeout_seconds":31}`), 0, 1000); err == nil {
		t.Fatal("invalid UDP deadline accepted")
	}
}

func TestAndroidSearchWorkerOwnerLossRevokesInsteadOfReportingProbeFailure(t *testing.T) {
	owner, _ := installFakeOwner(t)
	worker, err := newSearchWorkerProbes("127.0.0.1:1080", "user", "password")
	if err != nil {
		t.Fatal(err)
	}
	worker.check = &fakeCheckClient{onCheck: func() { owner.current = false }}
	page := model.Probe{ID: "p", Operator: "a", URL: "https://a.invalid", Kind: "page", Contains: "expected", Status: 200, TimeoutSeconds: 1}
	encoded, _ := json.Marshal(page)
	if _, err := worker.Check(encoded, 0, 1000); err == nil || err.Error() != "search_owner_lost" {
		t.Fatal("network loss was reported as a candidate result")
	}
	if !worker.WaitStopped() {
		t.Fatal("owner loss did not stop worker")
	}
}

var _ io.Closer = (*fakeAuthSocket)(nil)
