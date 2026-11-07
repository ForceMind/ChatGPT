// Package networkcore owns one application-local Xray instance.
package networkcore

import (
    "context"
    "errors"
    "strings"
    "sync"

    xlog "github.com/xtls/xray-core/common/log"
    "github.com/xtls/xray-core/common/session"
    "github.com/xtls/xray-core/core"
    _ "github.com/xtls/xray-core/main/distro/all"
)

var mu sync.Mutex
var instance *core.Instance
var instanceCancel context.CancelFunc

// Start starts a controlled configuration built by the Android application.
// Errors deliberately omit configuration values and node credentials.
func Start(config string) error {
    return start(config, false)
}

// StartWithTestDiagnostics is called only by Android's NETWORK_TEST build.
// It keeps the same configuration, transport behavior and startup failure rules.
func StartWithTestDiagnostics(config string) error {
    return start(config, true)
}

func start(config string, diagnostics bool) error {
    mu.Lock()
    defer mu.Unlock()
    if instance != nil { return errors.New("core already running") }
    var diagnosticHandler *testDiagnosticHandler
    if diagnostics { diagnosticHandler = beginTestDiagnostics() } else { endTestDiagnostics() }
    c, err := core.LoadConfig("json", strings.NewReader(config))
    if err != nil { endTestDiagnostics(); return errors.New("invalid proxy configuration") }
    // Xray Close shuts listeners, but accepted TCP streams inherit this context.
    // Own its cancellation so Stop also interrupts active HTTP/CONNECT requests.
    ctx, cancel := context.WithCancel(context.Background())
    // Preserve the original typed outbound error before Xray's logger flattens
    // it into a message. Only the CI entry point installs this passive observer.
    if diagnostics { ctx = session.TrackedConnectionError(ctx, diagnosticHandler) }
    next, err := core.NewWithContext(ctx, c)
    if err != nil {
        cancel()
        endTestDiagnostics()
        return errors.New("unable to initialize proxy core")
    }
    // core.NewWithContext installs its configured logger. Replace it only in
    // the explicit CI entry point, after construction and before listener start.
    // The diagnostic handler never forwards raw Xray messages to any writer.
    if diagnostics {
        xlog.RegisterHandler(diagnosticHandler)
    }
    if err = next.Start(); err != nil {
        cancel()
        _ = next.Close()
        endTestDiagnostics()
        return errors.New("unable to start proxy listener")
    }
    instance = next
    instanceCancel = cancel
    return nil
}

func Stop() error {
    mu.Lock()
    defer mu.Unlock()
    defer endTestDiagnostics()
    if instanceCancel != nil {
        instanceCancel()
        instanceCancel = nil
    }
    if instance == nil { return nil }
    err := instance.Close()
    instance = nil
    if err != nil { return errors.New("proxy shutdown failed") }
    return nil
}

// IsRunning reports only the local instance lifecycle, not remote connectivity.
// Use the same ownership lock as Start/Stop before reading Xray's locked state.
func IsRunning() bool {
    mu.Lock()
    defer mu.Unlock()
    return instance != nil && instance.IsRunning()
}

func Version() string { return core.Version() }
