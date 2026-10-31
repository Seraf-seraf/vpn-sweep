package private

import (
	"encoding/json"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"io"
	"os"
	"path/filepath"
	"syscall"
)

func Dir(path string) (string, error) {
	p, e := filepath.Abs(path)
	if e != nil {
		return "", model.Err("invalid_path", "filesystem")
	}
	if e = os.MkdirAll(p, 0700); e != nil {
		return "", model.Err("directory_unavailable", "filesystem")
	}
	st, e := os.Lstat(p)
	if e != nil || !st.IsDir() || st.Mode().Perm() != 0700 {
		return "", model.Err("unsafe_directory", "filesystem")
	}
	return p, nil
}
func Open(path string, flags int) (*os.File, error) {
	return open(path, flags, false)
}

func OpenWithCheckedClose(path string, flags int) (*os.File, error) {
	return open(path, flags, true)
}

func open(path string, flags int, checkClose bool) (*os.File, error) {
	fd, e := syscall.Open(path, flags|syscall.O_NOFOLLOW|syscall.O_CLOEXEC, 0600)
	if e != nil {
		return nil, model.Err("file_unavailable", "filesystem")
	}
	f := os.NewFile(uintptr(fd), path)
	return validateOpened(f, checkClose)
}

func validateOpened(f *os.File, checkClose bool) (*os.File, error) {
	st, e := f.Stat()
	if e != nil || !st.Mode().IsRegular() || st.Mode().Perm() != 0600 {
		if closeErr := f.Close(); checkClose && closeErr != nil {
			return nil, model.Err("file_cleanup_unknown", "filesystem")
		}
		return nil, model.Err("unsafe_file", "filesystem")
	}
	return f, nil
}
func Read(path string, max int64) ([]byte, error) {
	f, e := Open(path, os.O_RDONLY)
	if e != nil {
		return nil, e
	}
	defer f.Close()
	b, e := io.ReadAll(io.LimitReader(f, max+1))
	if e != nil || int64(len(b)) > max {
		return nil, model.Err("file_read_limit", "filesystem")
	}
	return b, nil
}
func Atomic(path string, b []byte) error {
	dir := filepath.Dir(path)
	if _, e := Dir(dir); e != nil {
		return e
	}
	if st, e := os.Lstat(path); e == nil && (!st.Mode().IsRegular() || st.Mode().Perm() != 0600) {
		return model.Err("unsafe_destination", "filesystem")
	}
	f, e := os.CreateTemp(dir, ".pending-")
	if e != nil {
		return model.Err("write_failed", "filesystem")
	}
	name := f.Name()
	defer os.Remove(name)
	defer f.Close()
	if e = f.Chmod(0600); e == nil {
		_, e = f.Write(b)
	}
	if e == nil {
		e = f.Sync()
	}
	if e == nil {
		e = f.Close()
	}
	if e == nil {
		e = os.Rename(name, path)
	}
	if e == nil {
		e = SyncDir(dir)
	}
	if e != nil {
		return model.Err("write_failed", "filesystem")
	}
	return nil
}
func JSON(path string, v any) error {
	b, e := json.MarshalIndent(v, "", "  ")
	if e != nil {
		return model.Err("serialization_failed", "filesystem")
	}
	return Atomic(path, b)
}
func SyncDir(path string) error {
	f, e := os.Open(path)
	if e != nil {
		return e
	}
	defer f.Close()
	return f.Sync()
}
