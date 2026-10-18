package probe

import (
	"bytes"
	"context"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
	"time"
)

func UDPEcho(ctx context.Context, proxy socks.Endpoint, id, address string, round int) model.ProbeResult {
	start := time.Now()
	payload := []byte("vpn-sweep-echo-v1:" + model.ID())
	reply, e := proxy.UDPEcho(ctx, address, payload)
	r := model.ProbeResult{ID: model.ID(), Probe: id, Round: round, Transport: "udp-socks5", Phase: "udp", Bytes: int64(len(reply)), DurationMS: time.Since(start).Milliseconds()}
	if e != nil {
		r.Outcome = model.Code(e)
	} else if !bytes.Equal(reply, payload) {
		r.Outcome = "udp_content_mismatch"
	} else {
		r.Outcome = "pass"
		r.Phase = "complete"
	}
	return r
}
func QUICUnsupported(id string, round int) model.ProbeResult {
	return model.ProbeResult{ID: model.ID(), Probe: id, Round: round, Transport: "quic", Phase: "capability", Outcome: "unsupported"}
}
