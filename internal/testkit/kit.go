package testkit

import (
	"context"
	"github.com/Seraf-seraf/vpn-sweep/internal/core"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"os/exec"
	"path/filepath"
	"runtime"
	"testing"
)

func FakeCore(t *testing.T) (string, model.CoreIdentity) {
	t.Helper()
	_, file, _, _ := runtime.Caller(0)
	dir := filepath.Dir(file)
	path := filepath.Join(t.TempDir(), "fakecore")
	c := exec.Command("go", "build", "-o", path, filepath.Join(dir, "fakecore"))
	if out, e := c.CombinedOutput(); e != nil {
		t.Fatalf("fake core build: %v %s", e, out)
	}
	id, e := core.Identify(context.Background(), path)
	if e != nil {
		t.Fatal(e)
	}
	return path, id
}
