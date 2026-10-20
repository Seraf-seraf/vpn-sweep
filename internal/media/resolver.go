package media

import (
	"bytes"
	"context"
	_ "embed"
	"encoding/json"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
)

//go:embed resolver.py
var resolverScript []byte

type Resolver struct {
	Python, JS string
	Identity   model.ResolverIdentity
}
type Resolved struct {
	URL          string `json:"url"`
	FormatID     string `json:"format_id"`
	Height       int    `json:"height"`
	NetworkBytes int64  `json:"network_bytes"`
	Error        string `json:"error"`
}
type limitedOutput struct{ bytes.Buffer }

func (w *limitedOutput) Write(p []byte) (int, error) {
	if w.Len()+len(p) > 16384 {
		return 0, model.Err("resolver_output_limit", "resolver")
	}
	return w.Buffer.Write(p)
}
func runProcess(ctx context.Context, cmd *exec.Cmd) error {
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	cmd.WaitDelay = time.Second
	if e := cmd.Start(); e != nil {
		return e
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		// Also kill descendants which outlived their parent.
		syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
		return err
	case <-ctx.Done():
		syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
		<-done
		return ctx.Err()
	}
}
func (r Resolver) call(ctx context.Context, req any, out any) error {
	if !filepath.IsAbs(r.Python) || !filepath.IsAbs(r.JS) {
		return model.Err("resolver_unavailable", "resolver")
	}
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	dir, e := os.MkdirTemp("", "vpn-sweep-resolver-")
	if e != nil {
		return model.Err("resolver_unavailable", "resolver")
	}
	defer os.RemoveAll(dir)
	path := filepath.Join(dir, "resolver.py")
	if os.WriteFile(path, resolverScript, 0600) != nil {
		return model.Err("resolver_unavailable", "resolver")
	}
	data, e := json.Marshal(req)
	if e != nil {
		return model.Err("resolver_invalid_input", "resolver")
	}
	cmd := exec.Command(r.Python, "-I", path)
	cmd.Dir = dir
	cmd.Env = []string{"PATH=" + filepath.Dir(r.Python) + ":" + filepath.Dir(r.JS), "HOME=" + dir, "TMPDIR=" + dir, "LANG=C.UTF-8"}
	cmd.Stdin = bytes.NewReader(data)
	b := &limitedOutput{}
	cmd.Stdout = b
	cmd.Stderr = io.Discard
	if e = runProcess(ctx, cmd); e != nil {
		return model.Err("resolver_blocked", "resolver")
	}
	if model.Decode(b.Bytes(), out) != nil {
		return model.Err("resolver_invalid_output", "resolver")
	}
	return nil
}
func (r Resolver) Identify(ctx context.Context) (model.ResolverIdentity, error) {
	var id model.ResolverIdentity
	e := r.call(ctx, map[string]any{"identity_only": true, "js": r.JS}, &id)
	if e != nil {
		return id, e
	}
	major, _ := strconv.Atoi(strings.Split(strings.TrimPrefix(id.JSVersion, "v"), ".")[0])
	if major < minNodeMajor {
		return id, model.Err("resolver_node_24_required", "resolver")
	}
	if id.YTDLP != ytDLPVersion || id.EJS != ejsVersion || id.AdapterVersion != 1 || !strings.HasPrefix(id.JSVersion, "v") {
		return id, model.Err("resolver_unsupported", "resolver")
	}
	return id, nil
}

var formatID = regexp.MustCompile(`^[a-zA-Z0-9_.-]{1,64}$`)

func (r Resolver) Resolve(ctx context.Context, p socks.Endpoint, y model.YouTubeReference) (Resolved, error) {
	var out Resolved
	req := map[string]any{"js": r.JS, "identity": r.Identity, "video_id": y.VideoID, "min_height": y.MinHeight, "proxy": map[string]string{"address": p.Address, "user": p.User, "pass": p.Pass}}
	e := r.call(ctx, req, &out)
	if out.NetworkBytes < 0 || out.NetworkBytes > MaxResolverBytes {
		return Resolved{NetworkBytes: MaxResolverBytes}, model.Err("resolver_invalid_output", "resolver")
	}
	if e != nil {
		out.NetworkBytes = MaxResolverBytes
		return out, e
	}
	if out.Error != "" {
		code := "resolver_blocked"
		if out.Error == "resolver_unsupported" || out.Error == "resolver_identity_changed" || out.Error == "resolver_auth_or_challenge" || out.Error == "resolver_token_required" || out.Error == "resolver_network_failed" {
			code = out.Error
		}
		return out, model.Err(code, "resolver")
	}
	// A URL returned by a subprocess remains untrusted. No auth headers are accepted.
	if !YouTubeURL(out.URL) || len(out.URL) > 8192 || !formatID.MatchString(out.FormatID) || out.Height < y.MinHeight {
		return Resolved{NetworkBytes: out.NetworkBytes}, model.Err("resolver_invalid_output", "resolver")
	}
	return out, nil
}
