package networkcore

import "testing"

func TestInvalidConfigurationAndStop(t *testing.T) {
    if IsRunning() { t.Fatal("core running before start") }
    if err := Start("not-json"); err == nil { t.Fatal("invalid config accepted") }
    if IsRunning() { t.Fatal("invalid config left a running core") }
    if err := Stop(); err != nil { t.Fatal(err) }
    if err := Stop(); err != nil { t.Fatal(err) }
    if IsRunning() { t.Fatal("core running after stop") }
}

func TestRunningStateTracksActualInstance(t *testing.T) {
    defer Stop()
    // No listeners or external endpoints are needed to test core lifecycle.
    config := `{"log":{"loglevel":"none"},"outbounds":[{"protocol":"freedom"}]}`
    if err := Start(config); err != nil { t.Fatal(err) }
    if !IsRunning() { t.Fatal("started core reported stopped") }
    // Close the actual instance while retaining the wrapper's pointer: IsRunning
    // must read Xray state rather than merely testing for a non-nil instance.
    mu.Lock()
    err := instance.Close()
    mu.Unlock()
    if err != nil { t.Fatal(err) }
    if IsRunning() { t.Fatal("closed instance reported running") }
    if err := Stop(); err != nil { t.Fatal(err) }
    if IsRunning() { t.Fatal("stopped wrapper reported running") }
    if err := Start(config); err != nil { t.Fatal(err) }
    if !IsRunning() { t.Fatal("explicit restart reported stopped") }
    if err := Stop(); err != nil { t.Fatal(err) }
    if IsRunning() { t.Fatal("core running after explicit stop") }
}
