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
		rec := Record{ID: id}
		safe := safeURI(string(line))
		if ctx.Err() != nil {
			return fail(contextErrorCode(ctx))
		}
		if !safe {
			rec.Reason = "unsupported_record"
		} else if result, err := importer.Parse(line, "uri"); err != nil || len(result.Profiles) != 1 {
			rec.Reason = "unsupported_record"
		} else {
			rec.Supported = true
			batch.profiles[id] = result.Profiles[0]
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

func safeURI(raw string) bool {
	unescaped, err := url.PathUnescape(raw)
	if err != nil || !utf8.ValidString(unescaped) {
		return false
	}
	if strings.HasPrefix(raw, "vmess://") {
		encoded := strings.SplitN(strings.TrimPrefix(raw, "vmess://"), "#", 2)[0]
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
	if strings.HasPrefix(raw, "ss://") && !strings.Contains(raw, "@") {
		encoded := strings.SplitN(strings.TrimPrefix(raw, "ss://"), "#", 2)[0]
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
