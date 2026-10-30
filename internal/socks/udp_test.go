package socks

import (
	"context"
	"encoding/binary"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"io"
	"net"
	"testing"
	"time"
)

func TestUDPExchangeChecksSourceAndLimit(t *testing.T) {
	for _, tc := range []struct {
		name    string
		source  net.IP
		port    uint16
		size    int
		legacy  bool
		wantErr string
	}{
		{name: "valid", source: net.IPv4(192, 0, 2, 1), port: 53, size: 512},
		{name: "wrong source", source: net.IPv4(192, 0, 2, 2), port: 53, size: 1, wantErr: "udp_reply_source_mismatch"},
		{name: "wrong port", source: net.IPv4(192, 0, 2, 1), port: 54, size: 1, wantErr: "udp_reply_source_mismatch"},
		{name: "over limit", source: net.IPv4(192, 0, 2, 1), port: 53, size: 513, wantErr: "udp_reply_too_large"},
		{name: "truncated datagram", source: net.IPv4(192, 0, 2, 1), port: 53, size: 1024, wantErr: "udp_reply_too_large"},
		{name: "legacy echo", source: net.IPv4(192, 0, 2, 2), port: 53, size: 1, legacy: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			tcp, err := net.Listen("tcp4", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			defer tcp.Close()
			udp, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
			if err != nil {
				t.Fatal(err)
			}
			defer udp.Close()
			if err := udp.SetDeadline(time.Now().Add(2 * time.Second)); err != nil {
				t.Fatal(err)
			}
			done := make(chan error, 1)
			go func() {
				conn, err := tcp.Accept()
				if err != nil {
					done <- err
					return
				}
				defer conn.Close()
				_ = conn.SetDeadline(time.Now().Add(2 * time.Second))
				buf := make([]byte, 10)
				if _, err = io.ReadFull(conn, buf[:3]); err == nil {
					_, err = conn.Write([]byte{5, 2})
				}
				if err == nil {
					_, err = io.ReadFull(conn, buf[:2])
				}
				if err == nil {
					_, err = io.ReadFull(conn, make([]byte, int(buf[1])+1))
				}
				if err == nil {
					_, err = io.ReadFull(conn, buf[:1])
				}
				if err == nil {
					_, err = conn.Write([]byte{1, 0})
				}
				if err == nil {
					_, err = io.ReadFull(conn, buf[:10])
				}
				if err != nil {
					done <- err
					return
				}
				port := udp.LocalAddr().(*net.UDPAddr).Port
				_, err = conn.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, byte(port >> 8), byte(port)})
				if err != nil {
					done <- err
					return
				}
				packet := make([]byte, 2048)
				_, peer, err := udp.ReadFromUDP(packet)
				if err != nil {
					done <- err
					return
				}
				reply := append([]byte{0, 0, 0, 1}, tc.source.To4()...)
				reply = binary.BigEndian.AppendUint16(reply, tc.port)
				reply = append(reply, make([]byte, tc.size)...)
				_, err = udp.WriteToUDP(reply, peer)
				done <- err
			}()
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			endpoint := Endpoint{Address: tcp.Addr().String(), User: "u", Pass: "p"}
			var reply []byte
			if tc.legacy {
				reply, err = endpoint.UDPEcho(ctx, "192.0.2.1:53", []byte("q"))
			} else {
				reply, err = endpoint.UDPExchange(ctx, "192.0.2.1:53", []byte("q"), 512)
			}
			if tc.wantErr == "" && (err != nil || len(reply) != tc.size) || tc.wantErr != "" && model.Code(err) != tc.wantErr {
				t.Fatalf("reply size %d, error %v", len(reply), err)
			}
			if err := <-done; err != nil {
				t.Fatal(err)
			}
		})
	}
}
