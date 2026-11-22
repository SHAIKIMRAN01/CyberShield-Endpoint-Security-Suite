# Changelog

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
