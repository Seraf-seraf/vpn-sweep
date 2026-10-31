package subscriptionbridge

import (
	"bytes"
	"context"
	"encoding/binary"
	"io"
	"net"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

const maxDNSBytes = 1<<16 - 1

type dnsConn struct {
	mu       sync.Mutex
	ctx      context.Context
	owner    *requestNetwork
	deadline time.Time
	closed   bool
	written  bool
	response *bytes.Reader
}

func (c *dnsConn) Write(frame []byte) (int, error) {
	c.mu.Lock()
	if c.closed || c.written {
		c.mu.Unlock()
		return 0, io.ErrClosedPipe
	}
	c.written = true
	deadline := c.deadline
	c.mu.Unlock()
	if !c.valid(deadline) || len(frame) < 2 || int(binary.BigEndian.Uint16(frame[:2])) != len(frame)-2 {
		return 0, errInput
	}
	packet := bytes.Clone(frame[2:])
	var query dnsmessage.Message
	if query.Unpack(packet) != nil || query.Header.Response || len(query.Questions) != 1 {
		return 0, errInput
	}
	response, err := c.query(packet)
	if err != nil {
		return 0, errInput
	}
	var answer dnsmessage.Message
	if len(response) > maxDNSBytes || answer.Unpack(response) != nil || !answer.Header.Response ||
		answer.Header.ID != query.Header.ID || len(answer.Questions) != 1 || answer.Questions[0] != query.Questions[0] || !c.valid(deadline) {
		return 0, errInput
	}
	result := make([]byte, 2, len(response)+2)
	binary.BigEndian.PutUint16(result, uint16(len(response)))
	result = append(result, response...)
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return 0, io.ErrClosedPipe
	}
	c.response = bytes.NewReader(result)
	return len(frame), nil
}

func (c *dnsConn) query(packet []byte) (response []byte, err error) {
	defer func() {
		if recover() != nil {
			response, err = nil, errInput
		}
	}()
	if !c.owner.current(c.ctx) {
		return nil, errInput
	}
	remaining := remainingMillis(c.ctx)
	if remaining == 0 {
		return nil, errInput
	}
	response, err = c.owner.route.QueryDNS(packet, remaining)
	if err != nil || !c.owner.current(c.ctx) {
		return nil, errInput
	}
	return response, nil
}

func (c *dnsConn) Read(p []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return 0, io.ErrClosedPipe
	}
	if !c.valid(c.deadline) {
		return 0, errInput
	}
	if c.response == nil {
		return 0, io.EOF
	}
	return c.response.Read(p)
}

func (c *dnsConn) valid(deadline time.Time) bool {
	return (deadline.IsZero() || time.Now().Before(deadline)) && c.owner.current(c.ctx)
}

func (c *dnsConn) Close() error {
	c.mu.Lock()
	c.closed = true
	c.mu.Unlock()
	return nil
}

func (*dnsConn) LocalAddr() net.Addr  { return &net.TCPAddr{} }
func (*dnsConn) RemoteAddr() net.Addr { return &net.TCPAddr{} }

func (c *dnsConn) SetDeadline(deadline time.Time) error {
	c.mu.Lock()
	c.deadline = deadline
	c.mu.Unlock()
	return nil
}

func (c *dnsConn) SetReadDeadline(deadline time.Time) error  { return c.SetDeadline(deadline) }
func (c *dnsConn) SetWriteDeadline(deadline time.Time) error { return c.SetDeadline(deadline) }
