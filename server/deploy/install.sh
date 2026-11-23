#!/bin/sh
# Installa (o aggiorna) il server GPStelemetry su Raspberry Pi OS / Debian.
#
#   sudo sh install.sh                                 # dati sulla scheda SD (/var/lib/gpstelemetry)
#   sudo sh install.sh --data /mnt/gpsdata/gpstelemetry  # dati sul disco esterno (vedi prepare-disk.sh)
#
# Un aggiornamento sostituisce solo l'eseguibile: dati e opzioni restano.
# Con --data su una cartella nuova, il database già presente viene spostato lì.
set -eu

[ "$(id -u)" -eq 0 ] || { echo "Esegui con sudo"; exit 1; }
HERE=$(cd "$(dirname "$0")" && pwd)
DEFAULT_DATA=/var/lib/gpstelemetry
ENVF=/etc/default/gpstelemetry
DATA=""
while [ $# -gt 0 ]; do
  case "$1" in
    --data) DATA=${2:?manca la cartella dopo --data}; shift 2 ;;
    *) echo "Opzione sconosciuta: $1"; exit 1 ;;
  esac
done

case "$(uname -m)" in
  aarch64|arm64) BIN=gpstelemetry-server-linux-arm64 ;;
  armv7l|armv6l) BIN=gpstelemetry-server-linux-armv7 ;;
  x86_64)        BIN=gpstelemetry-server-linux-amd64 ;;
  *) echo "Architettura non supportata: $(uname -m)"; exit 1 ;;
esac
SRC="$HERE/$BIN"
[ -f "$SRC" ] || SRC="$HERE/../dist/$BIN"
[ -f "$SRC" ] || { echo "Manca $BIN (compilalo con build.sh)"; exit 1; }

id gpstelemetry >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin gpstelemetry

install -d -m 0755 /opt/gpstelemetry
install -m 0755 "$SRC" /opt/gpstelemetry/gpstelemetry-server.new
mv /opt/gpstelemetry/gpstelemetry-server.new /opt/gpstelemetry/gpstelemetry-server
install -d -o gpstelemetry -g gpstelemetry -m 0750 "$DEFAULT_DATA"

[ -f "$ENVF" ] || install -m 0644 "$HERE/gpstelemetry.env" "$ENVF"
# File delle opzioni di una versione precedente: aggiunge le variabili nuove.
grep -q '^GPS_DATA=' "$ENVF" || echo "GPS_DATA=$DEFAULT_DATA" >> "$ENVF"
sed -i '/GPS_BACKUP/d; s# -data /var/lib/gpstelemetry##' "$ENVF"

install -m 0644 "$HERE/gpstelemetry.service" /etc/systemd/system/gpstelemetry.service
# Versioni precedenti: il backup notturno sulla scheda SD non c'è più.
if [ -e /etc/systemd/system/gpstelemetry-backup.timer ]; then
  systemctl disable --now gpstelemetry-backup.timer 2>/dev/null || true
  rm -rf /etc/systemd/system/gpstelemetry-backup.timer /etc/systemd/system/gpstelemetry-backup.service \
    /etc/systemd/system/gpstelemetry-backup.service.d "$DEFAULT_DATA/backup"
fi

if [ -n "$DATA" ]; then
  case "$DATA" in /*) ;; *) echo "--data vuole un percorso assoluto"; exit 1 ;; esac
  MOUNT=$(findmnt -n -o TARGET --target "$(dirname "$DATA")")
  if [ "$MOUNT" = "/" ]; then
    echo "ATTENZIONE: $DATA è sulla scheda SD, non su un disco esterno."
    echo "Collega il disco e preparalo con: sudo sh prepare-disk.sh"
    exit 1
  fi
  install -d -o gpstelemetry -g gpstelemetry -m 0750 "$DATA"
  OLD=$(sed -n 's/^GPS_DATA=//p' "$ENVF" | tail -n1)
  # Spostamento dei dati esistenti (solo se la cartella nuova è vuota).
  if [ "$OLD" != "$DATA" ] && [ -f "$OLD/gpstelemetry.db" ] && [ ! -f "$DATA/gpstelemetry.db" ]; then
    echo "Sposto i dati da $OLD a $DATA…"
    systemctl stop gpstelemetry.service 2>/dev/null || true
    for f in gpstelemetry.db gpstelemetry.db-wal gpstelemetry.db-shm; do
      [ -f "$OLD/$f" ] && cp -a "$OLD/$f" "$DATA/"
    done
    [ -d "$OLD/avatars" ] && cp -a "$OLD/avatars" "$DATA/"
    mv "$OLD/gpstelemetry.db" "$OLD/gpstelemetry.db.spostato"
    rm -f "$OLD/gpstelemetry.db-wal" "$OLD/gpstelemetry.db-shm"
  fi
  chown -R gpstelemetry:gpstelemetry "$DATA"
  sed -i "s#^GPS_DATA=.*#GPS_DATA=$DATA#" "$ENVF"
  # Il servizio parte solo con il disco montato e può scrivere solo lì.
  install -d /etc/systemd/system/gpstelemetry.service.d
  printf '[Unit]\nRequiresMountsFor=%s\n\n[Service]\nReadWritePaths=%s\n' "$DATA" "$DATA" > /etc/systemd/system/gpstelemetry.service.d/data.conf
fi

systemctl daemon-reload
systemctl enable --now gpstelemetry.service
systemctl restart gpstelemetry.service

sleep 1
if command -v curl >/dev/null 2>&1; then
  curl -fsS http://127.0.0.1:8080/v1/health && echo
fi
systemctl --no-pager --lines=0 status gpstelemetry.service | head -n 12
echo "Dati in: $(sed -n 's/^GPS_DATA=//p' "$ENVF" | tail -n1)"
echo "Opzioni: $ENVF (imposta -public-url).  Log: journalctl -u gpstelemetry -f"
