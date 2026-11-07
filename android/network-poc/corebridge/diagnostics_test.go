package networkcore

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"io"
	"net"
	"os"
	"strings"
	"syscall"
	"testing"

	xerrors "github.com/xtls/xray-core/common/errors"
	xlog "github.com/xtls/xray-core/common/log"
	xretry "github.com/xtls/xray-core/common/retry"
	"github.com/xtls/xray-core/common/session"
)

type forbiddenDiagnosticError struct{}

func (forbiddenDiagnosticError) Error() string { panic("diagnostics must not format an error") }

func TestDiagnosticsNeverFormatErrorOrMessage(t *testing.T) {
	handler := beginTestDiagnostics()
	defer endTestDiagnostics()
	handler.Handle(&xlog.GeneralMessage{Severity: xlog.Severity_Warning, Content: forbiddenDiagnosticError{}})
	secret := "vless://private-identity@private-host.invalid/secret-body"
	handler.Handle(&xlog.GeneralMessage{Severity: xlog.Severity_Info, Content: secret})
	cause := xerrors.New(secret).Base(&net.OpError{Op: secret, Net: secret, Err: syscall.ECONNRESET})
	handler.Handle(&xlog.GeneralMessage{Severity: xlog.Severity_Warning, Content: cause})
	result := TestDiagnostics()
	if strings.Contains(result, secret) || strings.Contains(result, "private-") {
		t.Fatal("sensitive content escaped enum diagnostics")
	}
	if !strings.Contains(result, "xray:reset>net-operation:reset>errno:reset") {
		t.Fatal("wrapped transport reset was not classified")
	}
}

func TestDiagnosticCategoriesAndTypes(t *testing.T) {
	cases := []struct {
		err            error
		category, kind string
	}{
		{syscall.ECONNRESET, "reset", "errno"},
		{syscall.ECONNREFUSED, "refused", "errno"},
		{syscall.EPIPE, "broken-pipe", "errno"},
		{syscall.ETIMEDOUT, "timeout", "errno"},
		{context.Canceled, "cancelled", "other"},
		{context.DeadlineExceeded, "deadline", "other"},
		{io.ErrUnexpectedEOF, "unexpected-eof", "other"},
		{io.EOF, "eof", "other"},
		{net.ErrClosed, "closed", "other"},
		{xretry.ErrRetryFailed, "retry-exhausted", "xray"},
		{&net.OpError{Err: syscall.ECONNRESET}, "reset", "net-operation"},
		{&os.SyscallError{Err: syscall.ECONNREFUSED}, "refused", "syscall"},
		{&net.DNSError{IsTimeout: true}, "timeout", "dns"},
		{&tls.CertificateVerificationError{Err: forbiddenDiagnosticError{}}, "certificate", "tls-certificate"},
		{tls.RecordHeaderError{}, "tls-record", "tls-record"},
		{x509.UnknownAuthorityError{}, "certificate", "x509-authority"},
		{x509.HostnameError{}, "certificate", "x509-hostname"},
		{x509.CertificateInvalidError{}, "certificate", "x509-invalid"},
		{forbiddenDiagnosticError{}, "other", "other"},
	}
	for _, test := range cases {
		if diagnosticErrorCategory(test.err) != test.category || diagnosticErrorType(test.err) != test.kind {
			t.Fatalf("wrong enums for case category=%s kind=%s", test.category, test.kind)
		}
	}
}

func TestTypedFeedbackThroughContextNeverFormatsPrivateErrors(t *testing.T) {
	handler := beginTestDiagnostics()
	defer endTestDiagnostics()
	parent := session.TrackedConnectionError(context.Background(), handler)
	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	// Use the public submission path inherited by the actual inbound context.
	session.SubmitOutboundErrorToOriginator(ctx, forbiddenDiagnosticError{})
	secret := "vless://private-identity@private-host.invalid/private-body"
	session.SubmitOutboundErrorToOriginator(ctx, xerrors.New(secret).Base(
		&net.OpError{Op: secret, Net: secret, Err: syscall.ECONNRESET}))
	// Match Xray retry's shape: per-dial errors are private message data, while
	// only exhaustion is publicly traversable. Do not invent a certificate cause.
	session.SubmitOutboundErrorToOriginator(ctx, xerrors.New([]error{
		x509.UnknownAuthorityError{}, forbiddenDiagnosticError{},
	}).Base(xretry.ErrRetryFailed))
	result := TestDiagnostics()
	if !strings.HasPrefix(result, "events=3 dropped=0\n") ||
		strings.Count(result, "source=outbound-feedback") != 3 ||
		!strings.Contains(result, "xray:reset>net-operation:reset>errno:reset") ||
		!strings.Contains(result, "xray:retry-exhausted>xray:retry-exhausted") {
		t.Fatal("public typed feedback lost the original visible error chain")
	}
	if strings.Contains(result, "private-") || strings.Contains(result, "certificate") {
		t.Fatal("typed feedback exposed private data or invented a hidden dial cause")
	}
	session.SubmitOutboundErrorToOriginator(ctx, nil)
	if TestDiagnostics() != result {
		t.Fatal("nil feedback created a failure event")
	}
}

func TestTypedFeedbackBoundsAndOldContextCannotCrossStartup(t *testing.T) {
	handler := beginTestDiagnostics()
	defer endTestDiagnostics()
	oldContext := session.TrackedConnectionError(context.Background(), handler)
	var cause error = syscall.ECONNRESET
	for depth := 0; depth < 12; depth++ {
		cause = xerrors.New("private-body").Base(cause)
	}
	for index := 0; index < testDiagnosticLimit+3; index++ {
		session.SubmitOutboundErrorToOriginator(oldContext, cause)
	}
	result := TestDiagnostics()
	if !strings.HasPrefix(result, "events=131 dropped=3\n") ||
		strings.Count(result, "\n") != testDiagnosticLimit ||
		!strings.Contains(result, ">truncated") {
		t.Fatal("typed feedback exceeded the common buffer or error-chain bounds")
	}
	endTestDiagnostics()
	session.SubmitOutboundErrorToOriginator(oldContext, cause)
	if TestDiagnostics() != result {
		t.Fatal("stopped context accepted late feedback")
	}
	next := beginTestDiagnostics()
	if handler == next {
		t.Fatal("new startup reused the old context tracker")
	}
	session.SubmitOutboundErrorToOriginator(oldContext, cause)
	if TestDiagnostics() != "events=0 dropped=0\n" {
		t.Fatal("old context contaminated the new startup")
	}
	newContext := session.TrackedConnectionError(context.Background(), next)
	session.SubmitOutboundErrorToOriginator(newContext, io.EOF)
	if !strings.Contains(TestDiagnostics(), "events=1 dropped=0\n") ||
		!strings.Contains(TestDiagnostics(), "chain=other:eof") {
		t.Fatal("new startup did not accept its own typed feedback")
	}
}

func TestDiagnosticsBoundBufferAndErrorDepthAndStop(t *testing.T) {
	handler := beginTestDiagnostics()
	defer endTestDiagnostics()
	var cause error = syscall.ECONNRESET
	for depth := 0; depth < 12; depth++ {
		cause = xerrors.New("secret").Base(cause)
	}
	for index := 0; index < testDiagnosticLimit+7; index++ {
		handler.Handle(&xlog.GeneralMessage{Severity: xlog.Severity_Warning, Content: cause})
	}
	result := TestDiagnostics()
	if !strings.HasPrefix(result, "events=135 dropped=7\n") || strings.Count(result, "\n") != testDiagnosticLimit {
		t.Fatal("diagnostic buffer did not keep its fixed bound/drop count")
	}
	if !strings.Contains(result, ">truncated") {
		t.Fatal("long error chain was not marked truncated")
	}
	endTestDiagnostics()
	handler.Handle(&xlog.GeneralMessage{Severity: xlog.Severity_Error, Content: cause})
	if TestDiagnostics() != result {
		t.Fatal("stopped diagnostics accepted new messages")
	}
	beginTestDiagnostics()
	if TestDiagnostics() != "events=0 dropped=0\n" {
		t.Fatal("new startup retained old events")
	}
}
