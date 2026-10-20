# Building, installing and updating

For people who want to build the APK themselves or maintain the releases. To just use the app, download it from
[Releases](https://github.com/mich-de/immic-for-pixels-phone/releases) (see the [README](../README.md)).

- [Requirements](#requirements)
- [Build](#build)
- [Install with adb](#install-with-adb)
- [Update Immich](#update-immich)
- [Automatic releases](#automatic-releases)

## Requirements

A Linux PC with `git curl tar gzip xz python3`, a JDK (17+) and the Android SDK (`build-tools`, `platforms;android-34`).
No Docker, no Gradle. Node and pnpm are downloaded by the scripts into `.build/`.

## Build

```sh
pc/build-app.sh        # builds the latest Immich for arm64 → dist/immich-android-<version>-arm64.tar.gz (~230 MB)
apk/build.sh           # assembles and signs the APK        → dist/immich-server.apk (~290 MB)
apk/build.sh --lite    # small APK (~400 KB) without the two large packages
```

`pc/build-app.sh` builds Immich's server, web and plugins from source, the same steps as Immich's official
Dockerfile, and fetches the native dependencies (sharp, bcrypt) directly for arm64, with no emulation. It also takes
the other dependencies from the official Docker image of that Immich version (see [Update Immich](#update-immich)).

**The signing key.** `apk/build.sh` signs the APK with `apk/immich-server.keystore`, created on the first build and kept
out of git. An APK only installs over the previous one (keeping the data) when it is signed with the same key, so keep
a copy of it somewhere safe: without it the next version can only be installed by uninstalling the old one, which
deletes photos and database.

## Install with adb

Connect the phone (USB or *Wireless debugging*) and:

```sh
apk/install.sh [-s SERIAL] [--lite] [--no-tweaks]
```

Besides installing the APK, it changes three Android settings that keep the server alive (all reversible; the undo
commands are in the script's header):
- grants the app `WRITE_SECURE_SETTINGS`, so it can manage the next setting itself;
- turns off Android 12+'s *child process restrictions*, which otherwise kill PostgreSQL and the other processes at
  random. The command that stops Android from restoring the limit (`device_config set_sync_disabled_for_tests
  persistent`) also pauses remote updates of other system flags;
- exempts the app from battery optimization.

`--no-tweaks` skips them; the same settings are reachable from the app's buttons.

## Update Immich

Phones running the app don't need a PC for this: the app finds the new release and installs it with one tap (see
[Updates](features.md#updates)). From a PC, when Immich publishes a new version (its web page tells the admins):

```sh
pc/update.sh --check              # published version, the one in dist/, and the one on each connected device
pc/update.sh -s SERIAL            # build the latest version (unless already built) and install it on that device
pc/update.sh --github -s SERIAL   # the same, but download the APK from Releases instead of building it
```

`pc/build-app.sh` picks the latest published Immich and derives by itself the dependencies that version uses in its
official Docker image, checksums included (`pc/build-app.sh --versions` prints them):

| Dependency | Taken from |
|---|---|
| base image | Immich's `server/Dockerfile` at that version (`base-server-prod:<tag>`) |
| Node | `server/Dockerfile` of [immich-app/base-images](https://github.com/immich-app/base-images) at that tag (the production version, not the development one in `mise.toml`) |
| jellyfin-ffmpeg + sha256 | `server/packages/ffmpeg.json` of base-images at that tag |
| VectorChord + sha256 | the postgres image tag in Immich's `docker/docker-compose.yml`; sha256 from its GitHub release |
| pnpm, extism-js, binaryen | Immich's `mise.toml` |

Each one can be forced with an environment variable (`IMMICH_VERSION`, `NODE_VERSION`, `FFMPEG_VERSION`, …; see the
top of the script).

On the phone an update is an `adb install -r`: the server stops for a few minutes and restarts by itself, Immich runs
its database migrations, and the app redoes the Debian setup if the new package brings different `.deb`s (ffmpeg,
VectorChord). Immich dumps its database every night to `library/backups`: before a big version jump, check that there
is a recent one.

Still manual: if a new Immich version changes its build steps, `pc/build-app.sh` stops with an error and needs
adapting; PostgreSQL stays at Debian's version (`PG_MAJOR`, 17).

Without reinstalling the APK: install the lite APK once, then push the package and restart the server from the app
(a package in that folder wins over the one inside the APK):

```sh
adb push dist/immich-android-<version>-arm64.tar.gz /sdcard/Android/data/org.nasonmobile.immich/files/immich-pack.tar.gz
```

## Automatic releases

The workflow [`.github/workflows/immich.yml`](../.github/workflows/immich.yml) checks every day at 04:23 UTC whether
Immich has published a new version. If so, it builds it on a GitHub runner with the same scripts and publishes a
release named after the Immich version (e.g. `v3.2.4`) with the full APK, the lite APK and the package.

- Run it by hand from *Actions → New Immich version → Run workflow*, optionally with a specific version or with
  *force* to rebuild and replace a release that already exists.
- It signs with the key stored in the repository secret `KEYSTORE_BASE64`, and stops if the secret is missing rather
  than creating a new key. To set it (in a fork, with your own key):

  ```sh
  base64 -w0 apk/immich-server.keystore | gh secret set KEYSTORE_BASE64 -R <owner>/<repo>
  ```
- For each new version it commits the file `ULTIMA_VERSIONE`, which keeps GitHub from suspending the scheduled runs
  after 60 days without activity (if it happens anyway, re-enable them from *Actions*).
- The three Termux packages proot needs are pinned in `apk/vendor/`, because Termux removes old versions from its
  repository.
