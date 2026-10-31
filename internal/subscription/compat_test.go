package subscription

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"reflect"
	"strings"
	"testing"

	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

func TestSubscriptionVLESSProfileGoldensPure(t *testing.T) {
	for _, tc := range []struct {
		name string
		uri  string
		want string
	}{
		{
			name: "tcp_tls",
			uri:  "vless://synthetic-id@node.invalid:443?type=tcp&security=tls&sni=tls.invalid&fp=chrome&alpn=h2%2Chttp%2F1.1&x-durev-flag=synthetic-hint",
			want: `{"outbound":{"protocol":"vless","settings":{"vnext":[{"address":"node.invalid","port":443,"users":[{"id":"synthetic-id","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"tls.invalid","fingerprint":"chrome","alpn":["h2","http/1.1"]}}}}`,
		},
		{
			name: "tcp_reality",
			uri:  "vless://synthetic-id@node.invalid:443?type=tcp&security=reality&sni=cover.invalid&fp=firefox&pbk=synthetic-public-key&sid=abc123&spx=%2Ffront",
			want: `{"outbound":{"protocol":"vless","settings":{"vnext":[{"address":"node.invalid","port":443,"users":[{"id":"synthetic-id","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"cover.invalid","fingerprint":"firefox","publicKey":"synthetic-public-key","shortId":"abc123","spiderX":"/front"}}}}`,
		},
		{
			name: "ws_tls",
			uri:  "vless://synthetic-id@node.invalid:443?type=ws&security=tls&sni=tls.invalid&fp=safari&alpn=h2&host=ws.invalid&path=%2Fsocket",
			want: `{"outbound":{"protocol":"vless","settings":{"vnext":[{"address":"node.invalid","port":443,"users":[{"id":"synthetic-id","encryption":"none"}]}]},"streamSettings":{"network":"ws","security":"tls","tlsSettings":{"serverName":"tls.invalid","fingerprint":"safari","alpn":["h2"]},"wsSettings":{"path":"/socket","headers":{"Host":"ws.invalid"}}}}}`,
		},
	} {
		t.Run(tc.name, func(t *testing.T) {
			batch, err := ParseSubscription(context.Background(), []byte(tc.uri), decodeLimits())
			if err != nil || len(batch.Records) != 1 || !batch.Records[0].Supported {
				t.Fatalf("subscription record rejected: %v", err)
			}
			profile, ok := batch.Profile(batch.Records[0].ID)
			if !ok {
				t.Fatal("profile missing")
			}
			var want model.Profile
			if err := json.Unmarshal([]byte(tc.want), &want); err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(profile, want) {
				got, _ := json.Marshal(profile)
				t.Fatalf("profile = %s, want %s", got, tc.want)
			}
		})
	}
}

func TestSubscriptionUnknownQueriesRemainStrictPure(t *testing.T) {
	base := "vless://synthetic-id@node.invalid:443?type=tcp&security=tls"
	for _, tc := range []struct {
		name  string
		query string
	}{
		{"standard", "&unknown=synthetic"},
		{"mux_functional", "&mux=true&muxConcurrency=8&unknown=synthetic"},
		{"fragment_functional", "&fragment=50-100%2C10-20%2Ctlshello&unknown=synthetic"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			batch, err := ParseSubscription(context.Background(), []byte(base+tc.query), decodeLimits())
			if model.Code(err) != "no_supported_profiles" || len(batch.Records) != 1 || batch.Records[0].Supported {
				t.Fatalf("unknown query accepted: records=%+v err=%v", batch.Records, err)
			}
		})
	}
}

func TestSubscriptionMuxAndFragmentGoldenPure(t *testing.T) {
	raw := "vless://synthetic-id@node.invalid:443?type=tcp&security=tls&sni=tls.invalid&fragment=50-100%2C10-20%2Ctlshello%2C4&mux=true&muxConcurrency=12&x-durev-flag=synthetic-hint"
	batch, err := ParseSubscription(context.Background(), []byte(raw), decodeLimits())
	if err != nil || len(batch.Records) != 1 || !batch.Records[0].Supported {
		t.Fatalf("subscription record rejected: %v", err)
	}
	profile, ok := batch.Profile(batch.Records[0].ID)
	if !ok {
		t.Fatal("profile missing")
	}
	wantFragment := &model.FragmentBaseline{Length: "50-100", Interval: "10-20", Packets: "tlshello", MaxSplit: func() *string { value := "4"; return &value }()}
	wantMux := map[string]any{"enabled": true, "concurrency": float64(12)}
	if !reflect.DeepEqual(profile.Fragment, wantFragment) || !reflect.DeepEqual(profile.Outbound["mux"], wantMux) {
		t.Fatalf("profile baseline changed: fragment=%+v mux=%v", profile.Fragment, profile.Outbound["mux"])
	}
	encoded, err := json.Marshal(profile)
	if err != nil {
		t.Fatal(err)
	}
	var golden map[string]any
	if err := json.Unmarshal(encoded, &golden); err != nil {
		t.Fatal(err)
	}
	fragment := golden["fragment"].(map[string]any)
	if len(fragment) != 4 || fragment["length"] != "50-100" || fragment["interval"] != "10-20" || fragment["packets"] != "tlshello" || fragment["maxSplit"] != "4" {
		t.Fatalf("fragment tuple/order/count changed: %s", encoded)
	}
	if strings.Contains(string(encoded), "synthetic-hint") {
		t.Fatal("provider hint was retained")
	}

	override := capability.Effective(profile, map[string]any{"mux": true, "tcpConcurrency": float64(20)})
	if override["mux"] != true || override["tcpConcurrency"] != float64(20) || override["xudpConcurrency"] != float64(0) || override["quic"] != "reject" {
		t.Fatalf("explicit mux override changed: %v", override)
	}
}

func TestSubscriptionOuterBase64DisplayTitlePure(t *testing.T) {
	uri := "vless://synthetic-id@node.invalid:443#%E6%9D%B1%E4%BA%AC%20region-2025"
	batch, err := ParseSubscription(context.Background(), []byte(base64.StdEncoding.EncodeToString([]byte(uri))), decodeLimits())
	if err != nil || len(batch.Records) != 1 || !batch.Records[0].Supported {
		t.Fatalf("outer Base64 subscription rejected: %v", err)
	}
	if batch.Records[0].Title != "東京 region-2025" {
		t.Fatalf("outer Base64 title = %q", batch.Records[0].Title)
	}
	encoded, err := json.Marshal(batch)
	if err != nil || strings.Contains(string(encoded), "synthetic-id") || strings.Contains(string(encoded), "node.invalid") {
		t.Fatalf("outer Base64 display leaked source data: %s, %v", encoded, err)
	}
}

func TestSafeDisplayLabelPreservesValidDecodedNamesPure(t *testing.T) {
	for _, tc := range []struct {
		fragment string
		want     string
	}{
		{"Zone.2", "Zone.2"},
		{"EU-2025", "EU-2025"},
		{"NorthernTerritory", "NorthernTerritory"},
		{"12345", "12345"},
		{"station+east", "station+east"},
		{"US/East", "US/East"},
		{"US%2FEast", "US/East"},
		{"NY?2", "NY?2"},
		{"Tier&Zone", "Tier&Zone"},
		{"Name%3A%20West", "Name: West"},
		{"100%25", "100%"},
		{"Łódź-東京", "Łódź-東京"},
	} {
		uri := "vless://synthetic-id@node.invalid:443#" + tc.fragment
		batch, err := ParseSubscription(context.Background(), []byte(uri), decodeLimits())
		if err != nil || len(batch.Records) != 1 || batch.Records[0].Title != tc.want {
			var title string
			if len(batch.Records) > 0 {
				title = batch.Records[0].Title
			}
			t.Errorf("valid title %q returned %q: err=%v", tc.want, title, err)
		}
	}
}

func TestSafeDisplayLabelRejectsURLFormsPure(t *testing.T) {
	for _, fragment := range []string{"HTTPS://other.invalid", "//other.invalid/path"} {
		uri := "vless://synthetic-id@node.invalid:443#" + fragment
		batch, err := ParseSubscription(context.Background(), []byte(uri), decodeLimits())
		if err != nil || len(batch.Records) != 1 || batch.Records[0].Title != "Сервер 1" {
			var title string
			if len(batch.Records) > 0 {
				title = batch.Records[0].Title
			}
			t.Errorf("URL-like title fragment %q returned %q: err=%v", fragment, title, err)
		}
	}
}

func TestSubscriptionFunctionalOptionParsingPure(t *testing.T) {
	raw := "vless://synthetic@node.invalid:443?security=tls&fragment=100-200%2C10-20%2Ctlshello&mux=true&muxConcurrency=8&unknown=keep"
	cleaned, opts, ok := extractFunctionalOptions(raw)
	if !ok || cleaned != "vless://synthetic@node.invalid:443?security=tls&unknown=keep" || opts.fragment == nil || opts.fragment.length != "100-200" || opts.fragment.interval != "10-20" || opts.fragment.packets != "tlshello" || opts.fragment.maxSplit != nil || opts.muxConcurrency == nil || *opts.muxConcurrency != 8 {
		t.Fatal("functional mapping")
	}
	_, opts, ok = extractFunctionalOptions("vless://synthetic@node.invalid:443?fragment=1,0,tlshello,2")
	if !ok || opts.fragment == nil || opts.fragment.maxSplit == nil || *opts.fragment.maxSplit != "2" {
		t.Fatal("optional maxSplit")
	}
	for _, q := range []string{"mux=true", "muxConcurrency=8", "mux=false&muxConcurrency=8", "mux=true&muxConcurrency=0", "mux=true&muxConcurrency=129", "mux=true&muxConcurrency=+8", "mux=true&muxConcurrency=8&muxConcurrency=8", "fragment=1,0", "fragment=0,0,tlshello", "fragment=1,1001,tlshello", "fragment=1,0,unknown", "fragment=1,0,tlshello,0", "fragment=1,0,tlshello&fragment=1,0,tlshello"} {
		if _, _, ok := extractFunctionalOptions("vless://synthetic@node.invalid:443?" + q); ok {
			t.Fatal("invalid option accepted")
		}
	}
	for _, q := range []string{"mux=true&muxConcurrency=1", "mux=true&muxConcurrency=128", "fragment=1,0,tlshello", "fragment=16384,1000,tlshello,1024"} {
		if _, _, ok := extractFunctionalOptions("vless://synthetic@node.invalid:443?" + q); !ok {
			t.Fatal("boundary option rejected")
		}
	}
}

func TestSubscriptionFunctionalRecordsPure(t *testing.T) {
	raw := "vless://synthetic-id@node.invalid:443?type=tcp&security=tls&sni=tls.invalid&fragment=100-200%2C10-20%2Ctlshello&mux=true&muxConcurrency=8&x-durev-flag=synthetic-hint"
	batch, err := ParseSubscription(context.Background(), []byte(raw), decodeLimits())
	if err != nil || len(batch.Records) != 1 || !batch.Records[0].Supported {
		t.Fatal("functional record rejected")
	}
	p, ok := batch.Profile(batch.Records[0].ID)
	if !ok || p.Fragment == nil || p.Fragment.Length != "100-200" || p.Fragment.Interval != "10-20" || p.Fragment.Packets != "tlshello" || p.Fragment.MaxSplit != nil {
		t.Fatal("baseline fragment lost")
	}
	mux, ok := p.Outbound["mux"].(map[string]any)
	if !ok || mux["enabled"] != true || mux["concurrency"] != float64(8) {
		t.Fatal("mux baseline lost")
	}
	stream := p.Outbound["streamSettings"].(map[string]any)
	tlsSettings := stream["tlsSettings"].(map[string]any)
	if stream["security"] != "tls" || tlsSettings["serverName"] != "tls.invalid" {
		t.Fatal("TLS changed")
	}
	effective := capability.Effective(p, nil)
	if effective["fragment"] != true || effective["length"] != "100-200" || effective["mux"] != true || effective["tcpConcurrency"] != float64(8) || effective["xudpConcurrency"] != float64(0) || effective["quic"] != "reject" {
		t.Fatal("effective baseline changed")
	}
	b, _ := json.Marshal(batch)
	if strings.Contains(string(b), "synthetic-hint") || strings.Contains(string(b), "synthetic-id") {
		t.Fatal("projection leaked")
	}
}
func TestSubscriptionFunctionalRejectionsPure(t *testing.T) {
	base := "vless://synthetic-id@node.invalid:443?type=tcp&security=tls"
	for _, q := range []string{
		"mux=true", "muxConcurrency=8", "mux=false&muxConcurrency=8", "mux=true&muxConcurrency=0", "mux=true&muxConcurrency=129", "mux=true&muxConcurrency=8&muxConcurrency=8",
		"fragment=0,0,tlshello", "fragment=1,1001,tlshello", "fragment=1,0,unknown", "fragment=1,0,tlshello,0", "fragment=1,0,tlshello&fragment=1,0,tlshello",
		"mux=true&muxConcurrency=8&unknown=secret-marker", "fragment=1,0,tlshello&security=tls", "mux=true&muxConcurrency=8&flow=xtls-rprx-vision",
	} {
		batch, err := ParseSubscription(context.Background(), []byte(base+"&"+q), decodeLimits())
		if model.Code(err) != "no_supported_profiles" || len(batch.Records) != 1 || batch.Records[0].Supported {
			t.Fatal("unsupported functional option accepted")
		}
	}
	raw := "vless://synthetic-id@node.invalid:443?type=tcp&security=none&fragment=1,0,tlshello"
	if _, err := ParseSubscription(context.Background(), []byte(raw), decodeLimits()); model.Code(err) != "no_supported_profiles" {
		t.Fatal("fragment applied to incompatible security")
	}
}

func TestSubscriptionVMessFunctionalOptionsPure(t *testing.T) {
	payload := `{"v":"2","add":"node.invalid","port":"443","id":"synthetic-id","net":"tcp","tls":"tls","sni":"tls.invalid"}`
	uri := "vmess://" + base64.StdEncoding.EncodeToString([]byte(payload)) + "?fragment=1,0,tlshello&mux=true&muxConcurrency=8&x-durev-flag=synthetic-hint"
	batch, err := ParseSubscription(context.Background(), []byte(uri), decodeLimits())
	if err != nil || len(batch.Records) != 1 || !batch.Records[0].Supported {
		t.Fatal("VMess functional record rejected")
	}
	p, ok := batch.Profile(batch.Records[0].ID)
	if !ok || p.Fragment == nil || p.Fragment.Length != "1" || p.Outbound["mux"].(map[string]any)["concurrency"] != float64(8) {
		t.Fatal("VMess baseline lost")
	}
	if _, err := ParseSubscription(context.Background(), []byte(uri+"&unknown=synthetic"), decodeLimits()); model.Code(err) != "no_supported_profiles" {
		t.Fatal("unknown VMess query accepted")
	}
}

func TestSubscriptionVMessEmptyQuerySegmentsPure(t *testing.T) {
	payload := `{"v":"2","add":"node.invalid","port":"443","id":"synthetic-id","net":"tcp","tls":"tls","sni":"tls.invalid"}`
	base := "vmess://" + base64.StdEncoding.EncodeToString([]byte(payload))
	for _, suffix := range []string{"", "?x-durev-flag=synthetic&", "?&x-durev-flag=synthetic&&", "?fragment=1,0,tlshello&", "?mux=true&muxConcurrency=8&&"} {
		batch, err := ParseSubscription(context.Background(), []byte(base+suffix), decodeLimits())
		if err != nil || len(batch.Records) != 1 || !batch.Records[0].Supported {
			t.Fatal("empty query segment blocked VMess")
		}
	}
	for _, suffix := range []string{"?x-durev-flag=synthetic&&unknown=value", "?fragment=1,0,tlshello&&unknown=value", "?x-durev-flag=%ZZ&"} {
		if _, err := ParseSubscription(context.Background(), []byte(base+suffix), decodeLimits()); model.Code(err) != "no_supported_profiles" {
			t.Fatal("strict query handling changed")
		}
	}
}

func TestSubscriptionFullBase64SSFragmentPure(t *testing.T) {
	payload := "aes-128-gcm:synthetic-password@node.invalid:8388"
	base := "ss://" + base64.RawURLEncoding.EncodeToString([]byte(payload))
	batch, err := ParseSubscription(context.Background(), []byte(base+"?fragment=1,0,1-1&x-durev-flag=synthetic"), decodeLimits())
	if err != nil || len(batch.Records) != 1 || !batch.Records[0].Supported {
		t.Fatal("full Base64 SS fragment rejected")
	}
	p, ok := batch.Profile(batch.Records[0].ID)
	if !ok || p.Fragment == nil || p.Fragment.Length != "1" || p.Fragment.Interval != "0" || p.Fragment.Packets != "1-1" {
		t.Fatal("SS baseline fragment lost")
	}
	for _, suffix := range []string{"?fragment=1,0,tlshello", "?fragment=1,0,1-1&unknown=x", "?fragment=1,0,1-1&fragment=1,0,1-1", "?fragment=1,0,1-1&x-durev-flag=%ZZ", "?mux=true&muxConcurrency=8"} {
		if _, err := ParseSubscription(context.Background(), []byte(base+suffix), decodeLimits()); model.Code(err) != "no_supported_profiles" {
			t.Fatal("unsafe full Base64 SS options accepted")
		}
	}
}
