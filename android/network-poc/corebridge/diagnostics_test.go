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
