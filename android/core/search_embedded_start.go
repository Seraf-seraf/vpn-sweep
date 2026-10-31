package mobile

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"io"
	"net"
	"strconv"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/android/core/searchdns"
	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/dns"
	xserial "github.com/xtls/xray-core/infra/conf/serial"
)

const (
	searchReadinessInterval = 25 * time.Millisecond
	searchReadinessTimeout  = 200 * time.Millisecond
)

type searchRunningCore interface {
	searchCore
	Start() error
}

type searchLaunchPorts struct {
	reserve   func(context.Context, *protection) (string, error)
	newCore   func(context.Context, *core.Config) (searchRunningCore, error)
	negotiate func(context.Context, *SearchWorkerProbes) (net.Conn, error)
}

func startSearchEmbedded(startup, attempt context.Context, owner *protection, profile model.Profile, params map[string]any) (searchCore, *SearchWorkerProbes, error) {
	ports := searchLaunchPorts{
		reserve: reserveSearchAddress,
		newCore: newSearchXray,
		negotiate: func(ctx context.Context, probes *SearchWorkerProbes) (net.Conn, error) {
			return probes.proxy.Negotiate(ctx)
		},
	}
	return launchSearchEmbedded(startup, attempt, owner, profile, params, ports)
}

func launchSearchEmbedded(startup, attempt context.Context, owner *protection, profile model.Profile, params map[string]any, ports searchLaunchPorts) (searchCore, *SearchWorkerProbes, error) {
	if err := startup.Err(); err != nil {
		return nil, nil, err
	}
	if !searchCurrent(owner) {
		return nil, nil, &searchEmbeddedError{SearchWorkerOwnerLost}
	}
	address, err := ports.reserve(startup, owner)
	if err != nil {
		return nil, nil, err
	}
	if err := startup.Err(); err != nil {
		return nil, nil, err
	}
	var credentials [32]byte
	if _, err := io.ReadFull(rand.Reader, credentials[:]); err != nil {
		return nil, nil, &searchEmbeddedError{SearchWorkerCapabilityUnavailable}
	}
	user, password := hex.EncodeToString(credentials[:16]), hex.EncodeToString(credentials[16:])
	config, err := capability.Render(profile, params, address, user, password)
	if err != nil {
		return nil, nil, model.Err("core_config_rejected", "core")
	}
	parsed, err := xserial.LoadJSONConfig(bytes.NewReader(config))
	if err != nil {
		return nil, nil, model.Err("core_config_rejected", "core")
	}
	parsed.App = append([]*serial.TypedMessage{serial.ToTypedMessage(&searchdns.Config{})}, parsed.App...)
	if err := startup.Err(); err != nil {
		return nil, nil, err
	}
	if !searchCurrent(owner) {
		return nil, nil, &searchEmbeddedError{SearchWorkerOwnerLost}
	}
	instance, err := ports.newCore(context.WithValue(attempt, searchDNSContextKey{}, owner), parsed)
	if err != nil {
		if instance == nil {
			return nil, nil, errSearchCoreCreation
		}
		return instance, nil, err
	}
	if instance == nil {
		return nil, nil, errSearchCoreCreation
	}
	if err := startup.Err(); err != nil {
		return instance, nil, err
	}
	if !searchCurrent(owner) {
		return instance, nil, &searchEmbeddedError{SearchWorkerOwnerLost}
	}
	if err := instance.Start(); err != nil {
		return instance, nil, model.Err("core_start_failed", "core")
	}
	if err := startup.Err(); err != nil {
		return instance, nil, err
	}
	probes, err := newSearchWorkerProbes(address, user, password)
	if err != nil || probes.owner != owner || !searchCurrent(owner) {
		if probes != nil {
			probes.Cancel()
		}
		return instance, nil, &searchEmbeddedError{SearchWorkerOwnerLost}
	}
	ticker := time.NewTicker(searchReadinessInterval)
	defer ticker.Stop()
	for {
		if err := startup.Err(); err != nil {
			return instance, probes, err
		}
		if !searchCurrent(owner) {
			return instance, probes, &searchEmbeddedError{SearchWorkerOwnerLost}
		}
		ctx, cancel := context.WithTimeout(startup, searchReadinessTimeout)
		conn, err := ports.negotiate(ctx, probes)
		cancel()
		if conn != nil {
			if conn.Close() != nil {
				return instance, probes, errSearchResourceCleanup
			}
			if err == nil {
				if !searchCurrent(owner) {
					return instance, probes, &searchEmbeddedError{SearchWorkerOwnerLost}
				}
				return instance, probes, nil
			}
		}
		select {
		case <-startup.Done():
			return instance, probes, startup.Err()
		case <-ticker.C:
		}
	}
}

func newSearchXray(ctx context.Context, config *core.Config) (searchRunningCore, error) {
	instance, err := core.NewWithContext(ctx, config)
	if err != nil {
		return nil, errSearchCoreCreation
	}
	client, ok := instance.GetFeature(dns.ClientType()).(*searchDNSClient)
	if !ok || client.owner != ctx.Value(searchDNSContextKey{}) {
		return instance, &searchEmbeddedError{SearchWorkerCapabilityUnavailable}
	}
	return instance, nil
}

func reserveSearchAddress(ctx context.Context, owner *protection) (string, error) {
	if !searchCurrent(owner) {
		return "", &searchEmbeddedError{SearchWorkerOwnerLost}
	}
	listener, err := (&net.ListenConfig{Control: searchControl(owner, SearchRoleSOCKSReservation)}).Listen(ctx, "tcp4", "127.0.0.1:0")
	if err != nil {
		if listener != nil && listener.Close() != nil {
			return "", errSearchResourceCleanup
		}
		if !searchCurrent(owner) {
			return "", &searchEmbeddedError{SearchWorkerOwnerLost}
		}
		return "", &searchEmbeddedError{SearchWorkerCapabilityUnavailable}
	}
	if listener == nil {
		return "", &searchEmbeddedError{SearchWorkerCapabilityUnavailable}
	}
	address, ok := listener.Addr().(*net.TCPAddr)
	closeErr := listener.Close()
	if closeErr != nil {
		return "", errSearchResourceCleanup
	}
	if !ok || address == nil || !address.IP.IsLoopback() || address.Port < 1 || address.Port > 65535 || !searchCurrent(owner) {
		return "", &searchEmbeddedError{SearchWorkerCapabilityUnavailable}
	}
	return net.JoinHostPort("127.0.0.1", strconv.Itoa(address.Port)), nil
}
