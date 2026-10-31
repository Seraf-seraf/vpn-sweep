package search

import (
	"bytes"
	"math/big"
	"testing"

	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/space"
	"github.com/Seraf-seraf/vpn-sweep/internal/state"
)

func TestSourceFragmentBaselineIdentityAndPrivatePersistence(t *testing.T) {
	r, err := importer.Parse([]byte("vless://synthetic@vpn.invalid:443?type=ws&security=tls"), "uri")
	if err != nil || len(r.Profiles) != 1 {
		t.Fatal("synthetic profile rejected", err)
	}
	p := r.Profiles[0]
	g, err := space.New(space.Spec{Version: 1, Strategy: "exhaustive", Parameters: []space.Domain{{Name: "fragment", Values: []any{false}}}})
	if err != nil {
		t.Fatal(err)
	}
	key := bytes.Repeat([]byte{7}, 32)
	legacy, _, ok := Candidate([]model.Profile{p}, key, g, big.NewInt(0))
	if !ok {
		t.Fatal("legacy baseline missing")
	}
	wantLegacy := model.PrivateID(key, []any{"candidate-v2", map[string]any{"outbound": p.Outbound}, capability.Effective(p, nil)})
	if legacy.ID != wantLegacy {
		t.Fatal("legacy candidate identity changed")
	}
	p.Fragment = &model.FragmentBaseline{Length: "4-8", Interval: "1-2", Packets: "tlshello"}
	baseline, _, ok := Candidate([]model.Profile{p}, key, g, big.NewInt(0))
	if !ok {
		t.Fatal("source baseline missing")
	}
	disabled, _, ok := Candidate([]model.Profile{p}, key, g, big.NewInt(1))
	if !ok || baseline.ID == disabled.ID || capability.Effective(p, baseline.Params)["fragment"] != true || capability.Effective(p, disabled.Params)["fragment"] != false {
		t.Fatal("source baseline and explicit disable collapsed")
	}
	profiles := []model.Profile{p}
	m := model.Manifest{Version: model.FragmentProfileSchemaVersion, InputID: model.PrivateID(key, profiles)}
	dir := t.TempDir()
	store, err := state.Open(dir, 0)
	if err != nil {
		t.Fatal(err)
	}
	if err = store.SaveManifest(m, profiles, key); err != nil {
		t.Fatal(err)
	}
	if err = store.Close(); err != nil {
		t.Fatal(err)
	}
	store, err = state.Open(dir, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	_, restored, restoredKey, err := store.LoadManifest()
	if err != nil || len(restored) != 1 || restored[0].Fragment == nil || restored[0].Fragment.Length != "4-8" || restored[0].Fragment.MaxSplit != nil || !bytes.Equal(restoredKey, key) {
		t.Fatal("private baseline did not survive reload", err)
	}
	reloaded, _, ok := Candidate(restored, restoredKey, g, big.NewInt(0))
	if !ok || reloaded.ID != baseline.ID {
		t.Fatal("baseline identity changed after reload")
	}
}
