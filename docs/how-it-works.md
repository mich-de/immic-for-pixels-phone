# How it works

Technical notes. Back to the [README](../README.md).

```
┌──────────────────────── "Immich Server" APK (Java, ~290 MB) ─────────────────────────┐
│  app: foreground service · wake locks · autostart · logs · diagnostics               │
│  proot (Termux binaries) ── runs a Debian 13 (arm64) userland without root           │
│      ├─ PostgreSQL 17 + pgvector + VectorChord   (data: files/immich/postgres)       │
│      ├─ Valkey 8                                                                     │
│      └─ Node 24 + Immich v3.2.4 (server, web, plugins) + jellyfin-ffmpeg + exiftool  │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

Immich doesn't support Android as a server platform: here it is built from source and run "ad hoc", without Docker.

## Components

- **proot** runs a **Debian 13 arm64** userland without root. Android doesn't let an app execute files from its data
  folder, but it does execute the APK's `lib*.so`: that's why proot, its loader and its two libraries are shipped in
  `lib/arm64-v8a/` (from Termux's packages, patched in `apk/build.sh`).
- Each service runs in its own proot session with its own Debian user (`--change-id`), with automatic restart and a
  clean shutdown (SIGTERM/SIGINT to the real process, not to proot).
- **Immich** is built on the PC from source (`pc/build-app.sh`); only the native modules (sharp with libvips, bcrypt)
  are arm64 binaries, taken from npm. Node is the version of Immich's production image, which can be newer than the
  one in its `mise.toml` (that's how Immich 3.2.4 fixes a memory leak of 3.2.2).
- **PostgreSQL** wants SysV shared memory, which Android lacks: proot emulates it (`--sysvipc`).
- **Valkey** holds Immich's job queue and also writes an append-only file (`fsync` every second): Android can kill the
  app abruptly, and with snapshots alone the jobs of the last minutes were lost.
- **ffmpeg** is Jellyfin's (`jellyfin-ffmpeg7`, the same `.deb` as Immich's official image): Immich converts HDR videos
  (10-bit BT.2020, as recent phones record them) with the `tonemapx` filter, which only exists there.
- **Machine learning** is turned off on the first start: it would be too slow on a phone and its service isn't
  included. Immich can be pointed to an ML server on another machine from its settings.
- The Debian setup script (`apk/assets/guest/guest-setup.sh`) runs again on existing installations when it changes
  (`GUEST_LEVEL`) or when a new Immich package brings different `.deb`s (`DEBS=` line of `/etc/immich-guest-ready`). If
  that update fails (no network), the server starts with the previous programs and retries at the next start.

## Network

Immich listens on all interfaces, port **2283**; PostgreSQL and Valkey only on `127.0.0.1`. The server speaks plain
HTTP and the phone is not meant to be exposed to the Internet: from outside, use a VPN.

## Data

Everything lives in the app's private folder `files/immich/`: `postgres` (database), `library` (photos, thumbnails,
transcoded videos, database dumps in `library/backups`) and `valkey`. With
[originals in DCIM/Immich](features.md#originals-in-dcimimmich) the originals are in `DCIM/Immich` and
`DCIM/.immich-upload` instead.

**Uninstalling the app deletes the private folder**, photos included. To copy the library to a PC:

```sh
adb exec-out run-as org.nasonmobile.immich tar cf - -C files/immich library > library.tar
```

This includes the database dumps but not the originals in `DCIM` (copy those like any other file). The
[backup on the phone](features.md#backup-on-the-phone) copies only the photos.

## Problems already solved

Three problems anyone trying proot on Android runs into:
1. `libandroid-shmem` has a Termux path hard-coded in the binary and loops if it doesn't exist (patched in
   `apk/build.sh`); it also deadlocks on the second `shmget` with the same key, so proot's own emulation is used
   instead (`PROOT_DONT_SHARE_LIBANDROID_SHMEM`).
2. The loader looked for libraries in `LD_LIBRARY_PATH`, which Android's linker sometimes ignores: the RUNPATH is now
   `$ORIGIN`.
3. `SIGQUIT` is blocked in the child processes of an Android app: force-closing proot uses `SIGABRT`.

## Repository layout

```
pc/build-app.sh        builds Immich for arm64 (no Docker), with the dependencies of its official image
pc/update.sh           checks versions, builds (or downloads) and installs the latest Immich
apk/build.sh           builds and signs the APK (aapt2, javac, d8, apksigner: no Gradle)
apk/install.sh         installs on a phone via adb and prepares Android
apk/src/…              the app: Stack (setup and services), ProotCmd, Tar, Configs, ServerService, MainActivity,
                       Exporter/Pruner (Google Photos), DcimMode (originals in DCIM), MissingCleaner, Backup,
                       BatteryGuard
apk/assets/guest/      the script that installs PostgreSQL, Valkey and jellyfin-ffmpeg inside Debian
apk/vendor/            pinned Termux packages for proot
apk/test/              PC-side tests: tar extractor (TarCheck) and configurations (ConfigsDump)
.github/               release workflow and the test report form
docs/                  this documentation
```

## Test status

Tested on a Pixel 5 (Android 14, AOSP build, 8 GB of RAM), in daily use since 2026-09-22 with a few hundred photos
and HDR videos uploaded from another phone:
- fresh install from the single APK: ~7 minutes to Immich's first answer; later starts ~40 s;
- clean shutdown in under 5 s, automatic restart of a crashed service, recovery after an abrupt power-off;
- uploads with thumbnails, metadata and video transcoding (HEVC and HDR included);
- update from Immich 3.2.2 to 3.2.4 with about 4 minutes of downtime;
- originals moved into `DCIM/Immich`: 395 files (9.96 GB) in about 2 minutes, SHA-1 of all 383 photos identical to
  Immich's database;
- idle, the whole stack uses roughly 0.7–1 GB of RAM.

Not tested yet: other phones (see [testers](../README.md#help-wanted-testers)), long-term battery and temperature,
starting after a reboot, the `adb push` update, the phone backup and the battery reminder on screen.
