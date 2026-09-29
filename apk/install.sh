#!/usr/bin/env bash
#
# Installa l'APK sul telefono collegato con adb (USB o debug wireless) e prepara Android perché il
# server resti attivo in background.
#
#   apk/install.sh [-s SERIALE] [--lite] [--no-tweaks]
#
#   -s SERIALE   telefono da usare (serve se adb ne vede più di uno)
#   --lite       installa dist/immich-server-lite.apk (i pacchetti grossi vanno poi messi con adb push)
#   --no-tweaks  non tocca le impostazioni di Android (vedi sotto)
#
# Impostazioni di Android che lo script modifica (senza, il server viene fermato dal sistema):
#   - disattiva le "restrizioni sui processi figli" (Android 12+ uccide le app con più di 32 processi figli):
#       settings put global settings_enable_monitor_phantom_procs false
#       device_config put activity_manager max_phantom_processes 2147483647
#       device_config set_sync_disabled_for_tests persistent   (impedisce che Android rimetta il limite;
#                                                              ferma anche l'aggiornamento remoto degli altri flag)
#   - esclude l'app dal risparmio batteria (Doze)
#   - concede all'app il permesso WRITE_SECURE_SETTINGS, così può gestire da sola la prima impostazione
# Per annullare tutto:
#   adb shell settings delete global settings_enable_monitor_phantom_procs
#   adb shell device_config delete activity_manager max_phantom_processes
#   adb shell device_config set_sync_disabled_for_tests none
#   adb shell dumpsys deviceidle whitelist -org.nasonmobile.immich
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG=org.nasonmobile.immich
APK="$ROOT/dist/immich-server.apk"
SERIAL=""
TWEAKS=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s) SERIAL="$2"; shift 2 ;;
    --lite) APK="$ROOT/dist/immich-server-lite.apk"; shift ;;
    --no-tweaks) TWEAKS=0; shift ;;
    -h|--help) sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "opzione sconosciuta: $1" >&2; exit 1 ;;
  esac
done

log() { printf '\033[1;34m==>\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31merrore:\033[0m %s\n' "$*" >&2; exit 1; }

command -v adb >/dev/null || die "manca adb (pacchetto android-tools / platform-tools)"
[[ -f "$APK" ]] || die "manca $APK: costruiscilo con pc/build-app.sh e apk/build.sh"

if [[ -z "$SERIAL" ]]; then
  mapfile -t DEVS < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  [[ ${#DEVS[@]} -eq 1 ]] || die "adb vede ${#DEVS[@]} dispositivi: indica quale con -s SERIALE ($(adb devices | awk 'NR>1 && $2=="device" {print $1}' | tr '\n' ' '))"
  SERIAL="${DEVS[0]}"
fi
A=(adb -s "$SERIAL")

log "telefono: $("${A[@]}" shell getprop ro.product.model | tr -d '\r') · Android $("${A[@]}" shell getprop ro.build.version.release | tr -d '\r') · $("${A[@]}" shell getprop ro.product.cpu.abilist | tr -d '\r')"
"${A[@]}" shell getprop ro.product.cpu.abilist | grep -q arm64 || die "serve un telefono arm64-v8a"

log "installo $(basename "$APK") ($(du -h "$APK" | cut -f1))"
"${A[@]}" install -r "$APK"

if [[ $TWEAKS == 1 ]]; then
  log "concedo WRITE_SECURE_SETTINGS all'app"
  "${A[@]}" shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
  log "disattivo le restrizioni sui processi figli"
  "${A[@]}" shell settings put global settings_enable_monitor_phantom_procs false
  "${A[@]}" shell device_config set_sync_disabled_for_tests persistent >/dev/null 2>&1 || true
  "${A[@]}" shell device_config put activity_manager max_phantom_processes 2147483647 >/dev/null 2>&1 || true
  log "escludo l'app dal risparmio batteria"
  "${A[@]}" shell dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1 || true
fi

log "apro l'app"
"${A[@]}" shell am start -n "$PKG/.MainActivity" >/dev/null
cat >&2 <<EOF

Fatto. Sul telefono premi "Installa e avvia" (serve internet, 10-30 minuti la prima volta).
Quando lo stato è "In esecuzione" apri l'indirizzo mostrato dall'app, per esempio http://192.168.x.x:2283
Log dal PC:  adb -s $SERIAL shell run-as $PKG tail -f files/immich/logs/immich.log
EOF
