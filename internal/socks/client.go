package socks

import (
	"context"
	"encoding/binary"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"io"
	"net"
	"strconv"
	"time"
)

type Endpoint struct {
	Address      string
	User         string
	Pass         string
	SocketDialer SocketDialer
}

// SocketDialer owns both SOCKS connection paths when supplied by a caller.
// A nil SocketDialer preserves the existing CLI network behavior.
type SocketDialer interface {
	DialTCP(context.Context, string) (net.Conn, error)
	DialUDP(context.Context, *net.UDPAddr) (UDPConn, error)
}

// UDPConn retains the datagram truncation information needed by UDPExchange.
type UDPConn interface {
	io.Reader
	io.Writer
	io.Closer
	SetDeadline(time.Time) error
	ReadMsgUDP([]byte, []byte) (int, int, int, *net.UDPAddr, error)
}

func (e Endpoint) Negotiate(ctx context.Context) (net.Conn, error) {
	host, _, err := net.SplitHostPort(e.Address)
	if err != nil || net.ParseIP(host) == nil || !net.ParseIP(host).IsLoopback() || len(e.User) < 1 || len(e.User) > 255 || len(e.Pass) < 1 || len(e.Pass) > 255 {
		return nil, model.Err("invalid_proxy_handle", "socks")
	}
	var c net.Conn
	if e.SocketDialer == nil {
		c, err = (&net.Dialer{}).DialContext(ctx, "tcp", e.Address)
	} else {
		c, err = e.SocketDialer.DialTCP(ctx, e.Address)
	}
	if err != nil || c == nil {
		return nil, model.Err("proxy_unavailable", "socks")
	}
	deadline := time.Now().Add(15 * time.Second)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}
	c.SetDeadline(deadline)
	stop := context.AfterFunc(ctx, func() { c.Close() })
	defer stop()
	bad := func() (net.Conn, error) { c.Close(); return nil, model.Err("socks_auth_failed", "socks") }
	if _, err = c.Write([]byte{5, 1, 2}); err != nil {
		return bad()
	}
	b := make([]byte, 2)
	if _, err = io.ReadFull(c, b); err != nil || b[0] != 5 || b[1] != 2 {
		return bad()
	}
	packet := append([]byte{1, byte(len(e.User))}, []byte(e.User)...)
	packet = append(packet, byte(len(e.Pass)))
	packet = append(packet, []byte(e.Pass)...)
	if _, err = c.Write(packet); err != nil {
		return bad()
	}
	if _, err = io.ReadFull(c, b); err != nil || b[0] != 1 || b[1] != 0 {
		return bad()
	}
	return c, nil
}
func (e Endpoint) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	if network != "tcp" && network != "tcp4" && network != "tcp6" {
		return nil, model.Err("unsupported_transport", "socks")
	}
	host, p, err := net.SplitHostPort(address)
	port, x := strconv.Atoi(p)
	if err != nil || x != nil || port < 1 || port > 65535 || len(host) > 253 {
		return nil, model.Err("invalid_target", "socks")
	}
	c, err := e.Negotiate(ctx)
	if err != nil {
		return nil, err
	}
	stop := context.AfterFunc(ctx, func() { c.Close() })
	defer stop()
	a := []byte{5, 1, 0}
	if ip := net.ParseIP(host); ip != nil {
		if ip4 := ip.To4(); ip4 != nil {
			a = append(a, 1)
			a = append(a, ip4...)
		} else {
			a = append(a, 4)
			a = append(a, ip.To16()...)
		}
	} else {
		a = append(a, 3, byte(len(host)))
		a = append(a, []byte(host)...)
	}
	a = binary.BigEndian.AppendUint16(a, uint16(port))
	if _, err = c.Write(a); err != nil {
		c.Close()
		return nil, model.Err("socks_connect_failed", "socks")
	}
	if err = readReply(c); err != nil {
		c.Close()
		return nil, err
	}
	c.SetDeadline(time.Time{})
	return c, nil
}
func readReply(c net.Conn) error {
	b := make([]byte, 4)
	if _, err := io.ReadFull(c, b); err != nil || b[0] != 5 || b[1] != 0 || b[2] != 0 {
		return model.Err("socks_connect_failed", "socks")
	}
	n := 0
	switch b[3] {
	case 1:
		n = 4
	case 4:
		n = 16
	case 3:
		if _, e := io.ReadFull(c, b[:1]); e != nil {
			return model.Err("socks_reply_invalid", "socks")
		}
		n = int(b[0])
	default:
		return model.Err("socks_reply_invalid", "socks")
	}
	if _, e := io.CopyN(io.Discard, c, int64(n+2)); e != nil {
		return model.Err("socks_reply_invalid", "socks")
	}
	return nil
}
