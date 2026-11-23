#!/bin/sh
# Compila il server per il Raspberry Pi (dal PC, serve Go 1.26 o successivo).
# Nessuna dipendenza C: l'eseguibile è unico e statico.
#   sh build.sh          → dist/gpstelemetry-server-linux-{arm64,armv7,amd64}
#   sh build.sh test     → esegue anche i test
set -eu
cd "$(dirname "$0")"
[ "${1:-}" = "test" ] && go test -count=1 ./...
mkdir -p dist
for target in arm64: arm:7 amd64:; do
  arch=${target%%:*}; arm=${target#*:}
  name=$arch; [ "$arch" = arm ] && name=armv7
  echo "→ linux/$name"
  CGO_ENABLED=0 GOOS=linux GOARCH=$arch GOARM=$arm \
    go build -trimpath -ldflags="-s -w" -o "dist/gpstelemetry-server-linux-$name" .
done
cp deploy/* dist/
echo "Pronto: copia la cartella dist/ sul Raspberry ed esegui  sudo sh install.sh"
