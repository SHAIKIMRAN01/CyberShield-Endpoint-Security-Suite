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
- A private viewer shows photos, text and code, PDFs, video and music inside CyberShield only, with copying blocked. Photos turn left and right and follow the camera's orientation. AAC sound (most MP4 videos, M4A songs) plays even where JavaFX can't decode it.
- Tools: check every stored file, back the store up to another drive and restore it, and rebuild a lost list from the store.

**Encryption Center** (works without the vault)
- Encrypt text or a file with a password, to share it. Files become `.cshield` files that open on any PC running CyberShield.
- SHA-256 and SHA-512 checksums of text or files, compared with an expected value.
- A password strength checker and generator.

**Reports**
- A security summary on screen and as a PDF: the score, password health, vault locks, protected files, 30 days of sign-ins and a count of everything done.
- A report never contains passwords, note text, website names or file names. Saved reports are listed with Open, Show in folder and Delete.

**Settings**
- Auto-lock minutes and clipboard clear time, per account.
- Change the account password or the master password.
- Sign-in history, with what each sign-in did.

**Coming next** (see the roadmap below): Steganography and Privacy Tools.

## Screenshots

| Sign in | Password Vault |
|---|---|
| ![Sign in](docs/screenshots/sign-in.png) | ![Password Vault](docs/screenshots/password-vault.png) |
| **Secure Notes** | **Encryption Center** |
| ![Secure Notes](docs/screenshots/secure-notes.png) | ![Encryption Center](docs/screenshots/encryption-center.png) |
| **File Security** | **File Security tools** |
| ![File Security](docs/screenshots/file-security.png) | ![File Security tools](docs/screenshots/file-security-tools.png) |
| **Settings** | |
| ![Settings](docs/screenshots/settings.png) | |

## Requirements

- Windows 10 or 11. File Security uses Windows file attributes; the rest runs anywhere Java does.
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
- File Security: protect, restore, the safety rules, export, the viewer (AAC sound, photo turns, zoom), the check, recovery and backups;
- the security score, and reports (no secrets in the PDF);
- settings, and every screen opening.

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

**Limits**
- Overwriting a file doesn't guarantee it is gone from an SSD.
- Video playback uses an internal JavaFX class, so re-test it when JavaFX is upgraded.
- AAC sound is decoded in memory. Over about 50 minutes it is too big: such a video plays muted, with a note, and such a song needs Export a copy.
- A report is a plain PDF on purpose: it holds no secrets.

### Upgrading from v1

In v1 all accounts shared one vault. The first time an account that has items in it opens the vault after updating, CyberShield asks for that vault's master password once. Its items then move into the account's own vault, with the same key, now wrapped with 210,000 rounds. Accounts with nothing in the shared vault set up their own. The shared vault is removed once no account has items left in it.

## Design

- **Colours:** warm off-white and brown in a 60-30-10 split. Off-white paper covers most of the screen, brown gives the structure (sidebar, headings, main buttons), and one caramel accent marks focus and highlights.
- **Layout:** follows the rule of thirds. Lists, navigation and explanations take one third and the main content the other two, and summary cards come three to a row.
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
  reports/      security reports and their PDF
  security/     BCrypt, session
  services/     sign-in and registration, activity log, security score, the open vault key
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
| 4 · Extras | 16–20 | Steganography, Privacy Tools | Next |
| 5 · Polish & release | 20–21 | Light and dark themes, installer, automatic builds | |

## License

All rights reserved. See [LICENSE](LICENSE).
