package export

import (
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestRejectUnverifiedSymlinkAndCheckout(t *testing.T) {
	r, _ := importer.Parse([]byte("trojan://secret-marker@vpn.invalid:443?security=tls&sni=tls.invalid"), "uri")
	if Save(t.TempDir(), model.Manifest{}, r.Profiles, model.Attempt{}) == nil {
		t.Fatal("partial exported")
	}
	d := t.TempDir()
	os.Mkdir(filepath.Join(d, ".git"), 0700)
	if _, e := SafeDirectory(filepath.Join(d, "out")); e == nil {
		t.Fatal("repo output")
	}
	target := t.TempDir()
	link := filepath.Join(t.TempDir(), "link")
	os.Symlink(target, link)
	if _, e := SafeDirectory(link); e == nil {
		t.Fatal("symlink accepted")
	}
	if strings.Contains(model.Err("write_failed", "export").Error(), "secret-marker") {
		t.Fatal("secret")
	}
}
