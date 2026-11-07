package networkcore

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"time"

	xerrors "github.com/xtls/xray-core/common/errors"
	xlog "github.com/xtls/xray-core/common/log"
)

const testDiagnosticLimit = 128

type testDiagnosticHandler struct {
	sync.Mutex
	active  bool
	started time.Time
	count   uint64
	rows    []string
}

var testDiagnostics = &testDiagnosticHandler{}

func beginTestDiagnostics() *testDiagnosticHandler {
	testDiagnostics.Lock()
	defer testDiagnostics.Unlock()
	testDiagnostics.active = true
	testDiagnostics.started = time.Now()
	testDiagnostics.count = 0
	testDiagnostics.rows = nil
	return testDiagnostics
}

func endTestDiagnostics() {
	testDiagnostics.Lock()
	defer testDiagnostics.Unlock()
	testDiagnostics.active = false
}

// TestDiagnostics returns only bounded enums and elapsed times, never error
// messages, configuration, destinations, credentials or request/response bytes.
// Android calls it only behind BuildConfig.NETWORK_TEST, before stopping a route.
func TestDiagnostics() string {
	testDiagnostics.Lock()
	defer testDiagnostics.Unlock()
	dropped := testDiagnostics.count - uint64(len(testDiagnostics.rows))
	return fmt.Sprintf("events=%d dropped=%d\n%s", testDiagnostics.count, dropped,
		strings.Join(testDiagnostics.rows, "\n"))
}

func (*testDiagnosticHandler) Severity() xlog.Severity { return xlog.Severity_Debug }

func (h *testDiagnosticHandler) Handle(message xlog.Message) {
	// Neither Message.String nor error.Error is used, even for classification.
	// The Xray source boundary is inferred only from static Go symbol prefixes.
	general, ok := message.(*xlog.GeneralMessage)
	if !ok {
		return
	}
	severity := "other"
	switch general.Severity {
	case xlog.Severity_Debug:
		severity = "debug"
	case xlog.Severity_Info:
		severity = "info"
	case xlog.Severity_Warning:
		severity = "warning"
	case xlog.Severity_Error:
		severity = "error"
	}
	source := diagnosticSource()
	chain := "no-error"
	if err, ok := general.Content.(error); ok {
		parts := make([]string, 0, 8)
		for depth := 0; err != nil && depth < 8; depth++ {
			parts = append(parts, diagnosticErrorType(err)+":"+diagnosticErrorCategory(err))
			err = errors.Unwrap(err)
		}
		if err != nil {
			parts = append(parts, "truncated")
		}
		chain = strings.Join(parts, ">")
	}
	h.Lock()
	defer h.Unlock()
	if !h.active {
		return
	}
	h.count++
	row := fmt.Sprintf("seq=%d elapsedMillis=%d source=%s severity=%s chain=%s",
		h.count, time.Since(h.started).Milliseconds(), source, severity, chain)
	if len(h.rows) == testDiagnosticLimit {
		copy(h.rows, h.rows[1:])
		h.rows[len(h.rows)-1] = row
	} else {
		h.rows = append(h.rows, row)
	}
}

func diagnosticSource() string {
	var pcs [24]uintptr
	count := runtime.Callers(2, pcs[:])
	frames := runtime.CallersFrames(pcs[:count])
	for {
		frame, more := frames.Next()
		switch {
		case strings.HasPrefix(frame.Function, "github.com/xtls/xray-core/proxy/http."):
			return "http-inbound"
		case strings.HasPrefix(frame.Function, "github.com/xtls/xray-core/proxy/vless/outbound."):
			return "vless-outbound"
		case strings.HasPrefix(frame.Function, "github.com/xtls/xray-core/transport/internet/tls."):
			return "tls-transport"
		case strings.HasPrefix(frame.Function, "github.com/xtls/xray-core/transport/internet/reality."):
			return "reality-transport"
		case strings.HasPrefix(frame.Function, "github.com/xtls/xray-core/transport/internet/tcp."):
			return "tcp-transport"
		case strings.HasPrefix(frame.Function, "github.com/xtls/xray-core/app/proxyman/outbound."):
			return "outbound-handler"
		case strings.HasPrefix(frame.Function, "github.com/xtls/xray-core/app/dispatcher."):
			return "dispatcher"
		}
		if !more {
			return "other"
		}
	}
}

func diagnosticErrorType(err error) string {
	switch err.(type) {
	case *xerrors.Error:
		return "xray"
	case *net.OpError:
		return "net-operation"
	case *net.DNSError:
		return "dns"
	case *os.SyscallError:
		return "syscall"
	case syscall.Errno:
		return "errno"
	case *tls.CertificateVerificationError:
		return "tls-certificate"
	case tls.RecordHeaderError:
		return "tls-record"
	case x509.UnknownAuthorityError:
		return "x509-authority"
	case x509.HostnameError:
		return "x509-hostname"
	case x509.CertificateInvalidError:
		return "x509-invalid"
	default:
		return "other"
	}
}

func diagnosticErrorCategory(err error) string {
	// Match identities and typed errors only. Every emitted value is fixed.
	switch {
	case errors.Is(err, syscall.ECONNRESET):
		return "reset"
	case errors.Is(err, syscall.ECONNREFUSED):
		return "refused"
	case errors.Is(err, syscall.EPIPE):
		return "broken-pipe"
	case errors.Is(err, syscall.ETIMEDOUT):
		return "timeout"
	case errors.Is(err, context.Canceled):
		return "cancelled"
	case errors.Is(err, context.DeadlineExceeded):
		return "deadline"
	case errors.Is(err, io.ErrUnexpectedEOF):
		return "unexpected-eof"
	case errors.Is(err, io.EOF):
		return "eof"
	case errors.Is(err, net.ErrClosed):
		return "closed"
	}
	if timed, ok := err.(net.Error); ok && timed.Timeout() {
		return "timeout"
	}
	switch err.(type) {
	case *tls.CertificateVerificationError, x509.UnknownAuthorityError, x509.HostnameError, x509.CertificateInvalidError:
		return "certificate"
	case tls.RecordHeaderError:
		return "tls-record"
	default:
		return "other"
	}
}
