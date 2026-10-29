#!/usr/bin/env bash
set -euo pipefail
test "${GITHUB_ACTIONS:-}" = true || { echo 'Build only in GitHub Actions'; exit 2; }
cd "$(dirname "$0")/../corebridge"
MOBILE_VERSION=v0.0.0-20261011034528-197770444867
go mod tidy
go mod verify
go test ./...
go install golang.org/x/mobile/cmd/gomobile@"$MOBILE_VERSION"
go install golang.org/x/mobile/cmd/gobind@"$MOBILE_VERSION"
go get golang.org/x/mobile@"$MOBILE_VERSION"
mkdir -p ../app/libs bin
gomobile bind -javapkg=studio -target=android/arm64,android/amd64 -androidapi=28 -o ../app/libs/networkcore.aar .
go build -trimpath -o bin/xray github.com/xtls/xray-core/main
go list -m -json all > ../app/libs/dependencies.json
