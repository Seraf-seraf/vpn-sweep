package mobile

import (
	"bytes"
	"context"
	"errors"
	"net"
	"net/netip"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	xlog "github.com/xtls/xray-core/common/log"
	"github.com/xtls/xray-core/common/platform"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	_ "github.com/xtls/xray-core/main/distro/all"
	_ "github.com/xtls/xray-core/proxy/tun"
	"github.com/xtls/xray-core/transport/internet"
	"golang.org/x/sys/unix"
)

const CoreCommit = "ac5d8a83ba798c390cd89f235c3d4c35c77c1586"
const bootstrapTimeout = 10 * time.Second

// Protector is implemented by VpnService. It protects and binds each new socket
// to the selected physical network before the socket can send any packets.
type Protector interface {
	Protect(fd int) bool
}

type protection struct {
	callback      Protector
	dnsIP         string
	search        AndroidNetwork
	searchContext context.Context
	searchCancel  context.CancelFunc
	searchRevoked atomic.Bool
	searchRoundMu sync.RWMutex
	searchRound   context.Context
	passed        atomic.Int64
	failed        atomic.Int64
}

var activeProtection atomic.Pointer[protection]
var nullFD int
var runtimeState struct {
	sync.Mutex
	instance            *core.Instance
	guard               *protection
	searchInitialized   bool
	searchWorkerCreated bool
}

type discardLogs struct{}

func (discardLogs) Handle(_ xlog.Message) {}

func init() {
	xlog.RegisterHandler(discardLogs{})
	var err error
	nullFD, err = unix.Open("/dev/null", unix.O_RDONLY|unix.O_CLOEXEC, 0)
	if err != nil {
		panic("socket_guard_unavailable")
	}
	// This module is the application's sole Go runtime. Install once so late
	// DNS work cannot race a resolver replacement during start/stop.
	net.DefaultResolver = &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
		guard := activeProtection.Load()
		if guard == nil {
			return nil, errors.New("vpn_not_running")
		}
		if guard.search != nil {
			return searchDNSConnection(ctx, guard)
		}
		dialer := net.Dialer{Timeout: bootstrapTimeout, Control: protectSocket}
		return dialer.DialContext(ctx, network, net.JoinHostPort(guard.dnsIP, "53"))
	}}
	if err := internet.RegisterDialerController(protectSocket); err != nil {
		panic("socket_controller_unavailable")
	}
	if err := internet.RegisterListenerController(protectListener); err != nil {
		panic("socket_controller_unavailable")
	}
}

func protectSocket(network string, address string, raw syscall.RawConn) error {
	return controlSocket(nil, SearchRoleXrayDial, network, address, raw, invalidateSocket)
}

func protectListener(network string, address string, raw syscall.RawConn) error {
	return controlSocket(nil, SearchRoleXrayListener, network, address, raw, invalidateSocket)
}

func controlSocket(expected *protection, role int, network string, address string, raw syscall.RawConn, invalidate func(int) error) error {
	var protectionErr error
	err := raw.Control(func(fd uintptr) {
		guard := activeProtection.Load()
		if guard != nil && (expected == nil || expected == guard) && guard.allowSocket(int(fd), role, network, address) &&
			activeProtection.Load() == guard {
			guard.passed.Add(1)
			return
		}
		if expected != nil {
			expected.revokeSearch()
		}
		if guard != nil {
			guard.revokeSearch()
			guard.failed.Add(1)
		}
		// This Xray version ignores controller errors. Keep descriptor ownership
		// with Go, but replace the socket with /dev/null so a dial cannot escape.
		if err := invalidate(int(fd)); err != nil {
			// Continuing with an unprotected live socket would create a VPN loop.
			panic("socket_protection_failed")
		}
		protectionErr = errors.New("socket_protection_failed")
	})
	if err != nil {
		if guard := activeProtection.Load(); guard != nil && guard.search != nil {
			panic("search_socket_control_failed")
		}
		return errors.New("socket_control_failed")
	}
	return protectionErr
}

func invalidateSocket(fd int) error { return unix.Dup3(nullFD, fd, unix.O_CLOEXEC) }

// ValidateConfig checks Xray's actual schema before Android establishes TUN.
func ValidateConfig(source []byte) error {
	config, err := runtimeConfig(source, "1.1.1.1")
	if err != nil {
		return err
	}
	if _, err = serial.LoadJSONConfig(bytes.NewReader(config)); err != nil {
		return errors.New("core_config_rejected")
	}
	return nil
}

// Start owns one embedded Xray instance. The application retains ownership of
// tunFD; upstream duplicates it and closes its duplicate on Stop.
func Start(source []byte, tunFD int, bootstrapDNS string, protector Protector) error {
	runtimeState.Lock()
	defer runtimeState.Unlock()
	if runtimeState.instance != nil {
		return errors.New("vpn_already_running")
	}
	if runtimeState.searchInitialized {
		return errors.New("search_already_initialized")
	}
	if tunFD < 0 || protector == nil {
		return errors.New("invalid_vpn_runtime")
	}
	dnsIP, err := netip.ParseAddr(bootstrapDNS)
	if err != nil {
		return errors.New("invalid_bootstrap_dns")
	}
	config, err := runtimeConfig(source, bootstrapDNS)
	if err != nil {
		return err
	}
	pb, err := serial.LoadJSONConfig(bytes.NewReader(config))
	if err != nil {
		return errors.New("core_config_rejected")
	}
	guard := &protection{callback: protector, dnsIP: dnsIP.String()}
	activeProtection.Store(guard)
	if err = os.Setenv(platform.TunFdKey, strconv.Itoa(tunFD)); err != nil {
		activeProtection.Store(nil)
		return errors.New("tun_fd_unavailable")
	}
	instance, err := core.New(pb)
	if err == nil {
		err = instance.Start()
	}
	if err != nil {
		if instance != nil {
			_ = instance.Close()
		}
		activeProtection.Store(nil)
		_ = os.Unsetenv(platform.TunFdKey)
		return errors.New("core_start_failed")
	}
	runtimeState.instance, runtimeState.guard = instance, guard
	return nil
}

func Stop() error {
	runtimeState.Lock()
	defer runtimeState.Unlock()
	if runtimeState.instance == nil {
		return nil
	}
	err := runtimeState.instance.Close()
	// Keep the guard installed while the core is closing its connections.
	activeProtection.Store(nil)
	runtimeState.instance, runtimeState.guard = nil, nil
	_ = os.Unsetenv(platform.TunFdKey)
	if err != nil {
		return errors.New("core_stop_failed")
	}
	return nil
}

func ProtectedSockets() int64 {
	if guard := activeProtection.Load(); guard != nil {
		return guard.passed.Load()
	}
	return 0
}

func ProtectionFailures() int64 {
	if guard := activeProtection.Load(); guard != nil {
		return guard.failed.Load()
	}
	return 0
}
