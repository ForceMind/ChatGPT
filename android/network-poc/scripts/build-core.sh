#!/usr/bin/env bash
set -euo pipefail
test "${GITHUB_ACTIONS:-}" = true || { echo 'Build only in GitHub Actions'; exit 2; }
cd "$(dirname "$0")/../corebridge"
MOBILE_VERSION=v0.0.0-20261011034528-197770444867
go mod tidy
go mod verify
go test -timeout 120s ./...
if [[ -s ../app/libs/networkcore.aar && -x bin/xray && -s ../app/libs/dependencies.json ]]; then
  echo 'Reuse exact-source native output after current host tests'
  exit 0
fi
go install golang.org/x/mobile/cmd/gomobile@"$MOBILE_VERSION"
go install golang.org/x/mobile/cmd/gobind@"$MOBILE_VERSION"
go get golang.org/x/mobile@"$MOBILE_VERSION"
mkdir -p ../app/libs bin
gomobile bind -trimpath -ldflags='-checklinkname=0 -s -w -extldflags=-Wl,-z,max-page-size=16384' -javapkg=studio -target=android/arm64,android/amd64 -androidapi=28 -o ../app/libs/networkcore.aar .
go build -trimpath -o bin/xray github.com/xtls/xray-core/main
go list -m -json all > ../app/libs/dependencies.json
