# Changelog

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
- **Status bar:** the line naming the encryption (AES-256-GCM, PBKDF2) is gone.
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
