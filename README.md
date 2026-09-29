# Immich Server for Android

**English** · [Italiano](README.it.md)

An APK that runs **the [Immich](https://immich.app) server** (v3.2.4) directly on an Android phone: a Pixel 5 becomes
your photo and video server. No Docker, no Termux: install the APK, press a button, and a few minutes later the server
answers on your home network.

Tested on a **Pixel 5 (Android 14, arm64)**. Unofficial project, not affiliated with Immich. The app's own interface is
in Italian: its buttons are quoted below as they appear, with a translation.

Ready-made APKs for every Immich version: [Releases](https://github.com/mich-de/immic-for-pixels-phone/releases)
(built and published automatically by a GitHub workflow, see [Automatic releases](#automatic-releases-on-github)).
To just install one, without building anything: [Quick start](#quick-start-just-install-it).

```
┌──────────────────────── "Immich Server" APK (Java, ~290 MB) ─────────────────────────┐
│  app: foreground service · wake locks · autostart · logs · diagnostics               │
│  proot (Termux binaries) ── runs a Debian 13 (arm64) userland without root           │
│      ├─ PostgreSQL 17 + pgvector + VectorChord   (data: files/immich/postgres)       │
│      ├─ Valkey 8                                                                     │
│      └─ Node 24 + Immich v3.2.4 (server, web, plugins) + jellyfin-ffmpeg + exiftool  │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

Immich doesn't officially support Android as a server platform: here it is built from source and installed "ad hoc"
(see [How it works](#how-it-works)).

## Quick start: just install it

Nothing to build: every Immich version is already built in
[Releases](https://github.com/mich-de/immic-for-pixels-phone/releases). No PC is needed, except for one setting on
Android 12–13 (step 3).

1. **On the phone that will be the server** (arm64, Android 8+, at least 6 GB of RAM and several GB free): open the
   [latest release](https://github.com/mich-de/immic-for-pixels-phone/releases/latest), download `immich-server.apk`
   (~290 MB) and open it. Let the browser install apps when Android asks, and choose *Install anyway* if Play Protect
   says it doesn't know the app.
2. Open **Immich Server** and press **Installa e avvia** (Install and start). The first time it needs Internet and
   10–15 minutes: keep the phone on and plugged in. When the status reads **In esecuzione** (Running), the app shows
   the server address, for example `http://192.168.1.20:2283`.
3. So that Android doesn't stop the server, in the same screen:
   - **Escludi dal risparmio batteria** (Exclude from battery optimization) → allow;
   - **Restrizioni sui processi figli** (Child process restrictions): on Android 14 and later turn on *Settings →
     System → Developer options → Disable child process restrictions* (to show *Developer options*, tap *Build number*
     7 times in *About phone*); on Android 12–13 it needs a PC with adb: the button shows the commands and copies them;
   - tick *Avvia il server all'accensione del telefono* (Start the server when the phone boots);
   - on Samsung, Xiaomi and similar phones also let the app run in the background (Samsung: *Never sleeping apps*;
     Xiaomi: *Autostart* and battery saver *No restrictions*).
4. From any device on the same Wi-Fi open that address in a browser and create the admin account.
5. On your other phones install the official **Immich** app (Play Store, F-Droid or GitHub), enter the same address as
   *Server URL*, log in and turn on backup.

Good to know:
- reserve the phone's IP in your router (static DHCP), otherwise the address can change; outside home you need a VPN
  (see [Reaching it over the network](#reaching-it-over-the-network));
- keep the server phone on Wi-Fi and charging (see [Keeping it running](#keeping-it-running));
- **updating**: download the new release's `immich-server.apk` and install it over the old one — photos and database
  stay, and the server restarts by itself;
- **never uninstall the app**: uninstalling deletes all photos and the database (see [Data and backups](#data-and-backups)).

## Requirements

- An **arm64** phone with Android 8+ (tested on Android 14), at least 6 GB of RAM, ~5 GB free plus room for the photos.
- Internet on the first start (the setup downloads Debian packages, about 500 MB).
- To build the APK yourself: a Linux PC with `git curl tar xz python3`, a JDK and the Android SDK (build-tools,
  `platforms;android-34`). No Docker, no Gradle. Or download a ready APK from Releases.

## Building

```sh
pc/build-app.sh        # builds the latest Immich for arm64 → dist/immich-android-<version>-arm64.tar.gz (~230 MB)
apk/build.sh           # assembles and signs the APK        → dist/immich-server.apk (~290 MB)
```

`pc/build-app.sh` downloads Node/pnpm (into `.build/`), builds server, web and plugins like the official Dockerfile, and
fetches the native dependencies (sharp, bcrypt…) directly for arm64: no emulation. It also downloads Jellyfin's ffmpeg
for Debian arm64, the same .deb as the official image (version and checksum taken from it, see
[Updating](#updating-immich)). `apk/build.sh --lite` produces a ~400 KB APK without the two large packages (see
[Updating](#updating-immich)).

The APK is signed with `apk/immich-server.keystore`, created on the first build: **keep it** — an update only installs
over the old version (without losing data) when it is signed with the same key.

## Installing

Connect the phone (USB or *Wireless debugging*) and:

```sh
apk/install.sh                # installs and prepares Android (see below)
```

(or download `immich-server.apk` from [Releases](https://github.com/mich-de/immic-for-pixels-phone/releases) directly
on the phone; then do the Android settings below from the app's buttons).

On the phone press **Installa e avvia** (Install and start). The first time it extracts Debian and Immich (~40 s),
installs PostgreSQL, Valkey and ffmpeg (7–12 min on a Pixel 5) and creates the database; then the status becomes
**In esecuzione** (Running) and the app shows the address, for example `http://192.168.1.20:2283`. Open it, create the
admin user, and in the Immich app on your phone or PC use the same address as the "Server URL".

Later starts take about 40 seconds.

`apk/install.sh` does four things, all reversible (the undo commands are in the script's header): it installs the APK,
grants the app `WRITE_SECURE_SETTINGS`, turns off Android 12+'s *child process restrictions* (without this the system
kills PostgreSQL and the other processes at random) and exempts the app from battery optimization. Note: the command
that keeps Android from restoring the limit (`device_config set_sync_disabled_for_tests persistent`) also pauses remote
updates of other system flags; `--no-tweaks` skips all of this and you can do the same from the app's buttons. To do it
by hand: `adb install dist/immich-server.apk`, then *Developer options → Disable child process restrictions* and
*Settings → Apps → Immich Server → Battery → Unrestricted* (the app has buttons for both).

## Reaching it over the network

- **At home**: yes. Immich listens on all interfaces (`0.0.0.0:2283`); PostgreSQL and Valkey only on `127.0.0.1`.
  From any device on the Wi-Fi: `http://<phone-ip>:2283`. The IP is the one the app shows. Reserve it in your router
  (static DHCP), otherwise it can change — and the Immich app then says *Unable to check app or server version*.
- **Away from home**: use a VPN (Tailscale, WireGuard) and reach the phone through its VPN address. Don't open port 2283
  to the Internet: the server speaks plain HTTP and the phone isn't meant to be exposed.
- The phone must stay on, on the Wi-Fi, and preferably charging (see below).

## Keeping it running

- The app keeps a **foreground service** (persistent "Immich Server" notification) with CPU and Wi-Fi wake locks: don't
  close it with "Force stop".
- Tick *Avvia il server all'accensione del telefono* (Start the server when the phone boots) to restart automatically
  after a reboot. After an **app update** the server restarts by itself (if it was running) in about a minute; if the
  update changes the programs of the Debian system (for example ffmpeg), the first start updates them and takes a few
  minutes and Internet.
- A phone always charging at 100% wears out its battery. Android doesn't give a normal app (without root) control over
  charging — no app, this one included, can switch the charger on or off by itself — but the app's *Batteria* (Battery)
  section can remind you: enable *Avvisami per non tenerlo sempre in carica al 100%* (Remind me not to keep it at 100%),
  choose the percentage to unplug at (default 80%) and to plug back in at (default 30%), and unplug/replug by hand when
  the notification arrives. Alternatives: a smart plug, or an 80% charge limit if your ROM has one. Keep the phone
  somewhere cool anyway: idle, the server uses little power, but heavy work (thumbnails, video transcoding) heats it up.
- **Machine learning** is turned off on the first start: on this phone it would be too slow and the service isn't
  included. Without it there is no smart search, face recognition or duplicate detection (*Utilities → Review
  duplicates* finds nothing; *Review large files* works). You can point Immich to an ML server on another PC from its
  settings.
- **Free space**: below 2 GB the app shows it in yellow in its menu and in the server notification ("spazio in
  esaurimento", running low); below 500 MB in red ("SPAZIO QUASI FINITO", almost full) — at that point Postgres and the
  copies can stall. Free some space (for example from the gallery copies, see below) before it runs out.

## Where the photos are, and Google Photos

The photos and videos you upload to Immich live in the **app's private storage**:
`files/immich/library/upload/<user-id>/<xx>/<yy>/<uuid>.jpg` (Immich renames the originals with a UUID; the real name
is in the database). Gallery, Files and Google Photos **can't see them**. To view them use Immich (web or app); to copy
them to a PC see [Data and backups](#data-and-backups).

To get them into **Google Photos through the Pixel 5**, the app has a *Galleria e Google Foto* (Gallery and Google
Photos) section: it copies the originals into the phone's gallery, folder `DCIM/Immich`, and from there the Google
Photos app can back them up. The originals are copied byte for byte, so the capture date stays the one in the metadata
(EXIF, or the video's creation date); for files without metadata (screenshots, PNG) Android uses the date of the copy.

1. Press *Immagine di prova* (Test image): an «Immich» folder with a test image appears in the gallery.
2. In Google Photos: *Settings → Backup → Back up device folders* and turn on «Immich» (once).
3. Tick *Copia automaticamente le foto nuove nella galleria* (Copy new photos to the gallery automatically, every 5
   minutes), or use *Copia ora* (Copy now).

Notes: only the admin's photos are copied (the other users' only if you tick that option, so as not to mix their
albums into your Google Photos) and never Immich's «locked» ones. On a Pixel 5 Google Photos' free unlimited storage is
in *Storage saver* quality (photos reduced to 16 MP, videos to 1080p), not original quality. **Don't turn on backup of
the «Immich» folder in the Immich app**: it would send those files back to the server.

### No second copy: the gallery copy is temporary

Google Photos can only upload files from shared storage, so one copy on the phone is unavoidable, but it doesn't have
to stay. The app **deletes it by itself** after the chosen time (*Elimina la copia in galleria dopo*, delete the
gallery copy after: never / 3 / 5 / **7** / 30 days). The original stays in Immich and, once Google Photos has uploaded
it, in the cloud too. So the lasting cost on the phone is only Immich: the gallery copies are a short queue of a few
days.

- **Cap** (*Copie in galleria in attesa di backup: al massimo*, gallery copies waiting for backup, at most: none / 5 /
  **10** / 20 / 50 GB): avoids suddenly doubling a large library. When the cap is reached the copy pauses, and resumes
  when old copies are deleted — keep an eye on the free space anyway.
- Google Photos' **Free up space** deletes right away the copies it has already uploaded; the app notices and resumes
  copying.
- *Elimina ora le copie in galleria* (Delete the gallery copies now) removes them all (the photos stay in Immich);
  *Elimina e ricopia da capo* (Delete and copy again) also restarts the copy from all the photos.
- Google Photos doesn't tell when it has finished uploading, so the expiry is a time, not an event. If Google Photos'
  backup stays off past the expiry, the copy is deleted before being uploaded: raise the days or check the backup.
- Photos taken with the Pixel and uploaded to Immich by its own app are already in the gallery and in Google Photos: no
  copies needed (Google Photos recognizes duplicates by content anyway).

### Not keeping the photo in Immich either (optional, permanent)

If you want the phone to keep *no* long-term copy — just a pass-through to Google Photos — the app can also delete the
original from Immich some days after it was copied to the gallery. **This is permanent**: from then on the only copy
left is the (compressed) one Google Photos uploaded, not the original.

To turn it on, in the same section of the app:
1. In Immich: *Account Settings → API Keys → New API Key*, with the `asset.delete` permission. Copy it.
2. Paste it into *Chiave API di Immich* (Immich API key) and press *Salva la chiave* (Save the key); *Prova la chiave*
   (Test the key) checks that it works without deleting anything.
3. Choose *Elimina l'originale da Immich dopo* (Delete the original from Immich after): **Mai** (Never, the default),
   2, 3, 5 or 7 days.

It only deletes assets that were already copied to the gallery successfully (it never touches a file the app couldn't
copy, for example a format Android doesn't support); it uses Immich's API, not direct database access, so Immich itself
takes care of thumbnails and the job queue. The endpoints were checked to behave as Immich's source code says (the
permission required, `force` also deleting the file on disk); not yet verified end to end with a real key.

**If the photos come from another phone with the Immich app** (for example your main phone): that app uploads every
photo it doesn't find on the server. Once the original is deleted from Immich, a photo still on that phone gets
**uploaded again**, and the cycle repeats every N days. So free that phone first: Immich app → *Settings → Free Up
Space* (*Select cutoff date*, *Custom date*) moves to the device trash only the photos that are already on the server
(empty the gallery's trash to really get the space back) — and do it more often than every N days. Google Photos' own
*Free up space* on that phone won't help if Google Photos' backup is off there: it only frees what that phone uploaded
itself.

## Diagnostics, logs and recovery

In the app, **Esegui diagnosi** (Run diagnostics) checks proot, Debian, Node, `sharp` (thumbnails), ffmpeg and
exiftool with real tests and writes `files/immich/logs/diagnosi.txt`. The menu above the box shows the setup,
PostgreSQL, Valkey and Immich logs.

From a PC (the app is debuggable, so `run-as` works without root, even with the phone locked):

```sh
adb shell run-as org.nasonmobile.immich tail -f files/immich/logs/immich.log
adb shell run-as org.nasonmobile.immich tail -n 100 files/immich/logs/setup.log

adb shell am start -n org.nasonmobile.immich/.MainActivity --ez start true        # start the server
adb shell am start -n org.nasonmobile.immich/.MainActivity --ez export_test true  # test image in DCIM/Immich
adb shell am start -n org.nasonmobile.immich/.MainActivity --ez export_dry true   # dry run of the copy (counts only, in the log)
adb shell am start -n org.nasonmobile.immich/.MainActivity --ez export_purge true      # delete the expired copies
adb shell am start -n org.nasonmobile.immich/.MainActivity --ez export_purge_all true  # delete all the gallery copies
```

| Symptom | What to do |
|---|---|
| Hangs while starting proot | *Avanzate → proot senza seccomp* (Advanced → proot without seccomp; the app already tries it once by itself) |
| The server stops after a while | check *child process restrictions* and *battery optimization* in the app |
| Setup interrupted halfway | press *Installa e avvia* again: it resumes from the right point |
| Broken Debian or packages | *Ripara* (Repair): reinstalls Debian and the Immich app, **database and photos stay** |
| Photos or videos without a thumbnail after the app was force-closed | jobs lost by APKs older than 2026-09-24 (Valkey without AOF): in Immich *Administration → Jobs*: *Extract metadata → Missing*, then *Generate Thumbnails → Missing* |
| Videos without a thumbnail, "Error loading image" | HDR videos with an ffmpeg lacking `tonemapx` (APKs older than 2026-09-23): update the APK, then in Immich *Administration → Jobs*: *Generate Thumbnails → Missing* and *Transcode videos → Missing* |
| The ImageMagick field of Immich's server information is empty | APKs older than 2026-09-28: that Debian had no ImageMagick (informational only, Immich doesn't use it otherwise). Update the APK |
| Running out of space | *Elimina ora le copie in galleria*, lower the cap or the days of the gallery copy, or delete from the phone backup (`ImmichBackup`) what you have saved elsewhere; on the server: *Utilities → Review large files*, then *Empty trash* (deleted assets only free space once the trash is emptied, or after 30 days) |

## Updating Immich

When Immich publishes a new version (its web page tells the admins), from the PC:

```sh
pc/update.sh --check              # published version, the one built in dist/, and the one on each connected device
pc/update.sh -s SERIAL            # builds the latest version (unless already built) and installs it on that device
pc/update.sh --github -s SERIAL   # the same, but downloads the APK from Releases instead of building it
```

`pc/update.sh` uses `pc/build-app.sh`, which by itself takes the latest published version and **derives** the
dependencies that same version uses in the official Docker image, checksums included (`pc/build-app.sh --versions`
prints them):

| Dependency | Where it comes from |
|---|---|
| base image | Immich's `server/Dockerfile` at that version (`base-server-prod:<tag>`) |
| Node | `server/Dockerfile` of [immich-app/base-images](https://github.com/immich-app/base-images) at that tag (the production one, not the development one in `mise.toml`) |
| jellyfin-ffmpeg + sha256 | `server/packages/ffmpeg.json` of base-images at that tag |
| VectorChord + sha256 | tag of the postgres image in Immich's `docker/docker-compose.yml`; sha256 from its GitHub release |
| pnpm, extism-js, binaryen | Immich's `mise.toml` |

Each one can be forced with its variable (`IMMICH_VERSION`, `NODE_VERSION`, `FFMPEG_VERSION`, …: see the top of the
script). On the phone the update is an `adb install -r`: the server stops for a few minutes and restarts by itself,
Immich runs its database migrations on start, and the app re-runs the Debian setup if the new package brings different
.debs (ffmpeg, VectorChord: compared with the `DEBS=` line of `/etc/immich-guest-ready`). `GUEST_LEVEL` in
`guest-setup.sh` only needs raising when that script itself changes. Immich dumps its database every night
(`library/backups`): before a big version jump check that there is a recent one.

Still manual: if a new version changes the build steps (new packages in the monorepo, more folders to fetch),
`pc/build-app.sh` stops with an error and needs adapting; Postgres stays at Debian's version (`PG_MAJOR`, 17).

Alternatively, without reinstalling the APK: `apk/build.sh --lite` once, then
`adb push dist/immich-android-<version>-arm64.tar.gz /sdcard/Android/data/org.nasonmobile.immich/files/immich-pack.tar.gz`
and restart the server from the app: a package in that folder takes precedence over the one inside the APK.

## Automatic releases on GitHub

The workflow [`.github/workflows/immich.yml`](.github/workflows/immich.yml) checks every day (04:23 UTC) whether Immich
has published a new version; if so, it builds it on a GitHub runner with the same scripts as on the PC
(`pc/build-app.sh`, `apk/build.sh`) and publishes it under **Releases** with Immich's version name (e.g. `v3.2.4`): full
APK, lite APK and package. It can also be started by hand from *Actions → Nuova versione di Immich → Run workflow*
(optionally with a specific version, or with *force* to rebuild a version already published). For each new version the
workflow also commits the file `ULTIMA_VERSIONE` ("latest version"): this keeps GitHub from suspending the scheduled
runs (it does so after 60 days without activity; if it happens, re-enable them from *Actions*).

Installing a release:

- on the phone itself: open the Releases page, download `immich-server.apk` and install it (Android asks for permission
  to install apps from the browser); it installs over the previous version without losing anything;
- from a PC: `pc/update.sh --github -s SERIAL` downloads the release APK instead of building it and installs it with adb.

**The signing key.** An update installs over the old version only if the APK is signed with the same key
(`apk/immich-server.keystore`, created by `apk/build.sh` on the first build and kept out of the repository by
`.gitignore`). The workflow takes it from the repository secret `KEYSTORE_BASE64`, and without it stops instead of
creating a new one. To set it (in a fork: with your own key):

```sh
base64 -w0 apk/immich-server.keystore | gh secret set KEYSTORE_BASE64 -R <owner>/<repo>
```

Keep a copy of the key off the PC: if it's lost, a new APK only installs after uninstalling the old one, and
uninstalling deletes photos and database.

## Data and backups

Everything is in the app's private folder: `files/immich/{postgres,library,valkey}`.
**Uninstalling the app deletes everything, photos included.** Immich can make periodic database dumps in
`library/backups` (see the database dump settings in *Administration → Settings*). To copy the photos to a PC:

```sh
adb exec-out run-as org.nasonmobile.immich tar cf - -C files/immich library > library.tar
```

(or use the Immich app to upload/download, or the server's export features).

### Backup on the phone (no PC)

The app's *Backup sul telefono* (Backup on the phone) section copies the originals (not the thumbnails) into a normal
folder of the phone's shared storage, **`ImmichBackup`**: any file manager sees it, and so does a PC when the phone is
connected like a USB drive, no adb needed. It's manual (*Copia ora sul telefono*, Copy to the phone now) and
incremental: it skips files already there with the same size, so it can be stopped and restarted without copying
everything again. It never deletes anything at the destination, not even when the original has been removed from
Immich. The first time it asks for Android's *Storage* permission: the app needs it to write outside its private folder.

This backup, the temporary gallery copy (for Google Photos) and the `tar` command on the PC are three different things
and can be combined: only `tar` also includes the database and the configuration, not just the photos.

## How it works

- **proot** (the binaries of the Termux package, patched in `apk/build.sh`) runs a **Debian 13 arm64** userland without
  root and without Docker. Android doesn't let an app execute files from its data folder, but it does execute the APK's
  `lib*.so`: that's why proot, its loader and its two libraries sit in `lib/arm64-v8a/`. The three Termux packages are
  pinned in `apk/vendor/` (Termux removes old versions from its repository).
- Each service runs in its own proot session with its own fake Debian user (`--change-id`), with automatic restart and
  a clean shutdown (SIGTERM/SIGINT to the real process, not to proot).
- **Immich** is built on the PC from source (`pc/build-app.sh`); only the native dependencies (sharp with libvips,
  bcrypt) are arm64/glibc, taken from npm's prebuilt binaries. pnpm is the version Immich declares (`mise.toml`); Node is
  the one of its production image (derived by `pc/build-app.sh`), which can be newer: that is how 3.2.4 fixes the memory
  leak of 3.2.2.
- The SysV shared memory that PostgreSQL wants and Android lacks is emulated by proot (`--sysvipc`).
- Valkey, which holds Immich's job queue, also writes the append-only file (AOF, `fsync` every second): Android can kill
  the app abruptly (`adb install -r` does too) and with snapshots alone the jobs of the last minutes were lost.
- **ffmpeg** is Jellyfin's (`jellyfin-ffmpeg7`, the same .deb as the official Immich image), not Debian's: for HDR
  videos (10-bit, BT.2020, as recent phones record them) Immich uses the `tonemapx` filter, which only exists there. With
  Debian's ffmpeg those videos get no thumbnail ("Error loading image") and no transcoding. It lives in
  `/usr/lib/jellyfin-ffmpeg`, with `ffmpeg` and `ffprobe` linked into `/usr/local/bin`.
- When `guest-setup.sh` changes (`GUEST_LEVEL` higher than the one written in `etc/immich-guest-ready`) or a new Immich
  package brings different .debs (`DEBS=` line), the app re-runs it on existing installations too; if that update fails
  (for example without network) the server starts with the previous programs and retries at the next start.

Three problems that anyone trying proot on Android runs into came up on the Pixel 5 and are already fixed:
1. `libandroid-shmem` has a Termux path hard-coded in the binary and loops if it doesn't exist (patched in
   `apk/build.sh`); it also deadlocks on the second `shmget` with the same key, so proot's internal emulation is used
   instead (`PROOT_DONT_SHARE_LIBANDROID_SHMEM`);
2. the binaries' loader looked for libraries in `LD_LIBRARY_PATH`, which Android's linker sometimes ignores: the RUNPATH
   is now `$ORIGIN`;
3. `SIGQUIT` is blocked in the child processes of an Android app: force-closing proot uses `SIGABRT`.

## Layout

```
pc/build-app.sh        builds Immich for arm64 (no Docker), with the dependencies of its official image
pc/update.sh           checks the versions, builds (or downloads) and installs the latest Immich
.github/workflows/     immich.yml: builds and publishes every new Immich version to Releases
apk/vendor/            pinned Termux packages for proot (Termux removes old versions)
apk/build.sh           builds and signs the APK (aapt2, javac, d8, apksigner: no Gradle)
apk/install.sh         installs on the phone via adb and prepares Android
apk/src/…              the app: Stack (setup and services), ProotCmd, Tar, Configs, service, screen,
                        Exporter/Pruner (Google Photos), Backup (copy on the phone), BatteryGuard (charging reminder)
apk/assets/guest/      the script that installs PostgreSQL, Valkey, jellyfin-ffmpeg inside Debian
apk/test/              tests on the PC: tar extractor (TarCheck) and configurations (ConfigsDump)
```

## Test status

Tested on the Pixel 5 (Android 14, AOSP build with a custom 4.19 kernel, 7.5 GB of RAM):

- fresh install from the single APK (~7 min until Immich's first answer), warm start in ~40 s;
- clean shutdown in under 5 s (PostgreSQL with a final checkpoint), automatic restart of a crashed service, recovery
  after an abrupt power-off;
- Immich answering on the local network (`/api/server/ping`, web interface);
- **real uploads**: a JPEG photo and an HEVC video → the phone generates thumbnails and previews (for the video too),
  reads the metadata (date, size) and transcodes the video to H.264, all in ~25 s; in daily use since 2026-09-22 with a
  few hundred photos and HDR videos uploaded from another phone;
- update from 3.2.2 to 3.2.4 with `pc/update.sh -s …` (`adb install -r`, 2026-09-29): about 4 minutes of downtime; the
  app re-ran the Debian setup to record the .debs (`DEBS=` line), Immich ran its migrations and re-imported the geodata
  (the *schema drift* warning about `geodata_places` during the import is transient: once it's done the indexes are
  there);
- ImageMagick inside Debian (`magick --version` in Immich's own environment, 2026-09-28);
- idle, the whole stack uses roughly 0.7–1 GB of RAM.

The tar extractor is checked on the PC against GNU tar. Not tested yet: long-term use (battery, temperature, memory under
load with large libraries), starting after a phone reboot, updating the package via `adb push`, other models. Not yet
verified on screen: the phone backup (in particular the *Storage* permission on Android 14 with `targetSdk` 28) and the
battery reminder (notification, thresholds).

## Licenses

The APK bundles third-party software under its own licenses: **proot** (GPL-2.0+,
[termux/proot](https://github.com/termux/proot)), **libtalloc** (LGPL-3.0+), **libandroid-shmem** (BSD-3-Clause),
**Debian** and its packages (various), **PostgreSQL** (PostgreSQL License), **VectorChord** (AGPL-3.0 or ELv2),
**Valkey** (BSD), **Node.js** (MIT), **jellyfin-ffmpeg** (GPL-3.0+) and **Immich** (AGPL-3.0). If you distribute the
APK, respect their terms, in particular the availability of the source code of proot and Immich (each release links the
exact Immich tag; the build scripts are in this repository).
