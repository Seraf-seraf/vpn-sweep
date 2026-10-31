package socks

import (
	"bytes"
	"context"
	"errors"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

type socketFakeTCP struct {
	input  *bytes.Reader
	output bytes.Buffer
	closed atomic.Bool
}

func (c *socketFakeTCP) Read(b []byte) (int, error)       { return c.input.Read(b) }
func (c *socketFakeTCP) Write(b []byte) (int, error)      { return c.output.Write(b) }
func (c *socketFakeTCP) Close() error                     { c.closed.Store(true); return nil }
func (c *socketFakeTCP) LocalAddr() net.Addr              { return nil }
func (c *socketFakeTCP) RemoteAddr() net.Addr             { return nil }
func (c *socketFakeTCP) SetDeadline(time.Time) error      { return nil }
func (c *socketFakeTCP) SetReadDeadline(time.Time) error  { return nil }
func (c *socketFakeTCP) SetWriteDeadline(time.Time) error { return nil }

type socketFakeDialer struct {
	tcp        *socketFakeTCP
	tcpErr     error
	tcpContext context.Context
	tcpAddress string
}

func (d *socketFakeDialer) DialTCP(ctx context.Context, address string) (net.Conn, error) {
	d.tcpContext, d.tcpAddress = ctx, address
	if d.tcpErr != nil {
		return nil, d.tcpErr
	}
	return d.tcp, nil
}

type socketBlockingTCP struct {
	entered chan struct{}
	closed  chan struct{}
	once    sync.Once
}

func (c *socketBlockingTCP) Read([]byte) (int, error) {
	close(c.entered)
	<-c.closed
	return 0, context.Canceled
}
func (*socketBlockingTCP) Write(b []byte) (int, error)      { return len(b), nil }
func (c *socketBlockingTCP) Close() error                   { c.once.Do(func() { close(c.closed) }); return nil }
func (*socketBlockingTCP) LocalAddr() net.Addr              { return nil }
func (*socketBlockingTCP) RemoteAddr() net.Addr             { return nil }
func (*socketBlockingTCP) SetDeadline(time.Time) error      { return nil }
func (*socketBlockingTCP) SetReadDeadline(time.Time) error  { return nil }
func (*socketBlockingTCP) SetWriteDeadline(time.Time) error { return nil }

type socketGeneralFakeDialer struct {
	tcp      net.Conn
	tcpErr   error
	tcpCalls atomic.Int32
}

func (d *socketGeneralFakeDialer) DialTCP(context.Context, string) (net.Conn, error) {
	d.tcpCalls.Add(1)
	return d.tcp, d.tcpErr
}
func TestEndpointInjectedTCPDialerFake(t *testing.T) {
	dialer := &socketFakeDialer{tcp: &socketFakeTCP{input: bytes.NewReader([]byte{5, 2, 1, 0})}}
	ctx := context.WithValue(context.Background(), struct{}{}, "marker")
	endpoint := Endpoint{Address: "127.0.0.1:1080", User: "u", Pass: "p", SocketDialer: dialer}
	conn, err := endpoint.Negotiate(ctx)
	if err != nil || conn != dialer.tcp || dialer.tcpContext != ctx || dialer.tcpAddress != endpoint.Address {
		t.Fatal("injected TCP negotiation did not use the supplied context and endpoint", err)
	}
	if !bytes.Equal(dialer.tcp.output.Bytes(), []byte{5, 1, 2, 1, 1, 'u', 1, 'p'}) {
		t.Fatal("SOCKS authentication request changed")
	}
	if err := conn.Close(); err != nil || !dialer.tcp.closed.Load() {
		t.Fatal("connection was not closed")
	}
}

func TestEndpointInjectedDialerFailureDoesNotFallbackFake(t *testing.T) {
	dialer := &socketFakeDialer{tcpErr: errors.New("synthetic failure")}
	endpoint := Endpoint{Address: "127.0.0.1:1080", User: "u", Pass: "p", SocketDialer: dialer}
	if _, err := endpoint.Negotiate(context.Background()); model.Code(err) != "proxy_unavailable" {
		t.Fatal("TCP failure changed or fell back", err)
	}
}

func TestEndpointInjectedLoopbackAndAuthenticationFake(t *testing.T) {
	dialer := &socketFakeDialer{}
	endpoint := Endpoint{Address: "192.0.2.1:1080", User: "u", Pass: "p", SocketDialer: dialer}
	if _, err := endpoint.Negotiate(context.Background()); model.Code(err) != "invalid_proxy_handle" || dialer.tcpAddress != "" {
		t.Fatal("non-loopback proxy reached the dialer", err)
	}

	endpoint.Address = "127.0.0.1:1080"
	dialer.tcp = &socketFakeTCP{input: bytes.NewReader([]byte{5, 2, 1, 1})}
	if _, err := endpoint.Negotiate(context.Background()); model.Code(err) != "socks_auth_failed" || !dialer.tcp.closed.Load() {
		t.Fatal("SOCKS authentication failure was not enforced", err)
	}

	dialer.tcp = &socketFakeTCP{input: bytes.NewReader([]byte{5, 2, 1, 0, 5, 0, 0, 1, 192, 0, 2, 1, 0x12, 0x34})}
	if _, err := endpoint.UDPExchange(context.Background(), "example.invalid:53", []byte("q"), 1); model.Code(err) != "udp_relay_not_loopback" || !dialer.tcp.closed.Load() {
		t.Fatal("non-loopback UDP relay reached the dialer", err)
	}
}

func TestEndpointInjectedTCPControlCancellationClosesFake(t *testing.T) {
	conn := &socketBlockingTCP{entered: make(chan struct{}), closed: make(chan struct{})}
	dialer := &socketGeneralFakeDialer{tcp: conn}
	endpoint := Endpoint{Address: "127.0.0.1:1080", User: "u", Pass: "p", SocketDialer: dialer}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { _, err := endpoint.Negotiate(ctx); done <- err }()
	select {
	case <-conn.entered:
	case <-time.After(time.Second):
		t.Fatal("fake TCP read did not start")
	}
	cancel()
	select {
	case err := <-done:
		if model.Code(err) != "socks_auth_failed" {
			t.Fatalf("cancellation returned %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("fake TCP did not close after cancellation")
	}
	if dialer.tcpCalls.Load() != 1 {
		t.Fatal("unexpected socket creation or fallback")
	}
}

func TestEndpointInjectedFactoryTimeoutDoesNotFallback(t *testing.T) {
	ctx, cancel := context.WithDeadline(context.Background(), time.Now().Add(-time.Second))
	defer cancel()
	dialer := &socketGeneralFakeDialer{tcpErr: context.DeadlineExceeded}
	endpoint := Endpoint{Address: "127.0.0.1:1080", User: "u", Pass: "p", SocketDialer: dialer}
	if _, err := endpoint.Negotiate(ctx); model.Code(err) != "proxy_unavailable" {
		t.Fatalf("TCP timeout returned %v", err)
	}
	if dialer.tcpCalls.Load() != 1 {
		t.Fatal("TCP timeout created another socket")
	}
}
