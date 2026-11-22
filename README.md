<img src="docs/logo.svg" width="96" alt="CyberShield's logo: a brown cartoon wolf guarding a treasure chest">

# CyberShield Endpoint Security Suite

A Windows desktop app that keeps passwords and files private on a shared PC. Every account has its own encrypted vault, and nothing leaves the computer. Built with Java 21, JavaFX 21 and SQLite.

![Home screen](docs/screenshots/home.png)

## Features

**Accounts**
- Register and sign in. Mistakes show under the form, and Enter signs in.
- After 5 failed attempts the account waits 30 seconds. The wait doubles with each further failure, up to 15 minutes. Unknown usernames get the same answers and waits, so they don't reveal which accounts exist.
- Every sign-in attempt is recorded (time, computer, result, sign-out time), together with what that sign-in did: files opened, logins and notes changed, tools used. Names in it are encrypted with the vault key. It is all kept for 30 days, and clearing it sooner needs the master password.

**Home**
- The vault state, saved logins, protected files and the last sign-in at a glance.
- A security score from 0 to 100 with the hints that would raise it most, and quick actions.

**Vault (one per account)**
- A master password opens the account's vault, and the vault locks itself after a few idle minutes.
- Changing the master password is instant: only the lock on the vault key changes, not the data.

**Password Vault**
- Save, edit, search and delete logins (Delete asks first). Every field is encrypted.
- Categories and favourites, and sorting by favourites, name, last change or weakest first.
- Password health marks weak, reused and year-old passwords.
- Generator with length, digit and symbol options, and a strength meter.
- Copy with an automatic clipboard clear (the delay is set in Settings).

**Secure Notes**
- Notes encrypted with the vault key, in a searchable list with an editor that saves as you type.
- Copied text clears itself from the clipboard like passwords, and notes leave the screen when the vault locks.

**File Security**
- Pick files and folders from anywhere: browse, tick, drag and drop, or Add files / Add folder.
- They are encrypted into a hidden store and the originals are wiped. Restore puts them back exactly: names, times and attributes.
- A private viewer shows photos, text and code, PDFs, video and music inside CyberShield only, with copying blocked. Photos turn left and right and follow the camera's orientation.
- Videos and songs of any size play straight from the encrypted store: MP4, MOV, MKV, AVI, WebM, phone videos in HEVC, MP3, M4A, FLAC and more. They are decrypted a piece at a time in memory, never written to disk.
- Tools: check every stored file, back the store up to another drive and restore it, and rebuild a lost list from the store.

**Encryption Center** (works without the vault)
- Encrypt text or a file with a password, to share it. Files become `.cshield` files that open on any PC running CyberShield.
- SHA-256 and SHA-512 checksums of text or files, compared with an expected value.
- A password strength checker and generator.

**Reports**
- A security summary on screen and as a PDF: the score, password health, vault locks, protected files, 30 days of sign-ins and a count of everything done.
- A report never contains passwords, note text, website names or file names. Saved reports are listed with Open, Show in folder and Delete.
- Sign-ins by date: pick any days in the last 30 to see every attempt, with wrong passwords and refused attempts counted, and save it as a PDF.
- A monthly report, if you turn it on: made by itself the first time the vault is unlocked in a month, saved in a folder you choose and listed with the others.
- A PDF of every sign-in: when, on which computer, and everything done, naming the files, logins and notes. Each is kept encrypted with the vault key and opens only inside CyberShield. Sharing one saves a copy protected with a password you choose; sharing or deleting asks for the master password.

**Steganography** (works without the vault)
- Hide a message or a small file inside a picture, locked with a password. Only the lowest bit of some colour values changes, so the picture looks the same.
- Before you start it shows how much fits; afterwards, the picture before and after, and where it changed.
- Reveal a message or file from a picture with the same password. Pictures are saved as PNG, because JPEG (and apps that shrink photos) would wipe what is hidden.

**Privacy Tools** (works without the vault)
- Shred files and folders: each file is renamed, overwritten with random data and deleted. The same safety rules as File Security apply, links and junctions are never followed, and a file open in another program is left whole.
- Clear the clipboard now.
- Photo details: see what JPEG and PNG photos give away (location, camera, time, EXIF, XMP, IPTC, comments, the extra pictures phones add) and save clean copies beside them. The picture isn't re-compressed, and its orientation is kept.
- Clean Windows traces: recent items, jump lists, the thumbnail cache and temporary files older than a day. It looks first and removes only what you tick; files in use are skipped.
- An on-screen keyboard beside the password on the sign-in and vault screens, against keyloggers. Mix the keys puts them in a new order.

**Settings**
- Auto-lock minutes and clipboard clear time, per account.
- Change the account password or the master password.
- Sign-in history, with what each sign-in did.
- Back up the protected files to another drive, and restore them (it asks to unlock the vault first if needed).

## Screenshots

| Sign in | Password Vault |
|---|---|
| ![Sign in](docs/screenshots/sign-in.png) | ![Password Vault](docs/screenshots/password-vault.png) |
| **Secure Notes** | **Encryption Center** |
| ![Secure Notes](docs/screenshots/secure-notes.png) | ![Encryption Center](docs/screenshots/encryption-center.png) |
| **File Security** | **File Security tools** |
| ![File Security](docs/screenshots/file-security.png) | ![File Security tools](docs/screenshots/file-security-tools.png) |
| **Steganography** | **Privacy Tools** |
| ![Steganography](docs/screenshots/steganography.png) | ![Privacy Tools](docs/screenshots/privacy-tools.png) |
| **Settings** | |
| ![Settings](docs/screenshots/settings.png) | |

## Requirements

- Windows 10 or 11, 64-bit. File Security uses Windows file attributes, and the video player's FFmpeg libraries are the Windows 64-bit ones.
- JDK 21 or newer.
- Maven 3.9 or newer.

## Run

```
mvn javafx:run
```

From IntelliJ IDEA, open the project as a Maven project and run the `javafx:run` goal from the Maven tool window (Plugins → javafx). That goal passes the one JVM option the video player needs:

```
--add-exports javafx.media/com.sun.media.jfxmedia.locator=ALL-UNNAMED
```

## Test

```
mvn test
```

The tests cover:
- encryption and the protected-file format, including tampering, and the Encryption Center's shared text, files and checksums;
- vaults: creating, unlocking, changing the master password, the PBKDF2 upgrade, and taking over the old shared vault;
- sign-in, waits, history and the activity log with its 30-day limit, and registration rules;
- Password Vault and Secure Notes: every field encrypted, older logins converted, password health, privacy between accounts;
- File Security: protect, restore, the safety rules, export, the viewer (photo turns, zoom), the check, recovery and backups;
- video streamed from encrypted files: MKV, AVI, MOV and MP4, big pictures, turned phone videos, seeking, songs, damaged files, and reading any part of a protected file;
- sign-in reports: stored encrypted, shared and deleted only with the master password, private to each account;
- the logo and the opening animation;
- the security score, and reports (no secrets in the PDF), sign-ins by date and the monthly report;
- steganography: messages and files in and out of pictures, wrong passwords, too-big messages, and JPEG wiping the bits;
- Privacy Tools, on made-up files and folders only: shredding (refused places, junctions, open files, stopping), photo details in JPEG and PNG, the traces cleaner, the on-screen keyboard;
- settings, backups from Settings, and every screen opening.

Each test uses its own database and file store in a temp folder, so the real data is never touched. `ScreensTest` opens the screens with JavaFX (in windows that are never shown), so it needs a desktop session.

To save a picture of every screen:

```
mvn test -Dtest=ScreensTest -Dcybershield.snapshots=target/screens
```

## Where data is kept

| What | Where |
|---|---|
| Accounts, encrypted logins, settings, sign-in history | `database/cybershield.db` in the folder the app runs from |
| Encrypted protected files (hidden and system folder) | `%LOCALAPPDATA%\CyberShield\FileVault` |
| A copy of each vault's wrapped key, for recovery | `%LOCALAPPDATA%\CyberShield\FileVault\keys` |
| What each sign-in did (names encrypted), removed after 30 days | the database |
| Backups of protected files | the folder you choose, as `CyberShield backup <date and time>` |
| Reports | the PDF wherever you save it; the list of them in the database |
| Monthly reports | `Documents\CyberShield reports`, or the folder you choose |
| Pictures with something hidden | where you save them, as PNG |

The database is not in git (see `.gitignore`).

## Security design

**Account passwords** are stored as BCrypt hashes.

**Vaults**
- Each vault has a random 256-bit key. It is stored only wrapped with AES-256-GCM, under a key derived from the master password with PBKDF2-HMAC-SHA512 (210,000 rounds, 16-byte random salt).
- Changing the master password rewraps that one key in one transaction. Vaults wrapped with fewer rounds are rewrapped on their next unlock.

**Saved data** is encrypted with AES-256-GCM and a fresh 12-byte IV for every value.

**Protected files** use the CSF1 format:
- Every file gets its own random key, wrapped with the vault key.
- Data is encrypted in 1 MiB chunks, with nonces built from a block type and a counter.
- The header is authenticated with every block, so changed, reordered, moved or cut-off blocks are refused.
- An original is replaced only after its encrypted copy has been written, flushed to disk and read back. It is then overwritten and deleted.

**If the database is lost**, the master password and the key file in `keys\` still open the files: File Security → Tools → Recover the list finds them in the store and lists them again.

**Backups** copy the encrypted files as they are, with an encrypted list of them and the vault's key file. Nothing is decrypted, so only the master password opens a backup.

**Shared encryption** (Encryption Center) uses AES-256-GCM with a key derived from the chosen password (PBKDF2-HMAC-SHA512, 210,000 rounds, random salt). Files are sealed in 1 MiB chunks, so a changed or cut-off file is refused, and a wrong password leaves nothing behind.

**Steganography** seals the message or file the same way before hiding it, then spreads it over the lowest bit of the red, green and blue values, behind a short header. Without the password the bits are just noise; a wrong password is refused.

**Shredding** renames each file to a random name, overwrites it with random data, flushes it to the disk, empties it and deletes it. The places File Security refuses (drives, the user folder, system and program folders, CyberShield's own files) are refused here too.

**Limits**
- Overwriting a file (File Security, the shredder) doesn't guarantee it is gone from an SSD or a USB stick, and OneDrive or other synced folders keep their own copies.
- Video playback uses an internal JavaFX class, so re-test it when JavaFX is upgraded.
- Video and music play through FFmpeg. If FFmpeg can't load, the viewer falls back to JavaFX's player, which holds a file of up to 1 GB in memory.
- A report is a plain PDF on purpose: it holds no secrets.

### Upgrading from v1

In v1 all accounts shared one vault. The first time an account that has items in it opens the vault after updating, CyberShield asks for that vault's master password once. Its items then move into the account's own vault, with the same key, now wrapped with 210,000 rounds. Accounts with nothing in the shared vault set up their own. The shared vault is removed once no account has items left in it.

## Design

- **Colours:** warm off-white and brown in a 60-30-10 split. Off-white paper covers most of the screen, brown gives the structure (sidebar, headings, main buttons), and one caramel accent marks focus and highlights.
- **Layout:** follows the rule of thirds. Lists, navigation and explanations take one third and the main content the other two, and summary cards come three to a row.
- **Logo:** a brown cartoon wolf guarding a treasure chest (`docs/logo.svg`), drawn from shapes in `ui/Logo.java`, so it is sharp from the window icon up.
- **Opening:** a golden key flies into the chest's lock and turns, the wolf steps back, the lid opens and light fills the screen, then the sign-in screen appears. A click or any key skips it; `-Dcybershield.noIntro=true` turns it off.
- **Sidebar:** the ☰ button (or Ctrl+B) shrinks it to its icons and back; ✕ closes it, and ☰ brings it back.
- **One stylesheet:** the palette lives in `src/main/resources/css/theme.css`.

## Project layout

```
src/main/java/com/cybershield/
  app/          entry point
  config/       paths and constants
  database/     schema set-up and upgrades
  dao/          users, sign-in history, activity, settings
  encryption/   AES-GCM, PBKDF2, the CSF1 file format, shared files, checksums
  files/        File Security: protect, hide, restore, check, back up, recover
  notes/        Secure Notes data
  password/     Password Vault data and password health
  privacy/      the shredder, photo details, the Windows traces cleaner
  reports/      security reports, sign-ins by date, the monthly report, a PDF of every sign-in
  security/     BCrypt, session
  services/     sign-in and registration, activity log, security score, the open vault key
  stego/        hiding messages and files in pictures
  vault/        per-account vaults, auto-lock, key backup
  ui/           screens (controllers) and the file viewer
  utils/        validation, generator, formatting
src/main/resources/
  fxml/         screen layouts
  css/          theme.css and one stylesheet per screen
  sql/          schema.sql
src/test/java/  JUnit tests
```

## Roadmap

| Phase | Weeks | Work | State |
|---|---|---|---|
| 0 · Stabilise | 1–2 | Tests, README, SessionManager fix, header contrast | Done |
| 1 · Accounts & Settings | 2–6 | Registration, sign-in history and waits, one vault per account, Settings, master password change | Done |
| 2 · Core modules | 6–11 | Secure Notes, every Password Vault field encrypted, home screen score | Done |
| 3 · Tools & reports | 11–15 | Encryption Center, PDF reports, File Security backup and recovery | Done |
| 4 · Extras | 16–20 | Steganography, Privacy Tools, sign-ins by date, the monthly report, backups from Settings | Done |
| 5 · Polish & release | 20–21 | Light and dark themes, installer, automatic builds | Next |

## License

All rights reserved. See [LICENSE](LICENSE).
