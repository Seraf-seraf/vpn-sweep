package mobile

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"

	"github.com/Seraf-seraf/vpn-sweep/internal/capability"
	"github.com/Seraf-seraf/vpn-sweep/internal/clock"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/policy"
	"github.com/Seraf-seraf/vpn-sweep/internal/space"
)

// The caller must obtain coreDigest from the installed embedded library.
func BuildDefaultSearchManifest(profileBytes []byte, coreDigest string) (result []byte, err error) {
	digest, decodeErr := hex.DecodeString(coreDigest)
	if decodeErr != nil || len(digest) != sha256.Size || capability.Gate("core", CoreCommit) != nil {
		return nil, errors.New("invalid_search_core_identity")
	}
	if len(profileBytes) == 0 || len(profileBytes) > importer.MaxInputBytes {
		return nil, errors.New("invalid_search_input")
	}
	var profiles []model.Profile
	if model.Decode(profileBytes, &profiles) != nil || len(profiles) == 0 || len(profiles) > 10000 {
		return nil, errors.New("invalid_search_input")
	}
	for _, profile := range profiles {
		if importer.Validate(profile) != nil {
			return nil, errors.New("invalid_search_input")
		}
	}
	generator, buildErr := space.New(space.Default())
	if buildErr != nil || policy.Validate(policy.Default()) != nil {
		return nil, errors.New("search_manifest_unavailable")
	}
	spec, buildErr := json.Marshal(generator.Spec)
	if buildErr != nil {
		return nil, errors.New("search_manifest_unavailable")
	}
	defer func() {
		if recover() != nil {
			result, err = nil, errors.New("search_manifest_unavailable")
		}
	}()
	manifest := model.Manifest{
		Version:       model.ProfileSchemaVersion(profiles),
		SearchVersion: model.SearchVersion,
		SeriesID:      model.ID(),
		Created:       (clock.System{}).Now().UTC(),
		Mode:          "core",
		Environment:   "unverified",
		Core:          model.CoreIdentity{Commit: CoreCommit, SHA256: hex.EncodeToString(digest)},
		Space:         spec,
		Policy:        policy.Default(),
		Budgets:       model.DefaultBudgets(),
		TrustID:       "system",
	}
	result, err = json.Marshal(manifest)
	if err != nil {
		return nil, errors.New("search_manifest_unavailable")
	}
	return result, nil
}

func SearchManifestCleanupMillis(manifestBytes []byte) (int64, error) {
	var manifest model.Manifest
	if model.Decode(manifestBytes, &manifest) != nil || !validSearchBudgets(manifest.Budgets) {
		return 0, errors.New("invalid_search_input")
	}
	return int64(manifest.Budgets.CleanupSeconds) * 1000, nil
}
