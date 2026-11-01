package mobile

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/space"
	"github.com/Seraf-seraf/vpn-sweep/internal/state"
)

func TestFreshAndroidSeriesPreserveInstallationIdentity(t *testing.T) {
	parent := t.TempDir()
	stateRoot := filepath.Join(parent, "search-state")
	stagingRoot := filepath.Join(parent, "search-staging")
	for _, dir := range []string{stateRoot, stagingRoot} {
		if err := os.Mkdir(dir, 0700); err != nil {
			t.Fatal(err)
		}
	}
	key, err := state.IdentityKey(stateRoot)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := importer.Parse([]byte("socks5://synthetic.invalid:1080"), "uri")
	if err != nil {
		t.Fatal(err)
	}
	profiles, err := json.Marshal(parsed.Profiles)
	if err != nil {
		t.Fatal(err)
	}
	spaceBytes, err := json.Marshal(space.Spec{Version: 1, Strategy: "exhaustive"})
	if err != nil {
		t.Fatal(err)
	}
	manifest := model.Manifest{Version: model.SchemaVersion, SearchVersion: model.SearchVersion, SeriesID: "synthetic",
		Mode: "core", Environment: "unverified", Core: model.CoreIdentity{Commit: model.TargetCommit}, Space: spaceBytes,
		Budgets: model.DefaultBudgets(), Policy: model.Policy{Version: "custom-v1", Rounds: 2, MinOperators: 1, Downloads: 1, MinDownloadBytes: 1,
			Probes: []model.Probe{
				{ID: "page", Operator: "a", URL: "https://a.invalid", Kind: "page", Contains: "expected", Status: 200, TimeoutSeconds: 1},
				{ID: "data", Operator: "b", URL: "https://b.invalid", Kind: "download", SHA256: strings.Repeat("0", 64), Bytes: 1, Status: 200, TimeoutSeconds: 1},
			}}}
	manifest.Budgets.IntervalMS = 0
	manifestBytes, err := json.Marshal(manifest)
	if err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{"11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222"} {
		stateDir, stagingDir := filepath.Join(stateRoot, id), filepath.Join(stagingRoot, id)
		for _, dir := range []string{stateDir, stagingDir} {
			if err := os.Mkdir(dir, 0700); err != nil {
				t.Fatal(err)
			}
		}
		session, err := NewSearchSession(1, manifestBytes, profiles, stateDir, stagingDir, &fakeEngineWorker{})
		if err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(session.runner.Key, key) {
			t.Fatal("installation identity changed across fresh series")
		}
		if err := session.Close(); err != nil {
			t.Fatal(err)
		}
	}
}
