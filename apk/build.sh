#!/usr/bin/env bash
#
# Costruisce dist/immich-server.apk senza Gradle: aapt2 + javac + d8 + zipalign + apksigner.
#
#   apk/build.sh          APK completo (rootfs Debian + pacchetto Immich dentro l'APK, ~280 MB)
#   apk/build.sh --lite   APK leggero: i due pacchetti grossi si mettono con "adb push" (vedi README)
#
# Prima serve il pacchetto Immich:  pc/build-app.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$ROOT/apk"
B="$ROOT/.build/apk"
CACHE="$ROOT/.build/cache"
DIST="$ROOT/dist"
LITE=0
[[ "${1:-}" == "--lite" ]] && LITE=1

VERSION_NAME_SET="${VERSION_NAME:-}" # se non indicato: la versione di Immich del pacchetto (vedi sotto)
VERSION_NAME="${VERSION_NAME:-1.0.0}"
VERSION_CODE="${VERSION_CODE:-1}"

log() { printf '\033[1;34m==>\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31merrore:\033[0m %s\n' "$*" >&2; exit 1; }

# --- SDK Android -----------------------------------------------------------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" ]] && command -v android >/dev/null; then
  SDK="$(android info sdk 2>/dev/null | sed -E 's/^sdk: *//' | head -1 || true)"
fi
[[ -n "$SDK" && -d "$SDK" ]] || SDK="$HOME/Android/Sdk"
[[ -d "$SDK/build-tools" ]] || die "SDK Android non trovato (imposta ANDROID_HOME)"
BT="$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)"; BT="${BT%/}"
JAR="$SDK/platforms/android-34/android.jar"
[[ -f "$JAR" ]] || JAR="$(ls -d "$SDK"/platforms/android-*/ | sort -V | tail -1)android.jar"
[[ -f "$JAR" ]] || die "manca android.jar in $SDK/platforms"
for t in javac keytool python3 ar tar xz gzip curl sha256sum; do command -v "$t" >/dev/null || die "manca '$t'"; done
log "SDK $SDK · build-tools $(basename "$BT") · $(basename "$(dirname "$JAR")")"

mkdir -p "$B" "$CACHE" "$DIST"

fetch() { # fetch URL DEST
  [[ -s "$2" ]] && return 0
  log "download $(basename "$2")"
  curl -fL --retry 3 -sS -o "$2.part" "$1"
  mv "$2.part" "$2"
}
check_sha() { # check_sha ATTESO FILE
  local got; got="$(sha256sum "$2" | cut -d' ' -f1)"
  [[ "$got" == "$1" ]] || die "checksum errato per $(basename "$2"): atteso $1, ottenuto $got"
}

# --- 1. binari nativi di proot (dai pacchetti Termux, aarch64) -------------
# proot + i suoi due .so e il loader statico. Ci servono nella cartella delle librerie native
# dell'app (l'unico posto da cui Android permette di eseguire file): quindi si chiamano lib*.so.
TERMUX=https://packages.termux.dev/apt/termux-main
declare -A URL=(
  [proot]=pool/main/p/proot/proot_5.1.107.92_aarch64.deb
  [talloc]=pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb
  [shmem]=pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb
)
declare -A SHA=(
  [proot]=1f1c983509701f6826f568482c70673ee453a9ba38c9f5fa445a472d6b7524e9
  [talloc]=ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da
  [shmem]=0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6
)
LIB="$B/lib/arm64-v8a"
rm -rf "$B/lib" "$B"/x-*
mkdir -p "$LIB"
for k in proot talloc shmem; do
  # Termux toglie dal repository le versioni vecchie: la copia in apk/vendor/ (stesso checksum) vale anche dopo
  vendored="$APK/vendor/$(basename "${URL[$k]}")"
  if [[ ! -s "$CACHE/termux-$k.deb" && -s "$vendored" ]]; then cp "$vendored" "$CACHE/termux-$k.deb"; fi
  fetch "$TERMUX/${URL[$k]}" "$CACHE/termux-$k.deb"
  check_sha "${SHA[$k]}" "$CACHE/termux-$k.deb"
  mkdir -p "$B/x-$k"
  (cd "$B/x-$k" && ar x "$CACHE/termux-$k.deb" && tar -xf data.tar.*)
done
TP="usr"
P="$B/x-proot/data/data/com.termux/files/$TP"
cp "$P/libexec/proot/loader" "$LIB/libproot-loader.so"
cp "$B/x-talloc/data/data/com.termux/files/$TP/lib/libtalloc.so.2.4.3" "$LIB/libtalloc.so"
# libandroid-shmem (memoria condivisa SysV di Android) crea dei symlink in un percorso cablato di Termux;
# se la cartella non esiste riprova all'infinito (bug della libreria) e PostgreSQL resta appeso.
# Riscriviamo il percorso (stessa lunghezza massima) verso una cartella dell'app, creata all'avvio.
PKG="$(sed -n 's/.*package="\([^"]*\)".*/\1/p' "$APK/AndroidManifest.xml" | head -1)"
python3 - "$B/x-shmem/data/data/com.termux/files/$TP/lib/libandroid-shmem.so" "$LIB/libandroid-shmem.so" "$PKG" <<'PY'
import sys
data = open(sys.argv[1], "rb").read()
old = b"/data/data/com.termux/files/usr/tmp/ashv_key_%d\x00"
new = ("/data/data/%s/t/ashv_key_%%d" % sys.argv[3]).encode() + b"\x00"
assert len(new) <= len(old), "il nome del pacchetto è troppo lungo per il percorso di libandroid-shmem (%d > %d)" % (len(new), len(old))
assert data.count(old) == 1, "percorso ashv_key non trovato (esattamente una volta) in libandroid-shmem"
open(sys.argv[2], "wb").write(data.replace(old, new.ljust(len(old), b"\x00")))
print("libandroid-shmem: chiavi in", new[:-1].decode())
PY
# Due ritocchi all'ELF di proot, entrambi a lunghezza invariata (altrimenti l'ELF si corrompe):
#  1. proot cerca libtalloc.so.2, ma nella cartella delle librerie il file deve chiamarsi libtalloc.so;
#  2. il RUNPATH punta a /data/data/com.termux/...: lo sostituiamo con $ORIGIN (la cartella di proot
#     stessa). Non ci si può affidare a LD_LIBRARY_PATH: il linker di Android lo ignora in alcuni casi.
python3 - "$P/bin/proot" "$LIB/libproot.so" <<'PY'
import sys
data = open(sys.argv[1], "rb").read()
def swap(data, old, new):
    assert len(old) == len(new), "la sostituzione deve mantenere la lunghezza"
    assert data.count(old) == 1, "%r non trovato (esattamente una volta) in proot" % old
    return data.replace(old, new)
data = swap(data, b"libtalloc.so.2\x00", b"libtalloc.so\x00\x00\x00")
runpath = b"/data/data/com.termux/files/usr/lib\x00"
data = swap(data, runpath, b"$ORIGIN\x00".ljust(len(runpath), b"\x00"))
open(sys.argv[2], "wb").write(data)
PY
chmod 755 "$LIB"/*
if command -v readelf >/dev/null; then
  log "dipendenze di libproot.so: $(readelf -d "$LIB/libproot.so" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p' | tr '\n' ' ')"
fi

# --- 2. pacchetti grossi ---------------------------------------------------
ROOTFS_URL=https://github.com/termux/proot-distro/releases/download/v4.29.0/debian-trixie-aarch64-pd-v4.29.0.tar.xz
ROOTFS_SHA=3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918
ASSETS=("guest/guest-setup.sh=$APK/assets/guest/guest-setup.sh")
if [[ $LITE == 0 ]]; then
  if [[ ! -s "$CACHE/rootfs.tar.gz" ]]; then
    fetch "$ROOTFS_URL" "$CACHE/rootfs.tar.xz"
    check_sha "$ROOTFS_SHA" "$CACHE/rootfs.tar.xz"
    log "ricomprimo il rootfs in .tar.gz (Android ha solo gzip di serie)"
    xz -dc "$CACHE/rootfs.tar.xz" | gzip -6 > "$CACHE/rootfs.tar.gz"
  fi
  PACK="$(ls -t "$DIST"/immich-android-*-arm64.tar.gz 2>/dev/null | head -1 || true)"
  [[ -n "$PACK" ]] || die "manca il pacchetto Immich: lancia prima pc/build-app.sh"
  log "pacchetto Immich: $(basename "$PACK")"
  ASSETS+=("rootfs.tar.gz=$CACHE/rootfs.tar.gz" "immich-pack.tar.gz=$PACK")
  # nome di versione dell'APK = versione di Immich dentro (si vede in Impostazioni → App)
  if [[ -z "${VERSION_NAME_SET:-}" ]]; then
    v="$(tar -xzOf "$PACK" --wildcards '*/VERSION' 2>/dev/null | sed -n 's/^immich=v//p' | head -1 || true)"
    [[ -n "$v" ]] && VERSION_NAME="$v"
  fi
fi

# --- 3. risorse, codice, pacchetto ----------------------------------------
log "risorse e manifest"
rm -f "$B/res.zip" "$B/base.apk"
"$BT/aapt2" compile --dir "$APK/res" -o "$B/res.zip"
"$BT/aapt2" link -o "$B/base.apk" -I "$JAR" --manifest "$APK/AndroidManifest.xml" -R "$B/res.zip" \
  --min-sdk-version 26 --target-sdk-version 28 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"

log "compilazione Java"
rm -rf "$B/classes" "$B/dex"
mkdir -p "$B/classes" "$B/dex"
javac --release 17 -Xlint:-options -encoding UTF-8 -cp "$JAR" -d "$B/classes" $(find "$APK/src" -name '*.java')
"$BT/d8" --release --min-api 26 --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')

log "assemblo l'APK"
ARGS=()
for a in "${ASSETS[@]}"; do ARGS+=(--asset "$a"); done
python3 "$APK/pack.py" --base "$B/base.apk" --dex "$B/dex/classes.dex" --lib "$B/lib" --out "$B/unsigned.apk" "${ARGS[@]}"
rm -f "$B/aligned.apk"
"$BT/zipalign" -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"

# --- 4. firma -------------------------------------------------------------
# Con la stessa chiave gli aggiornamenti si installano sopra la versione vecchia SENZA perdere i dati.
KS="$APK/immich-server.keystore"
# su GitHub Actions (CI=true) la chiave arriva dal segreto KEYSTORE_BASE64: crearne una nuova darebbe un APK che non
# si installa sopra quello esistente (e disinstallare cancellerebbe foto e database)
if [[ ! -f "$KS" && -n "${CI:-}" ]]; then
  die "manca la chiave di firma $KS: su GitHub va nel segreto KEYSTORE_BASE64 (vedi README)"
fi
if [[ ! -f "$KS" ]]; then
  log "creo la chiave di firma $KS (conservala: serve per gli aggiornamenti)"
  keytool -genkeypair -keystore "$KS" -alias immich -keyalg RSA -keysize 2048 -validity 36500 \
    -storepass immich-server -keypass immich-server -dname "CN=Immich Server, O=nasonmobile" >/dev/null 2>&1
fi
OUT="$DIST/immich-server.apk"
[[ $LITE == 1 ]] && OUT="$DIST/immich-server-lite.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:immich-server --key-pass pass:immich-server \
  --v1-signing-enabled false --v2-signing-enabled true --v4-signing-enabled false --min-sdk-version 26 --out "$OUT" "$B/aligned.apk"
"$BT/apksigner" verify --min-sdk-version 26 "$OUT"

log "fatto: $OUT ($(du -h "$OUT" | cut -f1))"
"$BT/aapt2" dump badging "$OUT" | grep -E "^(package|sdkVersion|targetSdkVersion|native-code|application-label:)" >&2 || true
