#!/usr/bin/env bash
#
# Compila Immich dai sorgenti, sul PC, e produce il pacchetto per il telefono
# (linux-arm64 / glibc). Nessun Docker: servono solo git, curl, tar, python3;
# Node e pnpm vengono scaricati dentro .build/.
#
#   pc/build-app.sh             costruisce dist/immich-android-<versione>-arm64.tar.gz dell'ultima versione di Immich
#   pc/build-app.sh --versions  stampa solo le versioni che userebbe (Immich e dipendenze) ed esce
#   pc/build-app.sh --clean     cancella .build/ e dist/
#
# Senza variabili segue da solo ogni nuova versione di Immich: prende l'ultima pubblicata e ricava le dipendenze
# da quello che usa la sua immagine Docker ufficiale per QUELLA versione (vedi "0. versioni"), checksum compresi.
# Variabili (facoltative, per forzare un valore): IMMICH_VERSION  NODE_VERSION  PG_MAJOR  VCHORD_VERSION
# VCHORD_SHA256  FFMPEG_VERSION  FFMPEG_SHA256  GITHUB_TOKEN (solo per il limite di richieste all'API di GitHub)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PG_MAJOR="${PG_MAJOR:-17}" # Postgres di Debian trixie
WORK="$ROOT/.build"
CACHE="$WORK/cache"
OUT="$ROOT/dist"

log() { printf '\033[1;34m==>\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31merrore:\033[0m %s\n' "$*" >&2; exit 1; }

case "${1:-}" in
  --clean) rm -rf "$WORK" "$OUT"; log "pulito"; exit 0 ;;
  --versions | "") ;;
  *) die "opzione sconosciuta: $1 (uso: $0 [--versions|--clean])" ;;
esac

for t in git curl tar gzip xz python3 sha256sum awk; do
  command -v "$t" >/dev/null || die "manca il comando '$t'"
done
mkdir -p "$CACHE" "$OUT"

# --- 0. versioni -------------------------------------------------------------
# Tutto discende dalla versione di Immich, esattamente come nella sua immagine Docker ufficiale:
#   Immich <tag>, server/Dockerfile           → immagine base (immich-app/base-images, tag AAAAMMGGhhmm)
#   immagine base, server/Dockerfile           → Node (quello di produzione, NON quello di sviluppo di mise.toml:
#                                                la 3.2.4 corregge così la perdita di memoria della 3.2.2)
#   immagine base, server/packages/ffmpeg.json → jellyfin-ffmpeg + sha256 del .deb arm64
#   Immich <tag>, docker/docker-compose.yml    → VectorChord (tag dell'immagine postgres); sha256 del .deb dal
#                                                rilascio su GitHub
gh_api() { # gh_api PERCORSO  → JSON dell'API di GitHub
  local auth=()
  [[ -n "${GITHUB_TOKEN:-}" ]] && auth=(-H "Authorization: Bearer $GITHUB_TOKEN")
  curl -fsSL --retry 3 "${auth[@]}" -H "Accept: application/vnd.github+json" "https://api.github.com/$1"
}
raw() { # raw REPO REF FILE  → contenuto del file a quella versione
  curl -fsSL --retry 3 "https://raw.githubusercontent.com/$1/$2/$3"
}

if [[ -z "${IMMICH_VERSION:-}" ]]; then
  IMMICH_VERSION="$(gh_api repos/immich-app/immich/releases/latest \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["tag_name"])')" \
    || die "non riesco a sapere da GitHub l'ultima versione di Immich: indicala con IMMICH_VERSION=vX.Y.Z"
fi
[[ "$IMMICH_VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "versione di Immich non valida: '$IMMICH_VERSION' (es. v3.2.4)"

dockerfile="$(raw immich-app/immich "$IMMICH_VERSION" server/Dockerfile)" \
  || die "non trovo server/Dockerfile di Immich $IMMICH_VERSION (la versione esiste?)"
BASE_TAG="$(sed -n 's|^FROM ghcr\.io/immich-app/base-server-prod:\([0-9][0-9]*\).*|\1|p' <<<"$dockerfile" | head -1)"
[[ -n "$BASE_TAG" ]] || die "non trovo l'immagine base (base-server-prod) nel server/Dockerfile di Immich $IMMICH_VERSION"

if [[ -z "${NODE_VERSION:-}" ]]; then
  based="$(raw immich-app/base-images "$BASE_TAG" server/Dockerfile)" || die "non trovo l'immagine base $BASE_TAG"
  NODE_VERSION="$(sed -n 's/^FROM node:\([0-9][0-9.]*\)-.* AS prod$/\1/p' <<<"$based" | head -1)"
  [[ -n "$NODE_VERSION" ]] || NODE_VERSION="$(sed -n 's/^FROM node:\([0-9][0-9.]*\)-.*/\1/p' <<<"$based" | head -1)"
  [[ -n "$NODE_VERSION" ]] || die "non trovo la versione di Node nell'immagine base $BASE_TAG: indicala con NODE_VERSION"
fi

if [[ -z "${FFMPEG_VERSION:-}" || -z "${FFMPEG_SHA256:-}" ]]; then
  ff="$(raw immich-app/base-images "$BASE_TAG" server/packages/ffmpeg.json \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["version"], d["sha256"]["arm64"])')" \
    || die "non riesco a leggere server/packages/ffmpeg.json dell'immagine base $BASE_TAG"
  FFMPEG_VERSION="${ff% *}"
  FFMPEG_SHA256="${ff#* }"
fi

if [[ -z "${VCHORD_VERSION:-}" ]]; then
  VCHORD_VERSION="$(raw immich-app/immich "$IMMICH_VERSION" docker/docker-compose.yml \
    | sed -n 's|.*immich-app/postgres:[^ ]*vectorchord\([0-9][0-9.]*[0-9]\).*|\1|p' | head -1)"
  [[ -n "$VCHORD_VERSION" ]] || die "non trovo VectorChord nel docker-compose.yml di Immich $IMMICH_VERSION: indicalo con VCHORD_VERSION"
fi
VCHORD_DEB="postgresql-$PG_MAJOR-vchord_${VCHORD_VERSION}-1_arm64.deb"
if [[ -z "${VCHORD_SHA256:-}" ]]; then
  VCHORD_SHA256="$(gh_api "repos/tensorchord/VectorChord/releases/tags/$VCHORD_VERSION" | python3 -c '
import json, sys
for a in json.load(sys.stdin).get("assets", []):
    if a["name"] == sys.argv[1] and (a.get("digest") or "").startswith("sha256:"):
        print(a["digest"][7:])
' "$VCHORD_DEB")" || die "non riesco a leggere il rilascio $VCHORD_VERSION di VectorChord su GitHub"
  [[ -n "$VCHORD_SHA256" ]] || die "il rilascio $VCHORD_VERSION di VectorChord non ha $VCHORD_DEB (o il suo sha256)"
fi

SRC="$WORK/src-$IMMICH_VERSION"
log "Immich $IMMICH_VERSION · immagine base $BASE_TAG · node $NODE_VERSION · jellyfin-ffmpeg $FFMPEG_VERSION · VectorChord $VCHORD_VERSION (Postgres $PG_MAJOR)"
if [[ "${1:-}" == "--versions" ]]; then
  printf 'immich=%s\nbase_image=%s\nnode=%s\nffmpeg=%s\nffmpeg_sha256=%s\nvchord=%s\nvchord_sha256=%s\npg_major=%s\n' \
    "$IMMICH_VERSION" "$BASE_TAG" "$NODE_VERSION" "$FFMPEG_VERSION" "$FFMPEG_SHA256" "$VCHORD_VERSION" "$VCHORD_SHA256" "$PG_MAJOR"
  exit 0
fi

fetch() { # fetch URL DEST  (riprende/salta se esiste già)
  local url="$1" dest="$2"
  if [[ ! -s "$dest" ]]; then
    log "download $(basename "$dest")"
    curl -fL --retry 3 --retry-delay 2 -sS -o "$dest.part" "$url"
    mv "$dest.part" "$dest"
  fi
}

sha_check() { # sha_check ATTESO FILE
  local got
  got="$(sha256sum "$2" | cut -d' ' -f1)"
  [[ -n "$1" && "$got" == "$1" ]] || die "checksum errato per $(basename "$2") (atteso '$1', ottenuto '$got')"
}

# --- 1. sorgenti -------------------------------------------------------------
log "sorgenti Immich $IMMICH_VERSION"
if [[ ! -d "$SRC/.git" ]]; then
  git clone --quiet --depth 1 --filter=blob:none --sparse --branch "$IMMICH_VERSION" \
    https://github.com/immich-app/immich.git "$SRC"
  git -C "$SRC" sparse-checkout set server web i18n packages
fi

# Le versioni degli strumenti sono quelle dichiarate da Immich stessa (mise.toml), tranne Node (vedi in cima).
mise_tool() {
  awk -v k="$1" '
    /^\[/ { in_tools = ($0 == "[tools]"); next }
    in_tools { l = $0; gsub(/"/, "", l); n = split(l, a, / *= */); if (n >= 2 && a[1] == k) { print a[2]; exit } }
  ' "$SRC/mise.toml"
}
PNPM_VERSION="${PNPM_VERSION:-$(mise_tool pnpm)}"
EXTISM_JS_VERSION="${EXTISM_JS_VERSION:-$(mise_tool github:extism/js-pdk)}"
BINARYEN_VERSION="${BINARYEN_VERSION:-$(mise_tool github:webassembly/binaryen)}"
[[ -n "$NODE_VERSION" && -n "$PNPM_VERSION" && -n "$EXTISM_JS_VERSION" && -n "$BINARYEN_VERSION" ]] \
  || die "non riesco a leggere le versioni degli strumenti da mise.toml"
log "node $NODE_VERSION · pnpm $PNPM_VERSION · extism-js $EXTISM_JS_VERSION · binaryen $BINARYEN_VERSION"

# --- 2. strumenti ------------------------------------------------------------
node_tarball() { # node_tarball x64|arm64  -> stampa il percorso del tar.xz verificato
  local f="node-v$NODE_VERSION-linux-$1.tar.xz"
  fetch "https://nodejs.org/dist/v$NODE_VERSION/$f" "$CACHE/$f"
  fetch "https://nodejs.org/dist/v$NODE_VERSION/SHASUMS256.txt" "$CACHE/node-$NODE_VERSION.SHASUMS256.txt"
  sha_check "$(awk -v f="$f" '$2 == f { print $1 }' "$CACHE/node-$NODE_VERSION.SHASUMS256.txt")" "$CACHE/$f"
  echo "$CACHE/$f"
}

NODE_BUILD="$CACHE/node-v$NODE_VERSION-linux-x64"
[[ -x "$NODE_BUILD/bin/node" ]] || tar -xJf "$(node_tarball x64)" -C "$CACHE"

PNPM_DIR="$CACHE/pnpm-$PNPM_VERSION"
if [[ ! -x "$PNPM_DIR/node_modules/.bin/pnpm" ]]; then
  log "installo pnpm $PNPM_VERSION"
  PATH="$NODE_BUILD/bin:$PATH" npm install --prefix "$PNPM_DIR" --no-audit --no-fund --loglevel=error "pnpm@$PNPM_VERSION"
fi

TOOLS="$CACHE/tools"
mkdir -p "$TOOLS/bin"
if [[ ! -x "$TOOLS/bin/extism-js" ]]; then
  f="extism-js-x86_64-linux-$EXTISM_JS_VERSION.gz"
  base="https://github.com/extism/js-pdk/releases/download/$EXTISM_JS_VERSION"
  fetch "$base/$f" "$CACHE/$f"
  fetch "$base/$f.sha256" "$CACHE/$f.sha256"
  sha_check "$(cut -d' ' -f1 "$CACHE/$f.sha256")" "$CACHE/$f"
  gzip -dc "$CACHE/$f" > "$TOOLS/bin/extism-js"
  chmod +x "$TOOLS/bin/extism-js"
fi
if [[ ! -x "$TOOLS/binaryen/bin/wasm-opt" ]]; then
  f="binaryen-$BINARYEN_VERSION-x86_64-linux.tar.gz"
  base="https://github.com/WebAssembly/binaryen/releases/download/$BINARYEN_VERSION"
  fetch "$base/$f" "$CACHE/$f"
  fetch "$base/$f.sha256" "$CACHE/$f.sha256"
  sha_check "$(cut -d' ' -f1 "$CACHE/$f.sha256")" "$CACHE/$f"
  mkdir -p "$TOOLS/binaryen"
  tar -xzf "$CACHE/$f" -C "$TOOLS/binaryen" --strip-components=1
fi

# pnpm della versione giusta in testa al PATH: gli script del monorepo richiamano "pnpm".
export PATH="$PNPM_DIR/node_modules/.bin:$NODE_BUILD/bin:$PATH"
export CI=1 COREPACK_ENABLE_DOWNLOAD_PROMPT=0

# --- 3. build (stessi passi del Dockerfile ufficiale) -----------------------
cd "$SRC"
log "sdk, plugin-sdk, server"
pnpm --filter @immich/sdk install --frozen-lockfile
pnpm --filter @immich/sdk build
pnpm --filter @immich/plugin-sdk install --frozen-lockfile
pnpm --filter @immich/plugin-sdk build
pnpm --filter immich install --frozen-lockfile
pnpm --filter immich build

log "web (interfaccia)"
pnpm --filter immich-web install --frozen-lockfile
NODE_OPTIONS="--max-old-space-size=3072" pnpm --filter immich-web build

log "plugin core (WASM)"
pnpm --filter @immich/plugin-core install --frozen-lockfile
PATH="$TOOLS/binaryen/bin:$TOOLS/bin:$PATH" pnpm --filter @immich/plugin-core build

# --- 4. dipendenze di produzione per il telefono (linux-arm64, glibc) -------
# pnpm sceglie i binari opzionali (sharp, bcrypt...) in base a supportedArchitectures:
# così otteniamo quelli arm64 senza emulazione e senza eseguire script di install.
log "server: dipendenze di produzione linux-arm64/glibc"
cp pnpm-workspace.yaml "$WORK/pnpm-workspace.yaml.orig"
restore_ws() { cp "$WORK/pnpm-workspace.yaml.orig" "$SRC/pnpm-workspace.yaml" 2>/dev/null || true; }
trap restore_ws EXIT
cat >> pnpm-workspace.yaml <<'EOF'
supportedArchitectures:
  os: [linux]
  cpu: [arm64]
  libc: [glibc]
EOF
rm -rf "$WORK/deploy"
pnpm --filter immich --prod deploy --ignore-scripts "$WORK/deploy/server"
restore_ws

D="$WORK/deploy/server"
[[ -f "$D/dist/main.js" ]] || die "dist/main.js mancante dopo il deploy"
ls "$D/node_modules/.pnpm" | grep -q '^@img+sharp-linux-arm64@' || die "manca il binario sharp per linux-arm64"
if ls "$D/node_modules/.pnpm" | grep -q -E '^@img\+sharp(-libvips)?-linux-x64@'; then
  die "trovati binari x64 nel pacchetto arm64"
fi
if command -v file >/dev/null; then
  wrong="$(find "$D/node_modules" -name '*.node' -type f -print0 2>/dev/null | xargs -0 -r file | grep -v -E 'aarch64|ARM aarch64' || true)"
  [[ -z "$wrong" ]] || { log "ATTENZIONE: moduli nativi non arm64:"; echo "$wrong" >&2; }
fi

# --- 5. assemblaggio del pacchetto ------------------------------------------
NAME="immich-android-$IMMICH_VERSION-arm64"
STAGE="$WORK/stage/$NAME"
rm -rf "$WORK/stage"
mkdir -p "$STAGE"/{build/plugins/immich-plugin-core,build/geodata,node/bin,deb}

log "assemblo $NAME"
mv "$D" "$STAGE/server"
cp -a "$SRC/web/build" "$STAGE/build/www"
cp -a "$SRC/packages/plugin-core/dist" "$STAGE/build/plugins/immich-plugin-core/dist"
cp -a "$SRC/packages/plugin-core/manifest.json" "$STAGE/build/plugins/immich-plugin-core/"

# dati geografici (reverse geocoding), gli stessi che l'immagine ufficiale scarica al build
G="$STAGE/build/geodata"
gd="https://download.geonames.org/export/dump"
fetch "$gd/cities500.zip" "$CACHE/cities500.zip"
for f in admin1CodesASCII.txt admin2Codes.txt countryInfo.txt; do
  fetch "$gd/$f" "$CACHE/$f"
  cp "$CACHE/$f" "$G/$f"
done
python3 - "$CACHE/cities500.zip" "$G" <<'PY'
import sys, zipfile
zipfile.ZipFile(sys.argv[1]).extract("cities500.txt", sys.argv[2])
PY
fetch "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/v5.1.2/geojson/ne_10m_admin_0_countries.geojson" \
  "$CACHE/ne_10m_admin_0_countries.geojson"
cp "$CACHE/ne_10m_admin_0_countries.geojson" "$G/"
date --iso-8601=seconds | tr -d '\n' > "$G/geodata-date.txt"

# Node arm64 (solo il binario)
tmp="$(mktemp -d)"
tar -xJf "$(node_tarball arm64)" -C "$tmp" --strip-components=1 \
  "node-v$NODE_VERSION-linux-arm64/bin/node" "node-v$NODE_VERSION-linux-arm64/LICENSE"
mv "$tmp/bin/node" "$STAGE/node/bin/node"
mv "$tmp/LICENSE" "$STAGE/node/LICENSE"
rm -rf "$tmp"

# VectorChord per Postgres $PG_MAJOR (arm64), dal rilascio ufficiale
fetch "https://github.com/tensorchord/VectorChord/releases/download/$VCHORD_VERSION/$VCHORD_DEB" "$CACHE/$VCHORD_DEB"
sha_check "$VCHORD_SHA256" "$CACHE/$VCHORD_DEB"
cp "$CACHE/$VCHORD_DEB" "$STAGE/deb/"

# ffmpeg di Jellyfin per Debian trixie (arm64), come nell'immagine ufficiale: Immich usa filtri che
# l'ffmpeg di Debian non ha (tonemapx per i video HDR). Lo installa guest-setup.sh.
ffdeb="jellyfin-ffmpeg${FFMPEG_VERSION%%.*}_$FFMPEG_VERSION-trixie_arm64.deb"
fetch "https://github.com/jellyfin/jellyfin-ffmpeg/releases/download/v$FFMPEG_VERSION/$ffdeb" "$CACHE/$ffdeb"
sha_check "$FFMPEG_SHA256" "$CACHE/$ffdeb"
cp "$CACHE/$ffdeb" "$STAGE/deb/"

cat > "$STAGE/VERSION" <<EOF
immich=$IMMICH_VERSION
base_image=$BASE_TAG
node=$NODE_VERSION
pg_major=$PG_MAJOR
vchord=$VCHORD_VERSION
vchord_deb_sha256=$VCHORD_SHA256
ffmpeg=jellyfin-ffmpeg $FFMPEG_VERSION
built=$(date --iso-8601=seconds)
EOF

tar --owner=0 --group=0 --numeric-owner -C "$WORK/stage" -czf "$OUT/$NAME.tar.gz" "$NAME"
(cd "$OUT" && sha256sum "$NAME.tar.gz" > "$NAME.tar.gz.sha256")

log "fatto: $OUT/$NAME.tar.gz ($(du -h "$OUT/$NAME.tar.gz" | cut -f1))"
log "prossimo passo: apk/build.sh"
