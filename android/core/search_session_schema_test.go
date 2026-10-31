package mobile

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/space"
)

func TestAndroidSearchSessionProfileSchemaCompatibility(t *testing.T) {
	input, err := importer.Parse([]byte("vless://synthetic@node.invalid:443?type=ws&security=tls"), "uri")
	if err != nil || len(input.Profiles) != 1 {
		t.Fatal("synthetic profile unavailable", err)
	}
	legacy := input.Profiles[0]
	fragment := legacy
	fragment.Fragment = &model.FragmentBaseline{Length: "100-200", Interval: "10-20", Packets: "tlshello"}
	spaceBytes, err := json.Marshal(space.Spec{Version: 1, Strategy: "exhaustive"})
	if err != nil {
		t.Fatal(err)
	}
	manifest := model.Manifest{SearchVersion: model.SearchVersion, Mode: "core", Environment: "unverified",
		Core: model.CoreIdentity{Commit: model.TargetCommit}, Space: spaceBytes, Budgets: model.DefaultBudgets(),
		Policy: model.Policy{Version: "custom-v1", Rounds: 2, MinOperators: 1, Downloads: 1, MinDownloadBytes: 1,
			Probes: []model.Probe{
				{ID: "page", Operator: "a", URL: "https://a.invalid", Kind: "page", Contains: "expected", Status: 200, TimeoutSeconds: 1},
				{ID: "data", Operator: "b", URL: "https://b.invalid", Kind: "download", SHA256: strings.Repeat("0", 64), Bytes: 1, Status: 200, TimeoutSeconds: 1},
			}}}
	for _, tc := range []struct {
		name    string
		version int
		profile model.Profile
		allowed bool
	}{
		{"v1_legacy", model.SchemaVersion, legacy, true},
		{"v2_legacy", model.FragmentProfileSchemaVersion, legacy, true},
		{"v2_fragment", model.FragmentProfileSchemaVersion, fragment, true},
		{"v1_fragment", model.SchemaVersion, fragment, false},
		{"unknown_zero", 0, legacy, false},
		{"unknown_next", model.FragmentProfileSchemaVersion + 1, fragment, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			manifest.Version = tc.version
			manifestBytes, err := json.Marshal(manifest)
			if err != nil {
				t.Fatal(err)
			}
			profileBytes, err := json.Marshal([]model.Profile{tc.profile})
			if err != nil {
				t.Fatal(err)
			}
			worker := &fakeEngineWorker{}
			_, err = NewSearchSession(42, manifestBytes, profileBytes, "\x00", "\x00", worker)
			want := "invalid_search_input"
			if tc.allowed {
				want = "search_staging_unavailable"
			}
			if err == nil || err.Error() != want {
				t.Fatalf("expected %s at validation boundary, got %v", want, err)
			}
			if worker.opens != 0 {
				t.Fatal("worker opened during schema validation")
			}
		})
	}
}
