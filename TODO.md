# TODO

## Next up

Nothing queued; see the sections below.

## Done

- [x] Editing files in Android/data (saved back over the phone's own Wireless debugging connection); the text editor pauses saving when the file changed elsewhere (Reload theirs / Keep mine).
- [x] Remote previews: SSH videos stream with seeking; downloads, uploads and transfers use their own SFTP channel so browsing isn't blocked; edited pictures show their new version.
- [x] Release build: signing from keystore.properties or EXPLORER_* variables; the classes JSch, libadb and the services load by name checked in the R8 output.
- [x] CI: GitHub Actions runs the unit tests and builds the debug APK on every push and pull request.
- [x] Terminal: select and copy text, tap links, several sessions (tabs) per server, extra keys hide with a hardware keyboard.
- [x] Free up space: large files, duplicates (size → quick hash → SHA-256), old downloads, installer files; deleting goes to Trash.
- [x] Audio player: background playback (Media3 session: notification, lock screen, headphones), folder queue, mini player and Now playing sheet.
- [x] Background transfers: copy/move queue in a foreground service with progress, Cancel and a summary notification.
- [x] Zip files: browse like a folder, open entries in the app, extract all/folder/selection, compress a selection.
- [x] SSH key login: generate (Ed25519/RSA) or import keys, sign in with a key, install a key on a server, forget a server's identity.

## Cleanup

- App caches and leftover folders of uninstalled apps (needs usage/storage access Android restricts).
- Similar (not identical) photos, and blurry ones.

## Audio

- Play a whole remote folder as a queue (now a remote song is downloaded and played on its own).
- Playlists, and resuming where you left off.

## Transfers

- Byte-level progress for big single files (now it's per item), and cancelling in the middle of a file.
- Show queued transfers, not only the current one.

## Archives

- Other formats: `.tar`, `.tar.gz`, `.7z`, `.rar` (needs a library such as Apache Commons Compress).
- Password-protected zips (java.util.zip can't decrypt them; show a clear message for now).
- Compress files on servers (e.g. run `zip` there) instead of only this phone's files.

## Terminal

- Drag to the top or bottom edge while selecting to scroll further back.
- Highlight links in the output (they open on tap but aren't underlined).

## Editors and previews

- Stream videos on other Android devices too (their sync protocol can't start mid-file; needs another way to read ranges).
- Picture editor: pinch to zoom while editing; work on pictures larger than 4096 px at full size.
- Video editor: keep saving when the editor is left (currently cancels); keep the original container (`.mkv`, `.webm`) instead of always MP4.
- Document preview: Word headers/footers and page breaks; PowerPoint charts, SmartArt, rotated shapes; the older `.doc`/`.ppt`/`.xls` formats.

## Project

- A signed release job in CI (needs the keystore and passwords as repository secrets).
- Play Store: justification for the `specialUse` foreground service and the battery-optimization exemption request, if published.
