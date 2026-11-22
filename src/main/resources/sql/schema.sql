PRAGMA foreign_keys = ON;

----------------------------------------------------
-- USERS
----------------------------------------------------

CREATE TABLE IF NOT EXISTS users
(
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    username TEXT NOT NULL UNIQUE,

    email TEXT NOT NULL UNIQUE,

    password_hash TEXT NOT NULL,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    last_login TIMESTAMP,

    active INTEGER DEFAULT 1
);

----------------------------------------------------
-- VAULTS: one per account.
-- The vault key encrypts that account's passwords, notes
-- and protected files. It is stored only wrapped (encrypted)
-- with a key derived from the master password (PBKDF2), so
-- changing the master password rewraps this one value.
-- vault_salt names the vault and is written into the header
-- of every protected file.
--
-- Databases from before accounts had their own vaults also
-- have a one-row table called vault. That shared vault is
-- moved into an account the first time it is unlocked.
----------------------------------------------------

CREATE TABLE IF NOT EXISTS user_vaults
(
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    user_id INTEGER NOT NULL UNIQUE,

    vault_salt BLOB NOT NULL,

    kdf_salt BLOB NOT NULL,

    kdf_iterations INTEGER NOT NULL,

    wrapped_key BLOB NOT NULL,

    verification_data TEXT NOT NULL,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    password_changed_at TIMESTAMP,

    FOREIGN KEY(user_id)
        REFERENCES users(id)
        ON DELETE CASCADE
);

----------------------------------------------------
-- PASSWORD VAULT
-- vault_id is NULL only for entries saved before accounts
-- had their own vaults (they belong to the shared vault).
----------------------------------------------------

CREATE TABLE IF NOT EXISTS password_entries
(
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    user_id INTEGER NOT NULL,

    vault_id INTEGER,

    website TEXT NOT NULL,

    username TEXT NOT NULL,

    password TEXT NOT NULL,

    notes TEXT,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY(user_id)
        REFERENCES users(id)
        ON DELETE CASCADE
);

----------------------------------------------------
-- SECURE NOTES
----------------------------------------------------

CREATE TABLE IF NOT EXISTS secure_notes
(
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    user_id INTEGER NOT NULL,

    vault_id INTEGER,

    title TEXT NOT NULL,

    content TEXT NOT NULL,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY(user_id)
        REFERENCES users(id)
        ON DELETE CASCADE
);

----------------------------------------------------
-- LOGIN HISTORY
-- One row per sign-in attempt. status is SUCCESS, FAILED,
-- or LOCKED (refused while failed attempts make it wait).
-- logout_time is filled in when that sign-in ends.
----------------------------------------------------

CREATE TABLE IF NOT EXISTS login_history
(
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    user_id INTEGER NOT NULL,

    login_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    logout_time TIMESTAMP,

    ip_address TEXT,

    device_name TEXT,

    status TEXT,

    FOREIGN KEY(user_id)
        REFERENCES users(id)
        ON DELETE CASCADE
);

----------------------------------------------------
-- SETTINGS
-- settings is the original single-row table and only
-- supplies defaults now. Each account's own choices are
-- kept in user_settings.
----------------------------------------------------

CREATE TABLE IF NOT EXISTS settings
(
    id INTEGER PRIMARY KEY CHECK(id = 1),

    theme TEXT DEFAULT 'Dark',

    auto_lock_minutes INTEGER DEFAULT 5,

    clipboard_clear_seconds INTEGER DEFAULT 30,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_settings
(
    user_id INTEGER PRIMARY KEY,

    auto_lock_minutes INTEGER NOT NULL DEFAULT 5,

    clipboard_clear_seconds INTEGER NOT NULL DEFAULT 30,

    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY(user_id)
        REFERENCES users(id)
        ON DELETE CASCADE
);

----------------------------------------------------
-- REPORTS
----------------------------------------------------

CREATE TABLE IF NOT EXISTS reports
(
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    report_name TEXT,

    generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    report_type TEXT,

    file_path TEXT
);

----------------------------------------------------
-- FILE VAULT
-- A protected item is one selected file or folder.
-- Its details (name, original path, sizes) are encrypted
-- with the vault key, so the database never shows them.
-- Each protected file is an encrypted file in the hidden
-- store, named by its id.
----------------------------------------------------

CREATE TABLE IF NOT EXISTS protected_items
(
    id TEXT PRIMARY KEY,

    user_id INTEGER NOT NULL,

    vault_id INTEGER,

    info TEXT NOT NULL,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY(user_id)
        REFERENCES users(id)
        ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS protected_files
(
    id TEXT PRIMARY KEY,

    item_id TEXT NOT NULL,

    FOREIGN KEY(item_id)
        REFERENCES protected_items(id)
        ON DELETE CASCADE
);

----------------------------------------------------
-- INDEXES
-- The vault_id indexes are made in DatabaseInitializer,
-- after older databases have been given that column.
----------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_users_username
ON users(username);

CREATE INDEX IF NOT EXISTS idx_users_email
ON users(email);

CREATE INDEX IF NOT EXISTS idx_password_user
ON password_entries(user_id);

CREATE INDEX IF NOT EXISTS idx_notes_user
ON secure_notes(user_id);

CREATE INDEX IF NOT EXISTS idx_login_history_user
ON login_history(user_id, login_time);

CREATE INDEX IF NOT EXISTS idx_protected_files_item
ON protected_files(item_id);
