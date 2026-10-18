package core_test

import (
	"context"
	"encoding/json"
	"github.com/Seraf-seraf/vpn-sweep/internal/core"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/Seraf-seraf/vpn-sweep/internal/socks"
	"github.com/Seraf-seraf/vpn-sweep/internal/testkit"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"testing"
	"time"
)

func cfg(addr, behavior string) []byte {
	_, p, _ := net.SplitHostPort(addr)
	var port int
	for _, c := range p {
		port = port*10 + int(c-'0')
	}
	b, _ := json.Marshal(map[string]any{"test_behavior": behavior, "inbounds": []any{map[string]any{"listen": "127.0.0.1", "port": port, "settings": map[string]any{"accounts": []any{map[string]any{"user": "local-user", "pass": "local-pass"}}}}}})
	return b
}

func TestParentDeathCleanup(t *testing.T) {
	if os.Getenv("VPN_SWEEP_PARENT_HELPER") == "1" {
		binary := os.Getenv("VPN_SWEEP_FAKE_BINARY")
		id, e := core.Identify(context.Background(), binary)
		if e != nil {
			os.Exit(81)
		}
		addr, e := core.ReserveAddress()
		if e != nil {
			os.Exit(82)
		}
		r := core.Runner{Binary: binary, Identity: id, Directory: os.Getenv("VPN_SWEEP_RUNTIME_DIR"), Startup: time.Second}
		_, e = r.Start(context.Background(), cfg(addr, ""), socks.Endpoint{Address: addr, User: "local-user", Pass: "local-pass"})
		if e != nil {
			os.Exit(83)
		}
		if os.WriteFile(os.Getenv("VPN_SWEEP_READY"), []byte(addr), 0600) != nil {
			os.Exit(84)
		}
		select {}
	}
	binary, _ := testkit.FakeCore(t)
	dir := t.TempDir()
	ready := filepath.Join(dir, "ready")
	parent := exec.Command(os.Args[0], "-test.run=^TestParentDeathCleanup$")
	parent.Env = append(os.Environ(), "VPN_SWEEP_PARENT_HELPER=1", "VPN_SWEEP_FAKE_BINARY="+binary, "VPN_SWEEP_RUNTIME_DIR="+filepath.Join(dir, "runtime"), "VPN_SWEEP_READY="+ready)
	if e := parent.Start(); e != nil {
		t.Fatal(e)
	}
	defer func() { parent.Process.Kill(); parent.Wait() }()
	var addr string
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		b, e := os.ReadFile(ready)
		if e == nil {
			addr = string(b)
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if addr == "" {
		t.Fatal("parent never ready")
	}
	if e := parent.Process.Kill(); e != nil {
		t.Fatal(e)
	}
	parent.Wait()
	deadline = time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		c, e := net.DialTimeout("tcp", addr, 20*time.Millisecond)
		if e != nil {
			return
		}
		c.Close()
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("child listener survived parent death")
}
func TestLifecycleAndNoForeignKill(t *testing.T) {
	binary, id := testkit.FakeCore(t)
	foreign := exec.Command("sleep", "30")
	if e := foreign.Start(); e != nil {
		t.Fatal(e)
	}
	defer func() { foreign.Process.Kill(); foreign.Wait() }()
	for _, behavior := range []string{"", "ignore-term", "crash", "delay", "invalid"} {
		t.Run(behavior, func(t *testing.T) {
			addr, e := core.ReserveAddress()
			if e != nil {
				t.Fatal(e)
			}
			proxy := socks.Endpoint{Address: addr, User: "local-user", Pass: "local-pass"}
			r := core.Runner{Binary: binary, Identity: id, Directory: t.TempDir(), Startup: 250 * time.Millisecond, Cleanup: 50 * time.Millisecond}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			h, e := r.Start(ctx, cfg(addr, behavior), proxy)
			if behavior == "crash" || behavior == "delay" || behavior == "invalid" {
				if e == nil {
					h.Close()
					t.Fatal("bad core accepted")
				}
				return
			}
			if e != nil {
				t.Fatal(e)
			}
			if e = h.Close(); e != nil {
				t.Fatal(e)
			}
			if e = h.Close(); e != nil {
				t.Fatal("non-idempotent")
			}
			c, e := net.DialTimeout("tcp", addr, 50*time.Millisecond)
			if e == nil {
				c.Close()
				t.Fatal("listener survived")
			}
			files, _ := os.ReadDir(r.Directory)
			if len(files) != 0 {
				t.Fatal("temporary config remained")
			}
		})
	}
	if e := foreign.Process.Signal(syscall.Signal(0)); e != nil {
		t.Fatal("foreign process was killed", e)
	}
	if model.TargetCommit != id.Commit {
		t.Fatal("identity")
	}
}
