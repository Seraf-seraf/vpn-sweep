package capability

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"net"
	"strconv"
	"strings"
)

type Parameter struct {
	Name     string   `json:"name"`
	Type     string   `json:"type"`
	Unit     string   `json:"unit,omitempty"`
	Min      int      `json:"min,omitempty"`
	Max      int      `json:"max,omitempty"`
	ActiveIf string   `json:"active_if,omitempty"`
	Values   []string `json:"values,omitempty"`
	Source   string   `json:"source"`
}

func Catalog() []Parameter {
	return []Parameter{
		{Name: "noise", Type: "bool", Source: "Freedom UDP; native Shadowsocks only in this profile"},
		{Name: "noises", Type: "noise-list", ActiveIf: "noise", Source: "ac5d8a8 infra/conf/freedom.go ParseNoise; no Happ mapping"},
		{Name: "fragment", Type: "bool", Source: "freedom/TCP"},
		{Name: "packets", Type: "enum", ActiveIf: "fragment", Values: []string{"tlshello", "1-1", "1-2", "1-3", "1-5"}, Source: "freedom.Fragment"},
		{Name: "length", Type: "range", Unit: "bytes", Min: 1, Max: 16384, ActiveIf: "fragment", Source: "product search safety envelope; not upstream field maximum"},
		{Name: "interval", Type: "range", Unit: "ms", Min: 0, Max: 1000, ActiveIf: "fragment", Source: "product search safety envelope"},
		{Name: "maxSplit", Type: "range", Unit: "parts", Min: 1, Max: 1024, ActiveIf: "fragment", Source: "product search safety envelope; omitted means core default"},
		{Name: "mux", Type: "bool", Source: "outbound.MuxConfig"},
		{Name: "tcpConcurrency", Type: "int", Min: -1, Max: 128, ActiveIf: "mux", Source: "Xray outbound docs"},
		{Name: "xudpConcurrency", Type: "int", Min: -1, Max: 1024, ActiveIf: "mux", Source: "Xray outbound docs"},
		{Name: "quic", Type: "enum", Values: []string{"reject", "allow", "skip"}, ActiveIf: "mux", Source: "xudpProxyUDP443"},
		{Name: "fingerprint", Type: "enum", Values: []string{"chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized"}, Source: "TLS/REALITY fingerprint"},
	}
}
func Gate(mode string, commit string) error {
	if mode != "core" && mode != "happ" {
		return model.Err("invalid_mode", "capability")
	}
	if mode == "happ" {
		return model.Err("happ_mapping_unverified", "capability")
	}
	if commit != model.TargetCommit {
		return model.Err("unsupported_core_commit", "capability")
	}
	return nil
}
func canonicalValue(v any) string { b, _ := json.Marshal(v); return string(b) }
func ValidateValue(name string, v any) error {
	for _, d := range Catalog() {
		if d.Name != name {
			continue
		}
		switch d.Type {
		case "noise-list":
			return validateNoises(v)
		case "bool":
			if _, ok := v.(bool); ok {
				return nil
			}
		case "int":
			n, ok := v.(float64)
			if ok && n == float64(int(n)) && n >= float64(d.Min) && n <= float64(d.Max) {
				return nil
			}
		case "enum":
			s, ok := v.(string)
			if ok {
				for _, x := range d.Values {
					if s == x {
						return nil
					}
				}
			}
		case "range":
			s, ok := v.(string)
			if ok {
				a, b, e := Range(s)
				if e == nil && a >= d.Min && b <= d.Max {
					return nil
				}
			}
		}
		return model.Err("invalid_parameter_value", "capability")
	}
	return model.Err("unsupported_parameter", "capability")
}
func Range(s string) (int, int, error) {
	p := strings.Split(s, "-")
	if len(p) < 1 || len(p) > 2 {
		return 0, 0, fmt.Errorf("range")
	}
	a, e := strconv.Atoi(p[0])
	if e != nil {
		return 0, 0, e
	}
	b := a
	if len(p) == 2 {
		b, e = strconv.Atoi(p[1])
	}
	if e != nil || a > b {
		return 0, 0, fmt.Errorf("range")
	}
	return a, b, nil
}
func Canonical(params map[string]any) map[string]any {
	out := map[string]any{}
	for k, v := range params {
		if k == "noises" && params["noise"] != true {
			continue
		}
		if (k == "packets" || k == "length" || k == "interval" || k == "maxSplit") && params["fragment"] != true {
			continue
		}
		if (k == "tcpConcurrency" || k == "xudpConcurrency" || k == "quic") && params["mux"] != true {
			continue
		}
		out[k] = Normalize(k, v)
	}
	return out
}

// Effective resolves client defaults from the same Mux settings used by Render.
// Explicit mux replaces source settings; omitted mux preserves the imported baseline.
func Effective(p model.Profile, params map[string]any) map[string]any {
	q := Canonical(params)
	if q["fragment"] == nil {
		q["fragment"] = false
	}
	if q["noise"] == nil {
		q["noise"] = false
	}
	m := muxSettings(p, q)
	q["mux"] = m["enabled"] == true
	if q["mux"] == true {
		q["tcpConcurrency"] = m["concurrency"]
		q["xudpConcurrency"] = m["xudpConcurrency"]
		q["quic"] = m["xudpProxyUDP443"]
	}
	return Canonical(q)
}

func muxSettings(p model.Profile, params map[string]any) map[string]any {
	m := map[string]any{}
	if enabled, explicit := params["mux"].(bool); explicit {
		m["enabled"] = enabled
		for k, target := range map[string]string{"tcpConcurrency": "concurrency", "xudpConcurrency": "xudpConcurrency", "quic": "xudpProxyUDP443"} {
			if v, ok := params[k]; ok {
				m[target] = v
			}
		}
	} else if src, ok := p.Outbound["mux"].(map[string]any); ok {
		for k, v := range src {
			m[k] = v
		}
	}
	if m["enabled"] != true {
		return map[string]any{"enabled": false}
	}
	if m["concurrency"] == nil || m["concurrency"] == float64(0) {
		m["concurrency"] = float64(8)
	}
	if m["xudpConcurrency"] == nil {
		m["xudpConcurrency"] = float64(0)
	}
	if m["xudpProxyUDP443"] == nil || m["xudpProxyUDP443"] == "" {
		m["xudpProxyUDP443"] = "reject"
	}
	return m
}

func Normalize(name string, v any) any {
	if name == "length" || name == "interval" || name == "maxSplit" {
		if s, ok := v.(string); ok {
			if a, b, e := Range(s); e == nil {
				if a == b {
					return strconv.Itoa(a)
				}
				return strconv.Itoa(a) + "-" + strconv.Itoa(b)
			}
		}
	}
	return v
}
func Check(p model.Profile, params map[string]any) error {
	if e := importer.Validate(p); e != nil {
		return e
	}
	for k, v := range params {
		if e := ValidateValue(k, v); e != nil {
			return e
		}
	}
	s, _ := p.Outbound["streamSettings"].(map[string]any)
	sec, _ := s["security"].(string)
	network, _ := s["network"].(string)
	if params["fingerprint"] != nil && sec != "tls" && sec != "reality" {
		return model.Err("fingerprint_not_applicable", "capability")
	}
	if params["fragment"] == true {
		if network != "" && network != "tcp" && network != "raw" && network != "ws" && network != "grpc" {
			return model.Err("fragment_not_applicable", "capability")
		}
		if params["length"] == nil || params["interval"] == nil || params["packets"] == nil {
			return model.Err("missing_fragment_fields", "capability")
		}
		if params["packets"] == "tlshello" && sec != "tls" && sec != "reality" {
			return model.Err("tlshello_not_applicable", "capability")
		}
	}
	proto := p.Outbound["protocol"]
	sourceMux, _ := p.Outbound["mux"].(map[string]any)
	muxOn := params["mux"] == true || params["mux"] == nil && sourceMux["enabled"] == true
	if params["noise"] == true {
		if proto != "shadowsocks" || muxOn || (network != "" && network != "tcp" && network != "raw") || (sec != "" && sec != "none") {
			return model.Err("noise_requires_native_udp_path", "capability")
		}
		if params["noises"] == nil {
			return model.Err("missing_noise_fields", "capability")
		}
	}
	if muxOn && proto != "vless" && proto != "vmess" {
		return model.Err("mux_not_supported_for_protocol", "capability")
	}
	if proto == "vless" {
		settings := p.Outbound["settings"].(map[string]any)
		srv := settings["vnext"].([]any)[0].(map[string]any)
		u := srv["users"].([]any)[0].(map[string]any)
		if u["flow"] == "xtls-rprx-vision" {
			if network != "" && network != "tcp" && network != "raw" {
				return model.Err("vision_transport_conflict", "capability")
			}
			m, _ := p.Outbound["mux"].(map[string]any)
			if params["mux"] == true || (params["mux"] == nil && m["enabled"] == true) {
				return model.Err("vision_mux_conflict", "capability")
			}
			if sec != "tls" && sec != "reality" {
				return model.Err("vision_security_conflict", "capability")
			}
		}
	}
	return nil
}

// Render preserves the source server outbound, replacing only declared client settings.
// The only direct Freedom outbound is a dialer for the endpoint, never a route for probes.
func Render(p model.Profile, params map[string]any, socksAddr, user, pass string) ([]byte, error) {
	params = Canonical(params)
	if e := Check(p, params); e != nil {
		return nil, e
	}
	host, port, e := net.SplitHostPort(socksAddr)
	if e != nil || host != "127.0.0.1" {
		return nil, model.Err("invalid_listener", "render")
	}
	pn, e := strconv.Atoi(port)
	if e != nil || pn < 1 || pn > 65535 {
		return nil, model.Err("invalid_listener", "render")
	}
	o := importer.Clone(p).Outbound
	o["tag"] = "candidate"
	s, _ := o["streamSettings"].(map[string]any)
	if s == nil {
		s = map[string]any{}
		o["streamSettings"] = s
	}
	if fp, ok := params["fingerprint"]; ok {
		k := "tlsSettings"
		if s["security"] == "reality" {
			k = "realitySettings"
		}
		v, _ := s[k].(map[string]any)
		if v == nil {
			v = map[string]any{}
			s[k] = v
		}
		v["fingerprint"] = fp
	}
	o["mux"] = muxSettings(p, params)
	outbounds := []any{o}
	if params["fragment"] == true || params["noise"] == true {
		settings := map[string]any{}
		if params["fragment"] == true {
			f := map[string]any{"packets": params["packets"], "length": params["length"], "interval": params["interval"]}
			if v, ok := params["maxSplit"]; ok {
				f["maxSplit"] = v
			}
			settings["fragment"] = f
		}
		if params["noise"] == true {
			settings["noises"] = params["noises"]
		}
		s["sockopt"] = map[string]any{"dialerProxy": "endpoint-fragment"}
		outbounds = append(outbounds, map[string]any{"tag": "endpoint-fragment", "protocol": "freedom", "settings": settings})
	}
	cfg := map[string]any{"log": map[string]any{"loglevel": "none"}, "inbounds": []any{map[string]any{"tag": "probe-in", "listen": "127.0.0.1", "port": pn, "protocol": "socks", "settings": map[string]any{"auth": "password", "accounts": []any{map[string]any{"user": user, "pass": pass}}, "udp": true}}}, "outbounds": outbounds, "routing": map[string]any{"rules": []any{map[string]any{"type": "field", "inboundTag": []string{"probe-in"}, "outboundTag": "candidate"}}}}
	return json.MarshalIndent(cfg, "", "  ")
}

func validateNoises(v any) error {
	a, ok := v.([]any)
	if !ok || len(a) < 1 || len(a) > 4 {
		return model.Err("invalid_noise_list", "capability")
	}
	for _, raw := range a {
		m, ok := raw.(map[string]any)
		if !ok {
			return model.Err("invalid_noise", "capability")
		}
		for k := range m {
			if k != "type" && k != "packet" && k != "delay" && k != "applyTo" {
				return model.Err("unsupported_noise_field", "capability")
			}
		}
		typ, _ := m["type"].(string)
		packet, ok := m["packet"].(string)
		if !ok {
			return model.Err("invalid_noise_packet", "capability")
		}
		size := 0
		switch typ {
		case "rand":
			lo, hi, e := Range(packet)
			if e != nil || lo < 1 || hi > 1200 {
				return model.Err("invalid_noise_range", "capability")
			}
			size = hi
		case "str":
			size = len([]byte(packet))
		case "hex":
			b, e := hex.DecodeString(packet)
			if e != nil {
				return model.Err("invalid_noise_hex", "capability")
			}
			size = len(b)
		case "base64":
			b, e := base64.StdEncoding.DecodeString(packet)
			if e != nil {
				b, e = base64.RawStdEncoding.DecodeString(packet)
			}
			if e != nil {
				b, e = base64.RawURLEncoding.DecodeString(packet)
			}
			if e != nil {
				return model.Err("invalid_noise_base64", "capability")
			}
			size = len(b)
		default:
			return model.Err("unsupported_noise_type", "capability")
		}
		if size < 1 || size > 1200 {
			return model.Err("noise_payload_limit", "capability")
		}
		if m["delay"] != nil {
			s, ok := m["delay"].(string)
			lo, hi, e := Range(s)
			if !ok || e != nil || lo < 0 || hi > 1000 {
				return model.Err("invalid_noise_delay", "capability")
			}
		}
		if m["applyTo"] != nil && m["applyTo"] != "ip" && m["applyTo"] != "ipv4" && m["applyTo"] != "ipv6" {
			return model.Err("invalid_noise_apply_to", "capability")
		}
	}
	return nil
}
