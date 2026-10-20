package media

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestPrepareInstallsReusesAndFreezesDependencies(t *testing.T) {
	base := t.TempDir()
	bin := filepath.Join(base, "bin")
	if err := os.Mkdir(bin, 0700); err != nil {
		t.Fatal(err)
	}
	t.Chdir(base)
	t.Setenv("PATH", bin)
	id := model.ResolverIdentity{YTDLP: ytDLPVersion, EJS: ejsVersion, AdapterVersion: 1, JSVersion: "v24.19.0", JSSHA256: "original"}
	identity, err := json.Marshal(id)
	if err != nil {
		t.Fatal(err)
	}
	ready, calls, fail := filepath.Join(base, "ready"), filepath.Join(base, "calls"), filepath.Join(base, "fail")
	quote := func(s string) string { return "'" + strings.ReplaceAll(s, "'", "'\\''") + "'" }
	script := fmt.Sprintf(`#!/bin/sh
printf '%%s\n' "$*" >> %s
if [ "$2" = "-m" ] && [ "$3" = "venv" ]; then
  /bin/mkdir -p "$4/bin"
  /bin/cp "$0" "$4/bin/python"
  exit 0
fi
if [ "$2" = "-m" ] && [ "$3" = "pip" ]; then
  if [ -e %s ]; then exit 1; fi
  /bin/touch %s
  exit 0
fi
if [ -e %s ]; then printf '%%s\n' %s; exit 0; fi
exit 1
`, quote(calls), quote(fail), quote(ready), quote(ready), quote(string(identity)))
	if err = os.WriteFile(filepath.Join(bin, "python3"), []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(filepath.Join(bin, "node"), []byte("#!/bin/sh\nprintf 'v24.19.0\\n'\n"), 0700); err != nil {
		t.Fatal(err)
	}
	dir := filepath.Join(base, "runtime with spaces")
	if err = os.WriteFile(fail, nil, 0600); err != nil {
		t.Fatal(err)
	}
	if _, err = Prepare(context.Background(), dir, nil, nil); model.Code(err) != "youtube_dependencies_install_failed" {
		t.Fatal("failed install accepted", err)
	}
	if err = os.Remove(fail); err != nil {
		t.Fatal(err)
	}
	r, err := Prepare(context.Background(), dir, nil, nil)
	if err != nil || r.Identity != id {
		t.Fatal("install retry failed", r, err)
	}
	before, _ := os.ReadFile(calls)
	if !strings.Contains(string(before), "yt-dlp=="+ytDLPVersion) || !strings.Contains(string(before), "yt-dlp-ejs=="+ejsVersion) {
		t.Fatal("pinned dependencies not installed")
	}
	if _, err = Prepare(context.Background(), dir, &id, nil); err != nil {
		t.Fatal("cached environment not reused", err)
	}
	id.JSSHA256 = "changed"
	if _, err = Prepare(context.Background(), dir, &id, nil); model.Code(err) != "resolver_identity_changed" {
		t.Fatal("changed environment accepted", err)
	}
	after, _ := os.ReadFile(calls)
	if strings.Count(string(after), "-m pip") != strings.Count(string(before), "-m pip") {
		t.Fatal("resume reinstalled dependencies")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err = Prepare(ctx, filepath.Join(base, "cancelled"), nil, nil); model.Code(err) != "interrupted" {
		t.Fatal("cancellation lost", err)
	}
	deadline, stop := context.WithDeadline(context.Background(), time.Now().Add(-time.Second))
	defer stop()
	if _, err = Prepare(deadline, filepath.Join(base, "expired"), nil, nil); model.Code(err) != "youtube_setup_timeout" {
		t.Fatal("timeout confused with cancellation", err)
	}
}
