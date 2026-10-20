package importer_test

import (
	"bytes"
	"context"
	"encoding/json"
	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/cli"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"reflect"
	"strings"
	"testing"
)

const happExport = `{
  "api":{"listen":"[::1]:10085","services":["StatsService"],"tag":"api"},
  "dns":{"hosts":{"domain:googleapis.cn":"googleapis.com"},"queryStrategy":"UseIPv4","servers":["1.1.1.1",{"address":"8.8.8.8","domains":[],"port":53}]},
  "inbounds":[{"listen":"127.0.0.1","port":10808,"protocol":"socks","settings":{"auth":"noauth","udp":true,"userLevel":8},"sniffing":{"destOverride":["http","tls","quic"],"enabled":true},"tag":"socks"}],
  "log":{"access":"/data/user/0/su.happ.proxyutility/files/logs/access/synthetic.txt","dnsLog":true,"error":"/data/user/0/su.happ.proxyutility/files/logs/error/synthetic.txt","loglevel":"debug"},
  "outbounds":[
    {
      "mux":{"concurrency":-1,"enabled":false,"xudpConcurrency":8,"xudpProxyUDP443":""},
      "protocol":"vless",
      "settings":{"address":"vpn.invalid","encryption":"none","flow":"","id":"synthetic-secret-marker","level":8,"port":443,"security":"auto"},
      "streamSettings":{"network":"ws","security":"tls","tlsSettings":{"fingerprint":"firefox","serverName":"tls.invalid","show":false},"wsSettings":{"headers":{"Host":"ws.invalid"},"path":"/synthetic/path"}},
      "tag":"proxy"
    },
    {"protocol":"freedom","tag":"direct"},
    {"protocol":"blackhole","tag":"block"}
  ],
  "policy":{"levels":{"8":{"connIdle":300,"downlinkOnly":1,"handshake":4,"uplinkOnly":1}},"system":{"statsOutboundUplink":true,"statsOutboundDownlink":true}},
  "remarks":"SYNTHETIC Happ export",
  "routing":{"domainStrategy":"IPIfNonMatch","rules":[{"ip":["1.1.1.1"],"outboundTag":"proxy","port":"53"},{"ip":["8.8.8.8"],"outboundTag":"direct","port":"53"}]},
  "stats":{}
}`

func TestHappExportImportsAndRendersWithoutEditing(t *testing.T) {
	r, err := importer.Parse([]byte(happExport), "auto")
	if err != nil {
		t.Fatal(err)
	}
	if len(r.Profiles) != 1 || importer.Endpoint(r.Profiles[0]) != "vpn.invalid:443" {
		t.Fatal("lost source endpoint")
	}
	var expected map[string]any
	if err = json.Unmarshal([]byte(`{"vnext":[{"address":"vpn.invalid","port":443,"users":[{"id":"synthetic-secret-marker","encryption":"none","flow":"","level":8,"security":"auto"}]}]}`), &expected); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(r.Profiles[0].Outbound["settings"], expected) {
		t.Fatal("flat settings changed meaning")
	}
	for _, params := range []map[string]any{{}, {"fragment": true, "packets": "tlshello", "length": "50-100", "interval": "10-20"}} {
		b, err := capability.Render(r.Profiles[0], params, "127.0.0.1:10801", "local-user", "local-pass")
		if err != nil {
			t.Fatal(err)
		}
		var cfg map[string]any
		if err = json.Unmarshal(b, &cfg); err != nil {
			t.Fatal(err)
		}
		outbound := cfg["outbounds"].([]any)[0].(map[string]any)
		if !reflect.DeepEqual(outbound["settings"], expected) {
			t.Fatal("render changed authentication or endpoint")
		}
		var stream map[string]any
		if err = json.Unmarshal([]byte(`{"network":"ws","security":"tls","tlsSettings":{"fingerprint":"firefox","serverName":"tls.invalid"},"wsSettings":{"headers":{"Host":"ws.invalid"},"path":"/synthetic/path"}}`), &stream); err != nil {
			t.Fatal(err)
		}
		actual := outbound["streamSettings"].(map[string]any)
		for _, name := range []string{"network", "security", "tlsSettings", "wsSettings"} {
			if !reflect.DeepEqual(actual[name], stream[name]) {
				t.Fatal("transport contract changed", name)
			}
		}
		for _, name := range []string{"api", "dns", "stats", "policy"} {
			if cfg[name] != nil {
				t.Fatal("source application envelope retained", name)
			}
		}
		if bytes.Contains(b, []byte("su.happ.proxyutility")) || bytes.Contains(b, []byte("10085")) {
			t.Fatal("source API or log files retained")
		}
	}
	var out, stderr bytes.Buffer
	if code := cli.Run(context.Background(), []string{"inspect", "--input", "-"}, strings.NewReader(happExport), &out, &stderr); code != 0 {
		t.Fatal(code, stderr.String())
	}
	if !json.Valid(out.Bytes()) || !strings.Contains(out.String(), `"protocol":"vless"`) {
		t.Fatal("CLI did not recognize exported JSON")
	}
	if strings.Contains(out.String()+stderr.String(), "synthetic-secret-marker") {
		t.Fatal("credentials leaked")
	}
}

func TestHappExportRejectsAmbiguousAndUnsafeFields(t *testing.T) {
	for _, tc := range []struct{ from, to, code string }{
		{`"address":"vpn.invalid"`, `"address":"vpn.invalid","vnext":[]`, "unsupported_settings"},
		{`"address":"vpn.invalid"`, `"address":"vpn.invalid","unknown":"synthetic-secret-marker"`, "unsupported_settings"},
		{`"port":443`, `"port":0`, "invalid_endpoint"},
		{`"port":443`, `"port":65536`, "invalid_endpoint"},
		{`"id":"synthetic-secret-marker"`, `"id":""`, "invalid_user"},
		{`"show":false`, `"show":"false"`, "unsupported_tls_field"},
		{`"show":false`, `"show":false,"allowInsecure":true`, "insecure_tls_rejected"},
	} {
		_, err := importer.Parse([]byte(strings.Replace(happExport, tc.from, tc.to, 1)), "auto")
		if model.Code(err) != tc.code {
			t.Fatal("wrong rejection", tc.code, err)
		}
		if strings.Contains(err.Error(), "synthetic-secret-marker") {
			t.Fatal("credentials in error")
		}
	}
}
