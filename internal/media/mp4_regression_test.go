package media

import (
	"context"
	"crypto/x509"
	"encoding/binary"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/core"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
	"github.com/Seraf-seraf/vpn-sweep/internal/testkit"
)

func fullBoxMovie(tkhd, hdlr []byte) []byte {
	b := box("ftyp", []byte("isom0000"))
	b = append(b, box("moov", box("trak", append(box("tkhd", tkhd), box("mdia", box("hdlr", hdlr))...)))...)
	return append(b, box("mdat", make([]byte, 100000))...)
}
func trackHeader(version byte) []byte {
	size := 84
	offset := 80
	if version == 1 {
		size = 96
		offset = 92
	}
	b := make([]byte, size)
	b[0] = version
	binary.BigEndian.PutUint32(b[offset:], 1080<<16)
	return b
}
func handlerHeader() []byte { b := make([]byte, 25); copy(b[8:12], "vide"); return b }
func TestMalformedFullBox(t *testing.T) {
	b := movie()
	b[40] = 255
	if MP4(b, int64(len(b)), int64(len(b)), 1080) {
		t.Fatal("invalid tkhd version accepted")
	}
}
func TestMP4FullBoxVersionsSizesAndOffsets(t *testing.T) {
	for _, v := range []byte{0, 1} {
		t.Run(fmt.Sprint(v), func(t *testing.T) {
			header := trackHeader(v)
			b := fullBoxMovie(header, handlerHeader())
			if !MP4(b, int64(len(b)), int64(len(b)), 1080) {
				t.Fatal("valid version rejected")
			}
			for n := 0; n < len(header); n++ {
				b = fullBoxMovie(header[:n], handlerHeader())
				if MP4(b, int64(len(b)), int64(len(b)), 1080) {
					t.Fatalf("truncated tkhd %d accepted", n)
				}
			}
			for _, n := range []int{1, 4, 12} {
				b = fullBoxMovie(append(append([]byte(nil), header...), make([]byte, n)...), handlerHeader())
				if MP4(b, int64(len(b)), int64(len(b)), 1080) {
					t.Fatal("oversized tkhd accepted")
				}
			}
		})
	}
	for _, v := range []byte{2, 255} {
		b := fullBoxMovie(trackHeader(v), handlerHeader())
		if MP4(b, int64(len(b)), int64(len(b)), 1080) {
			t.Fatal("unknown version accepted", v)
		}
	}
	// v1 stores height at payload offset92, never at the v0 offset80 or last bytes of an invalid size.
	b := trackHeader(1)
	binary.BigEndian.PutUint32(b[80:], 2160<<16)
	binary.BigEndian.PutUint32(b[92:], 720<<16)
	m := fullBoxMovie(b, handlerHeader())
	if MP4(m, int64(len(m)), int64(len(m)), 1080) {
		t.Fatal("wrong version height offset")
	}
	for n := 0; n < 24; n++ {
		m = fullBoxMovie(trackHeader(0), handlerHeader()[:n])
		if MP4(m, int64(len(m)), int64(len(m)), 1080) {
			t.Fatalf("truncated hdlr header %d accepted", n)
		}
	}
	for _, v := range []byte{1, 255} {
		h := handlerHeader()
		h[0] = v
		m = fullBoxMovie(trackHeader(0), h)
		if MP4(m, int64(len(m)), int64(len(m)), 1080) {
			t.Fatal("invalid hdlr version accepted", v)
		}
	}
	for _, offset := range []int{1, 4, 12, 23} {
		h := handlerHeader()
		h[offset] = 1
		m = fullBoxMovie(trackHeader(0), h)
		if MP4(m, int64(len(m)), int64(len(m)), 1080) {
			t.Fatal("invalid hdlr flags/reserved field", offset)
		}
	}
}
func TestMalformedYouTubeSample(t *testing.T) {
	b := movie()
	b[40] = 255
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", stringInt(len(b)))
		w.Header().Set("Content-Type", "video/mp4")
		w.Header().Set("Content-Range", "bytes 0-"+stringInt(len(b)-1)+"/"+stringInt(len(b)))
		w.WriteHeader(206)
		w.Write(b)
	}))
	defer server.Close()
	roots := x509.NewCertPool()
	roots.AddCert(server.Certificate())
	binary, id := testkit.FakeCore(t)
	addr, _ := core.ReserveAddress()
	proxy := socks.Endpoint{Address: addr, User: "user", Pass: "pass"}
	imp, _ := importer.Parse([]byte("socks5://127.0.0.1:9"), "uri")
	cfg, e := capability.Render(imp.Profiles[0], nil, addr, "user", "pass")
	if e != nil {
		t.Fatal(e)
	}
	h, e := (core.Runner{Binary: binary, Identity: id, Directory: t.TempDir(), Startup: time.Second}).Start(context.Background(), cfg, proxy)
	if e != nil {
		t.Fatal(e)
	}
	defer h.Close()
	sample, outcome := (Client{Roots: roots}).sample(context.Background(), proxy, server.URL, int64(len(b)), true, 1080, "", testPolicy().Video)
	if outcome != "container_unsupported_or_invalid" || sample.ContainerVerified || sample.ReferenceVerified {
		t.Fatal("malformed media false success", outcome, sample)
	}
	if !sample.TLSVerified || sample.Status != 206 || sample.Bytes != int64(len(b)) {
		t.Fatal("test did not reach media validation", sample)
	}
}
