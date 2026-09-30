# Troubleshooting

Back to the [README](../README.md).

## First look

- **Run diagnostics** (in the app, *Logs and diagnostics*) checks proot, Debian, Node, `sharp` (thumbnails), ffmpeg and
  exiftool with real tests, and saves the result to `files/immich/logs/diagnostics.txt`.
- The log viewer next to it shows the **Setup**, PostgreSQL, Valkey and Immich logs. The Setup log hides the database
  password, so it can be pasted into an issue.

## Common problems

| Symptom | What to do |
|---|---|
| The Immich app says *Unable to check app or server version* | The phone can't reach the server: you're away from home, the server is restarting, or the server's IP address changed (reserve it in the router). |
| The server stops after a while | Check *Child process restrictions* and *Exclude from battery optimization* in the app; on Samsung/Xiaomi also allow background activity. |
| It hangs while starting proot | *Advanced → proot without seccomp* (the app already tries it once by itself). |
| The first setup stopped halfway | Press **Install and start** again: it resumes from where it stopped. |
| Debian or its packages are broken | **Repair**: reinstalls Debian and the Immich package; database and photos stay. |
| Photos don't appear in Google Photos | With *Keep the originals in DCIM/Immich*: in Google Photos turn on the backup of the `admin` folder. With the gallery copy: turn on the `Immich` folder. Check that Google Photos' backup is on. |
| The same photo shows twice | In Immich: reload the page or reopen the app. In Google Photos or the gallery: it's the same photo (local original and cloud copy) — don't delete either. |
| Running out of space | See [Storage warnings](features.md#storage-warnings). |

## From a PC

The app is debuggable, so `adb shell run-as` works without root, even with the phone locked:

```sh
adb shell run-as org.nasonmobile.immich tail -f files/immich/logs/immich.log
adb shell run-as org.nasonmobile.immich tail -n 100 files/immich/logs/setup.log
adb shell run-as org.nasonmobile.immich cat files/immich/logs/diagnostics.txt
```

The app also takes commands through `am start`, handy when the phone is locked or out of reach:

```sh
adb shell am start -n org.nasonmobile.immich/.MainActivity --ez start true
```

| Extra | Effect |
|---|---|
| `--ez start true` | start the server |
| `--ez restart true` | restart it (settings that move files apply at start) |
| `--ez dcim_originals true --ez restart true` | originals in DCIM; needs the Storage permission: `adb shell pm grant org.nasonmobile.immich android.permission.WRITE_EXTERNAL_STORAGE` |
| `--ez missing_cleanup_now true` | clean up photos whose file is gone, now |
| `--ez export_test true` | test image in `DCIM/Immich` |
| `--ez export_dry true` | dry run of the gallery copy (counts only, in the log) |
| `--ez export_purge true` / `--ez export_purge_all true` | delete the expired / all the gallery copies |

## Problems of older versions

Fixed in current releases; update the app, then run the Immich jobs listed.

| Symptom | Cause and fix |
|---|---|
| Photos or videos without a thumbnail after the app was force-closed | Jobs lost by APKs older than 2026-09-24 (Valkey without an append-only file). In Immich *Administration → Jobs*: *Extract metadata → Missing*, then *Generate Thumbnails → Missing*. |
| HDR videos without a thumbnail, "Error loading image" | APKs older than 2026-09-23 had an ffmpeg without `tonemapx`. *Administration → Jobs*: *Generate Thumbnails → Missing* and *Transcode videos → Missing*. |
| The ImageMagick field in Immich's server information is empty | APKs older than 2026-09-28 had no ImageMagick in Debian (informational only). |

## Reporting a problem

[Open a test report](https://github.com/mich-de/immic-for-pixels-phone/issues/new?template=test-report.yml) with your
phone model, Android version, RAM, what happened, the output of **Run diagnostics** and the end of the **Setup** log.
