package networkcore

import (
    "crypto/ecdh"
    "crypto/ecdsa"
    "crypto/elliptic"
    "crypto/rand"
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
    "strconv"
    "strings"
    "testing"
    "time"

    "github.com/xtls/xray-core/core"
)

const fixtureID = "00000000-0000-4000-8000-000000000001"

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
    target := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request) {}))
    target.TLS = &tls.Config{Certificates:[]tls.Certificate{pair},MinVersion:tls.VersionTLS13,NextProtos:[]string{"h2","http/1.1"}}
    target.StartTLS()
    defer target.Close()
    for _, mode := range []string{"raw", "ws", "tls", "reality"} {
        t.Run(mode,func(t *testing.T) {
            defer Stop()
            serverPort, proxyPort := freePort(t),freePort(t)
            serverStream := map[string]any{"network":"raw","security":"none"}
            clientStream := map[string]any{"network":"raw","security":"none"}
            if mode=="ws" {
                serverStream = map[string]any{"network":"ws","security":"none","wsSettings":map[string]any{"path":"/owned"}}
                clientStream = map[string]any{"network":"ws","security":"none","wsSettings":map[string]any{"path":"/owned"}}
            }
            if mode=="tls" {
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
            serverJSON:=encode(map[string]any{"log":map[string]any{"loglevel":"none"},"inbounds":[]any{map[string]any{"listen":"127.0.0.1","port":serverPort,"protocol":"vless",
                "settings":map[string]any{"clients":[]any{map[string]any{"id":fixtureID}},"decryption":"none"},"streamSettings":serverStream}},"outbounds":[]any{map[string]any{"protocol":"freedom"}}})
            c,err:=core.LoadConfig("json",strings.NewReader(serverJSON))
            if err!=nil { t.Fatal(err) }
            server,err:=core.New(c)
            if err!=nil { t.Fatal(err) }
            defer server.Close()
            if err=server.Start();err!=nil { t.Fatal(err) }
            config:=encode(map[string]any{"log":map[string]any{"loglevel":"none"},"inbounds":[]any{map[string]any{"listen":"127.0.0.1","port":proxyPort,"protocol":"http","settings":map[string]any{}}},
                "outbounds":[]any{map[string]any{"protocol":"vless","settings":map[string]any{"vnext":[]any{map[string]any{"address":"127.0.0.1","port":serverPort,"users":[]any{map[string]any{"id":fixtureID,"encryption":"none"}}}}},"streamSettings":clientStream}}})
            if err=Start(config);err!=nil { t.Fatal(err) }
            if err=Start(config);err==nil { t.Fatal("second start accepted") }
            proxy,_:=url.Parse("http://127.0.0.1:"+strconv.Itoa(proxyPort))
            transport:=&http.Transport{Proxy:http.ProxyURL(proxy),DisableKeepAlives:true}
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
        })
    }
}
