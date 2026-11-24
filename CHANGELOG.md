# Changelog

## Unreleased: vault-opening and drawer animations

### Added
- **Unlocking the master vault** now plays a slow, skippable flourish over the screen you unlocked into — from the Home banner, the Password Vault, Secure Notes, File Security or Reports: a steel vault door whose dial spins, then the door swings open and fades to reveal the screen. It is purely decorative — the screen is already loaded underneath — drawn from shapes so it stays sharp at any size, and a click skips it.
- **Opening File Security** now slides the screen open downward like a drawer, slowly, as its files appear.
- Both are gentle and unhurried by design, and both are turned off together with the sign-in intro by `-Dcybershield.noIntro=true`.

### Changed
- **Tests:** 292 JUnit tests now, including one that unlocks the vault through the UI and checks the flourish appears.

## Unreleased: Backup

### Added
- **Backup** (new sidebar entry under Tools; works without the vault). Makes one compressed `.zip` copy of the folders you choose, saved where you choose. It starts with the usual folders (Pictures, Documents, Downloads, Desktop, Videos and the Windows Contacts folder) and you can add or remove any. Filters: only files changed in the last so-many days (7 by default) and skip anything over a set size (500 MB by default); it can also include a text list of installed programs. It **previews first** — count, total size, and what the filters left out — and copies nothing until you press Back up now; progress is shown, it can be stopped (removing the half-written file), and it opens the folder when done. Only the chosen folders are read and nothing is changed. It does **not** reach into other apps' private data (WhatsApp, text messages) — it copies your own files, not another app's store.

### Changed
- **Tests:** 277 JUnit tests now, including the date/size filters, the folder walk and archive round-trip on temp files, stopping, de-duplicating clashing names, and the installed-apps parser.

## Unreleased: Network Analyzer

### Added
- **Network Analyzer** (new sidebar entry under Tools; works without the vault). Press Refresh for a read-only snapshot of how the network is being used right now: every active TCP connection and listening port, the UDP endpoints apps are using, and the names Windows has looked up recently (the DNS cache) — each tied to the program behind it, with a plain-words explanation. It names well-known ports (HTTPS, DNS, email, Remote Desktop, file sharing and more), colour-codes where each connection goes (this computer, your local network, or the Internet) for both IPv4 and IPv6, and sorts the outgoing Internet connections to the top. It reads connection details only — never traffic contents — needs no administrator, and changes nothing. Full packet capture needs a driver and admin, so it isn't faked.
- The reading is one short, read-only PowerShell pass (`Get-NetTCPConnection`, `Get-NetUDPEndpoint`, process names, `Get-DnsClientCache`) behind a tested parser; the service, address-scope and explanation logic is pure.
- Each remote address is resolved to its website name by cross-referencing the DNS cache that was read, so a browser's many separate per-site connections show as `www.google.com`, `fonts.gstatic.com` and the like instead of bare IP numbers; rows are grouped by app so one program's connections sit together.

### Changed
- **Tests:** 264 JUnit tests now, including the port/service and IPv4/IPv6 scope rules, the per-connection explanations, and the new screen opening.

## Unreleased: Security Scan

### Added
- **Security Scan** (new sidebar entry under Tools; works without the vault). A **quick scan** reads how the laptop is set up to protect itself — antivirus, firewall, drive encryption, automatic sign-in, lock-on-wake, camera/microphone/location access and start-up programs — and marks each good, worth a look, needs attention, or "couldn't check" (never a guess), with advice. A **deep scan** also walks the whole user folder and flags files worth a second look: a program disguised as a document (photo.jpg.exe), a hidden program, an installer left in Downloads or a temp folder, a loose script, or a start-up item, each with a SHA-256 fingerprint. It is read-only — nothing is opened, moved or changed — and can be stopped at any time. Honest heuristics, not virus detection: CyberShield never claims a file is malware.
- The reading of Windows' settings is one short, read-only PowerShell pass; anything that needs an administrator (such as BitLocker) is reported as "couldn't check" rather than guessed.
- **Scan a device:** the scan screen now lists plugged-in external drives (USB sticks, SD cards, external disks, and phones that mount as a drive) and can look through one for files worth a second look. On a device it also flags an `autorun.inf` and any program or script on the drive. A phone only appears if Windows gives it a drive letter (MTP file-transfer mode has none); drives are read-only and the internal disk is never offered.

### Changed
- **Tests:** 247 JUnit tests now, including the risky-file rules on made-up names and a temp folder, the posture judgement on made-up settings, and the new screen opening.

## Unreleased: a password generator and file-password recovery

### Added
- **Password generator with three modes** (Encryption Center → Password generator): random characters (choose upper/lower/digits/symbols, length, and "no look-alikes"); a memorable passphrase of words from a built-in list, optionally capitalised and with a number; or a numeric PIN. Each shows how many passwords it could make and the strength in bits. A new 426-word passphrase list.
- **Recover a file password** (Encryption Center → Recover a file password), for a file **you own**: it tries the passwords people actually choose — your own hints (names, pets, places, dates, phone numbers) in their common forms, plus a short list of the most common passwords — so it recovers a password you picked and forgot, but not a long random one. Works on `.cshield` files and password-protected PDFs, with a background run, progress and Stop. You can import your own contacts (vCard or CSV) as hints. The screen is clear it is for files you own; there is no "crack any file" mode and no third-party wordlists.
- `SharedCipher.fileOpensWith` checks a `.cshield` password from the file's header alone, without decrypting it.

### Changed
- **Tests:** 216 JUnit tests now, including that a long random password is never "recovered".

## Unreleased: Phase 5, the dark theme and the Windows installer

### Added
- **Dark theme** (Settings → Appearance): light or dark, chosen per account and shown at once in every window. The sign-in screen shows the theme the last account used. `theme-dark.css` gives the colour names of `theme.css` deep brown values; every text colour in it is at least 4.5:1 against its background.
- **Windows installer:** `scripts\build-installer.ps1` makes `CyberShield-1.0.0.msi` with jpackage and WiX 3.14: a per-user install (no administrator) into `%LOCALAPPDATA%\CyberShield App`, with its own Java 25 runtime trimmed to the modules the app uses, Start menu and desktop shortcuts, an uninstall entry, the app icon, and an upgrade code so newer versions replace older ones. Tried here: installed silently, started, removed cleanly.
- **Automatic builds:** a GitHub Actions workflow builds and runs every test on Windows with Java 21 and 25 on each push and pull request; a version tag or a manual run also builds the installer. WiX is downloaded there and checked against its SHA-256.

### Changed
- **One data folder:** the database moves from `database\cybershield.db` beside the app to `%LOCALAPPDATA%\CyberShield\Data`, next to the protected-files store, so the installed app and a copy run from the project share it. On the first start an older database is copied across by SQLite itself (`VACUUM INTO`) through a `.part` file; the old file is kept, an existing database is never replaced, and a failed copy stops the start with a message instead of starting empty.
- **Logging:** one line per event (INFO, WARN, ERROR) on the console and in `%LOCALAPPDATA%\CyberShield\logs\cybershield.log`, kept to five files of 1 MB. Paths in messages become `<path>`, accounts appear by number, errors keep their stack trace in the file, and unexpected errors on any thread are logged. Messages on screen point at the log file instead of a console. Log4j, declared but never used, is gone.
- **Build:** Maven compiles with `--release 21`, and the jar and dependency plugins are pinned and copy the libraries for the installer.
- **Database upgrades on start:** `user_settings.theme` and a new `app_settings` table.
- **Tests:** 196 JUnit tests now. They also pass on the installer's Java runtime.

### Fixed
- **A flaky test:** JavaFX's own video player (the fallback when FFmpeg can't run) sometimes fails to start a good MP4 on Windows. The two tests that use it try up to three times, and skip where JavaFX can't play such a file at all.
- **The schema loader** split statements on every semicolon, so one in a comment cut a statement in two. Comments go first now.

## Unreleased: Phase 4, Steganography and Privacy Tools

### Added
- **Steganography** (no vault needed):
  - hide a message or a small file inside a picture, locked with a password (AES-256-GCM, PBKDF2) and spread over the lowest bit of the colour values;
  - how much fits is shown before you start, and the picture before and after, with Show what changed marking every changed pixel;
  - reveal with the same password; a hidden file is saved where you choose, under a safe name;
  - pictures are always saved as PNG, and the screen says why: JPEG wipes the hidden bits.
- **Privacy Tools** (no vault needed):
  - **Shred files:** files and folders, picked or dropped, are listed with their total before anything happens, then renamed, overwritten with random data and deleted after a confirmation. File Security's safety rules apply; links and junctions are never followed, even when chosen directly; a file open in another program stays whole and is reported; Stop leaves the rest untouched.
  - **Clear the clipboard** now.
  - **Photo details:** what JPEG and PNG photos give away (location, camera, time, EXIF, XMP, IPTC, comments, text blocks, the extra pictures phones add) and clean copies saved beside them. The image data is copied as it is; only the orientation is kept.
  - **Windows traces:** recent items, jump lists, the thumbnail cache and temporary files older than a day. It looks first and removes only what you tick (jump lists wait to be ticked, because pinned items go too). Files in use are skipped.
  - **On-screen keyboard:** a keyboard button beside the password on the sign-in and vault screens, with Shift, Backspace and Mix the keys.
- **Reports:**
  - **Sign-ins by date:** pick the days (within the 30 kept) and see every attempt with the wrong passwords and refused attempts counted; Save as PDF.
  - **Monthly report:** when turned on, a security report is made the first time the vault is unlocked in a month, saved in the folder you choose (Documents\CyberShield reports at first) and listed with the others. A month that couldn't be saved is tried again at the next unlock. The status bar says when one is made.
- **Settings → Backup and restore:** File Security's encrypted backup and restore, with progress and Stop. When the vault is locked it offers to unlock it and comes back.

### Changed
- **The sidebar:** Steganography and Privacy Tools open their screens; the "coming soon" page is gone.
- **Database upgrades on start:** a new `report_schedule` table.
- **Tests:** 182 JUnit tests now. The screenshot test covers the new screens, and the README screenshots are new.

## Unreleased: videos of any size, sign-in reports, a new look

### Fixed
- **Videos that wouldn't play:** the viewer refused anything over 1 GB, because it decrypted a whole file into memory first (and Java can't hold more than 2 GB in one piece). Videos and songs now stream from the encrypted store: each 1 MB piece is decrypted and checked when the player reaches it, so size no longer matters and nothing decrypted touches the disk. Checked with a 2.3 GB video, including seeking past the 2 GB mark.

### Added
- **Any video format:** playback now uses FFmpeg (JavaCV 1.5.14, Windows 64-bit libraries), so MOV, MKV, AVI, WebM, HEVC phone videos, FLAC, OGG and more play too. Turned phone videos are shown upright, and pictures larger than 1920 × 1080 are scaled down while decoding.
- **A PDF of every sign-in** (Reports → Sign-in reports): when, on which computer, and everything done, naming the files, logins and notes. Each is stored encrypted with the vault key and opens only inside CyberShield. A report is kept at sign-out, or when the app is closed with the vault open; any sign-in still without one gets it the next time the vault is unlocked.
  - Share saves a copy protected with a password you choose (AES-256 PDF encryption), after the master password is checked. The copy's password can't be the master password.
  - Delete and Delete all need the master password. A deleted report isn't made again.
- **New logo:** a brown cartoon wolf guarding a treasure chest, on the sign-in screen, in the sidebar and as the window icon.
- **Opening animation:** a golden key flies into the chest's lock and turns, the wolf steps back, the lid opens and light fills the screen before the sign-in screen appears. A click or any key skips it.
- **Sidebar:** the ☰ button in the header (or Ctrl+B) shrinks it to its icons, with the names as tooltips, and widens it again; ✕ closes it and ☰ brings it back.

### Changed
- **No encryption names on screen:** the status bar's line naming AES-256-GCM and PBKDF2, and the sign-in screen's "AES-256 encryption" point, are gone.
- **Database upgrades on start:** a new `session_reports` table.
- **Tests:** 151 JUnit tests now.

## Unreleased: Phases 2 and 3

### Added
- **Secure Notes:** notes encrypted with the vault key (title and text) in a searchable list, with an editor that saves a second after you stop typing. Delete asks first. Copied text clears itself from the clipboard after the time set in Settings. Notes leave the screen when the vault locks.
- **Password Vault:**
  - every field is encrypted (website, username, password, notes, category); logins saved before are converted the first time the vault opens;
  - Delete asks first;
  - categories and favourites, sorting (favourites first, A–Z, recently changed, weakest first) and a "Needs attention" filter;
  - password health marks weak, reused and year-old passwords;
  - the generator has length, digit and symbol options.
- **Home screen:** a security score from 0 to 100 with the hints worth most, and quick actions: lock now, protect files, new login, new note.
- **Encryption Center** (no vault needed):
  - encrypt text or files with a password, to share them; files become portable `.cshield` files;
  - SHA-256 and SHA-512 checksums of text or files, compared with an expected value;
  - a password strength checker and generator.
- **File Security tools:**
  - check every protected file;
  - back the store up to another drive (encrypted; the master password opens it) and restore from it;
  - rebuild a lost list from the store.
  - A running job's progress shows again after leaving the screen and coming back.
- **Reports:** a security summary on screen and as a PDF: score, password health, vault locks, protected files with the last check and backup, 30 days of sign-ins, and a count of everything done. It holds no passwords, note text, website names or file names. Saved reports are listed per account with Open, Show in folder and Delete (to the Recycle Bin).
- **What each sign-in did:** files opened, logins and notes changed, tools used, listed under each sign-in in Settings. Names are encrypted with the vault key. Activity and sign-ins older than 30 days are removed automatically, and clearing them sooner needs the master password.
- **Photo rotation:** turn photos left and right in the viewer; photos follow the camera's orientation.
- **Tests:** 130 JUnit tests now.

### Fixed
- **Video and music sound:** MP4 videos and M4A songs with AAC sound now play on PCs where JavaFX can't decode AAC. The sound is decoded in Java (JAAD) and kept in step with the picture. Sound over about 50 minutes is too big to decode in memory: such a video plays muted, with a note.
- **Shaking zoom:** a photo fitted to the window no longer shakes at some zoom levels (seen between 14% and 17%). The scroll bars were appearing and disappearing in a loop.
- **Cut-off lines:** wrapped hints in Settings and Reports now show in full.

### Changed
- **Database upgrades on start:** a new `activity_log` table; `password_entries` gets `category`, `favourite` and `fields_encrypted`; `reports` gets `user_id`.
- **New library:** JAAD 0.8.8, an AAC decoder (public domain).

## Unreleased: Phase 0 and Phase 1

### Added
- **Registration screen**, linked from sign-in, with the username, email and password rules and a strength meter.
- **Sign-in errors on screen**, with one message for an unknown user or a wrong password. Enter signs in.
- **Waiting after failed sign-ins:** 30 seconds after 5 failures, doubling up to 15 minutes. Unknown usernames wait the same way.
- **Sign-in history:** every attempt is recorded (time, computer, result, sign-out time) and shown in Settings and on the home screen.
- **One vault per account,** each with its own master password and data. The shared vault from v1 moves into the account that owns its items the first time it is unlocked.
- **Settings screen:**
  - auto-lock minutes and clipboard clear time, saved per account;
  - account password change and master password change;
  - sign-in history.
- **Home screen:** vault state, the number of saved logins and protected files, and the last sign-in with any failed attempts since.
- **Key backup:** a copy of each vault's wrapped key in the file store's `keys` folder, so the master password can open protected files even if the database is lost.
- **OneDrive warning:** before File Security changes anything, its review step says which selected files are in OneDrive. Protecting them removes them from OneDrive on every device, and online-only files are downloaded first.
- **Tests:** 96 JUnit tests (`mvn test`) that use temp folders, never the real data. They include an upgrade from a v1 database, an MP4 video played from the encrypted store, and a test that opens every screen.
- **Project files:** README, CHANGELOG and LICENSE.

### Changed
- **New look:** brown and off-white palette with rule-of-thirds layouts, and one shared stylesheet (`theme.css`). This fixes the hard-to-read header text.
- **Faster password change:** the master password now wraps a random vault key, so changing it rewrites one value instead of re-encrypting everything.
- **Stronger key derivation:** PBKDF2 raised from 100,000 to 210,000 rounds. Older vaults are rewrapped on their next unlock.
- **Clipboard time:** the Password Vault's clipboard clear time now comes from Settings (it was fixed at 30 seconds).
- **Modules not built yet** show what is coming instead of doing nothing.
- **The database path** can be set with `-Dcybershield.db=...` (used by the tests).

### Fixed
- **SessionManager:** it now sits in the `security` folder that matches its package.
- **Generated passwords:** the shared generator now always includes upper and lower case letters, a digit and a symbol. Before, about one password in eight broke the app's own rules.
- **Text encryption:** it now always uses UTF-8.
- **Video and music errors:** when a video or song can't be played because Windows on this PC can't decode its AAC sound (most MP4 videos and M4A songs), the viewer now says so and suggests Export a copy, instead of showing an error code. WAV, MP3 and video-only MP4 play.

## v1 (21 November 2026)

- **Master vault:** set up and unlock with a master password (PBKDF2-HMAC-SHA512 and AES-256-GCM), with auto-lock after inactivity.
- **Password Vault:**
  - add, edit, delete and search entries;
  - password generator and strength meter;
  - copy with a 30-second clipboard clear.
- **File Security:**
  - protect files and folders picked from several places at once;
  - they are encrypted into a hidden store and the originals are wiped, and Restore puts them back;
  - system folders are refused.
- **Private viewer:** photos, text and code, PDFs, video and music, inside CyberShield only, with copying blocked.
