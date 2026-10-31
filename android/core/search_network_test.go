package mobile

import (
	"context"
	"errors"
	"io"
	"net"
	"os"
	"reflect"
	"syscall"
	"testing"
	"time"

	"github.com/xtls/xray-core/transport/internet"
)

type fakeAndroidNetwork struct {
	current bool
	allow   bool
	roles   []int
}

func (n *fakeAndroidNetwork) Current() bool { return n.current }
func (n *fakeAndroidNetwork) QueryDNS([]byte, int64) ([]byte, error) {
	return nil, errors.New("unexpected_dns_query")
}
func (n *fakeAndroidNetwork) AllowSocket(_ int, role int, _, _ string) bool {
	n.roles = append(n.roles, role)
	return n.allow
}

type fakeRawConn struct{ fail bool }

func (r fakeRawConn) Control(f func(uintptr)) error {
	if r.fail {
		return errors.New("control_failed")
	}
	f(71)
	return nil
}
func (fakeRawConn) Read(func(uintptr) bool) error  { return nil }
func (fakeRawConn) Write(func(uintptr) bool) error { return nil }

type fakeSocketFactory struct {
	networks []string
	err      error
	conn     net.Conn
}

func (f *fakeSocketFactory) DialContext(ctx context.Context, network, address string, control func(string, string, syscall.RawConn) error) (net.Conn, error) {
	f.networks = append(f.networks, network)
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if f.err != nil {
		return nil, f.err
	}
	if err := control(network, address, fakeRawConn{}); err != nil {
		return nil, err
	}
	return f.conn, nil
}

type fakeSocket struct{ closed bool }

func (*fakeSocket) Read([]byte) (int, error)         { return 0, io.EOF }
func (*fakeSocket) Write(p []byte) (int, error)      { return len(p), nil }
func (c *fakeSocket) Close() error                   { c.closed = true; return nil }
func (*fakeSocket) LocalAddr() net.Addr              { return nil }
func (*fakeSocket) RemoteAddr() net.Addr             { return nil }
func (*fakeSocket) SetDeadline(time.Time) error      { return nil }
func (*fakeSocket) SetReadDeadline(time.Time) error  { return nil }
func (*fakeSocket) SetWriteDeadline(time.Time) error { return nil }
func (*fakeSocket) ReadMsgUDP([]byte, []byte) (int, int, int, *net.UDPAddr, error) {
	return 0, 0, 0, nil, io.EOF
}

func installFakeOwner(t *testing.T) (*fakeAndroidNetwork, *protection) {
	t.Helper()
	owner := &fakeAndroidNetwork{current: true, allow: true}
	ctx, cancel := context.WithCancel(context.Background())
	guard := &protection{search: owner, searchContext: ctx, searchCancel: cancel}
	previous := activeProtection.Swap(guard)
	t.Cleanup(func() { cancel(); activeProtection.Store(previous) })
	return owner, guard
}

func TestAndroidSearchSocketRolesUseOneOwner(t *testing.T) {
	owner, guard := installFakeOwner(t)
	for _, role := range []int{SearchRoleXrayDial, SearchRoleXrayListener, SearchRoleDNS, SearchRoleSOCKSReservation} {
		if err := controlSocket(guard, role, "tcp", "synthetic", fakeRawConn{}, func(int) error { t.Fatal("allowed FD invalidated"); return nil }); err != nil {
			t.Fatal(err)
		}
	}
	factory := &fakeSocketFactory{conn: &fakeSocket{}}
	dialer := searchSocketDialer{owner: guard, factory: factory}
	if _, err := dialer.DialTCP(context.Background(), "127.0.0.1:1080"); err != nil {
		t.Fatal(err)
	}
	if _, err := dialer.DialUDP(context.Background(), &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 1080}); err != nil {
		t.Fatal(err)
	}
	want := []int{SearchRoleXrayDial, SearchRoleXrayListener, SearchRoleDNS, SearchRoleSOCKSReservation, SearchRoleSOCKSControl, SearchRoleSOCKSRelay}
	if !reflect.DeepEqual(owner.roles, want) || !reflect.DeepEqual(factory.networks, []string{"tcp", "udp"}) {
		t.Fatalf("roles=%v networks=%v", owner.roles, factory.networks)
	}
}

func TestAndroidSearchSocketDenialInvalidatesBeforeReturning(t *testing.T) {
	owner, guard := installFakeOwner(t)
	owner.allow = false
	invalidated := 0
	err := controlSocket(guard, SearchRoleXrayDial, "tcp", "synthetic", fakeRawConn{}, func(fd int) error {
		if fd != 71 {
			t.Fatal("wrong FD")
		}
		invalidated++
		return nil
	})
	if err == nil || invalidated != 1 {
		t.Fatal("denial did not invalidate the descriptor")
	}
	if guard.current() {
		t.Fatal("denial did not revoke policy")
	}
	owner, guard = installFakeOwner(t)
	owner.current = false
	err = controlSocket(guard, SearchRoleXrayListener, "udp", "synthetic", fakeRawConn{}, func(int) error { invalidated++; return nil })
	if err == nil || invalidated != 2 {
		t.Fatal("stale generation allowed a listener")
	}
}

func TestAndroidSearchRegisteredXrayControllerInvalidatesDeniedFD(t *testing.T) {
	owner, _ := installFakeOwner(t)
	owner.allow = false
	file, err := os.CreateTemp(t.TempDir(), "synthetic-fd")
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	raw, err := file.SyscallConn()
	if err != nil {
		t.Fatal(err)
	}
	internet.ControllersLock.Lock()
	controllers := append([]func(string, string, syscall.RawConn) error(nil), internet.Controllers...)
	internet.ControllersLock.Unlock()
	called := false
	for _, control := range controllers {
		if reflect.ValueOf(control).Pointer() == reflect.ValueOf(protectSocket).Pointer() {
			_ = control("tcp", "synthetic", raw)
			called = true
			break
		}
	}
	info, statErr := file.Stat()
	if !called || statErr != nil || info.Mode()&os.ModeCharDevice == 0 {
		t.Fatal("registered controller left denied descriptor usable")
	}
}

func TestAndroidSearchGuardTerminatesOnFailedFDInvalidation(t *testing.T) {
	owner, guard := installFakeOwner(t)
	owner.allow = false
	defer func() {
		if recover() == nil {
			t.Fatal("failed FD replacement did not terminate")
		}
	}()
	_ = controlSocket(guard, SearchRoleXrayDial, "tcp", "synthetic", fakeRawConn{}, func(int) error {
		return errors.New("synthetic_failure")
	})
}

func TestAndroidSearchSocketFactoryRejectsNoOwnerAndNoFallback(t *testing.T) {
	owner, guard := installFakeOwner(t)
	factory := &fakeSocketFactory{conn: &fakeSocket{}}
	dialer := searchSocketDialer{owner: guard, factory: factory}
	if _, err := (searchSocketDialer{factory: factory}).DialTCP(context.Background(), "127.0.0.1:1080"); err == nil {
		t.Fatal("missing owner accepted")
	}
	if _, err := dialer.DialTCP(context.Background(), "192.0.2.5:1080"); err == nil {
		t.Fatal("non-loopback proxy accepted")
	}
	owner.current = false
	if _, err := dialer.DialUDP(context.Background(), &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 1080}); err == nil {
		t.Fatal("stale network accepted")
	}
	if len(factory.networks) != 0 {
		t.Fatal("denied operation reached factory")
	}
	owner, guard = installFakeOwner(t)
	dialer.owner = guard
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := dialer.DialTCP(ctx, "127.0.0.1:1080"); !errors.Is(err, context.Canceled) {
		t.Fatal("cancellation not preserved")
	}
	factory.err = errors.New("synthetic failure")
	if _, err := dialer.DialTCP(context.Background(), "127.0.0.1:1080"); err == nil || len(factory.networks) != 1 {
		t.Fatal("factory failure did not stop the operation")
	}
}

func TestAndroidSearchProxyRequiresCurrentPolicy(t *testing.T) {
	owner, guard := installFakeOwner(t)
	proxy, err := searchProxy(guard, "127.0.0.1:1080", "user", "password")
	if err != nil || proxy.SocketDialer == nil {
		t.Fatal("SEARCH proxy lacks its owner")
	}
	owner.current = false
	if _, err := searchProxy(guard, "127.0.0.1:1080", "user", "password"); err == nil {
		t.Fatal("stale policy created proxy")
	}
}
