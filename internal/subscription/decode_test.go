package subscription

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

func decodeLimits() DecodeLimits {
	return DecodeLimits{MaxInputBytes: 8 << 20, MaxDecodedBytes: 8 << 20, MaxRecordBytes: 8 << 20, MaxRecords: 10000}
}
func TestSubscriptionDecodePure(t *testing.T) {
	raw := "vless://synthetic-secret@node.invalid:443?security=tls\nunknown://synthetic-secret@node.invalid:1\n"
	for _, enc := range []*base64.Encoding{base64.StdEncoding, base64.RawStdEncoding, base64.URLEncoding, base64.RawURLEncoding} {
		batch, err := ParseSubscription(context.Background(), []byte(enc.EncodeToString([]byte(raw))), decodeLimits())
		if err != nil || len(batch.Records) != 2 || !batch.Records[0].Supported || batch.Records[1].Supported {
			t.Fatal("unexpected batch", err)
		}
		b, err := json.Marshal(batch)
		if err != nil || strings.Contains(string(b), "synthetic-secret") || strings.Contains(string(b), "node.invalid") {
			t.Fatal("unsafe projection")
		}
	}
	b, err := ParseSubscription(context.Background(), []byte(raw+raw), decodeLimits())
	if err != nil || len(b.Records) != 4 || b.Records[0].ID == b.Records[2].ID {
		t.Fatal("duplicate identity", err)
	}
	if _, ok := b.Profile(b.Records[0].ID); !ok {
		t.Fatal("private mapping")
	}
	for _, view := range []string{fmt.Sprintf("%v", b), fmt.Sprintf("%+v", b), fmt.Sprintf("%#v", b)} {
		if strings.Contains(view, "synthetic-secret") {
			t.Fatal("unsafe batch formatting")
		}
	}
	for _, raw := range []string{"vless://synthetic-secret@node.invalid:443?security=reality&alpn=h2", "vless://synthetic-secret@node.invalid:443?mux=true", "vless://synthetic-secret@node.invalid:443?type=tcp&type=ws"} {
		_, err := ParseSubscription(context.Background(), []byte(raw), decodeLimits())
		if model.Code(err) != "no_supported_profiles" {
			t.Fatal("unsafe record accepted")
		}
	}
}
func TestSubscriptionDecodeBoundsPure(t *testing.T) {
	raw := []byte("socks5://node.invalid:1")
	for _, delta := range []int{-1, 0, 1} {
		limits := decodeLimits()
		limits.MaxRecordBytes = len(raw) + delta
		_, err := ParseSubscription(context.Background(), raw, limits)
		if (err == nil) != (delta >= 0) {
			t.Fatal("record boundary", delta, err)
		}
	}
	for _, delta := range []int{-1, 0, 1} {
		limits := decodeLimits()
		limits.MaxInputBytes = len(raw) + delta
		_, err := ParseSubscription(context.Background(), raw, limits)
		if (err == nil) != (delta >= 0) {
			t.Fatal("input boundary", delta, err)
		}
	}
	limits := decodeLimits()
	limits.MaxRecords = 1
	if _, err := ParseSubscription(context.Background(), []byte(string(raw)+"\n"+string(raw)), limits); model.Code(err) != "record_count" {
		t.Fatal("record budget")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := ParseSubscription(ctx, raw, decodeLimits()); model.Code(err) != "cancelled" {
		t.Fatal("cancel")
	}
	for _, v := range [][]byte{nil, []byte("%%%"), {0xff}} {
		if _, err := ParseSubscription(context.Background(), v, decodeLimits()); err == nil {
			t.Fatal("invalid input")
		}
	}
}

func TestSubscriptionEnvelopePure(t *testing.T) {
	raw := []byte("socks5://node.invalid:1")
	encoded := base64.StdEncoding.EncodeToString(raw)
	encoded = encoded[:4] + "\r\n" + encoded[4:]
	if _, err := ParseSubscription(context.Background(), []byte(encoded), decodeLimits()); err != nil {
		t.Fatal("CRLF", err)
	}
	for _, invalid := range []string{"AA+_", "AAA===", "eA==="} {
		if _, err := ParseSubscription(context.Background(), []byte(invalid), decodeLimits()); err == nil {
			t.Fatal("malformed envelope accepted")
		}
	}
	for _, delta := range []int{-1, 0, 1} {
		limits := decodeLimits()
		limits.MaxDecodedBytes = len(raw) + delta
		_, err := ParseSubscription(context.Background(), []byte(base64.StdEncoding.EncodeToString(raw)), limits)
		if (err == nil) != (delta >= 0) {
			t.Fatal("decoded boundary", delta)
		}
	}
}

func TestSubscriptionAggregateLimitPure(t *testing.T) {
	good := "socks5://node.invalid:1\n"
	bad := "unknown://node.invalid:1\n"
	for _, count := range []int{9999, 10000, 10001} {
		data := []byte(good + strings.Repeat(bad, count-1))
		batch, err := ParseSubscription(context.Background(), data, decodeLimits())
		if count <= 10000 {
			if err != nil || len(batch.Records) != count || len(batch.profiles) != 1 {
				t.Fatal("aggregate count", count)
			}
		} else if model.Code(err) != "record_count" {
			t.Fatal("aggregate bound", count)
		}
	}
}

func TestSubscriptionProfileIsolationPure(t *testing.T) {
	batch, err := ParseSubscription(context.Background(), []byte("socks5://node.invalid:1"), decodeLimits())
	if err != nil {
		t.Fatal(err)
	}
	id := batch.Records[0].ID
	first, ok := batch.Profile(id)
	if !ok {
		t.Fatal("missing profile")
	}
	first.Outbound["settings"].(map[string]any)["servers"].([]any)[0].(map[string]any)["address"] = "changed.invalid"
	second, ok := batch.Profile(id)
	if !ok || second.Outbound["settings"].(map[string]any)["servers"].([]any)[0].(map[string]any)["address"] != "node.invalid" {
		t.Fatal("profile mapping mutated")
	}
}

func TestSubscriptionInvalidNestedUTF8Pure(t *testing.T) {
	vmess := append([]byte(`{"v":"2","add":"node.invalid","port":"1","id":"`), 0xff)
	vmess = append(vmess, []byte(`"}`)...)
	ssAuth := append([]byte("aes-128-gcm:"), 0xff)
	ssFull := append(append([]byte("aes-128-gcm:"), 0xff), []byte("@node.invalid:1")...)
	for _, raw := range []string{
		"vless://%FF@node.invalid:443", "vless://synthetic@node.invalid:443?security=tls&sni=%FF", "vmess://" + base64.StdEncoding.EncodeToString(vmess),
		"ss://" + base64.RawURLEncoding.EncodeToString(ssAuth) + "@node.invalid:1", "ss://" + base64.RawURLEncoding.EncodeToString(ssFull),
	} {
		batch, err := ParseSubscription(context.Background(), []byte(raw), decodeLimits())
		if model.Code(err) != "no_supported_profiles" || len(batch.profiles) != 0 {
			t.Fatal("invalid nested UTF-8 accepted")
		}
	}
}

func TestSubscriptionVMessSurrogatesPure(t *testing.T) {
	for _, tc := range []struct {
		id      string
		allowed bool
	}{{`\uD800`, false}, {`\uDC00`, false}, {`\uD800\u0041`, false}, {`\uD83D\uDE00`, true}, {`\\uD800`, true}} {
		payload := `{"v":"2","add":"node.invalid","port":"1","id":"` + tc.id + `"}`
		raw := "vmess://" + base64.StdEncoding.EncodeToString([]byte(payload))
		batch, err := ParseSubscription(context.Background(), []byte(raw), decodeLimits())
		if (err == nil) != tc.allowed {
			t.Fatal("surrogate handling")
		}
		if tc.id == `\uD83D\uDE00` {
			p, ok := batch.Profile(batch.Records[0].ID)
			if !ok {
				t.Fatal("missing profile")
			}
			settings := p.Outbound["settings"].(map[string]any)
			server := settings["vnext"].([]any)[0].(map[string]any)
			user := server["users"].([]any)[0].(map[string]any)
			if user["id"] != "😀" {
				t.Fatal("surrogate credential changed")
			}
		}
	}
}

type cancelAtCheck struct {
	calls int
	at    int
}

func (c *cancelAtCheck) Deadline() (time.Time, bool) { return time.Time{}, false }
func (c *cancelAtCheck) Done() <-chan struct{}       { return nil }
func (c *cancelAtCheck) Err() error {
	c.calls++
	if c.calls >= c.at {
		return context.Canceled
	}
	return nil
}
func (c *cancelAtCheck) Value(any) any { return nil }
func TestSubscriptionCancelLastRecordPure(t *testing.T) {
	for _, check := range []int{4, 5, 6} {
		ctx := &cancelAtCheck{at: check}
		_, err := ParseSubscription(ctx, []byte("socks5://node.invalid:1"), decodeLimits())
		if model.Code(err) != "cancelled" {
			t.Fatal("last record cancellation lost", check)
		}
	}
}

func TestSubscriptionContextClassificationPure(t *testing.T) {
	base, cancel := context.WithCancel(context.Background())
	cancel()
	for _, tc := range []struct {
		ctx  context.Context
		code string
	}{{base, "cancelled"}, {controlledDeadline{base}, "subscription_timeout"}} {
		_, err := ParseSubscription(tc.ctx, []byte("socks5://node.invalid:1"), decodeLimits())
		if model.Code(err) != tc.code {
			t.Fatal("context classification")
		}
	}
}
