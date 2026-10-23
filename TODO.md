# TODO

## Next up

- **SSH key login.** Import or generate keys (ed25519/RSA) for saved servers, as an alternative to passwords. Add a way to see and reset trusted host keys, for when a server's identity changes legitimately.
- **Zip files.** Browse an archive like a folder, extract all or a selection, and compress selected files into a zip. Explorer recognises archives (`FileKind.Archive`) but hands them to other apps.
- **Background transfers.** Run large copies to/from servers and devices in a foreground service with a progress notification and cancel, so they finish when the app is left (like `TerminalService`).
- **Audio player.** In-app playback with a mini player, background play and lock-screen/media controls (Media3 session).
- **Storage cleanup.** Large files, duplicates, old downloads and leftover APKs, like Google Files' "Clean" tab.

## Terminal

- Select and copy text (only the whole screen can be copied now).
- More than one session per server (tabs), instead of one window per server.
- Tap links (URLs) in the output to open them.
- Hide the extra keys automatically when a hardware keyboard is connected.

## Editors and previews

- Picture editor: pinch to zoom while editing; work on pictures larger than 4096 px at full size.
- Video editor: keep saving when the editor is left (currently cancels); keep the original container (`.mkv`, `.webm`) instead of always MP4.
- Document preview: Word headers/footers and page breaks; PowerPoint charts, SmartArt, rotated shapes; the older `.doc`/`.ppt`/`.xls` formats.
- Text editor: handle a file changed on the server while it's being edited (it's last-write-wins now).
- Remote previews: stream videos over SFTP/ADB instead of downloading the whole file first.
- Remote previews: a big download holds the server's SFTP session, so browsing that server waits until it finishes. Give downloads their own channel.
- Remote previews: after "Replace original" from the viewer, the viewer still shows the old picture until it's reopened.
- Remote files in Android/data are preview-only. Saving back would need a push path through the Wireless debugging connection.

## Project

- **Release build:** signing config, and check that R8 doesn't strip anything used by reflection (Media3 Transformer, JSch, libadb, Conscrypt).
- **CI:** run `testDebugUnitTest` (terminal replay tests, document reader tests) and a debug build on every push.
- Play Store: justification for the `specialUse` foreground service and the battery-optimization exemption request, if published.
