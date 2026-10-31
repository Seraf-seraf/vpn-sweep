package mobile

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"strings"
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

func TestAndroidSearchEmbeddedBeginReadinessCloseFailureQuarantines(t *testing.T) {
	owner, guard := installFakeOwner(t)
	instance := &fakeSearchRunningCore{}
	worker := newSearchEmbeddedWorker(guard, func(startup, attempt context.Context, actual *protection, profile model.Profile, params map[string]any) (searchCore, *SearchWorkerProbes, error) {
		if attempt.Value(searchReadinessTrackingKey{}) != true {
			return nil, nil, errors.New("missing_readiness_tracking")
		}
		ports := searchLaunchPorts{
			reserve: func(context.Context, *protection) (string, error) { return "127.0.0.1:1080", nil },
			newCore: func(context.Context, *core.Config) (searchRunningCore, error) { return instance, nil },
			negotiate: func(ctx context.Context, probes *SearchWorkerProbes) (net.Conn, error) {
				conn := &failingCloseAuthSocket{&fakeAuthSocket{input: bytes.NewReader([]byte{5, 0})}}
				probes.proxy.SocketDialer = searchSocketDialer{owner: guard, factory: &fakeSocketFactory{conn: conn}}
				return negotiateSearchReadiness(ctx, attempt, probes)
			},
		}
		return launchSearchEmbedded(startup, attempt, actual, profile, params, ports)
	})
	profile, params := embeddedInput(t)
	if status := worker.BeginOpenStatus(profile, params, 1000); status != SearchWorkerUnavailable {
		t.Fatalf("unknown readiness cleanup status=%d", status)
	}
	if instance.closes.Load() != 0 || len(owner.roles) != 1 || owner.roles[0] != SearchRoleSOCKSControl {
		t.Fatal("Begin cleaned the core or bypassed the socket guard")
	}
	if err := worker.Close(1000); err == nil || err.Error() != "worker_cleanup_incomplete" || instance.closes.Load() != 1 {
		t.Fatal("failed readiness close did not retain UNKNOWN", err)
	}
}

func TestAndroidSearchLegacyReadinessErrorRemainsUnchanged(t *testing.T) {
	_, guard := installFakeOwner(t)
	probes, err := newSearchWorkerProbes("127.0.0.1:1080", "user", "password")
	if err != nil {
		t.Fatal(err)
	}
	defer probes.Cancel()
	conn := &failingCloseAuthSocket{&fakeAuthSocket{input: bytes.NewReader([]byte{5, 0})}}
	probes.proxy.SocketDialer = searchSocketDialer{owner: guard, factory: &fakeSocketFactory{conn: conn}}
	result, err := negotiateSearchReadiness(context.Background(), context.Background(), probes)
	if result != nil || model.Code(err) != "socks_auth_failed" || errors.Is(err, errSearchResourceCleanup) || !conn.closed {
		t.Fatal("legacy readiness error changed")
	}
}

type blockedReadinessSocket struct {
	readEntered  chan struct{}
	readReturned chan struct{}
	closeEntered chan struct{}
	closeRelease chan struct{}
	closes       atomic.Int32
}

func (c *blockedReadinessSocket) Read([]byte) (int, error) {
	close(c.readEntered)
	<-c.closeEntered
	close(c.readReturned)
	return 0, io.EOF
}
func (*blockedReadinessSocket) Write(p []byte) (int, error)      { return len(p), nil }
func (*blockedReadinessSocket) LocalAddr() net.Addr              { return nil }
func (*blockedReadinessSocket) RemoteAddr() net.Addr             { return nil }
func (*blockedReadinessSocket) SetDeadline(time.Time) error      { return nil }
func (*blockedReadinessSocket) SetReadDeadline(time.Time) error  { return nil }
func (*blockedReadinessSocket) SetWriteDeadline(time.Time) error { return nil }
func (c *blockedReadinessSocket) Close() error {
	c.closes.Add(1)
	close(c.closeEntered)
	<-c.closeRelease
	return nil
}

func TestAndroidSearchBeginReadinessConcurrentCloseOccursOnce(t *testing.T) {
	_, guard := installFakeOwner(t)
	probes, err := newSearchWorkerProbes("127.0.0.1:1080", "user", "password")
	if err != nil {
		t.Fatal(err)
	}
	defer probes.Cancel()
	conn := &blockedReadinessSocket{
		readEntered: make(chan struct{}), readReturned: make(chan struct{}),
		closeEntered: make(chan struct{}), closeRelease: make(chan struct{}),
	}
	probes.proxy.SocketDialer = searchSocketDialer{owner: guard, factory: &fakeSocketFactory{conn: conn}}
	ctx, cancel := context.WithCancel(context.Background())
	attempt := context.WithValue(context.Background(), searchReadinessTrackingKey{}, true)
	done := make(chan error, 1)
	go func() { _, err := negotiateSearchReadiness(ctx, attempt, probes); done <- err }()
	<-conn.readEntered
	cancel()
	<-conn.closeEntered
	<-conn.readReturned
	select {
	case <-done:
		close(conn.closeRelease)
		t.Fatal("negotiation returned while a temporary close was unresolved")
	default:
	}
	close(conn.closeRelease)
	if err := <-done; model.Code(err) != "socks_auth_failed" || errors.Is(err, errSearchResourceCleanup) || conn.closes.Load() != 1 {
		t.Fatal("concurrent close was repeated or misclassified", err)
	}
}

func TestAndroidSearchEmbeddedBeginReadinessCloseIsInsideCleanupBudget(t *testing.T) {
	_, guard := installFakeOwner(t)
	instance := &fakeSearchRunningCore{}
	conn := &blockedReadinessSocket{
		readEntered: make(chan struct{}), readReturned: make(chan struct{}),
		closeEntered: make(chan struct{}), closeRelease: make(chan struct{}),
	}
	worker := newSearchEmbeddedWorker(guard, func(startup, attempt context.Context, actual *protection, profile model.Profile, params map[string]any) (searchCore, *SearchWorkerProbes, error) {
		ports := searchLaunchPorts{
			reserve: func(context.Context, *protection) (string, error) { return "127.0.0.1:1080", nil },
			newCore: func(context.Context, *core.Config) (searchRunningCore, error) { return instance, nil },
			negotiate: func(ctx context.Context, probes *SearchWorkerProbes) (net.Conn, error) {
				probes.proxy.SocketDialer = searchSocketDialer{owner: guard, factory: &fakeSocketFactory{conn: conn}}
				return negotiateSearchReadiness(ctx, attempt, probes)
			},
		}
		return launchSearchEmbedded(startup, attempt, actual, profile, params, ports)
	})
	profile, params := embeddedInput(t)
	done := make(chan int, 1)
	go func() { done <- worker.BeginOpenStatus(profile, params, 10000) }()
	<-conn.readEntered
	worker.Cancel()
	if status := <-done; status != SearchWorkerCancelled {
		close(conn.closeRelease)
		t.Fatalf("cancel status=%d", status)
	}
	<-conn.closeEntered
	<-conn.readReturned
	if err := worker.Close(10); err == nil || err.Error() != "worker_cleanup_incomplete" || instance.closes.Load() != 0 {
		close(conn.closeRelease)
		t.Fatal("Close confirmed unresolved readiness cleanup", err)
	}
	close(conn.closeRelease)
	if err := worker.Close(1000); err != nil || instance.closes.Load() != 1 || conn.closes.Load() != 1 {
		t.Fatal("readiness and core cleanup did not finish exactly once", err)
	}
}

func TestAndroidSearchEmbeddedAcceptsLargeValidParameters(t *testing.T) {
	input, err := importer.Parse([]byte("ss://YWVzLTEyOC1nY206c3ludGhldGlj@synthetic.invalid:8388"), "uri")
	if err != nil {
		t.Fatal(err)
	}
	profile, err := json.Marshal(input.Profiles[0])
	if err != nil {
		t.Fatal(err)
	}
	encode := func(delay string) ([]byte, error) {
		return json.Marshal(map[string]any{
			"noise": true,
			"noises": []any{map[string]any{
				"type": "rand", "packet": "1", "delay": delay, "applyTo": "ip",
			}},
		})
	}
	sample, err := encode("0")
	if err != nil {
		t.Fatal(err)
	}
	overhead := len(sample) - 1
	for _, tc := range []struct {
		name string
		size int
	}{
		{name: "below", size: importer.MaxInputBytes - 1},
		{name: "at", size: importer.MaxInputBytes},
		{name: "above", size: importer.MaxInputBytes + 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, guard := installFakeOwner(t)
			delay := strings.Repeat("0", tc.size-overhead)
			params, err := encode(delay)
			if err != nil || len(params) != tc.size {
				t.Fatal("incorrect parameter size")
			}
			var starts atomic.Int32
			worker := newSearchEmbeddedWorker(guard, func(_ context.Context, _ context.Context, _ *protection, actualProfile model.Profile, actualParams map[string]any) (searchCore, *SearchWorkerProbes, error) {
				starts.Add(1)
				noises, ok := actualParams["noises"].([]any)
				if !ok || len(noises) != 1 {
					return nil, nil, errors.New("unexpected_noise_parameters")
				}
				noise, ok := noises[0].(map[string]any)
				if !ok || actualProfile.Outbound["protocol"] != "shadowsocks" || actualParams["noise"] != true || noise["delay"] != delay {
					return nil, nil, errors.New("unexpected_noise_parameters")
				}
				return nil, nil, model.Err("core_config_rejected", "core")
			})
			if status := worker.OpenStatus(profile, params, maxSearchStartupMillis, 1000); status != SearchWorkerCandidateConfigRejected || starts.Load() != 1 {
				t.Fatalf("valid parameters status=%d starts=%d", status, starts.Load())
			}
		})
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
		{name: "oversize_invalid_params", profile: profile, params: make([]byte, importer.MaxInputBytes+1), startup: 1000, cleanup: 1000},
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
