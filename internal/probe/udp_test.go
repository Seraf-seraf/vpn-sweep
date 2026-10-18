package probe

import (
	"context"
	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/core"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
	"github.com/Seraf-seraf/vpn-sweep/internal/testkit"
	"net"
	"testing"
	"time"
)

func TestUDPSeparateFromQUIC(t *testing.T) {
	echo, e := net.ListenPacket("udp4", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	defer echo.Close()
	go func() {
		b := make([]byte, 2048)
		for {
			n, a, e := echo.ReadFrom(b)
			if e != nil {
				return
			}
			echo.WriteTo(b[:n], a)
		}
	}()
	binary, id := testkit.FakeCore(t)
	addr, _ := core.ReserveAddress()
	proxy := socks.Endpoint{Address: addr, User: "u", Pass: "p"}
	imp, _ := importer.Parse([]byte("socks5://127.0.0.1:9"), "uri")
	cfg, _ := capability.Render(imp.Profiles[0], nil, addr, "u", "p")
	h, e := (core.Runner{Binary: binary, Identity: id, Directory: t.TempDir()}).Start(context.Background(), cfg, proxy)
	if e != nil {
		t.Fatal(e)
	}
	defer h.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	r := UDPEcho(ctx, proxy, "udp", echo.LocalAddr().String(), 0)
	if r.Outcome != "pass" || r.Transport != "udp-socks5" {
		t.Fatal(r)
	}
	q := QUICUnsupported("q", 0)
	if q.Outcome != "unsupported" || q.Transport != "quic" {
		t.Fatal("TCP masqueraded as QUIC")
	}
	h.Close()
	ctx2, cancel2 := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel2()
	if r = UDPEcho(ctx2, proxy, "udp", echo.LocalAddr().String(), 0); r.Outcome == "pass" {
		t.Fatal("UDP direct fallback")
	}
}
