// Package mobile is the Android boundary for the pinned Xray core.
package mobile

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net"
	"net/netip"
)

const maxConfigBytes = 4 << 20
const tunnelMTUBytes = 1500

// PrepareConfig replaces only Android-owned input, routing and logging.
// The exported candidate and every supporting outbound remain intact.
func PrepareConfig(source []byte) ([]byte, error) {
	if len(source) == 0 || len(source) > maxConfigBytes {
		return nil, errors.New("invalid_config_size")
	}
	var cfg map[string]json.RawMessage
	if json.Unmarshal(source, &cfg) != nil || cfg == nil {
		return nil, errors.New("invalid_config")
	}
	var outbounds []struct {
		Tag string `json:"tag"`
	}
	if json.Unmarshal(cfg["outbounds"], &outbounds) != nil || len(outbounds) == 0 || outbounds[0].Tag != "candidate" {
		return nil, errors.New("expected_sweep_result")
	}
	// Only the exported sweep envelope is accepted by this integration probe.
	for key := range cfg {
		switch key {
		case "inbounds", "outbounds", "routing", "log":
		default:
			return nil, errors.New("unsupported_result_envelope")
		}
	}
	cfg["log"] = json.RawMessage(`{"loglevel":"none","access":"none","error":"none"}`)
	inbounds, err := json.Marshal([]any{map[string]any{
		"tag": "android-tun", "protocol": "tun",
		"settings": map[string]any{"name": "sweep", "mtu": tunnelMTUBytes},
	}})
	if err != nil {
		return nil, errors.New("config_render_failed")
	}
	cfg["inbounds"] = inbounds
	cfg["routing"] = json.RawMessage(`{"rules":[{"type":"field","inboundTag":["android-tun"],"outboundTag":"candidate"}]}`)
	return json.Marshal(cfg)
}

func runtimeConfig(source []byte, bootstrapDNS string) ([]byte, error) {
	ip, err := netip.ParseAddr(bootstrapDNS)
	if err != nil {
		return nil, errors.New("invalid_bootstrap_dns")
	}
	prepared, err := PrepareConfig(source)
	if err != nil {
		return nil, err
	}
	var cfg map[string]json.RawMessage
	if err := json.Unmarshal(prepared, &cfg); err != nil {
		return nil, errors.New("invalid_config")
	}
	// Xray's localdns client has its own resolver; give core DNS an explicit
	// protected bootstrap path as well as the Go resolver used by AsIs dials.
	dns, err := json.Marshal(map[string]any{"servers": []string{"tcp+local://" + net.JoinHostPort(ip.String(), "53")}})
	if err != nil {
		return nil, errors.New("config_render_failed")
	}
	cfg["dns"] = dns
	// Payload counters distinguish a silent TUN from an outbound that cannot
	// return traffic. These system counters do not alter candidate settings.
	cfg["stats"] = json.RawMessage(`{}`)
	cfg["policy"] = json.RawMessage(`{"system":{"statsInboundUplink":true,"statsInboundDownlink":true}}`)
	return json.Marshal(cfg)
}

// OutboundDigest allows device acceptance to compare the selected and used config.
func OutboundDigest(source []byte) (string, error) {
	prepared, err := PrepareConfig(source)
	if err != nil {
		return "", err
	}
	var cfg map[string]json.RawMessage
	if err = json.Unmarshal(prepared, &cfg); err != nil {
		return "", errors.New("invalid_config")
	}
	var compact json.RawMessage
	if err = json.Unmarshal(cfg["outbounds"], &compact); err != nil {
		return "", errors.New("invalid_config")
	}
	hash := sha256.Sum256(compact)
	return hex.EncodeToString(hash[:]), nil
}
