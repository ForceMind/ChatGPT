package networkcore

import (
    "bytes"
    "crypto/ecdh"
    "crypto/ecdsa"
    "crypto/elliptic"
    "crypto/rand"
    "crypto/sha256"
    "crypto/tls"
    "crypto/x509"
    "crypto/x509/pkix"
    "encoding/base64"
    "encoding/json"
    "encoding/pem"
    "io"
    "math/big"
    "net"
    "net/http"
    "net/http/httptest"
    "net/url"
    "os"
    "os/exec"
    "strconv"
    "strings"
    "testing"
    "time"

    "github.com/xtls/xray-core/core"
)

const fixtureID = "00000000-0000-4000-8000-000000000001"

// Xray has process-global network hooks. Servers must run in separate processes,
// exactly as the Android client and CI fixture do in the device matrix.
func TestCoreServerProcess(t *testing.T) {
    config:=os.Getenv("NETWORK_SYNTHETIC_SERVER")
    if config=="" { return }
    c,err:=core.LoadConfig("json",strings.NewReader(config))
    if err!=nil { t.Fatal(err) }
    server,err:=core.New(c)
    if err!=nil { t.Fatal(err) }
    if err=server.Start();err!=nil { t.Fatal(err) }
    select {}
}

func freePort(t *testing.T) int {
    t.Helper()
    l, err := net.Listen("tcp", "127.0.0.1:0")
    if err != nil { t.Fatal(err) }
    p := l.Addr().(*net.TCPAddr).Port
    _ = l.Close()
    return p
}

func encode(v any) string { b, _ := json.Marshal(v); return string(b) }

func testCertificate(t *testing.T) (string, string) {
    t.Helper()
    key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
    if err != nil { t.Fatal(err) }
    cert := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName:"network-fixture.test"},
        DNSNames: []string{"network-fixture.test"}, NotBefore:time.Now().Add(-time.Hour), NotAfter:time.Now().Add(time.Hour),
        KeyUsage:x509.KeyUsageDigitalSignature|x509.KeyUsageCertSign, ExtKeyUsage:[]x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
        IsCA:true, BasicConstraintsValid:true}
    der, err := x509.CreateCertificate(rand.Reader, cert, cert, &key.PublicKey, key)
    if err != nil { t.Fatal(err) }
    pk, err := x509.MarshalPKCS8PrivateKey(key)
    if err != nil { t.Fatal(err) }
    return string(pem.EncodeToMemory(&pem.Block{Type:"CERTIFICATE",Bytes:der})),
        string(pem.EncodeToMemory(&pem.Block{Type:"PRIVATE KEY",Bytes:pk}))
}

var fileProofExits = map[string]string{
    "ws": "127.0.0.2", "ws-tls": "127.0.0.3", "tcp-http": "127.0.0.4",
}

var fileProofUpload = bytes.Repeat([]byte{0, 0xff, 0x12, 0x34}, 64*1024)
var fileProofDownload = bytes.Repeat([]byte{0x56, 0, 0x78, 0xff}, 64*1024)

type fileProofVisit struct {
    method, exit string
    status, size int
    digest [sha256.Size]byte
}

func serveFileProof(w http.ResponseWriter, r *http.Request, visits chan<- fileProofVisit) bool {
    mode, ok := strings.CutPrefix(r.URL.Path, "/file-proof/")
    if !ok { return false }
    transport, kind, _ := strings.Cut(mode, "/")
    ip, _, _ := net.SplitHostPort(r.RemoteAddr)
    visit := fileProofVisit{method:r.Method, exit:ip, status:http.StatusForbidden}
    var download []byte
    if fileProofExits[transport] != "" && fileProofExits[transport] == ip &&
        r.Header.Get("Cookie") == "" && r.Header.Get("Authorization") == "" {
        switch {
        case r.Method == http.MethodPost && kind == "upload":
            body, err := io.ReadAll(io.LimitReader(r.Body, int64(len(fileProofUpload)+1)))
            visit.size, visit.digest = len(body), sha256.Sum256(body)
            visit.status = http.StatusUnprocessableEntity
            if err == nil && bytes.Equal(body, fileProofUpload) {
                visit.status = http.StatusNoContent
            }
        case r.Method == http.MethodGet && kind == "download":
            download = fileProofDownload
            visit.size, visit.digest = len(download), sha256.Sum256(download)
            visit.status = http.StatusOK
            w.Header().Set("Content-Type", "application/octet-stream")
        }
    }
    visits <- visit
    w.WriteHeader(visit.status)
    if download != nil { _, _ = w.Write(download) }
    return true
}

func verifyFileProof(t *testing.T, mode, target string, transport *http.Transport, visits <-chan fileProofVisit) {
    t.Helper()
    client := &http.Client{Transport:transport, Timeout:15*time.Second}
    base := target + "/file-proof/" + mode
    upload, err := client.Post(base+"/upload", "application/octet-stream", bytes.NewReader(fileProofUpload))
    if err != nil { t.Fatal(err) }
    upload.Body.Close()
    if upload.StatusCode != http.StatusNoContent { t.Fatalf("upload status: %d", upload.StatusCode) }

    response, err := client.Get(base+"/download")
    if err != nil { t.Fatal(err) }
    body, err := io.ReadAll(io.LimitReader(response.Body, int64(len(fileProofDownload)+1)))
    response.Body.Close()
    if err != nil || response.StatusCode != http.StatusOK || !bytes.Equal(body, fileProofDownload) {
        t.Fatalf("download status=%d bytes=%d err=%v", response.StatusCode, len(body), err)
    }

    direct := transport.Clone()
    direct.Proxy = nil
    defer direct.CloseIdleConnections()
    rejected, err := (&http.Client{Transport:direct, Timeout:15*time.Second}).Get(base+"/download")
    if err != nil { t.Fatal(err) }
    rejected.Body.Close()
    if rejected.StatusCode != http.StatusForbidden { t.Fatalf("direct request status: %d", rejected.StatusCode) }

    expected := []fileProofVisit{
        {method:http.MethodPost, exit:fileProofExits[mode], status:http.StatusNoContent, size:len(fileProofUpload), digest:sha256.Sum256(fileProofUpload)},
        {method:http.MethodGet, exit:fileProofExits[mode], status:http.StatusOK, size:len(fileProofDownload), digest:sha256.Sum256(fileProofDownload)},
        {method:http.MethodGet, exit:"127.0.0.1", status:http.StatusForbidden},
    }
    for _, want := range expected {
        select {
        case got := <-visits:
            if got != want { t.Fatalf("origin proof: got %+v, want %+v", got, want) }
            t.Logf("file proof %s status=%d exit=%s bytes=%d sha256=%x", got.method, got.status, got.exit, got.size, got.digest)
        default:
            t.Fatalf("missing origin proof: %+v", want)
        }
    }
    if len(visits) != 0 { t.Fatal("unexpected additional file request") }
}

// Every endpoint and key is created inside this test; external services are never contacted.
func TestOwnedTransportMatrix(t *testing.T) {
    origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request) {
        if r.Method == "POST" { _, _ = io.Copy(w,r.Body); return }
        _, _ = io.WriteString(w,"network-poc-fixture\n")
    }))
    defer origin.Close()
    cert, key := testCertificate(t)
    pair, err := tls.X509KeyPair([]byte(cert),[]byte(key))
    if err != nil { t.Fatal(err) }
    fileVisits := make(chan fileProofVisit, 16)
    target := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request) {
        if !serveFileProof(w, r, fileVisits) {
            _, _ = io.WriteString(w,"owned-https-fixture")
        }
    }))
    target.TLS = &tls.Config{Certificates:[]tls.Certificate{pair},MinVersion:tls.VersionTLS13,NextProtos:[]string{"h2","http/1.1"}}
    target.StartTLS()
    defer target.Close()
    for _, mode := range []string{"raw", "ws", "ws-tls", "tcp-http", "tls", "reality"} {
        t.Run(mode,func(t *testing.T) {
            defer Stop()
            serverPort, proxyPort := freePort(t),freePort(t)
            serverStream := map[string]any{"network":"raw","security":"none"}
            clientStream := map[string]any{"network":"raw","security":"none"}
            if mode=="ws" || mode=="ws-tls" {
                serverStream = map[string]any{"network":"ws","security":"none","wsSettings":map[string]any{"path":"/owned"}}
                clientStream = map[string]any{"network":"ws","security":"none","wsSettings":map[string]any{"path":"/owned"}}
            }
            if mode=="tcp-http" {
                header := map[string]any{"type":"http","request":map[string]any{
                    "path":[]string{"/owned"},"headers":map[string]any{"Host":[]string{"owned.test"}}}}
                serverStream["rawSettings"] = map[string]any{"header":header}
                clientStream["rawSettings"] = map[string]any{"header":header}
            }
            if mode=="tls" || mode=="ws-tls" {
                serverStream["security"]="tls"
                serverStream["tlsSettings"]=map[string]any{"certificates":[]any{map[string]any{"certificate":strings.Split(cert,"\n"),"key":strings.Split(key,"\n")}}}
                clientStream["security"]="tls"
                clientStream["tlsSettings"]=map[string]any{"serverName":"network-fixture.test","certificates":[]any{map[string]any{"certificate":strings.Split(cert,"\n"),"usage":"verify"}}}
            }
            if mode=="reality" {
                private,err:=ecdh.X25519().GenerateKey(rand.Reader)
                if err!=nil { t.Fatal(err) }
                serverStream["security"]="reality"
                serverStream["realitySettings"]=map[string]any{"target":target.Listener.Addr().String(),"serverNames":[]string{"network-fixture.test"},
                    "privateKey":base64.RawURLEncoding.EncodeToString(private.Bytes()),"shortIds":[]string{"1234"}}
                clientStream["security"]="reality"
                clientStream["realitySettings"]=map[string]any{"serverName":"network-fixture.test","fingerprint":"chrome",
                    "password":base64.RawURLEncoding.EncodeToString(private.PublicKey().Bytes()),"shortId":"1234","spiderX":"/"}
            }
            outbound := map[string]any{"protocol":"freedom",
                "settings":map[string]any{"finalRules":[]any{map[string]any{"action":"allow","ip":[]string{"127.0.0.1/32"},
                    "port":strconv.Itoa(origin.Listener.Addr().(*net.TCPAddr).Port)+","+strconv.Itoa(target.Listener.Addr().(*net.TCPAddr).Port)}}}}
            if exit := fileProofExits[mode]; exit != "" { outbound["sendThrough"] = exit }
            serverJSON:=encode(map[string]any{"log":map[string]any{"loglevel":"debug"},"inbounds":[]any{map[string]any{"listen":"127.0.0.1","port":serverPort,"protocol":"vless",
                "settings":map[string]any{"clients":[]any{map[string]any{"id":fixtureID}},"decryption":"none"},"streamSettings":serverStream}},"outbounds":[]any{outbound}})
            server:=exec.Command(os.Args[0],"-test.run=^TestCoreServerProcess$")
            var serverLog bytes.Buffer
            server.Stdout=&serverLog;server.Stderr=&serverLog
            server.Env=append(os.Environ(),"NETWORK_SYNTHETIC_SERVER="+serverJSON)
            if err:=server.Start();err!=nil { t.Fatal(err) }
            defer func(){ _=server.Process.Kill(); _=server.Wait();if t.Failed(){t.Log("owned server diagnostic:",serverLog.String())} }()
            deadline:=time.Now().Add(5*time.Second)
            for {
                socket,err:=net.DialTimeout("tcp",net.JoinHostPort("127.0.0.1",strconv.Itoa(serverPort)),100*time.Millisecond)
                if err==nil { socket.Close();break }
                if time.Now().After(deadline) { t.Fatal("fixture server did not start") }
                time.Sleep(25*time.Millisecond)
            }
            config:=encode(map[string]any{"log":map[string]any{"loglevel":"debug"},"inbounds":[]any{map[string]any{"listen":"127.0.0.1","port":proxyPort,"protocol":"http","settings":map[string]any{}}},
                "outbounds":[]any{map[string]any{"protocol":"vless","settings":map[string]any{"vnext":[]any{map[string]any{"address":"127.0.0.1","port":serverPort,"users":[]any{map[string]any{"id":fixtureID,"encryption":"none"}}}}},"streamSettings":clientStream}}})
            if err=Start(config);err!=nil { t.Fatal(err) }
            if err=Start(config);err==nil { t.Fatal("second start accepted") }
            var probeConfig map[string]any
            if err := json.Unmarshal([]byte(config), &probeConfig); err != nil { t.Fatal(err) }
            delete(probeConfig, "inbounds")
            if _, err := Probe(encode(probeConfig), origin.URL+"/probe"); err != nil { t.Fatal(err) }
            if !IsRunning() { t.Fatal("independent probe stopped the primary core") }
            probeBad := strings.Replace(encode(probeConfig), fixtureID,"00000000-0000-4000-8000-000000000002",1)
            if _, err := Probe(probeBad, origin.URL+"/probe"); err == nil { t.Fatal("invalid probe credentials reached origin") }
            if !IsRunning() { t.Fatal("failed probe stopped the primary core") }
            proxy,_:=url.Parse("http://127.0.0.1:"+strconv.Itoa(proxyPort))
            roots:=x509.NewCertPool()
            if !roots.AppendCertsFromPEM([]byte(cert)) { t.Fatal("fixture CA unavailable") }
            transport:=&http.Transport{Proxy:http.ProxyURL(proxy),DisableKeepAlives:true,
                TLSClientConfig:&tls.Config{RootCAs:roots,ServerName:"network-fixture.test",MinVersion:tls.VersionTLS12}}
            defer transport.CloseIdleConnections()
            client:=&http.Client{Transport:transport,Timeout:15*time.Second}
            res,err:=client.Get(origin.URL+"/download")
            if err!=nil { t.Fatal(err) }
            body,_:=io.ReadAll(res.Body);res.Body.Close()
            if string(body)!="network-poc-fixture\n" { t.Fatal("download differs") }
            res,err=client.Post(origin.URL+"/upload","application/octet-stream",strings.NewReader("owned-upload"))
            if err!=nil { t.Fatal(err) }
            body,_=io.ReadAll(res.Body);res.Body.Close()
            if string(body)!="owned-upload" { t.Fatal("upload differs") }
            res,err=client.Get(target.URL+"/download")
            if err!=nil { t.Fatal(err) }
            body,_=io.ReadAll(res.Body);res.Body.Close()
            if string(body)!="owned-https-fixture" { t.Fatal("HTTPS CONNECT download differs") }
            if fileProofExits[mode] != "" { verifyFileProof(t, mode, target.URL, transport, fileVisits) }
            if err=Stop();err!=nil { t.Fatal(err) }
            socket,err:=net.DialTimeout("tcp",net.JoinHostPort("127.0.0.1",strconv.Itoa(proxyPort)),200*time.Millisecond)
            if err==nil { socket.Close();t.Fatal("proxy listener remained open after stop") }
            badConfig:=strings.Replace(config,fixtureID,"00000000-0000-4000-8000-000000000002",1)
            if err=Start(badConfig);err!=nil { t.Fatal(err) }
            client.Timeout=1500*time.Millisecond
            res,err=client.Get(origin.URL+"/download")
            if err==nil {
                res.Body.Close()
                if res.StatusCode==http.StatusOK { t.Fatal("invalid credentials reached owned origin") }
            }
        })
    }
}
