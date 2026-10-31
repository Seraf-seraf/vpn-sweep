package mobile

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

const maxDNSWireMessageBytes = 1<<16 - 1

type searchDNSConn struct {
	mu        sync.Mutex
	ctx       context.Context
	cancel    context.CancelFunc
	stopRound func() bool
	owner     *protection
	deadline  time.Time
	closed    bool
	written   bool
	response  *bytes.Reader
}

func searchDNSConnection(ctx context.Context, owner *protection) (net.Conn, error) {
	if !searchCurrent(owner) {
		return nil, errors.New("search_network_unavailable")
	}
	owner.searchRoundMu.RLock()
	round := owner.searchRound
	owner.searchRoundMu.RUnlock()
	if round == nil || round.Err() != nil {
		return nil, errors.New("search_network_unavailable")
	}
	deadline, ok := round.Deadline()
	if !ok {
		return nil, errors.New("search_network_unavailable")
	}
	combined, cancel := context.WithDeadline(ctx, deadline)
	stop := context.AfterFunc(round, cancel)
	return &searchDNSConn{ctx: combined, cancel: cancel, stopRound: stop, owner: owner}, nil
}

func (c *searchDNSConn) Write(frame []byte) (int, error) {
	c.mu.Lock()
	if c.closed || c.written {
		c.mu.Unlock()
		return 0, io.ErrClosedPipe
	}
	c.written = true
	deadline := c.deadline
	c.mu.Unlock()
	if err := c.check(deadline); err != nil {
		return 0, err
	}
	if len(frame) < 2 || int(binary.BigEndian.Uint16(frame[:2])) != len(frame)-2 {
		return 0, errors.New("invalid_dns_request")
	}
	request := append([]byte(nil), frame[2:]...)
	var query dnsmessage.Message
	if query.Unpack(request) != nil || query.Header.Response || len(query.Questions) != 1 {
		return 0, errors.New("invalid_dns_request")
	}
	response, err := c.query(request)
	if err != nil {
		return 0, err
	}
	var answer dnsmessage.Message
	if len(response) > maxDNSWireMessageBytes || answer.Unpack(response) != nil || !answer.Header.Response ||
		answer.Header.ID != query.Header.ID || len(answer.Questions) != 1 || answer.Questions[0] != query.Questions[0] {
		c.owner.revokeSearch()
		return 0, errors.New("search_network_unavailable")
	}
	if err := c.check(deadline); err != nil {
		return 0, err
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

func (c *searchDNSConn) query(packet []byte) (response []byte, err error) {
	defer func() {
		if recover() != nil {
			c.owner.revokeSearch()
			response, err = nil, errors.New("search_network_unavailable")
		}
	}()
	remaining := remainingMillis(c.ctx)
	if remaining == 0 {
		return nil, context.DeadlineExceeded
	}
	response, err = c.owner.search.QueryDNS(packet, remaining)
	if contextErr := c.ctx.Err(); contextErr != nil {
		return nil, contextErr
	}
	if err != nil || !searchCurrent(c.owner) {
		c.owner.revokeSearch()
		return nil, errors.New("search_network_unavailable")
	}
	return response, nil
}

func (c *searchDNSConn) Read(p []byte) (int, error) {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return 0, io.ErrClosedPipe
	}
	deadline := c.deadline
	c.mu.Unlock()
	if err := c.check(deadline); err != nil {
		return 0, err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return 0, io.ErrClosedPipe
	}
	if c.response == nil {
		return 0, io.EOF
	}
	return c.response.Read(p)
}

func (c *searchDNSConn) check(deadline time.Time) error {
	if err := c.ctx.Err(); err != nil {
		return err
	}
	if !deadline.IsZero() && !time.Now().Before(deadline) {
		return context.DeadlineExceeded
	}
	if !searchCurrent(c.owner) {
		return errors.New("search_network_unavailable")
	}
	return nil
}

func (c *searchDNSConn) Close() error {
	c.mu.Lock()
	c.closed = true
	c.mu.Unlock()
	c.stopRound()
	c.cancel()
	return nil
}

func (*searchDNSConn) LocalAddr() net.Addr  { return &net.TCPAddr{} }
func (*searchDNSConn) RemoteAddr() net.Addr { return &net.TCPAddr{} }

func (c *searchDNSConn) SetDeadline(deadline time.Time) error {
	c.mu.Lock()
	c.deadline = deadline
	c.mu.Unlock()
	return nil
}

func (c *searchDNSConn) SetReadDeadline(deadline time.Time) error  { return c.SetDeadline(deadline) }
func (c *searchDNSConn) SetWriteDeadline(deadline time.Time) error { return c.SetDeadline(deadline) }
