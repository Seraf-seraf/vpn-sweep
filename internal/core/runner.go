package core

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/private"
	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"time"
)

type Runner struct {
	Binary    string
	Identity  model.CoreIdentity
	Directory string
	Startup   time.Duration
	Cleanup   time.Duration
}
type Handle struct {
	Proxy    socks.Endpoint
	cmd      *exec.Cmd
	done     chan struct{}
	closed   chan struct{}
	once     sync.Once
	closeErr error
	waitErr  error
	cleanup  time.Duration
	config   string
}

func (h *Handle) Endpoint() socks.Endpoint { return h.Proxy }

func (h *Handle) Failure() error {
	select {
	case <-h.done:
		var exit *exec.ExitError
		if errors.As(h.waitErr, &exit) {
			if ws, ok := exit.Sys().(syscall.WaitStatus); ok && ws.Signaled() {
				if ws.Signal() == syscall.SIGKILL {
					return model.Err("core_signal_9_cause_unknown", "core")
				}
				return model.Err("core_signaled", "core")
			}
		}
		return model.Err("core_exited", "core")
	default:
		return nil
	}
}

func Hash(binary string) (string, error) {
	f, e := os.Open(binary)
	if e != nil {
		return "", model.Err("core_unavailable", "core")
	}
	defer f.Close()
	st, e := f.Stat()
	if e != nil || !st.Mode().IsRegular() || st.Size() > 256<<20 {
		return "", model.Err("invalid_core_file", "core")
	}
	h := sha256.New()
	if _, e = io.Copy(h, f); e != nil {
		return "", model.Err("core_read_failed", "core")
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

type boundedOutput struct{ b []byte }

func (b *boundedOutput) Write(p []byte) (int, error) {
	n := len(p)
	if len(b.b)+n > 65536 {
		return 0, model.Err("core_output_limit", "core")
	}
	b.b = append(b.b, p...)
	return n, nil
}
func Identify(ctx context.Context, binary string) (model.CoreIdentity, error) {
	var id model.CoreIdentity
	h, e := Hash(binary)
	if e != nil {
		return id, e
	}
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	c := exec.CommandContext(ctx, binary, "version")
	c.Env = environment()
	out := &boundedOutput{}
	c.Stdout = out
	c.Stderr = io.Discard
	if e = runLocked(c); e != nil {
		return id, model.Err("core_version_failed", "core")
	}
	for _, f := range strings.Fields(string(out.b)) {
		if f == "ac5d8a8" || f == model.TargetCommit {
			id.Commit = model.TargetCommit
		}
	}
	if id.Commit == "" {
		return id, model.Err("unsupported_core_commit", "core")
	}
	id.SHA256 = h
	return id, nil
}
func environment() []string {
	env := []string{"LANG=C", "PATH=" + os.Getenv("PATH")}
	for _, k := range []string{"TMPDIR", "SSL_CERT_FILE", "SSL_CERT_DIR"} {
		if v := os.Getenv(k); v != "" {
			env = append(env, k+"="+v)
		}
	}
	return env
}

func runLocked(cmd *exec.Cmd) error {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	cmd.SysProcAttr = &syscall.SysProcAttr{Pdeathsig: syscall.SIGKILL}
	return cmd.Run()
}
func ReserveAddress() (string, error) {
	l, e := net.Listen("tcp4", "127.0.0.1:0")
	if e != nil {
		return "", model.Err("listener_unavailable", "core")
	}
	a := l.Addr().String()
	if e = l.Close(); e != nil {
		return "", e
	}
	return a, nil
}
func (r Runner) Start(ctx context.Context, config []byte, proxy socks.Endpoint) (*Handle, error) {
	hsh, e := Hash(r.Binary)
	if e != nil {
		return nil, e
	}
	if r.Identity.Commit != model.TargetCommit || hsh != r.Identity.SHA256 {
		return nil, model.Err("core_identity_changed", "core")
	}
	d, e := private.Dir(r.Directory)
	if e != nil {
		return nil, e
	}
	name := filepath.Join(d, "core-"+model.ID()+".json")
	if e = private.Atomic(name, config); e != nil {
		return nil, e
	}
	remove := func() { os.Remove(name) }
	startup := r.Startup
	if startup <= 0 {
		startup = 15 * time.Second
	}
	startCtx, cancel := context.WithTimeout(ctx, startup)
	defer cancel()
	test := exec.CommandContext(startCtx, r.Binary, "run", "-test", "-c", name)
	test.Env = environment()
	test.Stdout = io.Discard
	test.Stderr = io.Discard
	if e = runLocked(test); e != nil {
		remove()
		if startCtx.Err() != nil {
			return nil, model.Err("core_start_timeout", "core")
		}
		return nil, model.Err("core_config_rejected", "core")
	}
	cmd := exec.Command(r.Binary, "run", "-c", name)
	cmd.Env = environment()
	cmd.Stdout = io.Discard
	cmd.Stderr = io.Discard
	cmd.SysProcAttr = &syscall.SysProcAttr{Pdeathsig: syscall.SIGKILL}
	cleanup := r.Cleanup
	if cleanup <= 0 {
		cleanup = 5 * time.Second
	}
	h := &Handle{Proxy: proxy, cmd: cmd, done: make(chan struct{}), closed: make(chan struct{}), cleanup: cleanup, config: name}
	started := make(chan error, 1)
	go func() {
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		e := cmd.Start()
		started <- e
		if e == nil {
			h.waitErr = cmd.Wait()
			close(h.done)
		}
	}()
	if e = <-started; e != nil {
		remove()
		return nil, model.Err("core_start_failed", "core")
	}
	ticker := time.NewTicker(25 * time.Millisecond)
	defer ticker.Stop()
	for {
		probeCtx, c := context.WithTimeout(startCtx, 200*time.Millisecond)
		conn, err := proxy.Negotiate(probeCtx)
		c()
		if err == nil {
			conn.Close()
			go func() {
				select {
				case <-ctx.Done():
					h.Close()
				case <-h.closed:
				}
			}()
			return h, nil
		}
		select {
		case <-h.done:
			remove()
			return nil, model.Err("core_exited_before_ready", "core")
		case <-startCtx.Done():
			if e = h.Close(); e != nil {
				return nil, e
			}
			return nil, model.Err("core_start_timeout", "core")
		case <-ticker.C:
		}
	}
}
func (h *Handle) Close() error {
	h.once.Do(func() {
		defer close(h.closed)
		defer os.Remove(h.config)
		select {
		case <-h.done:
			return
		default:
		}
		e := h.cmd.Process.Signal(syscall.SIGTERM)
		if e != nil && !errors.Is(e, os.ErrProcessDone) {
			h.closeErr = model.Err("core_signal_failed", "cleanup")
		}
		timer := time.NewTimer(h.cleanup)
		defer timer.Stop()
		select {
		case <-h.done:
			return
		case <-timer.C:
		}
		if e = h.cmd.Process.Kill(); e != nil && !errors.Is(e, os.ErrProcessDone) {
			h.closeErr = model.Err("core_kill_failed", "cleanup")
		}
		select {
		case <-h.done:
		case <-time.After(h.cleanup):
			h.closeErr = model.Err("core_wait_timeout", "cleanup")
		}
	})
	return h.closeErr
}
