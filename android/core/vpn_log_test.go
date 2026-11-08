package mobile

import (
	"context"
	"errors"
	"fmt"
	"syscall"
	"testing"

	xerrors "github.com/xtls/xray-core/common/errors"
	xlog "github.com/xtls/xray-core/common/log"
)

func TestVPNLogCapturesXrayFailuresWithoutForwardingSensitiveText(t *testing.T) {
	beginVPNLog()
	defer endVPNLog()
	xerrors.LogInfo(context.Background(), "normal activity secret.example")
	if got := NextVpnLogCode(); got != 0 {
		t.Fatalf("ordinary activity was reported: %d", got)
	}
	xerrors.LogInfo(context.Background(), "failed to process outbound traffic > secret.example: connection reset by peer")
	if got := NextVpnLogCode(); got != vpnLogReset {
		t.Fatalf("outbound error not classified: %d", got)
	}
	xerrors.LogErrorInner(context.Background(), fmt.Errorf("private: %w", syscall.ETIMEDOUT), "connection failed")
	if got := NextVpnLogCode(); got != vpnLogTimeout {
		t.Fatalf("wrapped network error not classified: %d", got)
	}
	xlog.Record(&xlog.GeneralMessage{Severity: xlog.Severity_Error, Content: errors.New("private secret.example")})
	xlog.Record(&xlog.GeneralMessage{Severity: xlog.Severity_Error, Content: errors.New("another secret")})
	if got := NextVpnLogCode(); got != vpnLogOther {
		t.Fatalf("unknown error not reported safely: %d", got)
	}
	if got := NextVpnLogCode(); got != 0 {
		t.Fatalf("duplicate or raw entry leaked: %d", got)
	}
	xerrors.LogWarning(context.Background(), "private warning")
	if got := NextVpnLogCode(); got != vpnLogWarning {
		t.Fatalf("warning was presented as a connection failure: %d", got)
	}
}

func TestVPNLogResetsBetweenSessions(t *testing.T) {
	beginVPNLog()
	oldHandler := activeVPNLog.Load()
	xerrors.LogError(context.Background(), "failed")
	endVPNLog()
	beginVPNLog()
	defer endVPNLog()
	oldHandler.Handle(&xlog.GeneralMessage{Severity: xlog.Severity_Error, Content: errors.New("late")})
	if got := NextVpnLogCode(); got != 0 {
		t.Fatalf("stale session event: %d", got)
	}
}
