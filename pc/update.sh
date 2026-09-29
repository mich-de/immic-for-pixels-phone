#!/usr/bin/env bash
#
# Porta il server all'ultima versione di Immich pubblicata dallo sviluppatore, con un comando solo:
# controlla le versioni, compila pacchetto e APK se serve (pc/build-app.sh ricava da solo le dipendenze
# giuste per quella versione) e, se glielo chiedi, installa sui dispositivi collegati con adb.
#
#   pc/update.sh --check                 solo controllo: versione pubblicata, compilata e installata su ogni dispositivo
#   pc/update.sh                         compila l'ultima versione se non è già pronta in dist/ (non installa nulla)
#   pc/update.sh -s SERIALE [-s ...]     ...e la installa su quei dispositivi (USB o debug wireless, vedi `adb devices`)
#   pc/update.sh --version v3.2.5 ...    una versione precisa invece dell'ultima
#   pc/update.sh --force -s SERIALE      ricompila l'APK e reinstalla anche se le versioni coincidono
#                                        (per portare sul telefono modifiche dell'app stessa)
#   pc/update.sh --github -s SERIALE     invece di compilare scarica l'APK già pronto dai rilasci su GitHub
#                                        (li pubblica il workflow .github/workflows/immich.yml; REPO=utente/repo)
#
# Su un server già in uso l'installazione lo ferma per qualche minuto (adb install -r chiude l'app), poi riparte da
# solo: Immich aggiorna il database da sé e l'app rifà la preparazione di Debian se il pacchetto porta .deb nuovi.
# Immich fa un backup del database ogni notte (library/backups): prima di un salto di versione grosso controlla
# che ce ne sia uno recente.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG=org.nasonmobile.immich
APK="$ROOT/dist/immich-server.apk"
WANT=""
CHECK=0
FORCE=0
FROM_GITHUB=0
REPO="${REPO:-mich-de/immic-for-pixels-phone}"
SERIALS=()

log() { printf '\033[1;34m==>\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31merrore:\033[0m %s\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s) [[ -n "${2:-}" ]] || die "-s vuole il seriale del dispositivo"; SERIALS+=("$2"); shift 2 ;;
    --version) [[ -n "${2:-}" ]] || die "--version vuole una versione (es. v3.2.5)"; WANT="$2"; shift 2 ;;
    --check) CHECK=1; shift ;;
    --force) FORCE=1; shift ;;
    --github) FROM_GITHUB=1; shift ;;
    -h | --help) sed -n '2,21p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "opzione sconosciuta: $1 (vedi $0 --help)" ;;
  esac
done
for t in adb unzip tar curl python3; do
  command -v "$t" >/dev/null || die "manca il comando '$t'"
done

# versione (e dipendenze) che pc/build-app.sh userebbe: l'ultima pubblicata, o quella richiesta
versions="$(IMMICH_VERSION="$WANT" "$ROOT/pc/build-app.sh" --versions)" || die "non riesco a determinare la versione da costruire"
TARGET="$(sed -n 's/^immich=//p' <<<"$versions")"

# versione di Immich dentro l'APK già costruito ("" se non c'è)
apk_version() {
  [[ -f "$APK" ]] || return 0
  { unzip -p "$APK" assets/immich-pack.tar.gz | tar -xzOf - --wildcards '*/VERSION'; } 2>/dev/null \
    | sed -n 's/^immich=//p' | head -1 || true
}
# versione di Immich installata su un dispositivo ("" se l'app non c'è o non ha ancora estratto il pacchetto)
device_version() {
  adb -s "$1" exec-out run-as "$PKG" cat files/immich/app/pack/VERSION 2>/dev/null | tr -d '\r' \
    | sed -n 's/^immich=//p' | head -1 || true
}
device_name() {
  adb -s "$1" shell getprop ro.product.model 2>/dev/null | tr -d '\r'
}

BUILT="$(apk_version)"
log "ultima versione di Immich: $TARGET · APK in dist/: ${BUILT:-nessuno}"
mapfile -t CONNECTED < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
for s in "${CONNECTED[@]}"; do
  if adb -s "$s" shell pm path "$PKG" 2>/dev/null | grep -q '^package:'; then
    v="$(device_version "$s")"
    log "  $s ($(device_name "$s")): ${v:-app installata, server mai avviato}"
  else
    log "  $s ($(device_name "$s")): app non installata"
  fi
done
[[ $CHECK == 1 ]] && exit 0

for s in "${SERIALS[@]}"; do
  printf '%s\n' "${CONNECTED[@]}" | grep -qxF "$s" || die "dispositivo $s non collegato (adb devices)"
done

# --- compilazione, solo se serve --------------------------------------------
if [[ $FROM_GITHUB == 1 && ( "$BUILT" != "$TARGET" || $FORCE == 1 ) ]]; then
  url="https://github.com/$REPO/releases/download/$TARGET/immich-server.apk"
  log "scarico l'APK di Immich $TARGET da github.com/$REPO"
  mkdir -p "$ROOT/dist"
  curl -fL --retry 3 -# -o "$APK.part" "$url" \
    || die "su github.com/$REPO non c'è ancora l'APK di $TARGET (il workflow l'ha già pubblicato?)"
  mv "$APK.part" "$APK"
  BUILT="$(apk_version)"
  [[ "$BUILT" == "$TARGET" ]] || die "l'APK scaricato contiene Immich '$BUILT' invece di $TARGET"
elif [[ "$BUILT" != "$TARGET" || $FORCE == 1 ]]; then
  PACK="$ROOT/dist/immich-android-$TARGET-arm64.tar.gz"
  if [[ ! -s "$PACK" ]]; then
    log "compilo Immich $TARGET (decine di minuti la prima volta)"
    IMMICH_VERSION="$TARGET" "$ROOT/pc/build-app.sh"
  fi
  touch "$PACK" # apk/build.sh prende il pacchetto più recente in dist/
  log "costruisco l'APK"
  "$ROOT/apk/build.sh"
  BUILT="$(apk_version)"
  [[ "$BUILT" == "$TARGET" ]] || die "l'APK costruito contiene Immich '$BUILT' invece di $TARGET"
else
  log "APK già pronto con Immich $TARGET"
fi

# --- installazione ----------------------------------------------------------
if [[ ${#SERIALS[@]} -eq 0 ]]; then
  log "nessun dispositivo indicato: per installare  $0 -s SERIALE  (seriali in 'adb devices')"
  exit 0
fi
for s in "${SERIALS[@]}"; do
  cur="$(device_version "$s")"
  if [[ "$cur" == "$TARGET" && $FORCE == 0 ]]; then
    log "$s: ha già Immich $TARGET"
    continue
  fi
  if [[ -n "$cur" ]]; then
    log "$s: aggiorno da $cur a $TARGET — il server si ferma per qualche minuto e riparte da solo"
  else
    log "$s: installo Immich $TARGET"
  fi
  "$ROOT/apk/install.sh" -s "$s"
done
