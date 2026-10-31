package state

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/private"
)

func TestProfileSchemaLegacyReadAndIdentity(t *testing.T) {
	key := bytes.Repeat([]byte{0x37}, 32)
	profiles := []model.Profile{{Outbound: map[string]any{"protocol": "vless", "tag": "synthetic"}}}
	legacyShape := []any{map[string]any{"outbound": profiles[0].Outbound}}
	if model.PrivateID(key, profiles) != model.PrivateID(key, legacyShape) {
		t.Fatal("legacy profile identity changed")
	}
	if model.ProfileSchemaVersion(profiles) != model.SchemaVersion {
		t.Fatal("legacy profile schema changed")
	}
	store, err := Open(t.TempDir(), 0)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	manifest := model.Manifest{Version: model.SchemaVersion, SearchVersion: model.SearchVersion, InputID: model.PrivateID(key, profiles)}
	if err := store.SaveManifest(manifest, profiles, key); err != nil {
		t.Fatal(err)
	}
	profileFile := filepath.Join(store.Dir, "profiles.json")
	before, err := os.ReadFile(profileFile)
	if err != nil {
		t.Fatal(err)
	}
	loadedManifest, loaded, _, err := store.LoadManifest()
	if err != nil || loadedManifest.Version != model.SchemaVersion || !reflect.DeepEqual(loaded, profiles) || model.PrivateID(key, loaded) != manifest.InputID {
		t.Fatal("legacy profile failed to load unchanged", err)
	}
	after, err := os.ReadFile(profileFile)
	if err != nil || !bytes.Equal(before, after) {
		t.Fatal("legacy profiles were rewritten during read", err)
	}
}

func TestProfileSchemaNewRoundTripAndLegacyRejectKeepsLastGood(t *testing.T) {
	key := bytes.Repeat([]byte{0x47}, 32)
	profiles := []model.Profile{{
		Outbound: map[string]any{"protocol": "vless", "tag": "synthetic"},
		Fragment: &model.FragmentBaseline{Length: "100-200", Interval: "10-20", Packets: "tlshello"},
	}}
	if model.ProfileSchemaVersion(profiles) != model.FragmentProfileSchemaVersion {
		t.Fatal("fragment schema not declared")
	}
	root := t.TempDir()
	lastGood := filepath.Join(root, "lastgood.json")
	if err := os.WriteFile(lastGood, []byte(`{"synthetic":"previous"}`), 0600); err != nil {
		t.Fatal(err)
	}
	store, err := Open(filepath.Join(root, "new-run"), 0)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	manifest := model.Manifest{Version: model.FragmentProfileSchemaVersion, SearchVersion: model.SearchVersion, InputID: model.PrivateID(key, profiles)}
	if err := store.SaveManifest(manifest, profiles, key); err != nil {
		t.Fatal(err)
	}
	attempt := model.Attempt{ID: "synthetic-attempt", Candidate: model.Candidate{ID: "synthetic-lastgood"}}
	if err := store.Begin(attempt, "1"); err != nil {
		t.Fatal(err)
	}
	if err := store.End("verified_pending_export"); err != nil {
		t.Fatal(err)
	}
	if err := store.MarkSaved(attempt.Candidate.ID); err != nil {
		t.Fatal(err)
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	store, err = Open(filepath.Join(root, "new-run"), 0)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	loadedManifest, loaded, _, err := store.LoadManifest()
	if err != nil || loadedManifest.Version != model.FragmentProfileSchemaVersion || !reflect.DeepEqual(loaded, profiles) {
		t.Fatal("fragment baseline did not survive recovery", err)
	}
	if saved := store.View().LastSaved; saved == nil || saved.Candidate.ID != attempt.Candidate.ID {
		t.Fatal("last saved winner was lost on recovery")
	}
	manifestBytes, err := os.ReadFile(filepath.Join(store.Dir, "manifest.json"))
	if err != nil {
		t.Fatal(err)
	}
	profilesBytes, err := os.ReadFile(filepath.Join(store.Dir, "profiles.json"))
	if err != nil {
		t.Fatal(err)
	}
	var oldEnvelope manifestEnvelope
	if model.Decode(manifestBytes, &oldEnvelope) != nil || oldEnvelope.Manifest.Version == model.SchemaVersion {
		t.Fatal("old version guard would not reject the new run")
	}
	var oldProfiles []struct {
		Outbound map[string]any `json:"outbound"`
	}
	decoder := json.NewDecoder(bytes.NewReader(profilesBytes))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&oldProfiles); err == nil {
		t.Fatal("old strict profile reader accepted a new baseline")
	}
	after, err := os.ReadFile(lastGood)
	if err != nil || string(after) != `{"synthetic":"previous"}` {
		t.Fatal("lastgood was altered during recovery", err)
	}
}

func TestProfileSchemaRejectsMismatchedManifest(t *testing.T) {
	key := bytes.Repeat([]byte{0x57}, 32)
	profiles := []model.Profile{{Outbound: map[string]any{"protocol": "vless"}, Fragment: &model.FragmentBaseline{Length: "1", Interval: "0", Packets: "1-1"}}}
	store, err := Open(t.TempDir(), 0)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	manifest := model.Manifest{Version: model.SchemaVersion, InputID: model.PrivateID(key, profiles)}
	if err := store.SaveManifest(manifest, profiles, key); err == nil {
		t.Fatal("legacy manifest admitted fragment baseline")
	}
	manifest.Version = model.FragmentProfileSchemaVersion
	if err := store.SaveManifest(manifest, profiles, key); err != nil {
		t.Fatal(err)
	}
	manifest.Version = model.SchemaVersion
	if err := private.JSON(filepath.Join(store.Dir, "manifest.json"), manifestEnvelope{Manifest: manifest, Integrity: model.PrivateID(key, manifest)}); err != nil {
		t.Fatal(err)
	}
	if _, _, _, err := store.LoadManifest(); err == nil {
		t.Fatal("mismatched persisted version admitted fragment baseline")
	}
}

func TestProfileSchemaVersionSupportsOptionalBaseline(t *testing.T) {
	legacy := []model.Profile{{Outbound: map[string]any{"protocol": "vless"}}}
	fragment := []model.Profile{{Outbound: legacy[0].Outbound, Fragment: &model.FragmentBaseline{Length: "1", Interval: "0", Packets: "1-1"}}}
	for _, tc := range []struct {
		version  int
		profiles []model.Profile
		want     bool
	}{
		{model.SchemaVersion, legacy, true},
		{model.FragmentProfileSchemaVersion, legacy, true},
		{model.FragmentProfileSchemaVersion, fragment, true},
		{model.SchemaVersion, fragment, false},
		{0, legacy, false},
		{model.FragmentProfileSchemaVersion + 1, fragment, false},
	} {
		if got := model.SupportsProfileSchemaVersion(tc.version, tc.profiles); got != tc.want {
			t.Fatal("unexpected schema compatibility", tc.version, got)
		}
	}
}

func TestProfileSchemaV2WithoutBaselineRoundTrip(t *testing.T) {
	key := bytes.Repeat([]byte{0x67}, 32)
	profiles := []model.Profile{{Outbound: map[string]any{"protocol": "vless"}}}
	store, err := Open(t.TempDir(), 0)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	manifest := model.Manifest{Version: model.FragmentProfileSchemaVersion, InputID: model.PrivateID(key, profiles)}
	if err := store.SaveManifest(manifest, profiles, key); err != nil {
		t.Fatal("version 2 did not accept an absent optional baseline", err)
	}
	loadedManifest, loaded, _, err := store.LoadManifest()
	if err != nil || loadedManifest.Version != model.FragmentProfileSchemaVersion || !reflect.DeepEqual(loaded, profiles) {
		t.Fatal("version 2 with an absent baseline did not roundtrip", err)
	}
}
