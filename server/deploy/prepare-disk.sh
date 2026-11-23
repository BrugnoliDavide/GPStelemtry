#!/bin/sh
# Prepara il disco esterno (USB) per i dati del server GPStelemetry e lo
# monta sempre in /mnt/gpsdata, anche dopo ogni riavvio.
#
#   sudo sh prepare-disk.sh                  # elenca i dischi collegati
#   sudo sh prepare-disk.sh /dev/sda         # FORMATTA il disco (chiede conferma)
#   sudo sh prepare-disk.sh /dev/sda --keep  # usa la partizione ext4 che c'è già, senza cancellare
#
# Il disco viene montato con "nofail": se all'avvio non è collegato il
# Raspberry parte lo stesso, ma il server NON parte (non scrive sulla scheda SD).
set -eu

MNT=${MNT:-/mnt/gpsdata}
FSTAB=${FSTAB:-/etc/fstab}
LABEL=GPSDATA

[ "$(id -u)" -eq 0 ] || { echo "Esegui con sudo"; exit 1; }

if [ $# -lt 1 ]; then
  echo "Dischi collegati (la scheda SD è mmcblk0, NON va usata):"
  lsblk -o NAME,SIZE,TYPE,FSTYPE,LABEL,MODEL,MOUNTPOINT
  echo
  echo "Poi: sudo sh prepare-disk.sh /dev/NOME   (es. /dev/sda)"
  exit 0
fi

DEV=$1
KEEP=no
[ "${2:-}" = "--keep" ] && KEEP=yes
[ -b "$DEV" ] || { echo "$DEV non è un disco"; exit 1; }

TYPE=$(lsblk -dno TYPE "$DEV")
if [ "$TYPE" != disk ] && ! { [ "$TYPE" = loop ] && [ "${ALLOW_LOOP:-}" = 1 ]; }; then
  echo "$DEV è di tipo '$TYPE': indica il disco intero (es. /dev/sda, non /dev/sda1)"; exit 1
fi

# Mai il disco da cui gira il sistema.
ROOTSRC=$(findmnt -n -o SOURCE /)
ROOTDISK=$(lsblk -no PKNAME "$ROOTSRC" 2>/dev/null | head -n1 || true)
if [ -n "$ROOTDISK" ] && [ "/dev/$ROOTDISK" = "$DEV" ]; then
  echo "$DEV contiene il sistema operativo: scegli il disco esterno."; exit 1
fi

firstpart() { lsblk -lnpo NAME,TYPE "$DEV" | awk '$2=="part"{print $1; exit}'; }

if [ "$KEEP" = yes ]; then
  PART=$(lsblk -lnpo NAME,FSTYPE "$DEV" | awk '$2=="ext4"{print $1; exit}')
  [ -n "$PART" ] || { echo "Su $DEV non c'è una partizione ext4: togli --keep per formattarlo."; exit 1; }
else
  echo "ATTENZIONE: TUTTI i dati su $DEV verranno cancellati."
  lsblk -o NAME,SIZE,TYPE,FSTYPE,LABEL,MODEL "$DEV"
  printf "Per confermare scrivi il nome del disco (%s): " "$DEV"
  read -r CONFIRM
  [ "$CONFIRM" = "$DEV" ] || { echo "Annullato."; exit 1; }
  # Smonta eventuali partizioni montate automaticamente.
  for p in $(lsblk -lnpo NAME "$DEV" | tail -n +2); do umount "$p" 2>/dev/null || true; done
  wipefs -a -q "$DEV"
  printf 'label: gpt\n,,L\n' | sfdisk --quiet "$DEV"
  partprobe "$DEV" 2>/dev/null || true
  udevadm settle 2>/dev/null || sleep 2
  PART=$(firstpart)
  [ -n "$PART" ] || { echo "Partizione non trovata dopo la creazione"; exit 1; }
  # -m 1: riserva per root all'1% (il disco è solo per i dati).
  mkfs.ext4 -q -F -L "$LABEL" -m 1 "$PART"
fi

UUID=$(blkid -s UUID -o value "$PART")
[ -n "$UUID" ] || { echo "UUID di $PART non trovato"; exit 1; }

mkdir -p "$MNT"
if grep -q "[[:space:]]$MNT[[:space:]]" "$FSTAB" 2>/dev/null; then
  cp "$FSTAB" "$FSTAB.gpstelemetry.bak"
  grep -v "[[:space:]]$MNT[[:space:]]" "$FSTAB.gpstelemetry.bak" > "$FSTAB"
else
  cp "$FSTAB" "$FSTAB.gpstelemetry.bak" 2>/dev/null || true
fi
# noatime: meno scritture; nofail + timeout: il Pi si avvia anche senza disco.
echo "UUID=$UUID $MNT ext4 defaults,noatime,nofail,x-systemd.device-timeout=15s 0 2" >> "$FSTAB"

systemctl daemon-reload 2>/dev/null || true
# Montaggio dalla riga di fstab, così valgono subito le sue opzioni (noatime).
if mountpoint -q "$MNT"; then
  mount -o remount "$MNT" 2>/dev/null || true
elif [ "$FSTAB" = /etc/fstab ]; then
  mount "$MNT"
else
  mount UUID="$UUID" "$MNT"
fi
mkdir -p "$MNT/gpstelemetry"

echo
echo "Disco pronto: $PART montato in $MNT (UUID $UUID)."
echo "Ora: sudo sh install.sh --data $MNT/gpstelemetry"
