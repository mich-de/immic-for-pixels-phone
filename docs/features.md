# Features

Every option of the app, with its caveats. Back to the [README](../README.md).

- [Where the photos are](#where-the-photos-are)
- [Getting the photos into Google Photos](#getting-the-photos-into-google-photos)
  - [Originals in DCIM/Immich](#originals-in-dcimimmich) (recommended)
  - [Temporary gallery copy](#temporary-gallery-copy)
- [Deleting the original from Immich](#deleting-the-original-from-immich)
- [Cleaning up photos whose file is gone](#cleaning-up-photos-whose-file-is-gone)
- [Backup on the phone](#backup-on-the-phone)
- [Battery reminder](#battery-reminder)
- [Storage warnings](#storage-warnings)
- [Updates](#updates)
- [Advanced](#advanced)

## Where the photos are

By default the photos and videos uploaded to Immich live in the **app's private storage**
(`files/immich/library/upload/<user>/<xx>/<yy>/<uuid>.jpg`: Immich renames each original with a UUID and keeps the real
name in its database). They are ordinary files, not encrypted, but Android doesn't let other apps — Gallery, Files,
Google Photos — see an app's private storage. To view them use Immich (web or app).

**Uninstalling the app deletes everything**, photos and database included. See also
[Backup on the phone](#backup-on-the-phone) and the PC backup command in [how-it-works.md](how-it-works.md#data).

## Getting the photos into Google Photos

Google Photos can only back up files in the phone's shared storage (the gallery). There are two ways to put the
Immich photos there:

| | Originals in DCIM/Immich (recommended) | Temporary gallery copy |
|---|---|---|
| Where the originals live | shared storage, `DCIM/Immich/<user>` | app's private storage |
| Extra copies | none | one per photo, deleted after N days |
| Delay before Google Photos sees a photo | a few minutes | up to 5 minutes |
| Folder to back up in Google Photos | `admin` (one per Immich user) | `Immich` |
| Other apps can delete the originals | yes (see below) | no |

Either way, in Google Photos keep *Backup quality* on **Storage saver** on Pixel 2–5: that is the free unlimited one
(photos reduced to 16 MP, videos to 1080p); Original quality would count against your Google storage. On the first
Pixel, Original quality is free too. **Don't back up these folders with the Immich app**: it would send the files back
to the server.

### Originals in DCIM/Immich

Turn on **Keep the originals in DCIM/Immich**. The app asks for the *Storage* permission, restarts the server and
moves the photos already uploaded (a few minutes; if interrupted, it resumes). From then on:

| Inside Immich | On the phone |
|---|---|
| `/data/upload` (just uploaded) | `DCIM/.immich-upload` (hidden: Android doesn't index it) |
| `/data/library` (sorted by Immich) | `DCIM/Immich/<user>/<original name>` — `admin` for the administrator |

Immich moves each new photo from `upload` to `library` right after reading its metadata. Both folders are on the same
storage, so the move is an instant rename and Google Photos never sees a half-written file. The app then asks Android to
index each new original (checked every 5 minutes). Thumbnails, transcoded videos, the database and its backups stay in
the private storage.

In Google Photos, turn on the backup of the **admin** folder once: *Settings → Backup → Back up device folders*.

Good to know:
- **Other apps can now delete the originals** — for example Google Photos' *Free up space* after its backup, or
  deleting a photo from the gallery. Immich then keeps the photo (thumbnail) without its original; see
  [Cleaning up photos whose file is gone](#cleaning-up-photos-whose-file-is-gone).
- **Don't delete "duplicates" you see in Google Photos or the gallery** right after turning this on: they are the same
  photo seen twice (the new local original and the copy already in the cloud). Deleting one deletes the original.
- The temporary gallery copy is not needed anymore: with this option the app makes no copies and only records when
  each original lands in `DCIM/Immich` (that's when [Deleting the original from Immich](#deleting-the-original-from-immich)
  starts counting).
- Files are named after the original name (a duplicate name gets a `+1` suffix). Motion photos: the video part Immich
  extracts would land there too, as a separate short video.
- Turning the option off again only stops moving *new* photos into `DCIM/Immich`: they stay in the hidden folder, and
  the photos already in `DCIM/Immich` stay where they are.

### Temporary gallery copy

The alternative if you prefer to keep the originals private. In the *Gallery and Google Photos* section:

1. Press **Test image**: an `Immich` folder with a test image appears in the gallery.
2. In Google Photos: *Settings → Backup → Back up device folders* → turn on `Immich` (once).
3. Tick **Copy new photos to the gallery automatically** (every 5 minutes), or use **Copy now**.

The originals are copied byte for byte, so the capture date is the one in the metadata (EXIF, or the video's creation
date); files without metadata (screenshots, PNG) get the date of the copy. Only the admin's photos are copied (tick
*Include the photos of the other Immich users too* for the others), never Immich's "locked" ones.

The copy is **temporary**, so the phone doesn't hold every photo twice:
- **Delete the gallery copy after**: never / 3 / 5 / **7** / 30 days. Google Photos doesn't say when it has finished
  uploading, so this is a time, not an event: if Google Photos' backup is off for longer, a copy can be deleted before
  it's uploaded. The original always stays in Immich.
- **Gallery copies waiting for backup: at most** none / 5 / **10** / 20 / 50 GB: when the cap is reached, copying
  pauses until old copies are deleted.
- Google Photos' *Free up space* deletes the copies it has already uploaded; the app notices.
- **Delete the gallery copies now** removes them all; *Delete and copy again* also restarts from all the photos.

## Deleting the original from Immich

Optional, and **permanent**: some days after a photo reached Google Photos (through either way above), the app deletes
the original from Immich. The phone then keeps no long-term copy — it's just a pass-through — and the only copy left is
the one in Google Photos, in Storage saver quality on Pixel 2–5.

1. In Immich: *Account Settings → API Keys → New API Key* with the `all` permission (or at least `asset.delete`).
2. In the app paste it into **Immich API key**, press **Save the key**, then **Test the key** (it deletes nothing).
3. Choose **Delete the original from Immich after**: **Never** (default), 2, 3, 5 or 7 days.

It only deletes photos that reached the gallery successfully, and it goes through Immich's API, so Immich itself
removes thumbnails and files.

> **If the photos come from another phone with the Immich app** (your main phone, typically), free that phone first.
> The Immich app uploads every photo it doesn't find on the server: once the original is deleted from Immich, a photo
> still on that phone is **uploaded again**, and the cycle repeats. On that phone use the Immich app's own
> *Settings → Free Up Space* (*Custom date*, a few days ago): it moves to the phone's trash only the photos already on
> the server — and do it more often than every N days. Google Photos' *Free up space* on that phone doesn't help if its
> Google Photos backup is off: it only frees what that phone uploaded itself.

## Cleaning up photos whose file is gone

Every night at 3:00 Immich checks which originals in its database no longer exist on disk (*Administration →
Maintenance → Integrity Report → Missing Files*). Its **Delete All** button there moves those photos to Immich's trash
(recoverable for 30 days).

Tick **Every night move to Immich's trash the photos whose file is gone** and the app presses that button by itself
once a day after 4:00, with the API key from the previous section. **Check now** runs it right away. For safety it does
nothing if the folders of the originals aren't readable (every photo would look missing) or if too many are missing at
once (more than 20 and more than 10%).

The caveat about other phones applies here too: when a photo leaves Immich's trash, a phone that still has it uploads
it again.

## Backup on the phone

**Copy to the phone now** copies all the originals (not the thumbnails) to `ImmichBackup` in the phone's shared
storage: a normal folder that any file manager shows, and a PC too when the phone is connected by USB. It's manual and
incremental (it skips files already there with the same size, so it can be stopped and resumed), and it never deletes
anything. The first time it asks for the *Storage* permission. It copies photos only; for the database too, see the
PC backup in [how-it-works.md](how-it-works.md#data).

## Battery reminder

A phone that is always plugged in sits at 100%, which wears the battery. Android doesn't let an app switch the charger
on or off (not without root), but the app can remind you: tick **Remind me not to keep it charging at 100%** and choose
when to unplug (default 80%) and plug back in (default 30%). Alternatives: a smart plug, or a charge limit if your ROM
has one. Keep the phone somewhere cool: thumbnails and video transcoding heat it up.

## Storage warnings

Below 2 GB free, the app shows the free space in yellow and the server notification says *storage running low*; below
500 MB it turns red (*STORAGE ALMOST FULL*): PostgreSQL and the copies may stall. To free space on the server, in
Immich use *Utilities → Review large files*, then *Empty trash* (deleted photos only free space once the trash is
emptied, or after 30 days).

## Updates

Every new Immich version is built and published in this repository's
[Releases](https://github.com/mich-de/immic-for-pixels-phone/releases) automatically, usually within a day (see
[building.md](building.md#automatic-releases)). The app checks once a day and sends a notification when there is a new
release; the *Updates* section shows the installed version and the result of the last check.

To update, tap the notification or press **Update to vX**: the app downloads the new APK (about 300 MB), checks it
against the checksum GitHub publishes and hands it to Android, which asks you to confirm. The first time, Android also
asks you to allow *Immich Server* to install apps: allow it, go back and press the button again. Photos, database and
settings stay; the server stops for a few minutes, starts again by itself and Immich updates its database.

- Nothing is installed without your tap: an update stops the server for a while, so you choose when.
- Android only accepts an update signed with the same key as the installed app.
- A release rebuilt with fixes to the app for the same Immich version is offered too, as a *new build*.
- Untick **Check for updates every day** to turn off the automatic checks and the notification; **Check now** still
  works.
- Builds published before this feature (up to the first v3.3.1 build of 9 October 2026) don't check: install a newer
  `immich-server.apk` over them once, by hand, as described in the [README](../README.md#good-to-know).

## Advanced

- **proot without seccomp**: only if the server hangs while starting (the app already tries it once by itself).
- **Repair**: reinstalls the Debian system and the Immich package; the database and the photos are not touched.
- **Run diagnostics** and the log viewer: see [troubleshooting.md](troubleshooting.md).
