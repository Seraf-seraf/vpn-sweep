package export

import (
	"bytes"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestMultipleSavedResultsPreserveFilesAndRequireCompletion(t *testing.T) {
	r, err := importer.Parse([]byte("socks5://synthetic.invalid:1080"), "uri")
	if err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile("../../examples/google-youtube-web-v1.json")
	if err != nil {
		t.Fatal(err)
	}
	var pol model.Policy
	if err = model.Decode(raw, &pol); err != nil {
		t.Fatal(err)
	}
	m := model.Manifest{SeriesID: "synthetic-series", Policy: pol}
	a := model.Attempt{ID: "synthetic-attempt", Restarts: pol.Rounds - 1, Candidate: model.Candidate{ID: strings.Repeat("a", 64)}}
	for round := 0; round < pol.Rounds; round++ {
		for _, pr := range pol.Probes {
			a.Results = append(a.Results, model.ProbeResult{Probe: pr.ID, Round: round, Transport: "https-tcp", Outcome: "pass", HTTPStatus: 200, Bytes: pr.MinBytes, ContentVerified: true, MIME: "text/html", TLSVerified: true})
		}
	}
	dir := t.TempDir()
	if err = Save(dir, m, r.Profiles, a); err != nil {
		t.Fatal(err)
	}
	first := map[string][]byte{}
	for _, name := range []string{"working.json", "report.json", "verification.json", "COMPLETE.json"} {
		first[name], err = os.ReadFile(filepath.Join(dir, name))
		if err != nil {
			t.Fatal(err)
		}
	}
	second := strings.Repeat("b", 64)
	a.Candidate.ID = second
	a.Candidate.Params = map[string]any{"fragment": true, "packets": "1-1", "length": "10", "interval": "0"}
	child := filepath.Join(dir, second)
	if err = os.Mkdir(child, 0700); err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(filepath.Join(child, "working.json"), []byte("incomplete"), 0600); err != nil {
		t.Fatal(err)
	}
	saved, err := SavedCandidates(dir)
	if err != nil || len(saved) != 1 || saved[second] {
		t.Fatal("incomplete result treated as saved", saved, err)
	}
	if err = Save(dir, m, r.Profiles, a); err != nil {
		t.Fatal(err)
	}
	for name, before := range first {
		after, err := os.ReadFile(filepath.Join(dir, name))
		if err != nil || !bytes.Equal(before, after) {
			t.Fatal("earlier result overwritten", name, err)
		}
	}
	saved, err = SavedCandidates(dir)
	if err != nil || len(saved) != 2 || !saved[second] || !saved[strings.Repeat("a", 64)] {
		t.Fatal(saved, err)
	}
	before, err := os.ReadFile(filepath.Join(child, "working.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err = Save(dir, m, r.Profiles, a); err != nil {
		t.Fatal(err)
	}
	after, err := os.ReadFile(filepath.Join(child, "working.json"))
	if err != nil || !bytes.Equal(before, after) {
		t.Fatal("repeat export changed saved result", err)
	}
	if err = os.WriteFile(filepath.Join(child, "working.json"), []byte("tamper"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err = SavedCandidates(dir); model.Code(err) != "export_integrity_failed" {
		t.Fatal("corrupt result accepted", err)
	}
}

func TestRejectUnverifiedSymlinkAndCheckout(t *testing.T) {
	r, _ := importer.Parse([]byte("trojan://secret-marker@vpn.invalid:443?security=tls&sni=tls.invalid"), "uri")
	if Save(t.TempDir(), model.Manifest{}, r.Profiles, model.Attempt{}) == nil {
		t.Fatal("partial exported")
	}
	d := t.TempDir()
	os.Mkdir(filepath.Join(d, ".git"), 0700)
	if _, e := SafeDirectory(filepath.Join(d, "out")); e == nil {
		t.Fatal("repo output")
	}
	target := t.TempDir()
	link := filepath.Join(t.TempDir(), "link")
	os.Symlink(target, link)
	if _, e := SafeDirectory(link); e == nil {
		t.Fatal("symlink accepted")
	}
	catalog := t.TempDir()
	if e := os.Symlink(target, filepath.Join(catalog, strings.Repeat("c", 64))); e != nil {
		t.Fatal(e)
	}
	if _, e := SavedCandidates(catalog); e == nil {
		t.Fatal("saved result symlink accepted")
	}
	if strings.Contains(model.Err("write_failed", "export").Error(), "secret-marker") {
		t.Fatal("secret")
	}
}
