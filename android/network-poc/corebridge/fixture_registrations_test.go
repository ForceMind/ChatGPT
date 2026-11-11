package networkcore

import (
    // The owned fixtures act as servers and need modules absent from the client.
    _ "github.com/xtls/xray-core/proxy/freedom"
    _ "github.com/xtls/xray-core/proxy/vless/inbound"
)
