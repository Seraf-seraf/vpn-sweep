package mobile

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"strings"
	"testing"

	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/policy"
	"github.com/Seraf-seraf/vpn-sweep/internal/space"
)

var manifestProfile = []byte(`[{"outbound":{"protocol":"socks","settings":{"servers":[{"address":"synthetic.invalid","port":1080}]}}}]`)

func TestAndroidDefaultSearchManifestUsesExistingCLIDefaults(t *testing.T) {
	digest := strings.Repeat("A", 64)
	result, err := BuildDefaultSearchManifest(manifestProfile, digest)
	if err != nil {
		t.Fatal(err)
	}
	var got model.Manifest
	if err := model.Decode(result, &got); err != nil {
		t.Fatal(err)
	}
	generator, err := space.New(space.Default())
	if err != nil {
		t.Fatal(err)
	}
	wantSpace, err := json.Marshal(generator.Spec)
	if err != nil {
		t.Fatal(err)
	}
	if got.Version != model.SchemaVersion || got.SearchVersion != model.SearchVersion || got.Mode != "core" ||
		got.Environment != "unverified" || got.TrustID != "system" || got.Core.Commit != model.TargetCommit ||
		got.Core.SHA256 != strings.ToLower(digest) || got.InputID != "" || got.Created.IsZero() {
		t.Fatal("manifest metadata differs from the default contract")
	}
	if len(got.SeriesID) != 32 {
		t.Fatal("invalid series identity")
	}
	if _, err := hex.DecodeString(got.SeriesID); err != nil {
		t.Fatal("invalid series identity")
	}
	if !bytes.Equal(got.Space, wantSpace) || model.Digest(got.Policy) != model.Digest(policy.Default()) ||
		got.Budgets != model.DefaultBudgets() {
		t.Fatal("space, policy or budgets differ from the CLI defaults")
	}
}

func TestAndroidDefaultSearchManifestPreservesFragmentSchema(t *testing.T) {
	var profiles []model.Profile
	inputProfile := []byte(`[{"outbound":{"protocol":"vless","settings":{"vnext":[{"address":"synthetic.invalid","port":443,"users":[{"id":"synthetic","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"synthetic.invalid"}}}}]`)
	if err := model.Decode(inputProfile, &profiles); err != nil {
		t.Fatal(err)
	}
	profiles[0].Fragment = &model.FragmentBaseline{Length: "100-200", Interval: "10-20", Packets: "tlshello"}
	if err := capability.Check(profiles[0], nil); err != nil {
		t.Fatal("synthetic fragment profile is not applicable", err)
	}
	input, err := json.Marshal(profiles)
	if err != nil {
		t.Fatal(err)
	}
	result, err := BuildDefaultSearchManifest(input, strings.Repeat("a", 64))
	if err != nil {
		t.Fatal(err)
	}
	var got model.Manifest
	if err := model.Decode(result, &got); err != nil || got.Version != model.FragmentProfileSchemaVersion {
		t.Fatal("fragment profile schema was not preserved", err)
	}
}

func TestAndroidDefaultSearchManifestRejectsInvalidInputs(t *testing.T) {
	goodDigest := strings.Repeat("a", 64)
	for name, input := range map[string][]byte{
		"empty": nil, "empty-list": []byte(`[]`), "invalid-profile": []byte(`[{}]`),
		"malformed": []byte(`[`), "too-large": make([]byte, importer.MaxInputBytes+1),
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := BuildDefaultSearchManifest(input, goodDigest); err == nil {
				t.Fatal("invalid profile input was accepted")
			}
		})
	}
	for _, digest := range []string{"", strings.Repeat("a", 63), strings.Repeat("a", 65), strings.Repeat("z", 64)} {
		if _, err := BuildDefaultSearchManifest(manifestProfile, digest); err == nil {
			t.Fatal("invalid core digest was accepted")
		}
	}
}

func TestAndroidDefaultSearchManifestHonorsProfileByteBoundary(t *testing.T) {
	input := append(append([]byte(nil), manifestProfile...), bytes.Repeat([]byte{' '}, importer.MaxInputBytes-len(manifestProfile))...)
	if _, err := BuildDefaultSearchManifest(input, strings.Repeat("a", 64)); err != nil {
		t.Fatal("valid input at the byte limit rejected", err)
	}
	if _, err := BuildDefaultSearchManifest(append(input, ' '), strings.Repeat("a", 64)); err == nil {
		t.Fatal("input over the byte limit accepted")
	}
}

func TestAndroidDefaultSearchManifestHonorsProfileCountBoundary(t *testing.T) {
	var profile []model.Profile
	if err := model.Decode(manifestProfile, &profile); err != nil {
		t.Fatal(err)
	}
	profiles := make([]model.Profile, 10001)
	for index := range profiles {
		profiles[index] = profile[0]
	}
	for _, count := range []int{10000, 10001} {
		input, err := json.Marshal(profiles[:count])
		if err != nil {
			t.Fatal(err)
		}
		_, err = BuildDefaultSearchManifest(input, strings.Repeat("a", 64))
		if (err == nil) != (count == 10000) {
			t.Fatalf("unexpected result at profile count %d: %v", count, err)
		}
	}
}

func TestAndroidSearchManifestCleanupBudget(t *testing.T) {
	manifest := model.Manifest{Budgets: model.DefaultBudgets()}
	for _, seconds := range []int{0, 1, 60, 61} {
		manifest.Budgets.CleanupSeconds = seconds
		input, err := json.Marshal(manifest)
		if err != nil {
			t.Fatal(err)
		}
		millis, err := SearchManifestCleanupMillis(input)
		if seconds >= 1 && seconds <= 60 {
			if err != nil || millis != int64(seconds)*1000 {
				t.Fatalf("valid cleanup budget %d: millis=%d err=%v", seconds, millis, err)
			}
		} else if err == nil {
			t.Fatalf("invalid cleanup budget %d accepted", seconds)
		}
	}
	manifest.Budgets = model.DefaultBudgets()
	input, err := json.Marshal(manifest)
	if err != nil {
		t.Fatal(err)
	}
	duplicate := bytes.Replace(input, []byte(`"cleanup_seconds":5`), []byte(`"cleanup_seconds":5,"cleanup_seconds":5`), 1)
	if bytes.Equal(input, duplicate) {
		t.Fatal("synthetic cleanup field not found")
	}
	if _, err := SearchManifestCleanupMillis(duplicate); err == nil {
		t.Fatal("duplicate cleanup budget was accepted")
	}
}
