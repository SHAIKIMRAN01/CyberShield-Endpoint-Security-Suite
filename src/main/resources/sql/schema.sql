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
-- VAULT
----------------------------------------------------

CREATE TABLE IF NOT EXISTS vault
(
    id INTEGER PRIMARY KEY CHECK(id = 1),

    salt BLOB NOT NULL,

    verification_data TEXT NOT NULL,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

----------------------------------------------------
-- PASSWORD VAULT
----------------------------------------------------

CREATE TABLE IF NOT EXISTS password_entries
(
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    user_id INTEGER NOT NULL,

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
----------------------------------------------------

CREATE TABLE IF NOT EXISTS settings
(
    id INTEGER PRIMARY KEY CHECK(id = 1),

    theme TEXT DEFAULT 'Dark',

    auto_lock_minutes INTEGER DEFAULT 5,

    clipboard_clear_seconds INTEGER DEFAULT 30,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
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
ON login_history(user_id);

CREATE INDEX IF NOT EXISTS idx_protected_files_item
ON protected_files(item_id);