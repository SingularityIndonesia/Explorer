# TODO

## Next up

Nothing queued; see the sections below.

## Done

- [x] Archives: .tar (plain, gz, bz2, xz), .7z and .gz open like folders; password-protected zips (ZipCrypto/AES) and 7z ask for the password.
- [x] Transfer progress in bytes (350 MB of 1.2 GB), Cancel stops mid-file and removes the partial copy, queued count shown.
- [x] Editing files in Android/data (saved back over the phone's own Wireless debugging connection); the text editor pauses saving when the file changed elsewhere (Reload theirs / Keep mine).
- [x] Remote previews: SSH videos stream with seeking; downloads, uploads and transfers use their own SFTP channel so browsing isn't blocked; edited pictures show their new version.
- [x] Release build: signing from keystore.properties or EXPLORER_* variables; the classes JSch, libadb and the services load by name checked in the R8 output.
- [x] CI: GitHub Actions runs the unit tests and builds the debug APK on every push and pull request.
- [x] Audio: a folder on an SSH server plays as a streamed queue; playback resumes where it stopped after reopening the app.
- [x] Terminal: links underlined; holding a selection at the top or bottom edge scrolls.
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

- Playlists.
- Queue a whole folder on another Android device (songs there are downloaded one at a time).

## Transfers

- Copying to and from Android/data runs as shell commands: no byte progress or mid-file cancel there.
- Total size for transfers from servers (now only bytes copied are shown; the total needs a remote size scan).

## Archives

- `.rar` (proprietary format; would need a dedicated library such as junrar) and password-protected 7z writing.
- Compress files on servers (e.g. run `zip` there) instead of only this phone's files.

## Terminal

- (nothing open)

## Editors and previews

- Stream videos on other Android devices too (their sync protocol can't start mid-file; needs another way to read ranges).
- Picture editor: pinch to zoom while editing; work on pictures larger than 4096 px at full size.
- Video editor: keep saving when the editor is left (currently cancels); keep the original container (`.mkv`, `.webm`) instead of always MP4.
- Document preview: Word headers/footers and page breaks; PowerPoint charts, SmartArt, rotated shapes; the older `.doc`/`.ppt`/`.xls` formats.

## Project

- A signed release job in CI (needs the keystore and passwords as repository secrets).
- Play Store: justification for the `specialUse` foreground service and the battery-optimization exemption request, if published.
