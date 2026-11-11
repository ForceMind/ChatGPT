package networkcore

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	applog "github.com/xtls/xray-core/app/log"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/core"
)

var probeMu sync.Mutex

// Probe times one HTTP request through a temporary outbound-only core. It
// never touches the primary instance, its global logger or any inbound port.
func Probe(config, target string) (int64, error) {
	if !probeMu.TryLock() {
		return 0, errors.New("another route probe is in progress")
	}
	defer probeMu.Unlock()

	endpoint, err := url.Parse(target)
	if err != nil || (endpoint.Scheme != "https" && endpoint.Scheme != "http") ||
		endpoint.Host == "" || endpoint.User != nil {
		return 0, errors.New("invalid probe target")
	}
	parsed, err := core.LoadConfig("json", strings.NewReader(config))
	if err != nil || len(parsed.Inbound) != 0 || len(parsed.Outbound) != 1 {
		return 0, errors.New("invalid route probe configuration")
	}
	loggerType := serial.ToTypedMessage(&applog.Config{}).Type
	apps := parsed.App[:0]
	for _, app := range parsed.App {
		if app.Type != loggerType {
			apps = append(apps, app)
		}
	}
	parsed.App = apps

	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	probe, err := core.NewWithContext(ctx, parsed)
	if err != nil {
		return 0, errors.New("unable to initialize route probe")
	}
	defer func() {
		cancel()
		_ = probe.Close()
	}()
	if err := probe.Start(); err != nil {
		return 0, errors.New("unable to start route probe")
	}
	transport := &http.Transport{
		DisableKeepAlives: true,
		DialContext: func(dialContext context.Context, _, address string) (net.Conn, error) {
			destination, err := xnet.ParseDestination("tcp:" + address)
			if err != nil {
				return nil, err
			}
			return core.Dial(dialContext, probe, destination)
		},
	}
	defer transport.CloseIdleConnections()
	client := &http.Client{
		Transport: transport,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint.String(), nil)
	if err != nil {
		return 0, errors.New("invalid route probe request")
	}
	start := time.Now()
	response, err := client.Do(request)
	if err != nil {
		return 0, errors.New("route probe request failed")
	}
	defer response.Body.Close()
	if response.StatusCode < 100 || response.StatusCode >= 500 {
		return 0, errors.New("route probe target returned an unavailable status")
	}
	return time.Since(start).Milliseconds(), nil
}
