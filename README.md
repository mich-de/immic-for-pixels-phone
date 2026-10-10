# Immich Server for Android

**English** · [Italiano](README.it.md)

Turn an old **Google Pixel** into a private [Immich](https://immich.app) photo server, and use its free unlimited
Google Photos backup as a cloud copy. One APK: no root, no Docker.

```
 your phones ──Immich app──▶  old Pixel: Immich server  ──DCIM/Immich──▶  Google Photos
                              full-quality originals                       free unlimited copy
```

**[Download the latest release](https://github.com/mich-de/immic-for-pixels-phone/releases/latest)** · always the latest Immich ·
tested on a Pixel 5 (Android 14) · unofficial project, not affiliated with Immich.

## Why a Pixel 1–5

Google Photos gives these phones free, unlimited backup of the photos and videos uploaded *from the phone itself*:

| Phone | Free unlimited Google Photos backup |
|---|---|
| Pixel / Pixel XL (2016) | Original quality |
| Pixel 2 – Pixel 5 | Storage saver (photos up to 16 MP, videos up to 1080p) |
| Pixel 6 and later | none |

(From [Google Photos Help](https://support.google.com/photos/answer/6220791); Google may change these terms.)

So the Pixel keeps your originals at full quality in Immich, for the whole family, and passes every photo on to Google
Photos for free. The app also works on **any arm64 Android phone** as a plain Immich server, just without the free
Google Photos backup.

## Install

You need the phone that will be the server (arm64, Android 8+, a few GB free), your home Wi-Fi, and about 15 minutes.

1. **Install the APK.** On that phone open the
   [latest release](https://github.com/mich-de/immic-for-pixels-phone/releases/latest), download `immich-server.apk`
   (~290 MB) and open it. Let the browser install apps when Android asks; if Play Protect doesn't know the app, choose
   *Install anyway*.
2. **Start it.** Open **Immich Server** and press **Install and start**. The first time takes 10–15 minutes and needs
   Internet: keep the phone plugged in. When the status reads **Running**, the app shows the server address, for
   example `http://192.168.1.20:2283`.
3. **Keep Android from stopping it.** In the same screen:
   - **Exclude from battery optimization** → allow;
   - **Child process restrictions (Android 12+)**: on Android 14+ turn on *Settings → System → Developer options →
     Disable child process restrictions* (to see *Developer options*, tap *Build number* 7 times in *About phone*);
     on Android 12–13 this needs a PC with adb, and the button shows the commands;
   - tick **Start the server when the phone boots**;
   - on Samsung, Xiaomi and similar phones, also let the app run in the background.
4. **Create your account.** On any device on the same Wi-Fi, open that address in a browser and create the admin
   account.
5. **Connect your phones.** Install the official **Immich** app on your other phones, enter the same address as
   *Server URL*, log in and turn on backup.
6. **Send everything to Google Photos** (Pixel 1–5). On the server phone:
   - in the app, turn on **Keep the originals in DCIM/Immich** (it asks for the *Storage* permission and restarts the
     server);
   - in Google Photos, turn on *Backup*; on Pixel 2–5 keep *Backup quality* on **Storage saver** (Original quality
     would use your Google storage);
   - in Google Photos, *Settings → Backup → Back up device folders* → turn on **admin**.

   From then on every photo that reaches Immich shows up in Google Photos a few minutes later.

## Good to know

- **Never uninstall the app**: that deletes all the photos and the database. Updates install over it: the app tells you
  when a new version is out and installs it with one tap (or install the new release's `immich-server.apk` over the old
  one yourself). Everything stays and the server restarts by itself.
- **Keep the server phone on Wi-Fi and plugged in.** The app can remind you to unplug it at 80% to spare the battery
  (*Battery* section).
- **Reserve the phone's IP address in your router** (static DHCP), or the address can change. Away from home, use a
  VPN such as Tailscale or WireGuard; don't open port 2283 to the Internet.
- **No machine learning**: it's too heavy for a phone, so there is no smart search, face recognition or duplicate
  detection.

## Features

Everything is in the app's single screen; details and caveats in **[docs/features.md](docs/features.md)**.

| Feature | What it does |
|---|---|
| [Originals in DCIM/Immich](docs/features.md#originals-in-dcimimmich) | Keeps the originals where Google Photos sees them, with no copies (recommended for Google Photos) |
| [Temporary gallery copy](docs/features.md#temporary-gallery-copy) | Alternative: copies new photos to the gallery for Google Photos and deletes the copies after N days |
| [Delete the original from Immich](docs/features.md#deleting-the-original-from-immich) | Optional pass-through: removes the original from Immich N days after it reached Google Photos (permanent) |
| [Clean up photos whose file is gone](docs/features.md#cleaning-up-photos-whose-file-is-gone) | Every night moves to Immich's trash the photos whose original was deleted by another app |
| [Backup on the phone](docs/features.md#backup-on-the-phone) | Copies all the originals to a normal `ImmichBackup` folder |
| [Battery reminder](docs/features.md#battery-reminder) | Reminds you to unplug above and plug back in below a charge level |
| [Updates](docs/features.md#updates) | Tells you when a new version is out and installs it with one tap |

## Help wanted: testers

So far the app has been tested on one phone: a Pixel 5 (Android 14, 8 GB of RAM). If you try it on anything else —
especially a **Pixel 1, 2, 3, 3a, 4 or 4a**, or any other arm64 phone — please
[open a test report](https://github.com/mich-de/immic-for-pixels-phone/issues/new?template=test-report.yml), whether it
works or not. Phones with 4 GB of RAM are the biggest unknown.

## Documentation

- [docs/features.md](docs/features.md) — every option of the app, with its caveats
- [docs/troubleshooting.md](docs/troubleshooting.md) — diagnostics, logs and common problems
- [docs/building.md](docs/building.md) — building from source, installing with adb, updating Immich, automatic releases
- [docs/how-it-works.md](docs/how-it-works.md) — architecture, technical notes and test status

## License

The APK bundles third-party software under its own licenses:
proot (GPL-2.0+), libtalloc (LGPL-3.0+), libandroid-shmem (BSD-3-Clause), Debian and its packages (various),
PostgreSQL (PostgreSQL License), VectorChord (AGPL-3.0 or ELv2), Valkey (BSD), Node.js (MIT), jellyfin-ffmpeg
(GPL-3.0+) and Immich (AGPL-3.0). If you redistribute the APK, respect their terms, in particular the availability of
the source code of proot and Immich: each release links the exact Immich tag, and the build scripts are here.
