// Package networkcore owns one application-local Xray instance.
package networkcore

import (
    "context"
    "errors"
    "strings"
    "sync"

    "github.com/xtls/xray-core/core"
    _ "github.com/xtls/xray-core/main/distro/all"
)

var mu sync.Mutex
var instance *core.Instance
var instanceCancel context.CancelFunc

// Start starts a controlled configuration built by the Android application.
// Errors deliberately omit configuration values and node credentials.
func Start(config string) error {
    mu.Lock()
    defer mu.Unlock()
    if instance != nil { return errors.New("core already running") }
    c, err := core.LoadConfig("json", strings.NewReader(config))
    if err != nil { return errors.New("invalid proxy configuration") }
    // Xray Close shuts listeners, but accepted TCP streams inherit this context.
    // Own its cancellation so Stop also interrupts active HTTP/CONNECT requests.
    ctx, cancel := context.WithCancel(context.Background())
    next, err := core.NewWithContext(ctx, c)
    if err != nil {
        cancel()
        return errors.New("unable to initialize proxy core")
    }
    if err = next.Start(); err != nil {
        cancel()
        _ = next.Close()
        return errors.New("unable to start proxy listener")
    }
    instance = next
    instanceCancel = cancel
    return nil
}

func Stop() error {
    mu.Lock()
    defer mu.Unlock()
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
