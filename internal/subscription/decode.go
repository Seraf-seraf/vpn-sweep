package subscription

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net/url"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

const maxSubscriptionRecords = 10000

type DecodeLimits struct{ MaxInputBytes, MaxDecodedBytes, MaxRecordBytes, MaxRecords int }
type Record struct {
	ID        string `json:"id"`
	Title     string `json:"title,omitempty"`
	Subtitle  string `json:"subtitle,omitempty"`
	Supported bool   `json:"supported"`
	Reason    string `json:"reason,omitempty"`
}
type Batch struct {
	Records  []Record `json:"records"`
	profiles map[string]model.Profile
}

func (b Batch) String() string   { return "subscription batch" }
func (b Batch) GoString() string { return "subscription batch" }
func (b Batch) Profile(id string) (model.Profile, bool) {
	p, ok := b.profiles[id]
	if !ok {
		return model.Profile{}, false
	}
	return importer.Clone(p), true
}

func ParseSubscription(ctx context.Context, data []byte, limits DecodeLimits) (Batch, error) {
	var batch Batch
	fail := func(code string) (Batch, error) { return Batch{}, model.Err(code, "subscription") }
	if limits.MaxInputBytes <= 0 || limits.MaxDecodedBytes <= 0 || limits.MaxRecordBytes <= 0 || limits.MaxRecords <= 0 || limits.MaxRecords > maxSubscriptionRecords || limits.MaxInputBytes > importer.MaxInputBytes || limits.MaxDecodedBytes > importer.MaxInputBytes || limits.MaxRecordBytes > importer.MaxInputBytes {
		return fail("invalid_limits")
	}
	if err := ctx.Err(); err != nil {
		return fail(contextErrorCode(ctx))
	}
	if len(data) == 0 {
		return fail("empty_input")
	}
	if len(data) > limits.MaxInputBytes {
		return fail("input_too_large")
	}
	if !utf8.Valid(data) {
		return fail("invalid_utf8")
	}
	data = bytes.TrimSpace(data)
	if len(data) == 0 {
		return fail("empty_input")
	}
	if !bytes.Contains(data, []byte("://")) && data[0] != '#' {
		compact := bytes.ReplaceAll(bytes.ReplaceAll(data, []byte("\r"), nil), []byte("\n"), nil)
		decodedLen := len(compact) / 4 * 3
		if rem := len(compact) % 4; rem != 0 {
			decodedLen += rem * 3 / 4
		} else {
			if bytes.HasSuffix(compact, []byte("==")) {
				decodedLen -= 2
			} else if bytes.HasSuffix(compact, []byte("=")) {
				decodedLen--
			}
		}
		if decodedLen > limits.MaxDecodedBytes {
			return fail("decoded_too_large")
		}
		var decoded []byte
		var found bool
		for _, enc := range []*base64.Encoding{base64.StdEncoding.Strict(), base64.RawStdEncoding.Strict(), base64.URLEncoding.Strict(), base64.RawURLEncoding.Strict()} {
			b, err := enc.DecodeString(string(compact))
			if err == nil {
				if found && !bytes.Equal(decoded, b) {
					return fail("invalid_envelope")
				}
				decoded = b
				found = true
			}
		}
		if !found {
			return fail("invalid_envelope")
		}
		data = decoded
	}
	if ctx.Err() != nil {
		return fail(contextErrorCode(ctx))
	}
	if len(data) > limits.MaxDecodedBytes {
		return fail("decoded_too_large")
	}
	if !utf8.Valid(data) {
		return fail("invalid_utf8")
	}
	batch.profiles = make(map[string]model.Profile)
	count := 0
	seen := make(map[string]struct{})
	for len(data) > 0 {
		line, rest, _ := bytes.Cut(data, []byte("\n"))
		data = rest
		if ctx.Err() != nil {
			return fail(contextErrorCode(ctx))
		}
		line = bytes.TrimSpace(line)
		if len(line) == 0 || line[0] == '#' {
			continue
		}
		count++
		if count > limits.MaxRecords {
			return fail("record_count")
		}
		if len(line) > limits.MaxRecordBytes {
			return fail("record_too_large")
		}
		var idbytes [16]byte
		if _, err := rand.Read(idbytes[:]); err != nil {
			return fail("id_unavailable")
		}
		id := hex.EncodeToString(idbytes[:])
		for {
			if _, exists := seen[id]; !exists {
				break
			}
			if _, err := rand.Read(idbytes[:]); err != nil {
				return fail("id_unavailable")
			}
			id = hex.EncodeToString(idbytes[:])
		}
		seen[id] = struct{}{}
		rec := Record{ID: id, Title: serverTitle(count)}
		safe := safeURI(string(line))
		if ctx.Err() != nil {
			return fail(contextErrorCode(ctx))
		}
		if !safe {
			rec.Reason = "unsupported_record"
		} else if profile, ok := convertSubscriptionRecord(string(line)); !ok {
			rec.Reason = "unsupported_record"
		} else {
			rec.Supported = true
			rec.Title = displayTitle(string(line), count, profile)
			batch.profiles[id] = profile
		}
		if ctx.Err() != nil {
			return fail(contextErrorCode(ctx))
		}
		batch.Records = append(batch.Records, rec)
	}
	if ctx.Err() != nil {
		return fail(contextErrorCode(ctx))
	}
	if count == 0 {
		return batch, model.Err("empty_input", "subscription")
	}
	if len(batch.profiles) == 0 {
		return batch, model.Err("no_supported_profiles", "subscription")
	}
	return batch, nil
}

const maxDisplayTitleRunes = 64

func serverTitle(ordinal int) string {
	return "Сервер " + strconv.Itoa(ordinal)
}

func displayTitle(raw string, ordinal int, profile model.Profile) string {
	fallback := serverTitle(ordinal)
	u, err := url.Parse(raw)
	if err != nil {
		return fallback
	}
	fragment := strings.TrimSpace(u.Fragment)
	if hostname := u.Hostname(); hostname != "" && strings.EqualFold(fragment, hostname) {
		return fallback
	}
	if matchesUserinfoCredential(fragment, u.User) {
		return fallback
	}
	hosts, credentials, ok := profileDisplayValues(profile)
	if !ok || matchesProfileValue(fragment, hosts, true) || matchesProfileValue(fragment, credentials, false) {
		return fallback
	}
	return safeDisplayLabel(fragment, fallback)
}

func matchesUserinfoCredential(fragment string, user *url.Userinfo) bool {
	if user == nil {
		return false
	}
	if matchesCredential(fragment, user.Username()) {
		return true
	}
	password, hasPassword := user.Password()
	return hasPassword && matchesCredential(fragment, password)
}

func matchesCredential(fragment string, credential string) bool {
	if credential == "" {
		return false
	}
	return fragment == credential || (utf8.RuneCountInString(credential) >= 5 && strings.Contains(fragment, credential))
}

func matchesProfileValue(fragment string, values []string, caseInsensitive bool) bool {
	for _, value := range values {
		if caseInsensitive {
			if strings.Contains(strings.ToLower(fragment), strings.ToLower(value)) {
				return true
			}
		} else if strings.Contains(fragment, value) {
			return true
		}
	}
	return false
}

func profileDisplayValues(profile model.Profile) ([]string, []string, bool) {
	outbound := profile.Outbound
	protocol, ok := outbound["protocol"].(string)
	if !ok {
		return nil, nil, false
	}
	settings, ok := outbound["settings"].(map[string]any)
	if !ok {
		return nil, nil, false
	}
	listKey := "servers"
	credentialKey := "password"
	switch protocol {
	case "vless", "vmess":
		listKey = "vnext"
		credentialKey = "id"
	case "trojan", "shadowsocks":
	case "socks":
		credentialKey = ""
	default:
		return nil, nil, false
	}
	server := firstProfileMap(settings[listKey])
	if server == nil {
		return nil, nil, false
	}
	address, ok := server["address"].(string)
	if !ok || address == "" {
		return nil, nil, false
	}
	hosts := []string{address}
	var credentials []string
	if credentialKey != "" {
		credential, ok := serverCredential(server, protocol, credentialKey)
		if !ok || credential == "" {
			return nil, nil, false
		}
		credentials = append(credentials, credential)
	} else if usersValue, exists := server["users"]; exists {
		users, ok := usersValue.([]any)
		if !ok || len(users) != 1 {
			return nil, nil, false
		}
		user, ok := users[0].(map[string]any)
		if !ok {
			return nil, nil, false
		}
		for _, key := range []string{"user", "pass"} {
			value, ok := user[key].(string)
			if !ok {
				return nil, nil, false
			}
			if value != "" {
				credentials = append(credentials, value)
			}
		}
	}
	return hosts, credentials, true
}

func firstProfileMap(value any) map[string]any {
	items, ok := value.([]any)
	if !ok || len(items) != 1 {
		return nil
	}
	result, _ := items[0].(map[string]any)
	return result
}

func serverCredential(server map[string]any, protocol string, key string) (string, bool) {
	if protocol == "vless" || protocol == "vmess" {
		users, ok := server["users"].([]any)
		if !ok || len(users) != 1 {
			return "", false
		}
		user, ok := users[0].(map[string]any)
		if !ok {
			return "", false
		}
		value, ok := user[key].(string)
		return value, ok
	}
	value, ok := server[key].(string)
	return value, ok
}

func safeDisplayLabel(fragment string, fallback string) string {
	if !utf8.ValidString(fragment) {
		return fallback
	}
	for _, r := range fragment {
		if unicode.IsControl(r) || unicode.In(r, unicode.Cf) {
			return fallback
		}
	}
	title := strings.TrimSpace(fragment)
	titleRunes := utf8.RuneCountInString(title)
	if title == "" || titleRunes > maxDisplayTitleRunes {
		return fallback
	}
	lowerTitle := strings.ToLower(title)
	if strings.Contains(lowerTitle, "://") || strings.HasPrefix(title, "//") {
		return fallback
	}
	for _, r := range title {
		if !unicode.IsGraphic(r) && !unicode.IsSpace(r) {
			return fallback
		}
	}
	return title
}

func safeURI(raw string) bool {
	unescaped, err := url.PathUnescape(raw)
	if err != nil || !utf8.ValidString(unescaped) {
		return false
	}
	if strings.HasPrefix(raw, "vmess://") {
		encoded := strings.SplitN(strings.TrimPrefix(raw, "vmess://"), "#", 2)[0]
		if prefix, query, ok := strings.Cut(encoded, "?"); ok {
			if _, err := url.ParseQuery(query); err != nil {
				return false
			}
			encoded = prefix
		}
		var b []byte
		var err error
		for _, enc := range []*base64.Encoding{base64.StdEncoding.Strict(), base64.RawStdEncoding.Strict(), base64.URLEncoding.Strict(), base64.RawURLEncoding.Strict()} {
			b, err = enc.DecodeString(encoded)
			if err == nil {
				break
			}
		}
		if err != nil || !utf8.Valid(b) || !validSurrogates(b) {
			return false
		}
		var m map[string]json.RawMessage
		if model.Decode(b, &m) != nil {
			return false
		}
		for _, key := range []string{"v", "ps", "add", "id", "scy", "net", "type", "host", "path", "tls", "sni", "alpn", "fp"} {
			if v, ok := m[key]; ok {
				var value string
				if json.Unmarshal(v, &value) != nil || string(v) == "null" {
					return false
				}
			}
		}
		var version string
		if v, ok := m["v"]; ok {
			_ = json.Unmarshal(v, &version)
			if version != "" && version != "2" {
				return false
			}
		}
		if aid, ok := m["aid"]; ok && string(aid) != `"0"` && string(aid) != "0" {
			return false
		}
		var n, sec string
		_ = json.Unmarshal(m["net"], &n)
		_ = json.Unmarshal(m["tls"], &sec)
		present := func(k string) bool { v := m[k]; return len(v) > 0 && string(v) != `""` && string(v) != "null" }
		if sec != "tls" && (present("sni") || present("fp") || present("alpn")) {
			return false
		}
		if n != "ws" && present("host") {
			return false
		}
		if n != "ws" && n != "grpc" && present("path") {
			return false
		}
		return true
	}
	beforeFragment, _, _ := strings.Cut(raw, "#")
	authority, query, hasQuery := strings.Cut(beforeFragment, "?")
	if strings.HasPrefix(raw, "ss://") && !strings.Contains(authority, "@") {
		if hasQuery {
			if _, err := url.ParseQuery(query); err != nil {
				return false
			}
		}
		encoded := strings.TrimPrefix(authority, "ss://")
		for _, enc := range []*base64.Encoding{base64.StdEncoding, base64.RawStdEncoding, base64.URLEncoding, base64.RawURLEncoding} {
			if b, err := enc.DecodeString(encoded); err == nil {
				decoded, err := url.PathUnescape(string(b))
				return err == nil && utf8.ValidString(decoded)
			}
		}
		return false
	}
	u, err := url.Parse(raw)
	if err != nil {
		return false
	}
	if u.Scheme == "ss" {
		var encoded string
		if !strings.Contains(raw, "@") {
			encoded = strings.SplitN(strings.TrimPrefix(raw, "ss://"), "#", 2)[0]
		} else if u.User != nil {
			if _, ok := u.User.Password(); !ok {
				encoded = u.User.Username()
			}
		}
		if encoded != "" {
			valid := false
			for _, enc := range []*base64.Encoding{base64.StdEncoding, base64.RawStdEncoding, base64.URLEncoding, base64.RawURLEncoding} {
				b, err := enc.DecodeString(encoded)
				if err == nil {
					valid = utf8.Valid(b)
					break
				}
			}
			if !valid {
				return false
			}
		}
	}
	q, err := url.ParseQuery(u.RawQuery)
	if err != nil {
		return false
	}
	if q.Get("security") == "reality" && q.Has("alpn") {
		return false
	}
	return true
}

func validSurrogates(data []byte) bool {
	inString := false
	for i := 0; i < len(data); i++ {
		if !inString {
			if data[i] == '"' {
				inString = true
			}
			continue
		}
		if data[i] == '"' {
			inString = false
			continue
		}
		if data[i] != '\\' {
			continue
		}
		i++
		if i >= len(data) {
			return false
		}
		if data[i] != 'u' {
			continue
		}
		if i+4 >= len(data) {
			return false
		}
		value, err := strconv.ParseUint(string(data[i+1:i+5]), 16, 16)
		if err != nil {
			return false
		}
		i += 4
		if value >= 0xdc00 && value <= 0xdfff {
			return false
		}
		if value >= 0xd800 && value <= 0xdbff {
			if i+6 >= len(data) || data[i+1] != '\\' || data[i+2] != 'u' {
				return false
			}
			low, err := strconv.ParseUint(string(data[i+3:i+7]), 16, 16)
			if err != nil || low < 0xdc00 || low > 0xdfff {
				return false
			}
			i += 6
		}
	}
	return true
}

func stripProviderHints(raw string) string {
	beforeFragment, fragment, hasFragment := strings.Cut(raw, "#")
	base, query, hasQuery := strings.Cut(beforeFragment, "?")
	if !hasQuery {
		return raw
	}
	if _, err := url.ParseQuery(query); err != nil {
		return raw
	}
	kept := make([]string, 0)
	removed := false
	for _, part := range strings.Split(query, "&") {
		if part == "" {
			continue
		}
		key, _, _ := strings.Cut(part, "=")
		name, err := url.QueryUnescape(key)
		if err != nil {
			return raw
		}
		if strings.HasPrefix(name, "x-durev-") {
			removed = true
			continue
		}
		kept = append(kept, part)
	}
	if !removed {
		return raw
	}
	if len(kept) > 0 {
		base += "?" + strings.Join(kept, "&")
	}
	if hasFragment {
		base += "#" + fragment
	}
	return base
}
