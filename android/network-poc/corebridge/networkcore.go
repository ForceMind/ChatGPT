// Package networkcore owns one application-local Xray instance.
package networkcore

import (
    "errors"
    "strings"
    "sync"

    "github.com/xtls/xray-core/core"
    _ "github.com/xtls/xray-core/main/distro/all"
)

var mu sync.Mutex
var instance *core.Instance

// Start starts a controlled configuration built by the Android application.
// Errors deliberately omit configuration values and node credentials.
func Start(config string) error {
    mu.Lock()
    defer mu.Unlock()
    if instance != nil { return errors.New("core already running") }
    c, err := core.LoadConfig("json", strings.NewReader(config))
    if err != nil { return errors.New("invalid proxy configuration") }
    next, err := core.New(c)
    if err != nil { return errors.New("unable to initialize proxy core") }
    if err = next.Start(); err != nil {
        _ = next.Close()
        return errors.New("unable to start proxy listener")
    }
    instance = next
    return nil
}

func Stop() error {
    mu.Lock()
    defer mu.Unlock()
    if instance == nil { return nil }
    err := instance.Close()
    instance = nil
    if err != nil { return errors.New("proxy shutdown failed") }
    return nil
}

func Version() string { return core.Version() }
