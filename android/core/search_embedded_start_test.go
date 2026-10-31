package mobile

import (
	"bytes"
	"context"
	"errors"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Seraf-seraf/vpn-sweep/android/core/searchdns"
	"github.com/Seraf-seraf/vpn-sweep/internal/importer"
	"github.com/Seraf-seraf/vpn-sweep/internal/model"
	"github.com/xtls/xray-core/core"
	xsocks "github.com/xtls/xray-core/proxy/socks"
)

type fakeSearchRunningCore struct {
	starts   atomic.Int32
	closes   atomic.Int32
	startErr error
	closeErr error
}

func (c *fakeSearchRunningCore) Start() error { c.starts.Add(1); return c.startErr }
func (c *fakeSearchRunningCore) Close() error { c.closes.Add(1); return c.closeErr }

func launchProfile(t *testing.T) model.Profile {
	t.Helper()
	input, err := importer.Parse([]byte("socks5://synthetic.invalid:1080"), "uri")
	if err != nil {
		t.Fatal(err)
	}
	return input.Profiles[0]
}

func TestAndroidSearchLaunchRendersAuthenticatesAndKeepsAttemptContext(t *testing.T) {
	owner, guard := installFakeOwner(t)
	attempt, stopAttempt := context.WithCancel(guard.searchContext)
	defer stopAttempt()
	startup, stopStartup := context.WithCancel(attempt)
	instance := &fakeSearchRunningCore{}
	var instanceContext context.Context
	var configured *xsocks.ServerConfig
	var connection *fakeAuthSocket
	ports := searchLaunchPorts{
		reserve: func(ctx context.Context, actual *protection) (string, error) {
			if ctx.Err() != nil || actual != guard {
				t.Fatal("reservation lost owner or deadline")
			}
			return "127.0.0.1:1080", nil
		},
		newCore: func(ctx context.Context, config *core.Config) (searchRunningCore, error) {
			instanceContext = ctx
			if ctx.Value(searchDNSContextKey{}) != guard || len(config.App) == 0 || len(config.Inbound) != 1 {
				t.Fatal("rendered config lost the guarded DNS feature or inbound")
			}
			feature, err := config.App[0].GetInstance()
			if _, ok := feature.(*searchdns.Config); err != nil || !ok {
				t.Fatal("guarded DNS was not installed before Xray fallback")
			}
			raw, err := config.Inbound[0].ProxySettings.GetInstance()
			var ok bool
			configured, ok = raw.(*xsocks.ServerConfig)
			if err != nil || !ok || configured.AuthType != xsocks.AuthType_PASSWORD || !configured.UdpEnabled {
				t.Fatal("SOCKS inbound did not require credentials and UDP")
			}
			return instance, nil
		},
		negotiate: func(ctx context.Context, probes *SearchWorkerProbes) (net.Conn, error) {
			if configured == nil || len(probes.proxy.User) != 32 || len(probes.proxy.Pass) != 32 ||
				configured.Accounts[probes.proxy.User] != probes.proxy.Pass {
				t.Fatal("readiness did not use the rendered credentials")
			}
			connection = &fakeAuthSocket{input: bytes.NewReader([]byte{5, 2, 1, 0})}
			probes.proxy.SocketDialer = searchSocketDialer{owner: guard, factory: &fakeSocketFactory{conn: connection}}
			return probes.proxy.Negotiate(ctx)
		},
	}
	started, probes, err := launchSearchEmbedded(startup, attempt, guard, launchProfile(t), map[string]any{}, ports)
	if err != nil || started != instance || probes == nil || instance.starts.Load() != 1 {
		t.Fatal("fake embedded launch failed", err)
	}
	stopStartup()
	if instanceContext.Err() != nil {
		t.Fatal("Xray inherited the short-lived startup context")
	}
	if connection == nil || !connection.closed || len(owner.roles) != 1 || owner.roles[0] != SearchRoleSOCKSControl ||
		!bytes.Equal(connection.output.Bytes()[:3], []byte{5, 1, 2}) {
		t.Fatal("readiness was not authenticated, guarded and closed")
	}
	probes.Cancel()
	if err := started.Close(); err != nil || instance.closes.Load() != 1 {
		t.Fatal("fake core was not cleaned")
	}
}

func TestAndroidSearchEmbeddedFailureStatusesRequireCleanup(t *testing.T) {
	for _, tc := range []struct {
		name       string
		newErr     error
		startErr   error
		closeErr   error
		want       int
		wantStarts int32
		wantCloses int32
	}{
		{name: "new_unknown", newErr: errors.New("private"), want: SearchWorkerCleanupIncomplete},
		{name: "start_failed", startErr: errors.New("private"), want: SearchWorkerCandidateStartFailed, wantStarts: 1, wantCloses: 1},
		{name: "close_failed", startErr: errors.New("private"), closeErr: errors.New("private"), want: SearchWorkerCleanupIncomplete, wantStarts: 1, wantCloses: 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, guard := installFakeOwner(t)
			instance := &fakeSearchRunningCore{startErr: tc.startErr, closeErr: tc.closeErr}
			ports := searchLaunchPorts{
				reserve: func(context.Context, *protection) (string, error) { return "127.0.0.1:1080", nil },
				newCore: func(context.Context, *core.Config) (searchRunningCore, error) {
					if tc.newErr != nil {
						return nil, tc.newErr
					}
					return instance, nil
				},
				negotiate: func(context.Context, *SearchWorkerProbes) (net.Conn, error) {
					t.Fatal("failed start reached readiness")
					return nil, nil
				},
			}
			worker := newSearchEmbeddedWorker(guard, func(startup, attempt context.Context, owner *protection, profile model.Profile, params map[string]any) (searchCore, *SearchWorkerProbes, error) {
				return launchSearchEmbedded(startup, attempt, owner, profile, params, ports)
			})
			profile, params := embeddedInput(t)
			if got := worker.OpenStatus(profile, params, 1000, 1000); got != tc.want {
				t.Fatalf("status=%d want=%d", got, tc.want)
			}
			if instance.starts.Load() != tc.wantStarts || instance.closes.Load() != tc.wantCloses {
				t.Fatalf("starts=%d closes=%d", instance.starts.Load(), instance.closes.Load())
			}
		})
	}
}

func TestAndroidSearchEmbeddedReadinessTimeoutClosesCore(t *testing.T) {
	_, guard := installFakeOwner(t)
	instance := &fakeSearchRunningCore{}
	ports := searchLaunchPorts{
		reserve: func(context.Context, *protection) (string, error) { return "127.0.0.1:1080", nil },
		newCore: func(context.Context, *core.Config) (searchRunningCore, error) { return instance, nil },
		negotiate: func(context.Context, *SearchWorkerProbes) (net.Conn, error) {
			return nil, errors.New("not_ready")
		},
	}
	worker := newSearchEmbeddedWorker(guard, func(startup, attempt context.Context, owner *protection, profile model.Profile, params map[string]any) (searchCore, *SearchWorkerProbes, error) {
		return launchSearchEmbedded(startup, attempt, owner, profile, params, ports)
	})
	profile, params := embeddedInput(t)
	if got := worker.OpenStatus(profile, params, 50, 1000); got != SearchWorkerCandidateStartTimeout {
		t.Fatalf("readiness timeout status=%d", got)
	}
	if instance.starts.Load() != 1 || instance.closes.Load() != 1 {
		t.Fatal("readiness timeout did not clean its core")
	}
}

func TestAndroidSearchEmbeddedAttemptDeadlineIsNotStartupTimeout(t *testing.T) {
	_, guard := installFakeOwner(t)
	instance := &fakeEmbeddedCore{}
	worker := newSearchEmbeddedWorker(guard, func(startup context.Context, _ context.Context, _ *protection, _ model.Profile, _ map[string]any) (searchCore, *SearchWorkerProbes, error) {
		<-startup.Done()
		return instance, nil, startup.Err()
	})
	worker.cancel()
	worker.ctx, worker.cancel = context.WithTimeout(guard.searchContext, 30*time.Millisecond)
	profile, params := embeddedInput(t)
	if got := worker.OpenStatus(profile, params, 1000, 1000); got != SearchWorkerDeadline {
		t.Fatalf("attempt deadline status=%d", got)
	}
	if instance.closes.Load() != 1 {
		t.Fatal("attempt deadline did not clean its core")
	}
}

type failingCloseAuthSocket struct{ *fakeAuthSocket }

func (c *failingCloseAuthSocket) Close() error {
	c.fakeAuthSocket.Close()
	return errors.New("private close error")
}

func TestAndroidSearchEmbeddedReadinessCloseFailureQuarantines(t *testing.T) {
	_, guard := installFakeOwner(t)
	instance := &fakeSearchRunningCore{}
	ports := searchLaunchPorts{
		reserve: func(context.Context, *protection) (string, error) { return "127.0.0.1:1080", nil },
		newCore: func(context.Context, *core.Config) (searchRunningCore, error) { return instance, nil },
		negotiate: func(ctx context.Context, probes *SearchWorkerProbes) (net.Conn, error) {
			conn := &failingCloseAuthSocket{&fakeAuthSocket{input: bytes.NewReader([]byte{5, 2, 1, 0})}}
			probes.proxy.SocketDialer = searchSocketDialer{owner: guard, factory: &fakeSocketFactory{conn: conn}}
			return probes.proxy.Negotiate(ctx)
		},
	}
	worker := newSearchEmbeddedWorker(guard, func(startup, attempt context.Context, owner *protection, profile model.Profile, params map[string]any) (searchCore, *SearchWorkerProbes, error) {
		return launchSearchEmbedded(startup, attempt, owner, profile, params, ports)
	})
	profile, params := embeddedInput(t)
	if got := worker.OpenStatus(profile, params, 1000, 1000); got != SearchWorkerCleanupIncomplete {
		t.Fatalf("readiness close failure status=%d", got)
	}
	if instance.closes.Load() != 1 {
		t.Fatal("core cleanup was skipped after readiness close failure")
	}
}

func TestAndroidSearchEmbeddedRejectsInputAndBudgetBoundaries(t *testing.T) {
	profile, params := embeddedInput(t)
	for _, tc := range []struct {
		name    string
		profile []byte
		params  []byte
		startup int64
		cleanup int64
	}{
		{name: "empty_profile", params: params, startup: 1000, cleanup: 1000},
		{name: "oversize_profile", profile: make([]byte, importer.MaxInputBytes+1), params: params, startup: 1000, cleanup: 1000},
		{name: "empty_params", profile: profile, startup: 1000, cleanup: 1000},
		{name: "oversize_params", profile: profile, params: make([]byte, importer.MaxInputBytes+1), startup: 1000, cleanup: 1000},
		{name: "startup_zero", profile: profile, params: params, startup: 0, cleanup: 1000},
		{name: "startup_above_max", profile: profile, params: params, startup: maxSearchStartupMillis + 1, cleanup: 1000},
		{name: "cleanup_zero", profile: profile, params: params, startup: 1000, cleanup: 0},
		{name: "cleanup_above_max", profile: profile, params: params, startup: 1000, cleanup: maxSearchCleanupMillis + 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, guard := installFakeOwner(t)
			var starts atomic.Int32
			worker := newSearchEmbeddedWorker(guard, func(context.Context, context.Context, *protection, model.Profile, map[string]any) (searchCore, *SearchWorkerProbes, error) {
				starts.Add(1)
				return nil, nil, nil
			})
			if status := worker.OpenStatus(tc.profile, tc.params, tc.startup, tc.cleanup); status != SearchWorkerUnavailable {
				t.Fatalf("invalid input status=%d", status)
			}
			if starts.Load() != 0 {
				t.Fatal("invalid input launched core")
			}
			if err := worker.Close(1000); err != nil {
				t.Fatal("invalid input prevented cleanup")
			}
		})
	}
}
