package networkcore

import "testing"

func TestInvalidConfigurationAndStop(t *testing.T) {
    if err := Start("not-json"); err == nil { t.Fatal("invalid config accepted") }
    if err := Stop(); err != nil { t.Fatal(err) }
    if err := Stop(); err != nil { t.Fatal(err) }
}
