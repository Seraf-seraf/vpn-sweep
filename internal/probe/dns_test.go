package probe

import (
	"context"
	"encoding/binary"
	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/core"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
	"github.com/Seraf-seraf/vpn-sweep/internal/testkit"
	"net"
	"testing"
	"time"
)

func TestUDPDNSThroughSOCKS(t *testing.T) {
	binary, id := testkit.FakeCore(t)
	addr, err := core.ReserveAddress()
	if err != nil {
		t.Fatal(err)
	}
	proxy := socks.Endpoint{Address: addr, User: "u", Pass: "p"}
	imp, err := importer.Parse([]byte("socks5://127.0.0.1:9"), "uri")
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := capability.Render(imp.Profiles[0], nil, addr, "u", "p")
	if err != nil {
		t.Fatal(err)
	}
	h, err := (core.Runner{Binary: binary, Identity: id, Directory: t.TempDir()}).Start(context.Background(), cfg, proxy)
	if err != nil {
		t.Fatal(err)
	}
	defer h.Close()
	for _, tc := range []struct {
		name      string
		mutate    func([]byte) []byte
		pass      bool
		wantBytes int64
	}{
		{name: "valid A response", pass: true},
		{name: "wrong transaction", mutate: func(b []byte) []byte { b[0] ^= 1; return b }},
		{name: "wrong question", mutate: func(b []byte) []byte { b[13] ^= 1; return b }},
		{name: "truncated flag", mutate: func(b []byte) []byte { b[2] |= 2; return b }},
		{name: "no answer", mutate: func(b []byte) []byte { b[7] = 0; return b }},
		{name: "incomplete answer", mutate: func(b []byte) []byte { return b[:len(b)-1] }},
		{name: "bad compression pointer", mutate: func(b []byte) []byte { b[len(b)-16] = 0xff; return b }},
		{name: "response one byte over limit", mutate: func(b []byte) []byte { return append(b, make([]byte, 513-len(b))...) }, wantBytes: 513},
		{name: "oversized datagram", mutate: func(b []byte) []byte { return append(b, make([]byte, 1200-len(b))...) }, wantBytes: 1200},
	} {
		t.Run(tc.name, func(t *testing.T) {
			server, err := net.ListenPacket("udp4", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			defer server.Close()
			go func() {
				b := make([]byte, 512)
				n, peer, err := server.ReadFrom(b)
				if err != nil || n < 12 {
					return
				}
				reply := append([]byte(nil), b[:n]...)
				reply[2], reply[3] = 0x81, 0x80
				reply[6], reply[7] = 0, 1
				reply = append(reply, 0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 203, 0, 113, 10)
				if tc.mutate != nil {
					reply = tc.mutate(reply)
				}
				_, _ = server.WriteTo(reply, peer)
			}()
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			result := UDPDNS(ctx, proxy, "dns", server.LocalAddr().String(), 0)
			if (result.Outcome == "pass") != tc.pass || result.Transport != "udp-socks5" {
				t.Fatal(result)
			}
			if tc.wantBytes > 0 && result.Outcome != "udp_reply_too_large" {
				t.Fatal("DNS size limit was not enforced", result)
			}
			if tc.wantBytes > 0 && result.Bytes != tc.wantBytes {
				t.Fatalf("received payload was not counted: %+v", result)
			}
		})
	}
	if err := h.Close(); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	if result := UDPDNS(ctx, proxy, "dns", "127.0.0.1:9", 0); result.Outcome == "pass" {
		t.Fatal("DNS bypassed the stopped SOCKS proxy")
	}
}

func TestDNSAnswerMustResolveQuestion(t *testing.T) {
	query := append([]byte{0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0}, dnsQuestion...)
	name := func(labels ...string) []byte {
		var out []byte
		for _, label := range labels {
			out = append(out, byte(len(label)))
			out = append(out, label...)
		}
		return append(out, 0)
	}
	rr := func(owner []byte, typ uint16, data []byte) []byte {
		out := append([]byte(nil), owner...)
		out = binary.BigEndian.AppendUint16(out, typ)
		out = binary.BigEndian.AppendUint16(out, 1)
		out = binary.BigEndian.AppendUint32(out, 60)
		out = binary.BigEndian.AppendUint16(out, uint16(len(data)))
		return append(out, data...)
	}
	question := []byte{0xc0, 0x0c}
	alias := name("alias", "example")
	compressedAlias := []byte{5, 'a', 'l', 'i', 'a', 's', 0xc0, 0x10}
	other := name("bad", "example")
	ip := []byte{203, 0, 113, 10}
	for _, tc := range []struct {
		name    string
		answers [][]byte
		want    bool
	}{
		{name: "direct A", answers: [][]byte{rr(question, 1, ip)}, want: true},
		{name: "case-insensitive owner", answers: [][]byte{rr(name("WWW", "GOOGLE", "COM"), 1, ip)}, want: true},
		{name: "unrelated A", answers: [][]byte{rr(other, 1, ip)}},
		{name: "CNAME chain", answers: [][]byte{rr(question, 5, alias), rr(alias, 1, ip)}, want: true},
		{name: "compressed CNAME", answers: [][]byte{rr(question, 5, compressedAlias), rr(compressedAlias, 1, ip)}, want: true},
		{name: "CNAME answer order", answers: [][]byte{rr(alias, 1, ip), rr(question, 5, alias)}, want: true},
		{name: "unrelated CNAME", answers: [][]byte{rr(question, 5, alias), rr(other, 1, ip)}},
		{name: "CNAME cycle", answers: [][]byte{rr(question, 5, alias), rr(alias, 5, question)}},
		{name: "truncated CNAME", answers: [][]byte{rr(question, 5, []byte{0xc0})}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			reply := append([]byte(nil), query...)
			reply[2], reply[3] = 0x81, 0x80
			binary.BigEndian.PutUint16(reply[6:8], uint16(len(tc.answers)))
			for _, answer := range tc.answers {
				reply = append(reply, answer...)
			}
			if got := validDNSAnswer(query, reply); got != tc.want {
				t.Fatalf("validDNSAnswer() = %v, want %v", got, tc.want)
			}
		})
	}
}
