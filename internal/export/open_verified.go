package export

import (
	"crypto/sha256"
	"encoding/hex"
	"io"
	"os"
	"path/filepath"

	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/private"
)

const maxExportFileBytes int64 = 16 << 20

// OpenVerifiedResult returns an owned file positioned at the start of the
// verified export. The caller must close it and recheck its digest when used.
func OpenVerifiedResult(dir, seriesID, candidateID string) (*os.File, string, error) {
	root, err := existingSafeDirectory(dir)
	if err != nil {
		return nil, "", err
	}
	marker, exists, err := readCompletionWith(root, readAndClose)
	if err != nil {
		return nil, "", err
	}
	if !exists {
		return nil, "", model.Err("export_incomplete", "export")
	}
	if marker.CandidateID != candidateID {
		if !resultID(candidateID) {
			return nil, "", model.Err("export_conflict", "export")
		}
		root, err = existingSafeDirectory(filepath.Join(root, candidateID))
		if err != nil {
			return nil, "", err
		}
		marker, exists, err = readCompletionWith(root, readAndClose)
		if err != nil {
			return nil, "", err
		}
		if !exists {
			return nil, "", model.Err("export_incomplete", "export")
		}
	}
	if marker.SeriesID != seriesID || marker.CandidateID != candidateID {
		return nil, "", model.Err("export_conflict", "export")
	}
	file, err := openChecked(filepath.Join(root, "working.json"))
	if err != nil {
		return nil, "", err
	}
	hash := sha256.New()
	count, err := io.Copy(hash, io.LimitReader(file, maxExportFileBytes+1))
	if err != nil || count > maxExportFileBytes || hex.EncodeToString(hash.Sum(nil)) != marker.ConfigSHA256 {
		return nil, "", closeFailedResult(file, model.Err("export_integrity_failed", "export"))
	}
	if _, err = file.Seek(0, io.SeekStart); err != nil {
		return nil, "", closeFailedResult(file, model.Err("export_unavailable", "export"))
	}
	return file, marker.ConfigSHA256, nil
}

func existingSafeDirectory(path string) (string, error) {
	path, err := filepath.Abs(path)
	if err != nil {
		return "", model.Err("output_unavailable", "export")
	}
	info, err := os.Lstat(path)
	if err != nil || !info.IsDir() || info.Mode().Perm() != 0700 {
		return "", model.Err("output_unavailable", "export")
	}
	return safeResolvedDirectory(path)
}

func readAndClose(path string, maxBytes int64) ([]byte, error) {
	file, err := openChecked(path)
	if err != nil {
		return nil, err
	}
	data, readErr := io.ReadAll(io.LimitReader(file, maxBytes+1))
	if err = file.Close(); err != nil {
		return nil, model.Err("export_cleanup_unknown", "export")
	}
	if readErr != nil || int64(len(data)) > maxBytes {
		return nil, model.Err("file_read_limit", "filesystem")
	}
	return data, nil
}

func openChecked(path string) (*os.File, error) {
	file, err := private.OpenWithCheckedClose(path, os.O_RDONLY)
	if err != nil && model.Code(err) == "file_cleanup_unknown" {
		return nil, model.Err("export_cleanup_unknown", "export")
	}
	return file, err
}

func closeFailedResult(file *os.File, cause error) error {
	if err := file.Close(); err != nil {
		return model.Err("export_cleanup_unknown", "export")
	}
	return cause
}
