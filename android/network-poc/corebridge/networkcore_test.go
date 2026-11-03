package networkcore

import (
    "bytes"
    "context"
    "crypto/tls"
    "crypto/x509"
    "errors"
    "io"
    "net"
    "net/http"
    "net/http/httptest"
    "net/url"
    "strconv"
    "sync"
    "sync/atomic"
    "testing"
    "time"
)

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

// The owned origin remains gated throughout the assertion. A 30s client timeout
// cannot satisfy the 2s Stop bound, and the request context is not canceled by
// the test until cleanup. HTTPS traverses an actual HTTP CONNECT tunnel.
func TestStopCancelsAcceptedHTTPStreams(t *testing.T) {
    for _, scheme := range []string{"http", "https"} {
        for _, operation := range []string{"download", "upload"} {
            t.Run(scheme+"/"+operation, func(t *testing.T) {
                defer Stop()
                ready, release := make(chan struct{}), make(chan struct{})
                var readyOnce, releaseOnce sync.Once
                var requests, commits atomic.Int32
                prefix := bytes.Repeat([]byte("D"), 4096)
                upload := []byte("owned-in-flight-upload")
                handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
                    requests.Add(1)
                    if operation == "upload" {
                        body, err := io.ReadAll(io.LimitReader(r.Body, int64(len(upload)+1)))
                        if err != nil || !bytes.Equal(body, upload) {
                            w.WriteHeader(http.StatusBadRequest)
                            return
                        }
                        commits.Add(1)
                    } else {
                        w.Header().Set("Content-Length", strconv.Itoa(2*len(prefix)))
                        if _, err := w.Write(prefix); err != nil { return }
                        w.(http.Flusher).Flush()
                    }
                    readyOnce.Do(func() { close(ready) })
                    <-release
                    // Only test cleanup releases this gate, after the result
                    // assertion. No origin response can finish it beforehand.
                    _, _ = w.Write(prefix)
                })
                var origin *httptest.Server
                if scheme == "https" { origin = httptest.NewTLSServer(handler) } else { origin = httptest.NewServer(handler) }
                defer origin.Close()
                defer releaseOnce.Do(func() { close(release) })
                destination, err := url.Parse(origin.URL)
                if err != nil { t.Fatal(err) }
                port := freePort(t)
                config := encode(map[string]any{
                    "log": map[string]any{"loglevel":"none"},
                    "inbounds": []any{map[string]any{"listen":"127.0.0.1", "port":port,
                        "protocol":"http", "settings":map[string]any{}}},
                    "outbounds": []any{map[string]any{"protocol":"freedom", "settings":map[string]any{
                        "finalRules":[]any{map[string]any{"action":"allow", "ip":[]string{"127.0.0.1/32"}, "port":destination.Port()}}}}},
                })
                if err := Start(config); err != nil { t.Fatal(err) }
                proxy, _ := url.Parse("http://127.0.0.1:"+strconv.Itoa(port))
                transport := &http.Transport{Proxy:http.ProxyURL(proxy), DisableKeepAlives:true}
                if scheme == "https" {
                    roots := x509.NewCertPool()
                    roots.AddCert(origin.Certificate())
                    transport.TLSClientConfig = &tls.Config{RootCAs:roots, MinVersion:tls.VersionTLS12, NextProtos:[]string{"http/1.1"}}
                }
                defer transport.CloseIdleConnections()
                client := &http.Client{Transport:transport, Timeout:30*time.Second}
                ctx, cancel := context.WithCancel(context.Background())
                defer cancel()
                method, body := http.MethodGet, io.Reader(nil)
                if operation == "upload" { method, body = http.MethodPost, bytes.NewReader(upload) }
                request, err := http.NewRequestWithContext(ctx, method, origin.URL+"/owned-gate", body)
                if err != nil { t.Fatal(err) }
                type result struct { status int; err error }
                finished := make(chan result, 1)
                prefixRead := make(chan struct{})
                go func() {
                    response, err := client.Do(request)
                    if err != nil { finished <- result{err:err}; return }
                    defer response.Body.Close()
                    if operation == "download" && response.StatusCode == http.StatusOK {
                        first := make([]byte, len(prefix))
                        if _, err := io.ReadFull(response.Body, first); err != nil {
                            finished <- result{status:response.StatusCode, err:err}; return
                        }
                        if !bytes.Equal(first, prefix) {
                            finished <- result{status:response.StatusCode, err:errors.New("owned prefix differs")}; return
                        }
                        close(prefixRead)
                    }
                    _, err = io.ReadAll(response.Body)
                    finished <- result{status:response.StatusCode, err:err}
                }()
                select {
                case <-ready:
                case result := <-finished: t.Fatalf("request ended before gate: status=%d error=%v", result.status, result.err)
                case <-time.After(5*time.Second): t.Fatal("owned transfer did not reach gate")
                }
                if operation == "download" {
                    select {
                    case <-prefixRead:
                    case result := <-finished: t.Fatalf("client did not consume prefix: %v", result.err)
                    case <-time.After(5*time.Second): t.Fatal("client did not read owned prefix")
                    }
                }
                select {
                case result := <-finished: t.Fatalf("gated operation finished before Stop: %v", result.err)
                default:
                }
                stopped := make(chan error, 1)
                started := time.Now()
                go func() { stopped <- Stop() }()
                select {
                case err := <-stopped: if err != nil { t.Fatal(err) }
                case <-time.After(2*time.Second): t.Fatal("Stop blocked on an accepted stream")
                }
                if IsRunning() { t.Fatal("core remained running after Stop") }
                select {
                case result := <-finished:
                    if result.err == nil && result.status < 500 { t.Fatalf("gated operation completed successfully: HTTP %d", result.status) }
                    var timeout net.Error
                    if errors.As(result.err, &timeout) && timeout.Timeout() { t.Fatalf("client timeout satisfied Stop regression: %v", result.err) }
                    if errors.Is(result.err, context.DeadlineExceeded) || ctx.Err() != nil { t.Fatal("test/client context ended the transfer") }
                    if time.Since(started) > 2*time.Second { t.Fatal("accepted stream did not end promptly after Stop") }
                case <-time.After(2*time.Second): t.Fatal("accepted stream remained pending after Stop while origin gate stayed closed")
                }
                if requests.Load() != 1 { t.Fatalf("request replayed: %d", requests.Load()) }
                if operation == "upload" && commits.Load() != 1 { t.Fatalf("owned upload commit count: %d", commits.Load()) }
                // Explicitly starting a fresh instance must remain possible.
                if err := Start(config); err != nil { t.Fatal(err) }
                if !IsRunning() { t.Fatal("fresh explicit instance did not run") }
                if err := Stop(); err != nil { t.Fatal(err) }
            })
        }
    }
}
