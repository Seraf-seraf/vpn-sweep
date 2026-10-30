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
	"github.com/Seraf-seraf/vpn-sweep/internal/media"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/policy"
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
	for _, name := range []string{"subscription-file", "mode", "policy", "space", "network-label", "attest-no-other-vpn", "ca-file", "allow-custom-policy", "allow-unverified-web-profile", "allow-video-profile", "youtube-python", "youtube-js", "cleanup-seconds"} {
		var out, err bytes.Buffer
		if code := Run(context.Background(), []string{"inspect", "--" + name, "secret-marker"}, nil, &out, &err); code != 2 || !strings.Contains(err.String(), `"error":"invalid_arguments"`) {
			t.Fatal("removed flag accepted", name, code, err.String())
		}
		if strings.Contains(out.String()+err.String(), "secret-marker") {
			t.Fatal("argument leaked", name)
		}
	}
	var out, stderr bytes.Buffer
	if code := Run(context.Background(), []string{"resolver-info"}, nil, &out, &stderr); code != 2 || !strings.Contains(stderr.String(), `"error":"unknown_command"`) {
		t.Fatal("removed resolver command accepted", code, stderr.String())
	}
}

func TestSpaceReportsNoisePresetAndOptionalFingerprint(t *testing.T) {
	var out, stderr bytes.Buffer
	if code := Run(context.Background(), []string{"space"}, nil, &out, &stderr); code != 0 {
		t.Fatal(stderr.String())
	}
	var result struct {
		Parameters []struct {
			Name     string `json:"name"`
			Preset   string `json:"preset"`
			Optional bool   `json:"optional"`
		} `json:"parameters"`
	}
	if err := json.Unmarshal(out.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	var noisePreset, optionalFingerprint bool
	for _, d := range result.Parameters {
		if d.Name == "noises" && d.Preset == "udp-noise-v1" {
			noisePreset = true
		}
		if d.Name == "fingerprint" && d.Optional {
			optionalFingerprint = true
		}
	}
	if !noisePreset || !optionalFingerprint {
		t.Fatal("space output does not describe generated noises and the source fingerprint")
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
	input := filepath.Join(base, "input")
	os.WriteFile(input, []byte("socks5://synthetic-user:secret-marker@vpn.invalid:1080"), 0600)
	sum := sha256.Sum256(payload)
	pol := model.Policy{Version: "custom-v1", Rounds: 2, MinOperators: 1, Downloads: 1, MinDownloadBytes: 1, Probes: []model.Probe{{ID: "page", Operator: "test", URL: s.URL + "/secret-marker", Kind: "page", Contains: "synthetic", Status: 200, TimeoutSeconds: 3}, {ID: "data", Operator: "test", URL: s.URL + "/secret-marker", Kind: "download", Bytes: int64(len(payload)), SHA256: hex.EncodeToString(sum[:]), Status: 200, TimeoutSeconds: 3}}}
	stateDir, resultDir := filepath.Join(base, "state"), filepath.Join(base, "result")
	o := options{binary: binary, input: input, checks: pol, state: stateDir, output: resultDir, trust: pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: s.Certificate().Raw}), budgets: model.DefaultBudgets()}
	o.budgets.IntervalMS = 0
	var out, stderr bytes.Buffer
	if err := run(context.Background(), "run", o, nil, &out, &stderr); err != nil {
		t.Fatal(err, out.String(), stderr.String())
	}
	if !strings.Contains(out.String(), `"status":"success"`) {
		t.Fatal(out.String())
	}
	for _, message := range []string{"проверка Xray", "Попытка 1: конфигурация 1", "раунд 1: запуск Xray", "раунд 2: запуск Xray", "проверка 1 началась", "проверка 1: успех", "остановка Xray — успех", "сохранение результата", "Подбор завершён: успех"} {
		if !strings.Contains(stderr.String(), message) {
			t.Fatalf("missing progress %q: %s", message, stderr.String())
		}
	}
	for _, line := range strings.Split(strings.TrimSpace(out.String()), "\n") {
		if !json.Valid([]byte(line)) {
			t.Fatalf("progress corrupted JSON stdout: %s", line)
		}
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
	frozen, e := os.ReadFile(filepath.Join(stateDir, "manifest.json"))
	if e != nil {
		t.Fatal(e)
	}
	out.Reset()
	if code := Run(context.Background(), []string{"resume", "--core", binary, "--state", stateDir, "--output", resultDir}, nil, &out, &stderr); code != 0 {
		t.Fatal("resume export", stderr.String())
	}
	after, _ := os.ReadFile(filepath.Join(resultDir, "working.json"))
	if !bytes.Equal(before, after) {
		t.Fatal("previous working config overwritten")
	}
	afterManifest, e := os.ReadFile(filepath.Join(stateDir, "manifest.json"))
	if e != nil || !bytes.Equal(frozen, afterManifest) {
		t.Fatal("resume changed frozen search", e)
	}
	store, e := state.Open(stateDir, 0)
	if e != nil {
		t.Fatal(e)
	}
	v := store.View()
	store.Close()
	if v.LastSaved == nil || v.LastSaved.Candidate.ID != report.CandidateID || v.Winner == nil || v.Winner.Candidate.ID == report.CandidateID || v.Next == "1" {
		t.Fatal("resume did not search for a different config", v)
	}
	second, e := export.ReadReport(filepath.Join(resultDir, v.Winner.Candidate.ID))
	if e != nil || second.CandidateID != v.Winner.Candidate.ID || second.SeriesID != report.SeriesID {
		t.Fatal("new result not saved separately", e)
	}
	if !strings.Contains(stderr.String(), "Рабочий вариант уже сохранён; поиск следующего") {
		t.Fatal("saved result skip not logged")
	}
	out.Reset()
	changed := filepath.Join(base, "changed-core")
	coreBytes, err := os.ReadFile(binary)
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(changed, append(coreBytes, '\n'), 0700); err != nil {
		t.Fatal(err)
	}
	if code := Run(context.Background(), []string{"resume", "--core", changed, "--state", stateDir, "--output", resultDir}, nil, &out, &stderr); code != 2 || !strings.Contains(stderr.String(), "series_conditions_changed") {
		t.Fatal("core mismatch accepted", stderr.String())
	}
	os.WriteFile(filepath.Join(resultDir, "working.json"), []byte("tamper"), 0600)
	if Run(context.Background(), []string{"export", "--state", stateDir, "--output", resultDir}, nil, &out, &stderr) == 0 {
		t.Fatal("corrupt result accepted")
	}
}

func TestBuiltinPolicyAndLegacyResumeWithoutOptIn(t *testing.T) {
	base := t.TempDir()
	if err := os.Chmod(base, 0700); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile("../../examples/google-youtube-web-v1.json")
	if err != nil {
		t.Fatal(err)
	}
	var out, stderr bytes.Buffer
	if Run(context.Background(), []string{"policy"}, nil, &out, &stderr) != 0 || !strings.Contains(out.String(), `"policy":"`+policy.GoogleYouTubeWebNoise+`"`) || !strings.Contains(out.String(), `"network_requests":false`) {
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
	m := model.Manifest{Version: 1, SearchVersion: model.SearchVersion, SeriesID: model.ID(), Mode: "core", Core: id, TrustID: "system", Policy: pol, InputID: model.PrivateID(key, imp.Profiles), Space: json.RawMessage(`{"version":1,"strategy":"exhaustive"}`), NetworkLabel: model.PrivateID(key, "local"), Environment: "user_attested"}
	if err = s.SaveManifest(m, imp.Profiles, key); err != nil {
		t.Fatal(err)
	}
	a := model.Attempt{ID: model.ID(), Candidate: model.Candidate{Profile: 0, ID: model.ID()}}
	if err = s.Begin(a, "1"); err != nil {
		t.Fatal(err)
	}
	for round := 0; round < pol.Rounds; round++ {
		if round > 0 {
			if err = s.Restart(); err != nil {
				t.Fatal(err)
			}
		}
		for _, pr := range pol.Probes {
			if err = s.Probe(model.ProbeResult{ID: model.ID(), Probe: pr.ID, Round: round, Transport: "https-tcp", Outcome: "pass", HTTPStatus: 200, Bytes: pr.MinBytes, ContentVerified: true, MIME: "text/html", TLSVerified: true}); err != nil {
				t.Fatal(err)
			}
		}
	}
	if err = s.End("verified_pending_export"); err != nil {
		t.Fatal(err)
	}
	s.Close()
	out.Reset()
	stderr.Reset()
	args := []string{"resume", "--core", binary, "--state", dir, "--output", filepath.Join(base, "result")}
	if Run(context.Background(), args, nil, &out, &stderr) != 0 || !strings.Contains(out.String(), `"status":"success"`) {
		t.Fatal(out.String(), stderr.String())
	}
	s, err = state.Open(dir, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if s.View().Attempts != 1 {
		t.Fatal("legacy policy restarted instead of exporting")
	}
}

func TestLegacyYouTubeResumeRequiresNewSeries(t *testing.T) {
	binary, id := testkit.FakeCore(t)
	imp, err := importer.Parse([]byte("socks5://synthetic.invalid:1080"), "uri")
	if err != nil {
		t.Fatal(err)
	}
	for _, version := range []string{policy.GoogleYouTube, media.Version} {
		t.Run(version, func(t *testing.T) {
			base := t.TempDir()
			dir := filepath.Join(base, "series")
			key := make([]byte, 32)
			p := policy.Default()
			p.Version = version
			p.UDP = nil
			p.Video = &model.VideoPolicy{Required: []string{media.YouTube}, Samples: 3, MinMbps: 10, MaxReadGapMS: 3000, SampleBytes: 4 << 20, TimeoutSeconds: 30, YouTube: &model.YouTubeReference{VideoID: "abcdefghijk", Permission: "synthetic", MinHeight: 1080}}
			if version == media.Version {
				p.Probes = nil
				p.MinOperators = 0
				p.Video.Reference = model.MediaReference{URL: "https://sample.invalid/video.mp4", Bytes: 1 << 20, SHA256: strings.Repeat("1", 64), Status: "verified_reference", License: "synthetic", Provenance: "synthetic"}
			}
			if err := policy.Validate(p); err != nil {
				t.Fatal(err)
			}
			m := model.Manifest{Version: 1, SearchVersion: model.SearchVersion, SeriesID: model.ID(), Mode: "core", Core: id, TrustID: "system", Policy: p, InputID: model.PrivateID(key, imp.Profiles), Space: json.RawMessage(`{"version":1,"strategy":"exhaustive"}`), Environment: "unverified", Resolver: &model.ResolverIdentity{AdapterVersion: 1, YTDLP: "2026.8.19", EJS: "0.8.0"}}
			s, err := state.Open(dir, 0)
			if err != nil {
				t.Fatal(err)
			}
			if err = s.SaveManifest(m, imp.Profiles, key); err != nil {
				t.Fatal(err)
			}
			if err = s.Begin(model.Attempt{ID: model.ID(), Candidate: model.Candidate{ID: model.ID()}}, "1"); err != nil {
				t.Fatal(err)
			}
			if err = s.End("interrupted"); err != nil {
				t.Fatal(err)
			}
			if err = s.Close(); err != nil {
				t.Fatal(err)
			}
			before, err := os.ReadFile(filepath.Join(dir, "manifest.json"))
			if err != nil {
				t.Fatal(err)
			}
			journal, err := os.ReadFile(filepath.Join(dir, "journal.jsonl"))
			if err != nil {
				t.Fatal(err)
			}
			var out, stderr bytes.Buffer
			args := []string{"resume", "--core", binary, "--state", dir, "--output", filepath.Join(base, "result")}
			if code := Run(context.Background(), args, nil, &out, &stderr); code != 2 || !strings.Contains(stderr.String(), "youtube_video_removed_new_series_required") {
				t.Fatal("legacy resume not rejected explicitly", code, stderr.String())
			}
			after, err := os.ReadFile(filepath.Join(dir, "manifest.json"))
			if err != nil || !bytes.Equal(before, after) {
				t.Fatal("manifest changed", err)
			}
			afterJournal, err := os.ReadFile(filepath.Join(dir, "journal.jsonl"))
			if err != nil || !bytes.Equal(journal, afterJournal) {
				t.Fatal("journal changed", err)
			}
			for _, cmd := range []string{"status", "report"} {
				out.Reset()
				stderr.Reset()
				if code := Run(context.Background(), []string{cmd, "--state", dir}, nil, &out, &stderr); code != 0 {
					t.Fatal(cmd, stderr.String())
				}
			}
		})
	}
}

func TestExportSavedLegacyYouTubeWinner(t *testing.T) {
	imp, err := importer.Parse([]byte("socks5://synthetic.invalid:1080"), "uri")
	if err != nil {
		t.Fatal(err)
	}
	p := policy.Default()
	p.Version = policy.GoogleYouTube
	p.UDP = nil
	p.Video = &model.VideoPolicy{Required: []string{media.YouTube}, Samples: 3, MinMbps: 10, MaxReadGapMS: 3000, SampleBytes: 4 << 20, TimeoutSeconds: 30, YouTube: &model.YouTubeReference{VideoID: "abcdefghijk", Permission: "synthetic", MinHeight: 1080}}
	base := t.TempDir()
	dir := filepath.Join(base, "series")
	key := make([]byte, 32)
	m := model.Manifest{Version: 1, SearchVersion: model.SearchVersion, SeriesID: model.ID(), Mode: "core", TrustID: "system", Policy: p, InputID: model.PrivateID(key, imp.Profiles), Space: json.RawMessage(`{"version":1,"strategy":"exhaustive"}`), Environment: "unverified", Resolver: &model.ResolverIdentity{AdapterVersion: 1, YTDLP: "2026.8.19", EJS: "0.8.0"}}
	s, err := state.Open(dir, 0)
	if err != nil {
		t.Fatal(err)
	}
	if err = s.SaveManifest(m, imp.Profiles, key); err != nil {
		t.Fatal(err)
	}
	a := model.Attempt{ID: model.ID(), Candidate: model.Candidate{ID: model.ID(), Profile: 0}}
	if err = s.Begin(a, "1"); err != nil {
		t.Fatal(err)
	}
	for round := 0; round < p.Rounds; round++ {
		if round > 0 {
			if err = s.Restart(); err != nil {
				t.Fatal(err)
			}
		}
		for _, pr := range p.Probes {
			if err = s.Probe(model.ProbeResult{ID: model.ID(), Probe: pr.ID, Round: round, Transport: "https-tcp", Outcome: "pass", HTTPStatus: 200, Bytes: pr.MinBytes, MIME: "text/html", TLSVerified: true, ContentVerified: true}); err != nil {
				t.Fatal(err)
			}
		}
		r := model.ProbeResult{ID: model.ID(), Probe: media.YouTube, Round: round, Transport: "https-tcp", Outcome: "pass", TLSVerified: true, ContentVerified: true, Media: &model.MediaEvidence{Version: 1, Capability: media.YouTube, FormatID: "137", Height: 1080}}
		for i := 0; i < p.Video.Samples; i++ {
			r.Media.Samples = append(r.Media.Samples, model.MediaSample{Bytes: p.Video.SampleBytes, DurationNS: 1000000, MaxReadGapNS: 1000, SHA256: strings.Repeat("1", 64), ContainerVerified: true, TLSVerified: true, Status: 206})
		}
		if err = s.Probe(r); err != nil {
			t.Fatal(err)
		}
	}
	if err = s.End("verified_pending_export"); err != nil {
		t.Fatal(err)
	}
	if err = s.Close(); err != nil {
		t.Fatal(err)
	}
	result := filepath.Join(base, "result")
	var out, stderr bytes.Buffer
	if code := Run(context.Background(), []string{"export", "--state", dir, "--output", result}, nil, &out, &stderr); code != 0 {
		t.Fatal(code, stderr.String())
	}
	report, err := export.ReadReport(result)
	if err != nil || report.Capabilities[media.YouTube] != "pass" {
		t.Fatal(report, err)
	}
}
