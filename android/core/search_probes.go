package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"math"
	"net"
	"strconv"
	"sync"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/internal/media"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/policy"
	"github.com/Seraf-seraf/vpn-sweep/internal/probe"
	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
)

type checkClient interface {
	Check(context.Context, socks.Endpoint, model.Probe, int) model.ProbeResult
}

type mediaClient interface {
	Round(context.Context, socks.Endpoint, model.Policy, int) []model.ProbeResult
}

type SearchWorkerProbes struct {
	mu     sync.Mutex
	ctx    context.Context
	cancel context.CancelFunc
	owner  *protection
	proxy  socks.Endpoint
	check  checkClient
	media  mediaClient
	udp    func(context.Context, socks.Endpoint, model.UDPProbe, int) model.ProbeResult
}

func newSearchWorkerProbes(address, user, password string) (*SearchWorkerProbes, error) {
	host, port, err := net.SplitHostPort(address)
	ip := net.ParseIP(host)
	portNumber, portErr := strconv.Atoi(port)
	if err != nil || ip == nil || !ip.IsLoopback() || portErr != nil || portNumber < 1 || portNumber > 65535 ||
		len(user) < 1 || len(user) > 255 || len(password) < 1 || len(password) > 255 {
		return nil, errors.New("invalid_search_proxy")
	}
	owner := activeProtection.Load()
	endpoint, err := searchProxy(owner, address, user, password)
	if err != nil {
		return nil, err
	}
	if !searchCurrent(owner) || owner.searchContext == nil {
		return nil, errors.New("search_network_unavailable")
	}
	ctx, cancel := context.WithCancel(owner.searchContext)
	return &SearchWorkerProbes{ctx: ctx, cancel: cancel, owner: owner, proxy: endpoint, check: probe.Client{}, media: media.Client{}, udp: runWorkerUDP}, nil
}

func (w *SearchWorkerProbes) Authenticate(remainingMillis int64) error {
	ctx, cancel, err := w.deadline(remainingMillis)
	if err != nil {
		return err
	}
	defer cancel()
	w.mu.Lock()
	defer w.mu.Unlock()
	if err := w.contextError(ctx); err != nil {
		return err
	}
	conn, err := w.proxy.Negotiate(ctx)
	if err := w.contextError(ctx); err != nil {
		if conn != nil {
			_ = conn.Close()
		}
		return err
	}
	if !searchCurrent(w.owner) {
		if conn != nil {
			_ = conn.Close()
		}
		return errors.New("search_owner_lost")
	}
	if err := w.contextError(ctx); err != nil {
		if conn != nil {
			_ = conn.Close()
		}
		return err
	}
	if err != nil {
		return errors.New("search_proxy_not_ready")
	}
	if err = conn.Close(); err != nil {
		return errors.New("search_proxy_cleanup_failed")
	}
	return nil
}

func (w *SearchWorkerProbes) Check(source []byte, round int, remainingMillis int64) ([]byte, error) {
	ctx, cancel, err := w.deadline(remainingMillis)
	if err != nil {
		return nil, err
	}
	defer cancel()
	w.mu.Lock()
	defer w.mu.Unlock()
	var request model.Probe
	if round < 0 || model.Decode(source, &request) != nil || probe.Validate(request) != nil {
		return nil, errors.New("invalid_probe")
	}
	if err := w.contextError(ctx); err != nil {
		return nil, err
	}
	result := w.check.Check(ctx, w.proxy, request, round)
	if err := w.contextError(ctx); err != nil {
		return nil, err
	}
	if !searchCurrent(w.owner) {
		return nil, errors.New("search_owner_lost")
	}
	if err := w.contextError(ctx); err != nil {
		return nil, err
	}
	return encodeProbeResult(result)
}

func (w *SearchWorkerProbes) UDP(source []byte, round int, remainingMillis int64) ([]byte, error) {
	ctx, cancel, err := w.deadline(remainingMillis)
	if err != nil {
		return nil, err
	}
	defer cancel()
	w.mu.Lock()
	defer w.mu.Unlock()
	var request model.UDPProbe
	if round < 0 || model.Decode(source, &request) != nil || !validWorkerUDP(request) {
		return nil, errors.New("invalid_udp_probe")
	}
	if err := w.contextError(ctx); err != nil {
		return nil, err
	}
	result := w.udp(ctx, w.proxy, request, round)
	if err := w.contextError(ctx); err != nil {
		return nil, err
	}
	if !searchCurrent(w.owner) {
		return nil, errors.New("search_owner_lost")
	}
	if err := w.contextError(ctx); err != nil {
		return nil, err
	}
	return encodeProbeResult(result)
}

func (w *SearchWorkerProbes) Media(source []byte, round int, remainingMillis int64) ([]byte, error) {
	ctx, cancel, err := w.deadline(remainingMillis)
	if err != nil {
		return nil, err
	}
	defer cancel()
	w.mu.Lock()
	defer w.mu.Unlock()
	var request model.Policy
	if round < 0 || model.Decode(source, &request) != nil || policy.Validate(request) != nil {
		return nil, errors.New("invalid_media_policy")
	}
	if err := w.contextError(ctx); err != nil {
		return nil, err
	}
	result := w.media.Round(ctx, w.proxy, request, round)
	if err := w.contextError(ctx); err != nil {
		return nil, err
	}
	if !searchCurrent(w.owner) {
		return nil, errors.New("search_owner_lost")
	}
	if err := w.contextError(ctx); err != nil {
		return nil, err
	}
	return encodeProbeResult(result)
}

func (w *SearchWorkerProbes) Cancel() { w.cancel() }

func (w *SearchWorkerProbes) WaitStopped() bool {
	<-w.ctx.Done()
	return !searchCurrent(w.owner)
}

func (w *SearchWorkerProbes) deadline(millis int64) (context.Context, context.CancelFunc, error) {
	if millis < 0 || millis > math.MaxInt64/int64(time.Millisecond) {
		return nil, nil, errors.New("invalid_deadline")
	}
	if err := w.contextError(w.ctx); err != nil {
		return nil, nil, err
	}
	if millis == 0 {
		return nil, nil, context.DeadlineExceeded
	}
	ctx, cancel := context.WithTimeout(w.ctx, time.Duration(millis)*time.Millisecond)
	if !searchCurrent(w.owner) {
		cancel()
		return nil, nil, errors.New("search_owner_lost")
	}
	if err := w.contextError(ctx); err != nil {
		cancel()
		return nil, nil, err
	}
	return ctx, cancel, nil
}

func (w *SearchWorkerProbes) contextError(ctx context.Context) error {
	owner := w.owner
	if owner == nil || owner.search == nil {
		return errors.New("search_owner_lost")
	}
	if activeProtection.Load() != owner {
		owner.revokeSearch()
	}
	if owner.searchRevoked.Load() {
		return errors.New("search_owner_lost")
	}
	err := ctx.Err()
	if err == nil {
		return nil
	}
	if activeProtection.Load() != owner {
		owner.revokeSearch()
	}
	if owner.searchRevoked.Load() {
		return errors.New("search_owner_lost")
	}
	return err
}

func validWorkerUDP(p model.UDPProbe) bool {
	host, port, err := net.SplitHostPort(p.Address)
	portNumber, portErr := strconv.Atoi(port)
	return err == nil && portErr == nil && host != "" && portNumber >= 1 && portNumber <= 65535 &&
		p.ID != "" && p.TimeoutSeconds >= 1 && p.TimeoutSeconds <= 30
}

func runWorkerUDP(ctx context.Context, proxy socks.Endpoint, p model.UDPProbe, round int) model.ProbeResult {
	switch p.Kind {
	case "":
		return probe.UDPEcho(ctx, proxy, p.ID, p.Address, round)
	case "dns-a":
		return probe.UDPDNS(ctx, proxy, p.ID, p.Address, round)
	default:
		return model.ProbeResult{ID: model.ID(), Probe: p.ID, Round: round, Transport: "udp-socks5", Outcome: "unsupported", Phase: "capability"}
	}
}

func encodeProbeResult(value any) ([]byte, error) {
	result, err := json.Marshal(value)
	if err != nil {
		return nil, errors.New("probe_result_encoding_failed")
	}
	return result, nil
}
