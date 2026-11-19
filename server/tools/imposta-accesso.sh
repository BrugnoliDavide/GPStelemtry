#!/bin/sh
# Imposta nome utente e password del Raspberry Pi scrivendo userconf.txt
# sulla partizione di avvio della scheda SD (Raspberry Pi OS Bookworm).
#
# Al prossimo avvio il Raspberry rinomina il primo utente (UID 1000) con il
# nome scelto, gli assegna la nuova password e cancella il file.
# La password non viene salvata in chiaro: nel file c'è solo l'hash SHA-512.
#
# Uso (Git Bash su Windows, scheda inserita come D:):
#   sh imposta-accesso.sh          # partizione di avvio in /d
#   sh imposta-accesso.sh /e       # se la scheda ha un'altra lettera
set -eu
BOOT=${1:-/d}
[ -f "$BOOT/cmdline.txt" ] && [ -f "$BOOT/config.txt" ] || {
  echo "In $BOOT non c'è la partizione di avvio del Raspberry (manca cmdline.txt)."; exit 1; }
command -v openssl >/dev/null || { echo "Serve openssl (incluso in Git Bash)."; exit 1; }

printf "Nome utente [pi]: "
read -r USERNAME
USERNAME=${USERNAME:-pi}
case "$USERNAME" in
  *[!a-z0-9_-]*|[!a-z_]*) echo "Nome non valido: solo minuscole, cifre, _ e -, inizia con una lettera."; exit 1 ;;
esac

while :; do
  stty -echo 2>/dev/null || true
  printf "Nuova password: "; read -r P1; echo
  printf "Ripeti la password: "; read -r P2; echo
  stty echo 2>/dev/null || true
  if [ "$P1" != "$P2" ]; then echo "Le password non coincidono, riprova."; continue; fi
  if [ ${#P1} -lt 8 ]; then echo "Almeno 8 caratteri, riprova."; continue; fi
  break
done

HASH=$(printf '%s' "$P1" | openssl passwd -6 -stdin)
P1=; P2=
printf '%s:%s\n' "$USERNAME" "$HASH" > "$BOOT/userconf.txt"
: > "$BOOT/ssh"
echo
echo "Fatto: $BOOT/userconf.txt e $BOOT/ssh pronti."
echo "Rimetti la scheda nel Raspberry, accendilo e dopo un paio di minuti:"
echo "  ssh $USERNAME@raspberrypi.local"
