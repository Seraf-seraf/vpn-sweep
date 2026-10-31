package private

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/Seraf-seraf/vpn-sweep/internal/model"
)

func TestOpenedFileCloseFailureReportsUnknownOnlyForCheckedOpen(t *testing.T) {
	for _, tc := range []struct {
		name  string
		check bool
		code  string
	}{
		{"legacy", false, "unsafe_file"},
		{"checked", true, "file_cleanup_unknown"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			file, err := os.OpenFile(filepath.Join(t.TempDir(), "input"), os.O_CREATE|os.O_RDONLY, 0600)
			if err != nil {
				t.Fatal(err)
			}
			if err = file.Close(); err != nil {
				t.Fatal(err)
			}
			opened, err := validateOpened(file, tc.check)
			if opened != nil || model.Code(err) != tc.code {
				t.Fatalf("close outcome: got %q, want %q", model.Code(err), tc.code)
			}
		})
	}
}
