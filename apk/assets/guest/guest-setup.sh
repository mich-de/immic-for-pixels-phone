#!/bin/bash
#
# Gira DENTRO la userland Debian (proot), come root "finto". Lo lancia l'app
# (Stack.java) al primo avvio. Installa Postgres + pgvector + VectorChord, Valkey, ffmpeg,
# perl e crea gli utenti di servizio. Idempotente: si può rilanciare.
#
#   guest-setup.sh setup    installa e verifica (default)
#   guest-setup.sh check    solo verifiche
set -euo pipefail

# Versione della preparazione: l'app rilancia questo script anche sulle installazioni esistenti
# quando il numero cresce (Stack.ensureSetup). Va aumentato con ogni modifica di QUESTO script che deve arrivare lì.
# Per i .deb del pacchetto (ffmpeg, VectorChord) invece non serve: quelli installati sono annotati nella riga
# DEBS= del marcatore e l'app rifà la preparazione da sola quando un pacchetto nuovo di Immich ne porta di diversi.
#   2: ffmpeg di Jellyfin al posto di quello di Debian (tonemapx per i video HDR)
#   3: ImageMagick (Immich lo usa solo per mostrarne la versione in Amministrazione → Informazioni server:
#      senza, quel campo resta vuoto)
GUEST_LEVEL=3

APP=/opt/immich
PG_MAJOR="${PG_MAJOR:-17}"
export DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8 LC_ALL=C.UTF-8

say() { printf '\n[guest] %s\n' "$*"; }
fail() { printf '\n[guest] ERROR: %s\n' "$*" >&2; exit 1; }

check() {
  say "checks"
  local ok=1

  "$APP/node/bin/node" --version >/dev/null 2>&1 \
    && echo "  node       $("$APP/node/bin/node" --version)" \
    || { echo "  node       DOES NOT START"; ok=0; }

  # moduli nativi caricati da Node (sharp = libvips, bcrypt): se falliscono qui
  # il server non parte, meglio scoprirlo ora.
  if (cd "$APP/server" && "$APP/node/bin/node" -e "
      const s = require('sharp'); const b = require('bcrypt');
      console.log('  sharp      libvips ' + s.versions.vips + ' · bcrypt ok');
      const f = Object.keys(s.format).filter(k => s.format[k].input && s.format[k].input.buffer);
      console.log('  formati    ' + f.join(' '));
    "); then :; else echo "  native modules (sharp/bcrypt): ERROR"; ok=0; fi

  # Immich converte i video HDR (10 bit, BT.2020) con il filtro tonemapx: senza, niente miniature né transcodifica
  if command -v ffmpeg >/dev/null; then
    local ffv filters
    ffv="$(ffmpeg -version | head -1 | cut -d' ' -f3)"
    filters="$(ffmpeg -hide_banner -filters 2>/dev/null || true)"
    case "$filters" in
      *tonemapx*) echo "  ffmpeg     $ffv (tonemapx ok)" ;;
      *) echo "  ffmpeg     $ffv WITHOUT tonemapx: HDR videos would get no thumbnails"; ok=0 ;;
    esac
  else echo "  ffmpeg     MISSING"; ok=0; fi
  if command -v ffprobe >/dev/null; then echo "  ffprobe    $(ffprobe -version | head -1 | cut -d' ' -f3)"; else echo "  ffprobe    MISSING"; ok=0; fi
  if command -v perl >/dev/null;   then echo "  perl       $(perl -e 'print $^V')"; else echo "  perl       MISSING"; ok=0; fi
  # solo informativo (Amministrazione → Informazioni server): se manca, quel campo resta vuoto, il server funziona lo stesso
  if command -v magick >/dev/null; then echo "  magick     $(magick --version | head -1)"; else echo "  magick     missing (only the ImageMagick field in the server info stays empty)"; fi

  local pgbin="/usr/lib/postgresql/$PG_MAJOR/bin"
  if [ -x "$pgbin/postgres" ]; then echo "  postgres   $("$pgbin/postgres" --version | cut -d' ' -f3)"; else echo "  postgres   MISSING"; ok=0; fi
  ls "/usr/lib/postgresql/$PG_MAJOR/lib/vchord.so" >/dev/null 2>&1 && echo "  vchord     ok" || { echo "  vchord     MISSING"; ok=0; }
  ls "/usr/share/postgresql/$PG_MAJOR/extension/vector.control" >/dev/null 2>&1 && echo "  pgvector   ok" || { echo "  pgvector   MISSING"; ok=0; }
  if command -v valkey-server >/dev/null; then echo "  valkey     $(valkey-server --version | sed -n 's/.*v=\([^ ]*\).*/\1/p')"; else echo "  valkey     MISSING"; ok=0; fi

  for u in postgres valkey immich; do id "$u" >/dev/null 2>&1 || { echo "  user $u  MISSING"; ok=0; }; done

  [ "$ok" = 1 ] || fail "some checks failed (see above)"
  say "all ok"
}

setup() {
  [ -x "$APP/node/bin/node" ] || fail "missing $APP/node/bin/node: the app package is not mounted at $APP"
  ARCH="$(dpkg --print-architecture)"
  VCHORD_DEB="$(ls "$APP"/deb/postgresql-"$PG_MAJOR"-vchord_*_"$ARCH".deb 2>/dev/null | head -1 || true)"
  [ -n "$VCHORD_DEB" ] || fail "missing the VectorChord .deb for Postgres $PG_MAJOR ($ARCH) in $APP/deb"
  FFMPEG_DEB="$(ls "$APP"/deb/jellyfin-ffmpeg*_"$ARCH".deb 2>/dev/null | head -1 || true)"
  [ -n "$FFMPEG_DEB" ] || fail "missing the jellyfin-ffmpeg .deb ($ARCH) in $APP/deb: rebuild the package with pc/build-app.sh"

  say "apt/dpkg configuration for the proot environment"
  # Niente init system: impedisce ai pacchetti di provare ad avviare i servizi.
  printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
  chmod +x /usr/sbin/policy-rc.d
  # In proot l'utente _apt "finto" non riesce a leggere i file: apt scarica come root.
  cat > /etc/apt/apt.conf.d/90-immich-proot <<'EOF'
APT::Sandbox::User "root";
APT::Install-Recommends "false";
Acquire::Retries "3";
Dpkg::Options { "--force-unsafe-io"; "--force-confdef"; "--force-confold"; };
EOF
  # Il cluster Postgres lo creiamo noi (dati fuori dal rootfs): niente cluster "main" automatico.
  mkdir -p /etc/postgresql-common
  printf 'create_main_cluster = false\n' > /etc/postgresql-common/createcluster.conf

  # un'installazione interrotta (app chiusa, telefono spento) può lasciare dpkg a metà
  dpkg --configure -a

  say "apt update + upgrade"
  apt-get update
  apt-get -y upgrade

  say "packages: postgresql-$PG_MAJOR, pgvector, valkey, perl, locales..."
  apt-get -y install \
    ca-certificates curl locales tzdata perl xz-utils procps \
    "postgresql-$PG_MAJOR" "postgresql-$PG_MAJOR-pgvector" \
    valkey-server valkey-tools \
    imagemagick

  say "VectorChord (official $ARCH deb)"
  apt-get -y install "$VCHORD_DEB"

  # Lo stesso ffmpeg dell'immagine ufficiale di Immich: quello di Debian non ha tonemapx, e i video HDR
  # restano senza miniature ("Errore nel caricamento dell'immagine"). In /usr/local/bin vince nel PATH.
  say "Jellyfin ffmpeg ($(basename "$FFMPEG_DEB"))"
  apt-get -y install "$FFMPEG_DEB"
  ln -sf /usr/lib/jellyfin-ffmpeg/ffmpeg /usr/local/bin/ffmpeg
  ln -sf /usr/lib/jellyfin-ffmpeg/ffprobe /usr/local/bin/ffprobe
  # le installazioni precedenti avevano l'ffmpeg di Debian: toglierlo libera qualche centinaio di MB
  if dpkg -s ffmpeg >/dev/null 2>&1; then
    say "removing Debian's ffmpeg"
    apt-get -y purge --autoremove ffmpeg
  fi

  say "locale en_US.UTF-8 (like Immich's Postgres image)"
  sed -i 's/^# *\(en_US.UTF-8 UTF-8\)/\1/' /etc/locale.gen
  locale-gen >/dev/null

  say "service user 'immich'"
  if ! id immich >/dev/null 2>&1; then
    useradd --system --create-home --home-dir /var/lib/immich --shell /bin/bash immich
  fi

  apt-get clean
  rm -rf /var/lib/apt/lists/*

  check
  # stesso formato che calcola Stack.packDebs: nomi dei .deb del pacchetto, in ordine, separati da uno spazio
  DEBS="$(cd "$APP/deb" && ls -1 -- *.deb | LC_ALL=C sort | tr '\n' ' ' | sed 's/ $//')"
  { echo "PG_MAJOR=$PG_MAJOR"; echo "GUEST_LEVEL=$GUEST_LEVEL"; echo "DEBS=$DEBS"; date --iso-8601=seconds; } > /etc/immich-guest-ready
}

case "${1:-setup}" in
  setup) setup ;;
  check) check ;;
  *) fail "usage: $0 [setup|check]" ;;
esac
