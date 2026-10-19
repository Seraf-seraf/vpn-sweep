package media

import (
	"context"
	"crypto/sha256"
	"crypto/x509"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/core"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
	"github.com/Seraf-seraf/vpn-sweep/internal/testkit"
)

func box(k string, b []byte) []byte {
	return append(append(binary.BigEndian.AppendUint32(nil, uint32(len(b)+8)), []byte(k)...), b...)
}
func movie() []byte {
	tkhd := make([]byte, 84)
	binary.BigEndian.PutUint32(tkhd[80:], 1080<<16)
	hdlr := make([]byte, 25)
	copy(hdlr[8:], "vide")
	b := box("ftyp", []byte("isom0000"))
	b = append(b, box("moov", box("trak", append(box("tkhd", tkhd), box("mdia", box("hdlr", hdlr))...)))...)
	return append(b, box("mdat", make([]byte, 100000))...)
}
func testPolicy() model.Policy {
	b := movie()
	h := sha256.Sum256(b)
	return model.Policy{Version: Version, Rounds: 2, Video: &model.VideoPolicy{Required: []string{Transfer}, Samples: 3, MinMbps: 0.001, MaxReadGapMS: 3000, SampleBytes: 1 << 20, TimeoutSeconds: 2, Reference: model.MediaReference{URL: "https://example.invalid/movie.mp4", Bytes: int64(len(b)), SHA256: hex.EncodeToString(h[:]), Provenance: "synthetic test", License: "synthetic", Status: "verified_reference"}}}
}
func TestPolicyAndIndependentCapabilities(t *testing.T) {
	p := testPolicy()
	if Validate(p) != nil {
		t.Fatal(Validate(p))
	}
	a := model.Attempt{Restarts: 1}
	for r := 0; r < 2; r++ {
		samples := []model.MediaSample{}
		for i := 0; i < 3; i++ {
			samples = append(samples, model.MediaSample{Bytes: p.Video.Reference.Bytes, DurationNS: 1000000, MaxReadGapNS: 100, ContainerVerified: true, ReferenceVerified: true, TLSVerified: true, Status: 200, SHA256: p.Video.Reference.SHA256})
		}
		a.Results = append(a.Results, model.ProbeResult{Probe: Transfer, Round: r, Outcome: "pass", Transport: "https-tcp", ContentVerified: true, TLSVerified: true, Media: &model.MediaEvidence{Version: 1, Capability: Transfer, Samples: samples}})
	}
	if Evaluate(p, a) != "pass" {
		t.Fatal(Capabilities(p, a))
	}
	p.Video.YouTube = &model.YouTubeReference{VideoID: "abcdefghijk", Permission: "test consent", MinHeight: 1080}
	if Capabilities(p, a)[YouTube] != "partial" || Evaluate(p, a) != "pass" {
		t.Fatal("independence")
	}
	p.Video.Required = append(p.Video.Required, YouTube)
	if Evaluate(p, a) == "pass" {
		t.Fatal("generic media counted as YouTube")
	}
	p.Video.Required = []string{Transfer}
	a.Restarts = 0
	if Evaluate(p, a) == "pass" {
		t.Fatal("restart missing")
	}
	a.Restarts = 1
	a.Results[0].Media.Samples[0].SHA256 = strings.Repeat("0", 64)
	if Evaluate(p, a) == "pass" {
		t.Fatal("false reference hash")
	}
	p.Video.Required = nil
	if Validate(p) == nil {
		t.Fatal("implicit required")
	}
}
func TestMP4Boundaries(t *testing.T) {
	b := movie()
	if !MP4(b, int64(len(b)), int64(len(b)), 1080) {
		t.Fatal("valid movie")
	}
	if MP4(b, int64(len(b)), int64(len(b)), 2160) {
		t.Fatal("resolution")
	}
	for _, bad := range [][]byte{[]byte("<html>video</html>"), box("ftyp", []byte("isom0000")), box("mdat", make([]byte, 100000)), b[:16]} {
		if MP4(bad, int64(len(bad)), int64(len(bad)), 1) {
			t.Fatal("nonmedia accepted")
		}
	}
	for n := 0; n < 150; n++ {
		if MP4(b[:n], int64(n), int64(len(b)), 1) {
			t.Fatal("truncated init", n)
		}
	}
	bad := append([]byte(nil), b...)
	binary.BigEndian.PutUint32(bad[:4], 0xffffffff)
	if MP4(bad, int64(len(bad)), int64(len(bad)), 1) {
		t.Fatal("overflow")
	}
}
func TestStreamingTLSRangeHashNoFallback(t *testing.T) {
	b := movie()
	var hits atomic.Int64
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		w.Header().Set("Content-Type", "video/mp4")
		if r.URL.Path == "/html" {
			w.Header().Set("Content-Type", "text/html")
		}
		if r.URL.Path == "/redirect" {
			http.Redirect(w, r, "https://else.invalid", 302)
			return
		}
		if r.URL.Path == "/partial" {
			w.Header().Set("Content-Length", "999999")
			w.Write(b[:32])
			return
		}
		if r.URL.Path == "/stall" {
			w.Header().Set("Content-Length", "100000")
			w.Write(b[:32])
			w.(http.Flusher).Flush()
			time.Sleep(100 * time.Millisecond)
			return
		}
		w.Header().Set("Content-Length", stringInt(len(b)))
		if r.URL.Path == "/range" {
			w.Header().Set("Content-Range", "bytes 0-"+stringInt(len(b)-1)+"/"+stringInt(len(b)))
			w.WriteHeader(206)
		}
		if r.URL.Path == "/bad-range" {
			w.Header().Set("Content-Range", "bytes 1-2/3")
			w.WriteHeader(206)
		}
		w.Write(b)
	}))
	defer server.Close()
	roots := x509.NewCertPool()
	roots.AddCert(server.Certificate())
	binary, id := testkit.FakeCore(t)
	addr, _ := core.ReserveAddress()
	proxy := socks.Endpoint{Address: addr, User: "user", Pass: "pass"}
	imp, _ := importer.Parse([]byte("socks5://127.0.0.1:9"), "uri")
	cfg, _ := capability.Render(imp.Profiles[0], nil, addr, "user", "pass")
	h, e := (core.Runner{Binary: binary, Identity: id, Directory: t.TempDir(), Startup: time.Second}).Start(context.Background(), cfg, proxy)
	if e != nil {
		t.Fatal(e)
	}
	defer h.Close()
	p := testPolicy()
	v := p.Video
	c := Client{Roots: roots}
	sample, out := c.sample(context.Background(), proxy, server.URL, int64(len(b)), false, 1080, v.Reference.SHA256, v)
	if out != "pass" || !sample.ReferenceVerified {
		t.Fatal(out, sample)
	}
	for _, tc := range []struct {
		path   string
		ranged bool
		want   string
	}{{"/html", false, "media_mime_unsupported"}, {"/redirect", false, "media_request_failed"}, {"/partial", false, "media_size_mismatch"}, {"/bad-range", true, "range_mismatch"}, {"/", true, "endpoint_status_unsupported"}, {"/range", true, "pass"}} {
		_, out = c.sample(context.Background(), proxy, server.URL+tc.path, int64(len(b)), tc.ranged, 1080, "", v)
		if out != tc.want {
			t.Fatal(tc.path, out)
		}
	}
	_, out = c.sample(context.Background(), proxy, server.URL, int64(len(b)), false, 1080, strings.Repeat("0", 64), v)
	if out != "reference_hash_mismatch" {
		t.Fatal(out)
	}
	_, out = (Client{}).sample(context.Background(), proxy, server.URL, int64(len(b)), false, 1080, "", v)
	if out == "pass" {
		t.Fatal("TLS bypass")
	}
	v.MaxReadGapMS = 20
	_, out = c.sample(context.Background(), proxy, server.URL+"/stall", 100000, false, 1080, "", v)
	if out == "pass" {
		t.Fatal("stall accepted")
	}
	h.Close()
	before := hits.Load()
	_, out = c.sample(context.Background(), proxy, server.URL, int64(len(b)), false, 1080, "", v)
	if out == "pass" || hits.Load() != before {
		t.Fatal("direct fallback")
	}
}
func stringInt(n int) string { b, _ := json.Marshal(n); return string(b) }
func TestResolverURLGate(t *testing.T) {
	for _, bad := range []string{"https://googlevideo.com.evil.invalid/videoplayback", "https://storage.googleapis.com/movie.mp4", "http://r.googlevideo.com/videoplayback", "https://a:b@r.googlevideo.com/videoplayback", "https://r.googlevideo.com:444/videoplayback", "https://r.googlevideo.com/thumbnail"} {
		if YouTubeURL(bad) {
			t.Fatal(bad)
		}
	}
	if !YouTubeURL("https://rr1.googlevideo.com/videoplayback?expire=synthetic") {
		t.Fatal("valid")
	}
}
func TestResolverProcessTimeout(t *testing.T) {
	// No shell invocation or real dependency/network required by the adapter test.
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	cmd := exec.Command("sh", "-c", "sleep 60 & wait")
	started := time.Now()
	if runProcess(ctx, cmd) == nil || time.Since(started) > 2*time.Second {
		t.Fatal("process cleanup")
	}
}

func TestResolverOutputBound(t *testing.T) {
	w := &limitedOutput{}
	if _, e := w.Write(make([]byte, 16384)); e != nil {
		t.Fatal(e)
	}
	if _, e := w.Write([]byte("synthetic signed secret")); e == nil {
		t.Fatal("unbounded output")
	}
}

func TestResolverIdentityAndBlockedSOCKS(t *testing.T) {
	python := os.Getenv("VPN_SWEEP_TEST_PYTHON")
	js := os.Getenv("VPN_SWEEP_TEST_JS")
	if python == "" || js == "" {
		t.Skip("explicit optional isolated dependencies not provided")
	}
	r := Resolver{Python: python, JS: js}
	id, e := r.Identify(context.Background())
	if e != nil {
		t.Fatal(e)
	}
	r.Identity = id
	// A closed loopback port makes any attempted resolver request fail locally.
	addr, e := core.ReserveAddress()
	if e != nil {
		t.Fatal(e)
	}
	result, e := r.Resolve(context.Background(), socks.Endpoint{Address: addr, User: "synthetic-secret-user", Pass: "synthetic-secret-password"}, model.YouTubeReference{VideoID: "abcdefghijk", MinHeight: 1080})
	if e == nil || model.Code(e) != "resolver_network_failed" || result.NetworkBytes > MaxResolverBytes {
		t.Fatal("resolver fallback", model.Code(e))
	}
	r.Identity.PackageSHA256 = "changed"
	_, e = r.Resolve(context.Background(), socks.Endpoint{Address: addr, User: "user", Pass: "pass"}, model.YouTubeReference{VideoID: "abcdefghijk", MinHeight: 1080})
	if model.Code(e) != "resolver_identity_changed" {
		t.Fatal("frozen identity", model.Code(e))
	}
}

func TestPolicyMeasurementBoundaryMatrix(t *testing.T) {
	for _, tc := range []struct {
		name   string
		mutate func(*model.Policy)
	}{
		{"zero_samples", func(p *model.Policy) { p.Video.Samples = 0 }},
		{"too_many_samples", func(p *model.Policy) { p.Video.Samples = 11 }},
		{"below_min_bytes", func(p *model.Policy) { p.Video.SampleBytes = 65535 }},
		{"above_max_bytes", func(p *model.Policy) { p.Video.SampleBytes = MaxSampleBytes + 1 }},
		{"zero_gap", func(p *model.Policy) { p.Video.MaxReadGapMS = 0 }},
		{"zero_speed", func(p *model.Policy) { p.Video.MinMbps = 0 }},
		{"hash_missing", func(p *model.Policy) { p.Video.Reference.SHA256 = "" }},
		{"youtube_without_reference", func(p *model.Policy) { p.Video.Required = []string{YouTube} }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			p := testPolicy()
			tc.mutate(&p)
			if Validate(p) == nil {
				t.Fatal("invalid policy accepted")
			}
		})
	}
	p := testPolicy()
	p.Video.SampleBytes = MaxSampleBytes
	p.Video.Samples = 10
	p.Video.MinMbps = 10000
	p.Video.MaxReadGapMS = 30000
	p.Video.TimeoutSeconds = 300
	if Validate(p) != nil {
		t.Fatal("boundary rejected")
	}
}

func TestResolverKillsOwnedChildrenNotUnrelatedProcess(t *testing.T) {
	unrelated := exec.Command("sleep", "60")
	if e := unrelated.Start(); e != nil {
		t.Fatal(e)
	}
	defer func() { unrelated.Process.Kill(); unrelated.Wait() }()
	pidFile := filepath.Join(t.TempDir(), "child-pid")
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	cmd := exec.Command("sh", "-c", `sleep 60 & printf '%s' "$!" > "$1"; wait`, "owned", pidFile)
	if runProcess(ctx, cmd) == nil {
		t.Fatal("timeout expected")
	}
	b, e := os.ReadFile(pidFile)
	if e != nil {
		t.Fatal(e)
	}
	pid, e := strconv.Atoi(string(b))
	if e != nil {
		t.Fatal(e)
	}
	for i := 0; i < 50; i++ {
		stat, e := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
		if os.IsNotExist(e) {
			break
		}
		if e == nil {
			end := strings.LastIndex(string(stat), ")")
			if end >= 0 && strings.HasPrefix(string(stat[end+1:]), " Z") {
				break
			}
		}
		if i == 49 {
			t.Fatal("owned child still running")
		}
		time.Sleep(10 * time.Millisecond)
	}
	if e := unrelated.Process.Signal(syscall.Signal(0)); e != nil {
		t.Fatal("foreign process affected", e)
	}
}
