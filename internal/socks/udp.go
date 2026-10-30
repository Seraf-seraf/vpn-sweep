package socks

import (
	"context"
	"encoding/binary"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"io"
	"net"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const maxUDPHeaderBytes = 4 + 1 + 253 + 2

func (e Endpoint) UDPEcho(ctx context.Context, address string, payload []byte) ([]byte, error) {
	return e.udpExchange(ctx, address, payload, false, 65536)
}

func (e Endpoint) UDPExchange(ctx context.Context, address string, payload []byte, maxReplyBytes int) ([]byte, error) {
	return e.udpExchange(ctx, address, payload, true, maxReplyBytes)
}

func (e Endpoint) udpExchange(ctx context.Context, address string, payload []byte, verifySource bool, maxReplyBytes int) ([]byte, error) {
	host, p, err := net.SplitHostPort(address)
	port, x := strconv.Atoi(p)
	if err != nil || x != nil || port < 1 || port > 65535 || len(host) > 253 || len(payload) > 1200 || maxReplyBytes < 1 || maxReplyBytes > 65536 {
		return nil, model.Err("invalid_udp_probe", "udp")
	}
	control, err := e.Negotiate(ctx)
	if err != nil {
		return nil, err
	}
	defer control.Close()
	stop := context.AfterFunc(ctx, func() { control.Close() })
	defer stop()
	if _, err = control.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		return nil, model.Err("udp_associate_failed", "udp")
	}
	h := make([]byte, 4)
	if _, err = io.ReadFull(control, h); err != nil || h[0] != 5 || h[1] != 0 {
		return nil, model.Err("udp_associate_unsupported", "udp")
	}
	var ip net.IP
	switch h[3] {
	case 1:
		ip = make(net.IP, 4)
	case 4:
		ip = make(net.IP, 16)
	default:
		return nil, model.Err("udp_relay_invalid", "udp")
	}
	if _, err = io.ReadFull(control, ip); err != nil {
		return nil, model.Err("udp_relay_invalid", "udp")
	}
	b := make([]byte, 2)
	if _, err = io.ReadFull(control, b); err != nil {
		return nil, model.Err("udp_relay_invalid", "udp")
	}
	if ip.IsUnspecified() {
		ip = net.ParseIP("127.0.0.1")
	}
	if !ip.IsLoopback() {
		return nil, model.Err("udp_relay_not_loopback", "udp")
	}
	relay := &net.UDPAddr{IP: ip, Port: int(binary.BigEndian.Uint16(b))}
	c, err := net.DialUDP("udp", nil, relay)
	if err != nil {
		return nil, model.Err("udp_socket_failed", "udp")
	}
	defer c.Close()
	cancel := context.AfterFunc(ctx, func() { c.Close() })
	defer cancel()
	deadline := time.Now().Add(5 * time.Second)
	if d, ok := ctx.Deadline(); ok {
		deadline = d
	}
	c.SetDeadline(deadline)
	header := []byte{0, 0, 0}
	if target := net.ParseIP(host); target != nil {
		if a := target.To4(); a != nil {
			header = append(header, 1)
			header = append(header, a...)
		} else {
			header = append(header, 4)
			header = append(header, target.To16()...)
		}
	} else {
		header = append(header, 3, byte(len(host)))
		header = append(header, []byte(host)...)
	}
	header = binary.BigEndian.AppendUint16(header, uint16(port))
	packet := append(header, payload...)
	if _, err = c.Write(packet); err != nil {
		return nil, model.Err("udp_send_failed", "udp")
	}
	bufferSize := maxReplyBytes + maxUDPHeaderBytes
	if bufferSize > 65536 {
		bufferSize = 65536
	}
	reply := make([]byte, bufferSize)
	var n int
	if verifySource {
		var flags int
		n, _, flags, _, err = c.ReadMsgUDP(reply, nil)
		if err == nil && flags&syscall.MSG_TRUNC != 0 {
			return nil, model.Err("udp_reply_too_large", "udp")
		}
	} else {
		n, err = c.Read(reply)
	}
	if err != nil {
		return nil, model.Err("udp_timeout_or_read_failed", "udp")
	}
	if n < 4 || reply[0] != 0 || reply[1] != 0 || reply[2] != 0 {
		return nil, model.Err("udp_fragment_unsupported", "udp")
	}
	off := 4
	var source string
	switch reply[3] {
	case 1:
		if n < off+4+2 {
			return nil, model.Err("udp_reply_invalid", "udp")
		}
		source = net.IP(reply[off : off+4]).String()
		off += 4
	case 4:
		if n < off+16+2 {
			return nil, model.Err("udp_reply_invalid", "udp")
		}
		source = net.IP(reply[off : off+16]).String()
		off += 16
	case 3:
		if n < 5 {
			return nil, model.Err("udp_reply_invalid", "udp")
		}
		length := int(reply[4])
		if n < off+1+length+2 {
			return nil, model.Err("udp_reply_invalid", "udp")
		}
		source = string(reply[off+1 : off+1+length])
		off += 1 + length
	default:
		return nil, model.Err("udp_reply_invalid", "udp")
	}
	sourcePort := int(binary.BigEndian.Uint16(reply[off : off+2]))
	off += 2
	if verifySource {
		expectedIP, actualIP := net.ParseIP(host), net.ParseIP(source)
		matches := expectedIP != nil && actualIP != nil && expectedIP.Equal(actualIP)
		if expectedIP == nil && actualIP == nil {
			matches = strings.EqualFold(host, source)
		}
		if !matches || sourcePort != port {
			return nil, model.Err("udp_reply_source_mismatch", "udp")
		}
		if n-off > maxReplyBytes {
			return nil, model.Err("udp_reply_too_large", "udp")
		}
	}
	return append([]byte(nil), reply[off:n]...), nil
}
