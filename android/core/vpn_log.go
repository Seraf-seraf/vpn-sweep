package mobile

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"net"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"

	xlog "github.com/xtls/xray-core/common/log"
)

const maxVPNLogEvents = 32

const (
	vpnLogOther int64 = iota + 1
	vpnLogTimeout
	vpnLogReset
	vpnLogRefused
	vpnLogDNS
	vpnLogTLS
	vpnLogWarning
)

// Only classified codes cross the mobile boundary. Xray messages can contain
// addresses, credentials, URLs and other untrusted data and must not be shown.
type vpnLogHandler struct {
	mu     sync.Mutex
	events []int64
}

var activeVPNLog atomic.Pointer[vpnLogHandler]

func (h *vpnLogHandler) Severity() xlog.Severity { return xlog.Severity_Info }

func (h *vpnLogHandler) Handle(message xlog.Message) {
	entry, ok := message.(*xlog.GeneralMessage)
	if !ok || entry.Severity > xlog.Severity_Info || entry.Severity == xlog.Severity_Unknown {
		return
	}
	if entry.Severity == xlog.Severity_Info {
		// The pinned Xray reports outbound failures at Info, with the cause
		// embedded in a string. Do not forward that string to Android.
		err, isError := entry.Content.(error)
		if !isError || (!strings.Contains(err.Error(), "failed to process outbound traffic") &&
			!strings.Contains(err.Error(), "failed to process mux outbound traffic") &&
			!strings.Contains(err.Error(), "failed to resolve ip for target")) {
			return
		}
	}
	code := classifyVPNError(entry.Content)
	if code == vpnLogOther && entry.Severity == xlog.Severity_Warning {
		code = vpnLogWarning
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if activeVPNLog.Load() != h {
		return
	}
	for _, pending := range h.events {
		if pending == code {
			return
		}
	}
	if len(h.events) < maxVPNLogEvents {
		h.events = append(h.events, code)
	}
}

func classifyVPNError(content any) int64 {
	err, ok := content.(error)
	if !ok {
		return vpnLogOther
	}
	var dns *net.DNSError
	var unknownAuthority x509.UnknownAuthorityError
	var hostname x509.HostnameError
	var certificate x509.CertificateInvalidError
	var record tls.RecordHeaderError
	switch {
	case errors.As(err, &dns):
		return vpnLogDNS
	case errors.As(err, &unknownAuthority), errors.As(err, &hostname), errors.As(err, &certificate), errors.As(err, &record):
		return vpnLogTLS
	case errors.Is(err, context.DeadlineExceeded), errors.Is(err, os.ErrDeadlineExceeded), errors.Is(err, syscall.ETIMEDOUT):
		return vpnLogTimeout
	case errors.Is(err, syscall.ECONNRESET), errors.Is(err, syscall.EPIPE):
		return vpnLogReset
	case errors.Is(err, syscall.ECONNREFUSED):
		return vpnLogRefused
	default:
		// Some pinned Xray paths stringify the underlying error before logging.
		// Match fixed error phrases but never expose the original message.
		text := strings.ToLower(err.Error())
		switch {
		case strings.Contains(text, "failed to resolve ip for target"), strings.Contains(text, "no such host"):
			return vpnLogDNS
		case strings.Contains(text, "certificate signed by unknown authority"), strings.Contains(text, "certificate is valid for"), strings.Contains(text, "tls: "):
			return vpnLogTLS
		case strings.Contains(text, "i/o timeout"), strings.Contains(text, "deadline exceeded"), strings.Contains(text, "connection timed out"):
			return vpnLogTimeout
		case strings.Contains(text, "connection reset by peer"), strings.Contains(text, "broken pipe"):
			return vpnLogReset
		case strings.Contains(text, "connection refused"):
			return vpnLogRefused
		default:
			return vpnLogOther
		}
	}
}

func beginVPNLog() {
	handler := &vpnLogHandler{}
	activeVPNLog.Store(handler)
	// core.New installs Xray's own global logger; replace it only for the VPN.
	xlog.RegisterHandler(handler)
}

func endVPNLog() {
	activeVPNLog.Store(nil)
	xlog.RegisterHandler(discardLogs{})
}

// NextVpnLogCode consumes one safe event, or returns zero when none are pending.
func NextVpnLogCode() int64 {
	handler := activeVPNLog.Load()
	if handler == nil {
		return 0
	}
	handler.mu.Lock()
	defer handler.mu.Unlock()
	if activeVPNLog.Load() != handler || len(handler.events) == 0 {
		return 0
	}
	code := handler.events[0]
	handler.events = handler.events[1:]
	return code
}
