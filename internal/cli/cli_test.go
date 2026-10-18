package cli

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"github.com/Seraf-seraf/vpn-sweep/internal/export"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/state"
	"github.com/Seraf-seraf/vpn-sweep/internal/testkit"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestPublicCLIAndSecretErrors(t *testing.T) {
	for _, args := range [][]string{{"version"}, {"help"}, {"space"}} {
		var out, err bytes.Buffer
		if Run(context.Background(), args, nil, &out, &err) != 0 || out.Len() == 0 {
			t.Fatal(args, err.String())
		}
	}
	for _, args := range [][]string{{"unknown-secret-marker"}, {"inspect", "--unknown-secret-marker"}, {"inspect", "--input", "-"}} {
		var out, err bytes.Buffer
		if Run(context.Background(), args, strings.NewReader("secret-marker"), &out, &err) == 0 {
			t.Fatal("bad CLI success")
		}
		if strings.Contains(out.String()+err.String(), "secret-marker") {
			t.Fatal("secret leaked")
		}
	}
}
func TestCompleteCLIWithControlledCore(t *testing.T) {
	payload := []byte("synthetic verified data")
	s := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Write(payload)
	}))
	defer s.Close()
	binary, _ := testkit.FakeCore(t)
	base := t.TempDir()
	write := func(name string, v any) string {
		p := filepath.Join(base, name)
		b, e := json.Marshal(v)
		if e != nil {
			t.Fatal(e)
		}
		if e = os.WriteFile(p, b, 0600); e != nil {
			t.Fatal(e)
		}
		return p
	}
	input := filepath.Join(base, "input")
	os.WriteFile(input, []byte("socks5://synthetic-user:secret-marker@vpn.invalid:1080"), 0600)
	sum := sha256.Sum256(payload)
	pol := model.Policy{Version: "custom-v1", Rounds: 2, MinOperators: 1, Downloads: 1, MinDownloadBytes: 1, Probes: []model.Probe{{ID: "page", Operator: "test", URL: s.URL, Kind: "page", Contains: "synthetic", Status: 200, TimeoutSeconds: 3}, {ID: "data", Operator: "test", URL: s.URL, Kind: "download", Bytes: int64(len(payload)), SHA256: hex.EncodeToString(sum[:]), Status: 200, TimeoutSeconds: 3}}}
	policyPath := write("policy.json", pol)
	ca := filepath.Join(base, "ca.pem")
	os.WriteFile(ca, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: s.Certificate().Raw}), 0600)
	stateDir, resultDir := filepath.Join(base, "state"), filepath.Join(base, "result")
	args := []string{"run", "--mode", "core", "--core", binary, "--input", input, "--policy", policyPath, "--allow-custom-policy", "--ca-file", ca, "--state", stateDir, "--output", resultDir, "--network-label", "test-network", "--attest-no-other-vpn", "--interval-ms", "0"}
	var out, stderr bytes.Buffer
	if code := Run(context.Background(), args, nil, &out, &stderr); code != 0 {
		t.Fatal(code, out.String(), stderr.String())
	}
	if !strings.Contains(out.String(), `"status":"success"`) {
		t.Fatal(out.String())
	}
	for _, name := range []string{"working.json", "report.json", "COMPLETE.json"} {
		st, e := os.Stat(filepath.Join(resultDir, name))
		if e != nil || st.Mode().Perm() != 0600 {
			t.Fatal("export permissions", e)
		}
	}
	report, e := export.ReadReport(resultDir)
	if e != nil || len(report.Results) != 3 {
		t.Fatal(report, e)
	}
	var public bytes.Buffer
	if code := Run(context.Background(), []string{"report", "--state", stateDir}, nil, &public, &stderr); code != 0 {
		t.Fatal(stderr.String())
	}
	rb, _ := os.ReadFile(filepath.Join(resultDir, "report.json"))
	if strings.Contains(out.String()+stderr.String()+public.String()+string(rb), "secret-marker") {
		t.Fatal("secret in public diagnostics")
	}
	before, _ := os.ReadFile(filepath.Join(resultDir, "working.json"))
	out.Reset()
	if code := Run(context.Background(), []string{"resume", "--core", binary, "--state", stateDir, "--output", resultDir, "--network-label", "test-network", "--attest-no-other-vpn"}, nil, &out, &stderr); code != 0 {
		t.Fatal("resume export", stderr.String())
	}
	after, _ := os.ReadFile(filepath.Join(resultDir, "working.json"))
	if !bytes.Equal(before, after) {
		t.Fatal("non-idempotent export")
	}
	out.Reset()
	if code := Run(context.Background(), []string{"resume", "--core", binary, "--state", stateDir, "--output", resultDir, "--network-label", "changed-network", "--attest-no-other-vpn"}, nil, &out, &stderr); code == 0 {
		t.Fatal("network mismatch accepted")
	}
	os.WriteFile(filepath.Join(resultDir, "working.json"), []byte("tamper"), 0600)
	if Run(context.Background(), []string{"export", "--state", stateDir, "--output", resultDir}, nil, &out, &stderr) == 0 {
		t.Fatal("corrupt result accepted")
	}
}

func TestWebPolicyValidationAndResumeOptIn(t *testing.T) {
	base := t.TempDir()
	if err := os.Chmod(base, 0700); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile("../../examples/google-youtube-web-v1.json")
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(base, "policy.json")
	if err = os.WriteFile(path, b, 0600); err != nil {
		t.Fatal(err)
	}
	var out, stderr bytes.Buffer
	if Run(context.Background(), []string{"policy", "--policy", path}, nil, &out, &stderr) != 0 || !strings.Contains(out.String(), "live_unverified") || !strings.Contains(out.String(), `"network_requests":false`) {
		t.Fatal(out.String(), stderr.String())
	}
	binary, id := testkit.FakeCore(t)
	imp, err := importer.Parse([]byte("socks5://synthetic.invalid:1080"), "uri")
	if err != nil {
		t.Fatal(err)
	}
	var pol model.Policy
	if err = model.Decode(b, &pol); err != nil {
		t.Fatal(err)
	}
	key := make([]byte, 32)
	dir := filepath.Join(base, "series")
	s, err := state.Open(dir, 0)
	if err != nil {
		t.Fatal(err)
	}
	m := model.Manifest{Version: 1, SearchVersion: model.SearchVersion, Mode: "core", Core: id, TrustID: "system", Policy: pol, InputID: model.PrivateID(key, imp.Profiles), Space: json.RawMessage(`{"version":1,"strategy":"exhaustive"}`), NetworkLabel: model.PrivateID(key, "local"), Environment: "user_attested"}
	if err = s.SaveManifest(m, imp.Profiles, key); err != nil {
		t.Fatal(err)
	}
	s.Close()
	out.Reset()
	stderr.Reset()
	args := []string{"resume", "--core", binary, "--state", dir, "--output", filepath.Join(base, "result"), "--network-label", "local", "--attest-no-other-vpn"}
	if Run(context.Background(), args, nil, &out, &stderr) != 2 || !strings.Contains(stderr.String(), "unverified_web_profile_requires_opt_in") {
		t.Fatal(out.String(), stderr.String())
	}
	s, err = state.Open(dir, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if s.View().Attempts != 0 {
		t.Fatal("attempt issued without opt-in")
	}
}
