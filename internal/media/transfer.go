package media

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"io"
	"mime"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
)

const prefixLimit = 1 << 20

// MP4 checks structure and a video track, not codec decoding or playback.
func MP4(prefix []byte, received, total int64, minHeight int) bool {
	ftyp, video, mdat := false, false, false
	for off := int64(0); off < received; {
		if off+8 > int64(len(prefix)) {
			return false
		}
		n := int64(binary.BigEndian.Uint32(prefix[off : off+4]))
		head := int64(8)
		if n == 1 {
			if off+16 > int64(len(prefix)) {
				return false
			}
			u := binary.BigEndian.Uint64(prefix[off+8 : off+16])
			if u > uint64(total) {
				return false
			}
			n = int64(u)
			head = 16
		}
		if n < head || n > total-off {
			return false
		}
		kind := string(prefix[off+4 : off+8])
		switch kind {
		case "ftyp":
			ftyp = n >= 16
		case "moov":
			if off+n > int64(len(prefix)) {
				return false
			}
			video = videoTrack(prefix[off+head:off+n], minHeight)
		case "mdat":
			if received-off-head < 65536 {
				return false
			}
			mdat = true
		}
		if off+n > received {
			return kind == "mdat" && ftyp && video && mdat
		}
		off += n
		if off == received {
			return ftyp && video && mdat
		}
	}
	return false
}
func boxes(b []byte, visit func(string, []byte)) bool {
	for len(b) > 0 {
		if len(b) < 8 {
			return false
		}
		n := int(binary.BigEndian.Uint32(b))
		if n < 8 || n > len(b) {
			return false
		}
		visit(string(b[4:8]), b[8:n])
		b = b[n:]
	}
	return true
}

// tkhd FullBox payload sizes include version/flags. The v1 time fields are
// wider, moving the 16.16 height field from byte80 to byte92.
func trackHeight(data []byte) (int, bool) {
	const v0Bytes, v1Bytes = 84, 96
	if len(data) < 4 || data[1] != 0 || data[2] != 0 || data[3]&^byte(15) != 0 {
		return 0, false
	}
	offset := 0
	switch data[0] {
	case 0:
		if len(data) != v0Bytes {
			return 0, false
		}
		offset = 80
	case 1:
		if len(data) != v1Bytes {
			return 0, false
		}
		offset = 92
	default:
		return 0, false
	}
	return int(binary.BigEndian.Uint32(data[offset:offset+4]) >> 16), true
}
func handlerType(data []byte) (string, bool) {
	// FullBox4 + pre_defined4 + handler_type4 + reserved12; optional name follows.
	if len(data) < 24 {
		return "", false
	}
	for _, v := range data[:8] {
		if v != 0 {
			return "", false
		}
	}
	for _, v := range data[12:24] {
		if v != 0 {
			return "", false
		}
	}
	return string(data[8:12]), true
}
func videoTrack(b []byte, minHeight int) bool {
	video, headersValid := false, true
	ok := boxes(b, func(k string, track []byte) {
		if k != "trak" {
			return
		}
		height := 0
		tkhdSeen, mdiaSeen, hdlrSeen := false, false, false
		handler := ""
		good := boxes(track, func(k string, data []byte) {
			switch k {
			case "tkhd":
				if tkhdSeen {
					headersValid = false
				}
				tkhdSeen = true
				h, valid := trackHeight(data)
				if !valid {
					headersValid = false
				}
				height = h
			case "mdia":
				if mdiaSeen {
					headersValid = false
				}
				mdiaSeen = true
				if !boxes(data, func(k string, d []byte) {
					if k != "hdlr" {
						return
					}
					if hdlrSeen {
						headersValid = false
					}
					hdlrSeen = true
					h, valid := handlerType(d)
					if !valid {
						headersValid = false
					}
					handler = h
				}) {
					headersValid = false
				}
			}
		})
		if !good || !tkhdSeen || !mdiaSeen || !hdlrSeen {
			headersValid = false
		}
		if handler == "vide" && height > 0 && height >= minHeight {
			video = true
		}
	})
	return ok && headersValid && video
}

type Client struct {
	Roots    *x509.CertPool
	Resolver *Resolver
}

func (c Client) sample(ctx context.Context, proxy socks.Endpoint, raw string, n int64, ranged bool, minHeight int, wantHash string, v *model.VideoPolicy) (s model.MediaSample, outcome string) {
	started := time.Now()
	defer func() { s.DurationNS = time.Since(started).Nanoseconds() }()
	ctx, cancel := context.WithTimeout(ctx, time.Duration(v.TimeoutSeconds)*time.Second)
	defer cancel()
	tr := &http.Transport{Proxy: nil, DialContext: proxy.DialContext, TLSClientConfig: &tls.Config{RootCAs: c.Roots, MinVersion: tls.VersionTLS12}, DisableKeepAlives: true, DisableCompression: true}
	defer tr.CloseIdleConnections()
	client := &http.Client{Transport: tr, CheckRedirect: func(*http.Request, []*http.Request) error { return model.Err("redirect_unsupported", "media") }}
	req, e := http.NewRequestWithContext(ctx, "GET", raw, nil)
	if e != nil || !HTTPS(raw) {
		return s, "endpoint_unsupported"
	}
	req.Header.Set("Accept-Encoding", "identity")
	if ranged {
		req.Header.Set("Range", fmt.Sprintf("bytes=0-%d", n-1))
	}
	resp, e := client.Do(req)
	if e != nil {
		if ctx.Err() != nil {
			return s, "transfer_timeout"
		}
		return s, "media_request_failed"
	}
	defer resp.Body.Close()
	s.Status = resp.StatusCode
	s.TLSVerified = resp.TLS != nil && len(resp.TLS.VerifiedChains) > 0
	status := 200
	if ranged {
		status = 206
	}
	if resp.StatusCode != status {
		return s, "endpoint_status_unsupported"
	}
	if !s.TLSVerified {
		return s, "tls_failure"
	}
	mt, _, e := mime.ParseMediaType(resp.Header.Get("Content-Type"))
	if e != nil || mt != "video/mp4" || resp.Header.Get("Content-Encoding") != "" {
		return s, "media_mime_unsupported"
	}
	total := n
	if ranged {
		cr := resp.Header.Get("Content-Range")
		parts := strings.Split(cr, "/")
		if len(parts) != 2 || parts[0] != fmt.Sprintf("bytes 0-%d", n-1) {
			return s, "range_mismatch"
		}
		total, e = strconv.ParseInt(parts[1], 10, 64)
		if e != nil || total < n {
			return s, "range_mismatch"
		}
	}
	if resp.ContentLength != n {
		return s, "media_size_mismatch"
	}
	h := sha256.New()
	prefix := make([]byte, 0, prefixLimit)
	buf := make([]byte, 32<<10)
	last := time.Now()
	gapLimit := time.Duration(v.MaxReadGapMS) * time.Millisecond
	timer := time.AfterFunc(gapLimit, cancel)
	defer timer.Stop()
	for s.Bytes <= n {
		readStart := last
		got, err := resp.Body.Read(buf[:min(int64(len(buf)), n-s.Bytes+1)])
		now := time.Now()
		gap := now.Sub(readStart)
		if gap.Nanoseconds() > s.MaxReadGapNS {
			s.MaxReadGapNS = gap.Nanoseconds()
		}
		if got > 0 {
			s.Bytes += int64(got)
			h.Write(buf[:got])
			keep := min(got, prefixLimit-len(prefix))
			prefix = append(prefix, buf[:keep]...)
			last = now
			timer.Reset(gapLimit)
		}
		if gap > gapLimit {
			return s, "read_gap_exceeded"
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			if ctx.Err() != nil {
				return s, "transfer_timeout"
			}
			return s, "transfer_failed"
		}
		if got == 0 && ctx.Err() != nil {
			return s, "transfer_timeout"
		}
	}
	if s.Bytes != n {
		return s, "media_size_mismatch"
	}
	s.SHA256 = hex.EncodeToString(h.Sum(nil))
	s.ReferenceVerified = wantHash != "" && s.SHA256 == wantHash
	if wantHash != "" && !s.ReferenceVerified {
		return s, "reference_hash_mismatch"
	}
	s.ContainerVerified = MP4(prefix, n, total, minHeight)
	if !s.ContainerVerified {
		return s, "container_unsupported_or_invalid"
	}
	if float64(n)*8/time.Since(started).Seconds()/1e6 < v.MinMbps {
		return s, "throughput_below_threshold"
	}
	return s, "pass"
}
func (c Client) Round(ctx context.Context, proxy socks.Endpoint, p model.Policy, round int) []model.ProbeResult {
	v := p.Video
	out := []model.ProbeResult{}
	for _, capability := range []string{Transfer, YouTube} {
		if capability == YouTube && v.YouTube == nil {
			continue
		}
		r := model.ProbeResult{ID: model.ID(), Probe: capability, Round: round, Transport: "https-tcp", Phase: "media", Outcome: "pass", Media: &model.MediaEvidence{Version: 1, Capability: capability}}
		started := time.Now()
		raw := v.Reference.URL
		n := v.Reference.Bytes
		hash := v.Reference.SHA256
		height := 1
		if capability == Transfer && v.Reference.Status != "verified_reference" {
			r.Outcome = "reference_unverified"
		}
		if capability == YouTube {
			if c.Resolver == nil {
				r.Outcome = "resolver_unavailable"
			} else {
				var resolved Resolved
				var e error
				resolved, e = c.Resolver.Resolve(ctx, proxy, *v.YouTube)
				r.Bytes += resolved.NetworkBytes
				if e != nil {
					r.Outcome = model.Code(e)
					r.Phase = "resolver"
				} else {
					raw = resolved.URL
					n = v.SampleBytes
					hash = ""
					height = v.YouTube.MinHeight
					r.Media.FormatID = resolved.FormatID
					r.Media.Height = resolved.Height
				}
			}
		}
		if r.Outcome == "pass" {
			for i := 0; i < v.Samples; i++ {
				s, outcome := c.sample(ctx, proxy, raw, n, capability == YouTube, height, hash, v)
				r.Bytes += s.Bytes
				r.Media.Samples = append(r.Media.Samples, s)
				if outcome != "pass" {
					r.Outcome = outcome
					break
				}
			}
		}
		r.DurationMS = time.Since(started).Milliseconds()
		r.TLSVerified = r.Outcome == "pass"
		r.ContentVerified = r.Outcome == "pass"
		out = append(out, r)
	}
	return out
}
func Blocked(outcome string) bool {
	switch outcome {
	case "resolver_auth_or_challenge", "resolver_token_required", "reference_unverified", "resolver_unavailable", "resolver_blocked", "resolver_identity_changed", "resolver_invalid_output", "resolver_output_limit", "resolver_unsupported", "endpoint_status_unsupported", "media_mime_unsupported", "container_unsupported_or_invalid", "range_mismatch":
		return true
	}
	return false
}
