package mobile

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/android/core/searchbridge"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

type profileSizeWorker struct {
	fakeEngineWorker
	profileSize int
}

func (w *profileSizeWorker) Open(session, attempt int64, round int, generation int64, profile, params []byte, attemptMillis, startupMillis int64) int {
	w.mu.Lock()
	w.profileSize = len(profile)
	w.mu.Unlock()
	return w.fakeEngineWorker.Open(session, attempt, round, generation, profile, params, attemptMillis, startupMillis)
}

func profileInputWithSerializedSize(t *testing.T, size int, precedingProfile bool) []byte {
	t.Helper()
	const prefix = `{"outbound":{"protocol":"trojan","settings":{"servers":[{"address":"a.invalid","port":443,"password":"`
	const suffix = `"}]}}}`
	valueSize := size - len(prefix) - len(suffix)
	if valueSize < 6 {
		t.Fatal("invalid synthetic profile size")
	}
	profile := prefix + strings.Repeat("<", valueSize/6) + strings.Repeat("a", valueSize%6) + suffix
	input := "[" + profile + "]"
	if precedingProfile {
		input = "[" + string(manifestProfile[1:len(manifestProfile)-1]) + "," + profile + "]"
	}
	if len(input) > importer.MaxInputBytes {
		t.Fatal("synthetic raw input exceeds its existing limit")
	}
	var decoded []model.Profile
	if err := model.Decode([]byte(input), &decoded); err != nil {
		t.Fatal("synthetic profile could not be decoded", err)
	}
	serialized, err := json.Marshal(decoded[len(decoded)-1])
	if err != nil || len(serialized) != size {
		t.Fatalf("synthetic serialized size=%d want=%d error=%v", len(serialized), size, err)
	}
	return []byte(input)
}

func TestAndroidSearchSessionSerializedProfileSizeBoundary(t *testing.T) {
	manifest, err := BuildDefaultSearchManifest(manifestProfile, strings.Repeat("a", 64))
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		name             string
		size             int
		precedingProfile bool
		preserveExisting bool
		wantSizeError    bool
	}{
		{name: "below", size: importer.MaxInputBytes - 1},
		{name: "at", size: importer.MaxInputBytes},
		{name: "above", size: importer.MaxInputBytes + 1, wantSizeError: true},
		{name: "above_after_valid_profile", size: importer.MaxInputBytes + 1, precedingProfile: true, preserveExisting: true, wantSizeError: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			input := profileInputWithSerializedSize(t, tc.size, tc.precedingProfile)
			dir := t.TempDir()
			stateDir, stagingDir := filepath.Join(dir, "state"), filepath.Join(dir, "staging")
			const sentinel = "synthetic-previous-result"
			if tc.preserveExisting {
				for _, path := range []string{stateDir, stagingDir} {
					if err := os.Mkdir(path, 0700); err != nil {
						t.Fatal(err)
					}
					if err := os.WriteFile(filepath.Join(path, "sentinel"), []byte(sentinel), 0600); err != nil {
						t.Fatal(err)
					}
				}
			}
			worker := &profileSizeWorker{}
			session, err := NewSearchSession(42, manifest, input, stateDir, stagingDir, worker)
			if session != nil {
				t.Cleanup(func() { _ = session.Close() })
			}
			if tc.wantSizeError {
				if err == nil || err.Error() != "search_profile_size" || session != nil {
					t.Errorf("oversize profile: session present=%t error=%v", session != nil, err)
				}
				for _, path := range []string{stateDir, stagingDir} {
					if tc.preserveExisting {
						entries, listErr := os.ReadDir(path)
						data, readErr := os.ReadFile(filepath.Join(path, "sentinel"))
						if listErr != nil || readErr != nil || len(entries) != 1 || entries[0].Name() != "sentinel" || string(data) != sentinel {
							t.Error("existing state or staging changed after size rejection")
						}
					} else if _, statErr := os.Stat(path); !os.IsNotExist(statErr) {
						t.Error("state or staging created before size rejection")
					}
				}
				if worker.opens != 0 {
					t.Error("worker opened before size rejection")
				}
				return
			}
			if err != nil || session == nil {
				t.Fatalf("allowed profile rejected: %v", err)
			}
			ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
			defer cancel()
			round, err := session.runner.Worker.Open(ctx, searchbridge.Identity{SessionGeneration: 42, Attempt: 1, Round: 1, WorkerGeneration: 1}, session.runner.Profiles[0], map[string]any{})
			if err != nil || round == nil {
				t.Fatalf("fake worker open: %v", err)
			}
			if err := round.Close(); err != nil {
				t.Fatal(err)
			}
			worker.mu.Lock()
			size, opens, closes := worker.profileSize, worker.opens, worker.closes
			worker.mu.Unlock()
			if size != tc.size || opens != 1 || closes != 1 {
				t.Fatalf("worker profile size=%d opens=%d closes=%d", size, opens, closes)
			}
		})
	}
}

func TestAndroidSearchSessionRawProfileLimitUnchanged(t *testing.T) {
	worker := &fakeEngineWorker{}
	for _, input := range [][]byte{nil, make([]byte, importer.MaxInputBytes+1)} {
		if session, err := NewSearchSession(42, nil, input, "\x00", "\x00", worker); session != nil || err == nil || err.Error() != "invalid_search_input" {
			t.Fatalf("raw profile input contract changed: %v", err)
		}
	}
	if worker.opens != 0 {
		t.Fatal("worker opened for invalid raw input")
	}
}

func TestAndroidSearchSessionInvalidSpaceBeforeProfileSize(t *testing.T) {
	manifestBytes, err := BuildDefaultSearchManifest(manifestProfile, strings.Repeat("a", 64))
	if err != nil {
		t.Fatal(err)
	}
	var manifest model.Manifest
	if err := model.Decode(manifestBytes, &manifest); err != nil {
		t.Fatal(err)
	}
	manifest.Space = json.RawMessage(`{"version":99,"strategy":"exhaustive"}`)
	manifestBytes, err = json.Marshal(manifest)
	if err != nil {
		t.Fatal(err)
	}
	worker := &fakeEngineWorker{}
	input := profileInputWithSerializedSize(t, importer.MaxInputBytes+1, false)
	if session, err := NewSearchSession(42, manifestBytes, input, "\x00", "\x00", worker); session != nil || err == nil || err.Error() != "invalid_search_space" {
		t.Fatalf("space validation precedence changed: %v", err)
	}
	if worker.opens != 0 {
		t.Fatal("worker opened for invalid space")
	}
}
