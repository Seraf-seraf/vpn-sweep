package mobile

import (
	"context"
	"net"
	"testing"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/transport/internet"
)

type testProtector bool

func (p testProtector) Protect(_ int) bool { return bool(p) }

func TestProtectionFailurePreventsTrafficDespiteIgnoredError(t *testing.T) {
	for _, allow := range []bool{false, true} {
		t.Run(map[bool]string{false: "denied", true: "allowed"}[allow], func(t *testing.T) {
			guard := &protection{callback: testProtector(allow)}
			activeProtection.Store(guard)
			defer activeProtection.Store(nil)
			listener, err := net.Listen("tcp4", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			defer listener.Close()
			port := listener.Addr().(*net.TCPAddr).Port
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			dialer := &internet.DefaultSystemDialer{}
			conn, err := dialer.Dial(ctx, nil, xnet.TCPDestination(xnet.LocalHostIP, xnet.Port(port)), nil)
			if conn != nil {
				conn.Close()
			}
			if allow && (err != nil || guard.passed.Load() != 1) {
				t.Fatal("protected connection failed", err)
			}
			if !allow && (err == nil || guard.failed.Load() != 1) {
				t.Fatal("unprotected connection was allowed")
			}
			udp, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
			if err != nil {
				t.Fatal(err)
			}
			defer udp.Close()
			conn, err = dialer.Dial(ctx, nil, xnet.UDPDestination(xnet.LocalHostIP, xnet.Port(udp.LocalAddr().(*net.UDPAddr).Port)), nil)
			if conn != nil {
				_, err = conn.Write([]byte("guard-test"))
				conn.Close()
			}
			if allow && err != nil {
				t.Fatal("protected UDP failed", err)
			}
			if !allow && err == nil {
				t.Fatal("unprotected UDP was allowed")
			}
			packet, err := internet.ListenSystemPacket(ctx, &net.UDPAddr{IP: net.IPv4zero}, nil)
			if packet != nil {
				packet.Close()
			}
			if allow && err != nil {
				t.Fatal("protected packet listener failed", err)
			}
			if !allow && err == nil {
				t.Fatal("unprotected packet listener was allowed")
			}
		})
	}
}

func TestRejectedStartAndIdempotentStop(t *testing.T) {
	if err := Start([]byte(`{}`), -1, "1.1.1.1", testProtector(true)); err == nil {
		t.Fatal("invalid FD accepted")
	}
	if err := Stop(); err != nil {
		t.Fatal(err)
	}
	if err := Stop(); err != nil {
		t.Fatal(err)
	}
}
