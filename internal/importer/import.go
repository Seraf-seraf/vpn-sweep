package importer

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"net"
	"net/url"
	"strconv"
	"strings"

	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

const MaxInputBytes = 8 << 20

type Result struct {
	Profiles []model.Profile `json:"-"`
	Format   string          `json:"format"`
	Notices  []string        `json:"notices"`
}

func keys(m map[string]any, allowed string) bool {
	set := map[string]bool{}
	for _, k := range strings.Fields(allowed) {
		set[k] = true
	}
	for k := range m {
		if !set[k] {
			return false
		}
	}
	return true
}
func obj(v any) map[string]any { m, _ := v.(map[string]any); return m }
func str(v any) string         { s, _ := v.(string); return s }
func list(v any) []any         { a, _ := v.([]any); return a }
func number(v any) int {
	n, ok := v.(float64)
	if !ok || n != float64(int(n)) {
		return -1
	}
	return int(n)
}
func fail(code string) error { return model.Err(code, "import") }

func Parse(data []byte, format string) (Result, error) {
	var out Result
	if len(data) == 0 || len(data) > MaxInputBytes {
		return out, fail("input_size")
	}
	data = bytes.TrimSpace(data)
	if format == "auto" || format == "" {
		if len(data) == 0 {
			return out, fail("empty_input")
		}
		if data[0] == '{' || data[0] == '[' {
			format = "json"
		} else if bytes.Contains(data, []byte("://")) {
			format = "uri"
		} else {
			return out, fail("unknown_format")
		}
	}
	out.Format = format
	switch format {
	case "json":
		var raw any
		if e := model.Decode(data, &raw); e != nil {
			return out, e
		}
		items := []any{raw}
		if a, ok := raw.([]any); ok {
			items = a
		}
		if len(items) == 0 || len(items) > 10000 {
			return out, fail("profile_count")
		}
		for _, item := range items {
			m := obj(item)
			if m == nil {
				return out, fail("invalid_json_profile")
			}
			if !keys(m, "outbounds inbounds dns routing policy log stats api remarks meta") {
				return out, fail("unsupported_root_field")
			}
			obs := list(m["outbounds"])
			if len(obs) == 0 {
				return out, fail("missing_outbound")
			}
			for _, v := range obs {
				o := obj(v)
				if o == nil {
					return out, fail("invalid_outbound")
				}
				protocol := str(o["protocol"])
				if protocol == "freedom" || protocol == "blackhole" {
					out.Notices = append(out.Notices, "utility_outbound_removed")
					continue
				}
				notices, e := normalizeJSONOutbound(o)
				if e != nil {
					return out, e
				}
				if e := Validate(model.Profile{Outbound: o}); e != nil {
					return out, e
				}
				out.Notices = append(out.Notices, notices...)
				out.Profiles = append(out.Profiles, model.Profile{Outbound: o})
			}
			for _, k := range []string{"inbounds", "dns", "routing", "policy", "log", "stats", "api"} {
				if m[k] != nil {
					out.Notices = append(out.Notices, "test_envelope_replaces_"+k)
				}
			}
		}
	case "uri":
		lines := strings.Split(string(data), "\n")
		for _, line := range lines {
			line = strings.TrimSpace(line)
			if line == "" {
				continue
			}
			if strings.HasPrefix(line, "#") {
				out.Notices = append(out.Notices, "subscription_metadata_ignored")
				continue
			}
			p, e := parseURI(line)
			if e != nil {
				return out, e
			}
			if e = Validate(p); e != nil {
				return out, e
			}
			out.Profiles = append(out.Profiles, p)
			if len(out.Profiles) > 10000 {
				return out, fail("profile_count")
			}
		}
	default:
		return out, fail("unsupported_format")
	}
	if len(out.Profiles) == 0 {
		return out, fail("no_supported_profiles")
	}
	return out, nil
}

func normalizeJSONOutbound(o map[string]any) ([]string, error) {
	var notices []string
	s := obj(o["settings"])
	_, flat := s["address"]
	_, nested := s["vnext"]
	if str(o["protocol"]) == "vless" && flat && !nested {
		if !keys(s, "address port id encryption flow level security") {
			return nil, fail("unsupported_settings")
		}
		user := map[string]any{}
		for _, k := range []string{"id", "encryption", "flow", "level", "security"} {
			if v, ok := s[k]; ok {
				user[k] = v
			}
		}
		o["settings"] = map[string]any{"vnext": []any{map[string]any{
			"address": s["address"], "port": s["port"], "users": []any{user},
		}}}
		notices = append(notices, "flat_vless_settings_normalized")
	}
	tls := obj(obj(o["streamSettings"])["tlsSettings"])
	if show, ok := tls["show"]; ok {
		if _, ok := show.(bool); !ok {
			return nil, fail("unsupported_tls_field")
		}
		delete(tls, "show")
		notices = append(notices, "client_tls_show_removed")
	}
	return notices, nil
}

func Validate(p model.Profile) error {
	o := p.Outbound
	if !keys(o, "protocol settings streamSettings mux tag") {
		return fail("unsupported_outbound_field")
	}
	s := obj(o["settings"])
	if s == nil {
		return fail("missing_settings")
	}
	proto := str(o["protocol"])
	switch proto {
	case "vless", "vmess":
		if !keys(s, "vnext") {
			return fail("unsupported_settings")
		}
		a := list(s["vnext"])
		if len(a) != 1 {
			return fail("unsupported_server_group")
		}
		srv := obj(a[0])
		if srv == nil || !keys(srv, "address port users") || !endpoint(srv) {
			return fail("invalid_endpoint")
		}
		u := list(srv["users"])
		if len(u) != 1 {
			return fail("unsupported_user_group")
		}
		user := obj(u[0])
		if user == nil || !keys(user, "id encryption flow security alterId level") || str(user["id"]) == "" {
			return fail("invalid_user")
		}
		if len(str(user["id"])) > 256 {
			return fail("invalid_user")
		}
		if proto == "vless" {
			if str(user["encryption"]) != "none" {
				return fail("unsupported_vless_encryption")
			}
			if f := str(user["flow"]); f != "" && f != "xtls-rprx-vision" {
				return fail("unsupported_flow")
			}
		} else {
			switch str(user["security"]) {
			case "", "auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero":
			default:
				return fail("unsupported_vmess_cipher")
			}
			if user["alterId"] != nil && number(user["alterId"]) != 0 {
				return fail("unsupported_vmess_legacy")
			}
			if str(user["flow"]) != "" {
				return fail("unsupported_flow")
			}
		}
	case "trojan", "shadowsocks", "socks":
		if !keys(s, "servers") {
			return fail("unsupported_settings")
		}
		a := list(s["servers"])
		if len(a) != 1 {
			return fail("unsupported_server_group")
		}
		srv := obj(a[0])
		if srv == nil || !endpoint(srv) {
			return fail("invalid_endpoint")
		}
		switch proto {
		case "trojan":
			if !keys(srv, "address port password level") || str(srv["password"]) == "" {
				return fail("invalid_trojan")
			}
		case "shadowsocks":
			if !keys(srv, "address port password method level") || str(srv["password"]) == "" {
				return fail("invalid_shadowsocks")
			}
			switch str(srv["method"]) {
			case "aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305":
			default:
				return fail("unsupported_cipher")
			}
		case "socks":
			if !keys(srv, "address port users") {
				return fail("unsupported_socks")
			}
			if srv["users"] != nil {
				us := list(srv["users"])
				if len(us) != 1 || obj(us[0]) == nil || !keys(obj(us[0]), "user pass") || str(obj(us[0])["user"]) == "" {
					return fail("invalid_socks_auth")
				}
			}
		}
	default:
		return fail("unsupported_protocol")
	}
	if o["streamSettings"] != nil {
		if e := validateStream(obj(o["streamSettings"])); e != nil {
			return e
		}
	}
	if o["mux"] != nil {
		m := obj(o["mux"])
		if m == nil || !keys(m, "enabled concurrency xudpConcurrency xudpProxyUDP443") {
			return fail("unsupported_mux")
		}
		if _, ok := m["enabled"].(bool); !ok {
			return fail("invalid_mux")
		}
		for _, k := range []string{"concurrency", "xudpConcurrency"} {
			if m[k] != nil {
				n := number(m[k])
				max := 128
				if k == "xudpConcurrency" {
					max = 1024
				}
				if n < -1 || n > max {
					return fail("invalid_mux_range")
				}
			}
		}
		q := str(m["xudpProxyUDP443"])
		if q != "" && q != "reject" && q != "allow" && q != "skip" {
			return fail("invalid_quic_policy")
		}
	}
	return nil
}
func endpoint(m map[string]any) bool {
	a := str(m["address"])
	p := number(m["port"])
	return a != "" && len(a) <= 253 && !strings.ContainsAny(a, "\x00\r\n /\\") && p > 0 && p <= 65535
}
func validateStream(s map[string]any) error {
	if s == nil || !keys(s, "network security tlsSettings realitySettings wsSettings grpcSettings tcpSettings") {
		return fail("unsupported_stream_field")
	}
	n := str(s["network"])
	if n != "" && n != "tcp" && n != "raw" && n != "ws" && n != "grpc" {
		return fail("unsupported_transport")
	}
	sec := str(s["security"])
	if s["tlsSettings"] != nil && sec != "tls" || s["realitySettings"] != nil && sec != "reality" {
		return fail("inconsistent_security")
	}
	if sec != "" && sec != "none" && sec != "tls" && sec != "reality" {
		return fail("unsupported_security")
	}
	if s["tlsSettings"] != nil {
		v := obj(s["tlsSettings"])
		if v == nil || !keys(v, "serverName allowInsecure alpn fingerprint pinnedPeerCertificateChainSha256") {
			return fail("unsupported_tls_field")
		}
		if v["allowInsecure"] != nil && v["allowInsecure"] != false {
			return fail("insecure_tls_rejected")
		}
		if v["fingerprint"] != nil && !Fingerprint(str(v["fingerprint"])) {
			return fail("unsupported_fingerprint")
		}
	}
	if s["realitySettings"] != nil {
		v := obj(s["realitySettings"])
		if v == nil || !keys(v, "serverName fingerprint publicKey password shortId spiderX") || str(v["serverName"]) == "" || (str(v["publicKey"]) == "" && str(v["password"]) == "") {
			return fail("unsupported_reality")
		}
		if !Fingerprint(str(v["fingerprint"])) {
			return fail("unsupported_fingerprint")
		}
		if n != "tcp" && n != "raw" && n != "" {
			return fail("unsupported_reality_transport")
		}
	}
	if s["wsSettings"] != nil {
		v := obj(s["wsSettings"])
		if v == nil || !keys(v, "path host headers") {
			return fail("unsupported_ws_field")
		}
		if v["headers"] != nil {
			h := obj(v["headers"])
			if h == nil || !keys(h, "Host") {
				return fail("unsupported_ws_headers")
			}
		}
		if n != "ws" {
			return fail("inconsistent_transport")
		}
	}
	if s["grpcSettings"] != nil {
		v := obj(s["grpcSettings"])
		if v == nil || !keys(v, "serviceName multiMode authority") {
			return fail("unsupported_grpc_field")
		}
		if n != "grpc" {
			return fail("inconsistent_transport")
		}
	}
	if s["tcpSettings"] != nil {
		return fail("unsupported_tcp_settings")
	}
	return nil
}
func Fingerprint(s string) bool {
	switch s {
	case "", "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized":
		return true
	}
	return false
}

func decode64(s string) ([]byte, error) {
	s = strings.TrimSpace(s)
	for _, enc := range []*base64.Encoding{base64.StdEncoding, base64.RawStdEncoding, base64.URLEncoding, base64.RawURLEncoding} {
		if b, e := enc.DecodeString(s); e == nil {
			return b, nil
		}
	}
	return nil, fail("invalid_base64")
}
func parseURI(raw string) (model.Profile, error) {
	var p model.Profile
	if strings.HasPrefix(raw, "vmess://") {
		return parseVMess(strings.TrimPrefix(raw, "vmess://"))
	}
	if strings.HasPrefix(raw, "ss://") && !strings.Contains(raw, "@") {
		b, e := decode64(strings.Split(strings.TrimPrefix(raw, "ss://"), "#")[0])
		if e != nil {
			return p, e
		}
		raw = "ss://" + string(b)
	}
	u, e := url.Parse(raw)
	if e != nil || u.Opaque != "" || u.Hostname() == "" {
		return p, fail("invalid_uri")
	}
	port, e := strconv.Atoi(u.Port())
	if e != nil || port < 1 || port > 65535 {
		return p, fail("invalid_port")
	}
	if u.Path != "" && u.Path != "/" {
		return p, fail("unsupported_uri_path")
	}
	q, e := url.ParseQuery(u.RawQuery)
	if e != nil {
		return p, fail("invalid_query")
	}
	for _, vs := range q {
		if len(vs) != 1 {
			return p, fail("duplicate_query")
		}
	}
	allowed := strings.Fields("type security encryption flow sni host path serviceName mode authority fp pbk sid spx alpn headerType")
	for k := range q {
		ok := false
		for _, a := range allowed {
			if k == a {
				ok = true
			}
		}
		if !ok {
			return p, fail("unsupported_uri_parameter")
		}
	}
	o := map[string]any{}
	p.Outbound = o
	addr := u.Hostname()
	srv := map[string]any{"address": addr, "port": float64(port)}
	user := ""
	pass := ""
	if u.User != nil {
		user = u.User.Username()
		pass, _ = u.User.Password()
	}
	switch u.Scheme {
	case "vless":
		o["protocol"] = "vless"
		if user == "" || pass != "" {
			return p, fail("invalid_user")
		}
		enc := q.Get("encryption")
		if enc == "" {
			enc = "none"
		}
		v := map[string]any{"id": user, "encryption": enc}
		if q.Get("flow") != "" {
			v["flow"] = q.Get("flow")
		}
		srv["users"] = []any{v}
		o["settings"] = map[string]any{"vnext": []any{srv}}
	case "trojan":
		o["protocol"] = "trojan"
		if user == "" || pass != "" {
			return p, fail("invalid_user")
		}
		srv["password"] = user
		o["settings"] = map[string]any{"servers": []any{srv}}
	case "ss":
		o["protocol"] = "shadowsocks"
		if pass == "" {
			b, e := decode64(user)
			if e != nil {
				return p, e
			}
			x := strings.SplitN(string(b), ":", 2)
			if len(x) != 2 {
				return p, fail("invalid_ss_auth")
			}
			user, pass = x[0], x[1]
		}
		srv["method"] = user
		srv["password"] = pass
		o["settings"] = map[string]any{"servers": []any{srv}}
	case "socks", "socks5":
		o["protocol"] = "socks"
		if user != "" {
			srv["users"] = []any{map[string]any{"user": user, "pass": pass}}
		}
		o["settings"] = map[string]any{"servers": []any{srv}}
	default:
		return p, fail("unsupported_protocol")
	}
	if (u.Scheme == "ss" || u.Scheme == "socks" || u.Scheme == "socks5") && len(q) > 0 {
		return p, fail("unsupported_uri_parameter")
	}
	n := q.Get("type")
	if n == "" {
		n = "tcp"
	}
	sec := q.Get("security")
	if sec == "" {
		if u.Scheme == "trojan" {
			sec = "tls"
		} else {
			sec = "none"
		}
	}
	s := map[string]any{"network": n, "security": sec}
	o["streamSettings"] = s
	for _, k := range []string{"sni", "fp", "alpn"} {
		if q.Has(k) && sec != "tls" && sec != "reality" {
			return p, fail("inapplicable_uri_parameter")
		}
	}
	for _, k := range []string{"pbk", "sid", "spx"} {
		if q.Has(k) && sec != "reality" {
			return p, fail("inapplicable_uri_parameter")
		}
	}
	for _, k := range []string{"host", "path"} {
		if q.Has(k) && n != "ws" {
			return p, fail("inapplicable_uri_parameter")
		}
	}
	for _, k := range []string{"serviceName", "mode", "authority"} {
		if q.Has(k) && n != "grpc" {
			return p, fail("inapplicable_uri_parameter")
		}
	}
	if q.Has("flow") && u.Scheme != "vless" || q.Has("encryption") && u.Scheme != "vless" {
		return p, fail("inapplicable_uri_parameter")
	}
	if h := q.Get("headerType"); h != "" && h != "none" {
		return p, fail("unsupported_header")
	}
	if sec == "tls" || sec == "reality" {
		v := map[string]any{}
		if q.Get("sni") != "" {
			v["serverName"] = q.Get("sni")
		}
		if q.Get("fp") != "" {
			v["fingerprint"] = q.Get("fp")
		}
		if sec == "tls" {
			if q.Get("alpn") != "" {
				a := []any{}
				for _, x := range strings.Split(q.Get("alpn"), ",") {
					a = append(a, x)
				}
				v["alpn"] = a
			}
			s["tlsSettings"] = v
		} else {
			v["publicKey"] = q.Get("pbk")
			v["shortId"] = q.Get("sid")
			if q.Get("spx") != "" {
				v["spiderX"] = q.Get("spx")
			}
			s["realitySettings"] = v
		}
	}
	if n == "ws" {
		v := map[string]any{"path": q.Get("path")}
		if q.Get("host") != "" {
			v["headers"] = map[string]any{"Host": q.Get("host")}
		}
		s["wsSettings"] = v
	} else if n == "grpc" {
		v := map[string]any{"serviceName": q.Get("serviceName")}
		if q.Get("mode") != "" && q.Get("mode") != "gun" && q.Get("mode") != "multi" {
			return p, fail("unsupported_grpc_mode")
		}
		if q.Get("mode") == "multi" {
			v["multiMode"] = true
		}
		if q.Get("authority") != "" {
			v["authority"] = q.Get("authority")
		}
		s["grpcSettings"] = v
	}
	return p, nil
}
func parseVMess(raw string) (model.Profile, error) {
	var p model.Profile
	b, e := decode64(strings.Split(raw, "#")[0])
	if e != nil {
		return p, e
	}
	var m map[string]any
	if e = model.Decode(b, &m); e != nil {
		return p, e
	}
	if !keys(m, "v ps add port id aid scy net type host path tls sni alpn fp") {
		return p, fail("unsupported_vmess_uri")
	}
	num := func(v any) int {
		if s, ok := v.(string); ok {
			n, _ := strconv.Atoi(s)
			return n
		}
		return number(v)
	}
	if m["aid"] != nil && num(m["aid"]) != 0 {
		return p, fail("unsupported_vmess_legacy")
	}
	n := str(m["net"])
	if n == "" {
		n = "tcp"
	}
	sec := "none"
	if str(m["tls"]) != "" {
		if str(m["tls"]) != "tls" {
			return p, fail("unsupported_security")
		}
		sec = "tls"
	}
	s := map[string]any{"network": n, "security": sec}
	if str(m["type"]) != "" && str(m["type"]) != "none" {
		return p, fail("unsupported_header")
	}
	if sec == "tls" {
		v := map[string]any{}
		if str(m["sni"]) != "" {
			v["serverName"] = m["sni"]
		}
		if str(m["fp"]) != "" {
			v["fingerprint"] = m["fp"]
		}
		if str(m["alpn"]) != "" {
			a := []any{}
			for _, x := range strings.Split(str(m["alpn"]), ",") {
				a = append(a, x)
			}
			v["alpn"] = a
		}
		s["tlsSettings"] = v
	}
	if n == "ws" {
		s["wsSettings"] = map[string]any{"path": str(m["path"]), "headers": map[string]any{"Host": str(m["host"])}}
	}
	if n == "grpc" {
		s["grpcSettings"] = map[string]any{"serviceName": str(m["path"])}
	}
	cipher := str(m["scy"])
	if cipher == "" {
		cipher = "auto"
	}
	user := map[string]any{"id": str(m["id"]), "alterId": float64(0), "security": cipher}
	srv := map[string]any{"address": str(m["add"]), "port": float64(num(m["port"])), "users": []any{user}}
	p.Outbound = map[string]any{"protocol": "vmess", "settings": map[string]any{"vnext": []any{srv}}, "streamSettings": s}
	return p, nil
}

func Endpoint(p model.Profile) string {
	s := obj(p.Outbound["settings"])
	k := "servers"
	if p.Outbound["protocol"] == "vless" || p.Outbound["protocol"] == "vmess" {
		k = "vnext"
	}
	a := obj(list(s[k])[0])
	return net.JoinHostPort(str(a["address"]), strconv.Itoa(number(a["port"])))
}
func Clone(p model.Profile) model.Profile {
	b, _ := json.Marshal(p)
	var c model.Profile
	_ = json.Unmarshal(b, &c)
	return c
}
