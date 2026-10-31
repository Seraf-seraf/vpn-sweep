package socks

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"testing"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

type socketFakeTCP struct {
	input  *bytes.Reader
	output bytes.Buffer
	closed bool
}

func (c *socketFakeTCP) Read(b []byte) (int, error)       { return c.input.Read(b) }
func (c *socketFakeTCP) Write(b []byte) (int, error)      { return c.output.Write(b) }
func (c *socketFakeTCP) Close() error                     { c.closed = true; return nil }
func (c *socketFakeTCP) LocalAddr() net.Addr              { return nil }
func (c *socketFakeTCP) RemoteAddr() net.Addr             { return nil }
func (c *socketFakeTCP) SetDeadline(time.Time) error      { return nil }
func (c *socketFakeTCP) SetReadDeadline(time.Time) error  { return nil }
func (c *socketFakeTCP) SetWriteDeadline(time.Time) error { return nil }

type socketFakeUDP struct {
	input  []byte
	output bytes.Buffer
	closed bool
}

func (c *socketFakeUDP) Read(b []byte) (int, error)  { return copy(b, c.input), nil }
func (c *socketFakeUDP) Write(b []byte) (int, error) { return c.output.Write(b) }
func (c *socketFakeUDP) Close() error                { c.closed = true; return nil }
func (c *socketFakeUDP) SetDeadline(time.Time) error { return nil }
func (c *socketFakeUDP) ReadMsgUDP(b, _ []byte) (int, int, int, *net.UDPAddr, error) {
	return copy(b, c.input), 0, 0, nil, nil
}

type socketFakeDialer struct {
	tcp        *socketFakeTCP
	udp        *socketFakeUDP
	tcpErr     error
	udpErr     error
	tcpContext context.Context
	udpContext context.Context
	tcpAddress string
	udpAddress *net.UDPAddr
}

func (d *socketFakeDialer) DialTCP(ctx context.Context, address string) (net.Conn, error) {
	d.tcpContext, d.tcpAddress = ctx, address
	if d.tcpErr != nil {
		return nil, d.tcpErr
	}
	return d.tcp, nil
}

func (d *socketFakeDialer) DialUDP(ctx context.Context, relay *net.UDPAddr) (UDPConn, error) {
	d.udpContext, d.udpAddress = ctx, relay
	if d.udpErr != nil {
		return nil, d.udpErr
	}
	return d.udp, nil
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
	if err := conn.Close(); err != nil || !dialer.tcp.closed {
		t.Fatal("connection was not closed")
	}
}

func TestEndpointInjectedUDPDialerFake(t *testing.T) {
	const host = "example.invalid"
	control := []byte{5, 2, 1, 0, 5, 0, 0, 1, 127, 0, 0, 1, 0x12, 0x34}
	reply := append([]byte{0, 0, 0, 3, byte(len(host))}, []byte(host)...)
	reply = append(reply, 0, 53, 'o', 'k')
	dialer := &socketFakeDialer{
		tcp: &socketFakeTCP{input: bytes.NewReader(control)},
		udp: &socketFakeUDP{input: reply},
	}
	ctx := context.WithValue(context.Background(), struct{}{}, "marker")
	endpoint := Endpoint{Address: "127.0.0.1:1080", User: "u", Pass: "p", SocketDialer: dialer}
	got, err := endpoint.UDPExchange(ctx, host+":53", []byte("q"), 2)
	if err != nil || string(got) != "ok" {
		t.Fatal("injected UDP exchange failed", err)
	}
	if dialer.tcpContext != ctx || dialer.udpContext != ctx || dialer.udpAddress == nil ||
		!dialer.udpAddress.IP.Equal(net.IPv4(127, 0, 0, 1)) || dialer.udpAddress.Port != 0x1234 {
		t.Fatal("UDP relay or context did not reach the injected dialer")
	}
	if !bytes.HasSuffix(dialer.udp.output.Bytes(), []byte("q")) || !dialer.udp.closed || !dialer.tcp.closed {
		t.Fatal("UDP payload or cleanup changed")
	}
}

func TestEndpointInjectedDialerFailureDoesNotFallbackFake(t *testing.T) {
	dialer := &socketFakeDialer{tcpErr: errors.New("synthetic failure")}
	endpoint := Endpoint{Address: "127.0.0.1:1080", User: "u", Pass: "p", SocketDialer: dialer}
	if _, err := endpoint.Negotiate(context.Background()); model.Code(err) != "proxy_unavailable" {
		t.Fatal("TCP failure changed or fell back", err)
	}
	dialer.tcpErr = nil
	dialer.tcp = &socketFakeTCP{input: bytes.NewReader([]byte{5, 2, 1, 0, 5, 0, 0, 1, 127, 0, 0, 1, 0x12, 0x34})}
	dialer.udpErr = io.EOF
	if _, err := endpoint.UDPExchange(context.Background(), "example.invalid:53", []byte("q"), 1); model.Code(err) != "udp_socket_failed" {
		t.Fatal("UDP failure changed or fell back", err)
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
	if _, err := endpoint.Negotiate(context.Background()); model.Code(err) != "socks_auth_failed" || !dialer.tcp.closed {
		t.Fatal("SOCKS authentication failure was not enforced", err)
	}

	dialer.tcp = &socketFakeTCP{input: bytes.NewReader([]byte{5, 2, 1, 0, 5, 0, 0, 1, 192, 0, 2, 1, 0x12, 0x34})}
	if _, err := endpoint.UDPExchange(context.Background(), "example.invalid:53", []byte("q"), 1); model.Code(err) != "udp_relay_not_loopback" || dialer.udpAddress != nil {
		t.Fatal("non-loopback UDP relay reached the dialer", err)
	}
}
