package export

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestOpenVerifiedResultUsesRootAndCandidateDirectory(t *testing.T) {
	root := t.TempDir()
	first := strings.Repeat("a", 64)
	second := strings.Repeat("b", 64)
	writeVerifiedFixture(t, root, "series", first, "first-config")
	child := filepath.Join(root, second)
	if err := os.Mkdir(child, 0700); err != nil {
		t.Fatal(err)
	}
	writeVerifiedFixture(t, child, "series", second, "second-config")
	for _, tc := range []struct{ candidate, content string }{{first, "first-config"}, {second, "second-config"}} {
		t.Run(tc.content, func(t *testing.T) {
			file, digest, err := OpenVerifiedResult(root, "series", tc.candidate)
			if err != nil {
				t.Fatal(err)
			}
			data, readErr := io.ReadAll(file)
			closeErr := file.Close()
			if readErr != nil || closeErr != nil || string(data) != tc.content || digest != fixtureDigest([]byte(tc.content)) {
				t.Fatalf("wrong result or ownership: read=%v close=%v", readErr, closeErr)
			}
		})
	}
}

func TestOpenVerifiedResultRejectsMismatchAndDoesNotCreateDirectories(t *testing.T) {
	root := t.TempDir()
	candidate := strings.Repeat("a", 64)
	writeVerifiedFixture(t, root, "series", candidate, "config")
	for _, tc := range []struct{ name, dir, series, candidate string }{
		{"series", root, "other", candidate},
		{"invalid-candidate", root, "series", "../other"},
		{"missing-candidate", root, "series", strings.Repeat("b", 64)},
		{"missing-root", filepath.Join(root, "missing"), "series", candidate},
	} {
		t.Run(tc.name, func(t *testing.T) {
			file, _, err := OpenVerifiedResult(tc.dir, tc.series, tc.candidate)
			if file != nil {
				_ = file.Close()
			}
			if err == nil || file != nil {
				t.Fatal("mismatch was accepted")
			}
		})
	}
	for _, path := range []string{filepath.Join(root, "missing"), filepath.Join(root, strings.Repeat("b", 64))} {
		if _, err := os.Lstat(path); !os.IsNotExist(err) {
			t.Fatalf("lookup created a directory: %v", err)
		}
	}
}

func TestOpenVerifiedResultRequiresAllCompletionHashesAndPrivateFiles(t *testing.T) {
	for _, name := range []string{"working.json", "report.json", "verification.json"} {
		t.Run("tampered-"+name, func(t *testing.T) {
			root := t.TempDir()
			candidate := strings.Repeat("a", 64)
			writeVerifiedFixture(t, root, "series", candidate, "config")
			if err := os.WriteFile(filepath.Join(root, name), []byte("tampered"), 0600); err != nil {
				t.Fatal(err)
			}
			file, _, err := OpenVerifiedResult(root, "series", candidate)
			if file != nil {
				_ = file.Close()
			}
			if err == nil || file != nil {
				t.Fatal("tampered export was accepted")
			}
		})
	}
	for _, mode := range []string{"missing-completion", "symlink-completion", "unsafe-mode", "symlink-root", "unsafe-dir"} {
		t.Run(mode, func(t *testing.T) {
			root := t.TempDir()
			candidate := strings.Repeat("a", 64)
			writeVerifiedFixture(t, root, "series", candidate, "config")
			path := root
			switch mode {
			case "missing-completion":
				if err := os.Remove(filepath.Join(root, "COMPLETE.json")); err != nil {
					t.Fatal(err)
				}
			case "symlink-completion":
				marker := filepath.Join(root, "COMPLETE.json")
				if err := os.Remove(marker); err != nil {
					t.Fatal(err)
				}
				if err := os.Symlink(filepath.Join(root, "working.json"), marker); err != nil {
					t.Fatal(err)
				}
			case "unsafe-mode":
				if err := os.Chmod(filepath.Join(root, "working.json"), 0644); err != nil {
					t.Fatal(err)
				}
			case "symlink-root":
				path = filepath.Join(t.TempDir(), "link")
				if err := os.Symlink(root, path); err != nil {
					t.Fatal(err)
				}
			case "unsafe-dir":
				if err := os.Chmod(root, 0755); err != nil {
					t.Fatal(err)
				}
			}
			file, _, err := OpenVerifiedResult(path, "series", candidate)
			if file != nil {
				_ = file.Close()
			}
			if err == nil || file != nil {
				t.Fatal("unsafe export was accepted")
			}
		})
	}
}

func TestOpenVerifiedResultKeepsVerifiedOpenedFile(t *testing.T) {
	root := t.TempDir()
	candidate := strings.Repeat("a", 64)
	writeVerifiedFixture(t, root, "series", candidate, "original")
	file, digest, err := OpenVerifiedResult(root, "series", candidate)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	path := filepath.Join(root, "working.json")
	if err := os.Rename(path, filepath.Join(root, "old")); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("replacement"), 0600); err != nil {
		t.Fatal(err)
	}
	data, err := io.ReadAll(file)
	if err != nil || string(data) != "original" || digest != fixtureDigest(data) {
		t.Fatalf("result did not retain verified file: %v", err)
	}
}

func TestOpenVerifiedResultEnforcesFileSizeLimit(t *testing.T) {
	for _, delta := range []int{-1, 0, 1} {
		t.Run(fmt.Sprintf("delta-%d", delta), func(t *testing.T) {
			root := t.TempDir()
			candidate := strings.Repeat("a", 64)
			content := strings.Repeat("x", int(maxExportFileBytes)+delta)
			writeVerifiedFixture(t, root, "series", candidate, content)
			file, _, err := OpenVerifiedResult(root, "series", candidate)
			if delta > 0 {
				if file != nil {
					_ = file.Close()
				}
				if err == nil || file != nil {
					t.Fatal("oversized result was accepted")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if err = file.Close(); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func writeVerifiedFixture(t *testing.T, dir, series, candidate, config string) {
	t.Helper()
	contents := map[string][]byte{
		"working.json":      []byte(config),
		"report.json":       []byte("synthetic-report"),
		"verification.json": []byte("synthetic-verification"),
	}
	for name, data := range contents {
		if err := os.WriteFile(filepath.Join(dir, name), data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	marker := completion{Version: 1, SeriesID: series, CandidateID: candidate,
		ConfigSHA256: fixtureDigest(contents["working.json"]), ReportSHA256: fixtureDigest(contents["report.json"]),
		VerificationSHA256: fixtureDigest(contents["verification.json"])}
	data, err := json.Marshal(marker)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "COMPLETE.json"), data, 0600); err != nil {
		t.Fatal(err)
	}
}

func fixtureDigest(data []byte) string {
	hash := sha256.Sum256(data)
	return hex.EncodeToString(hash[:])
}
