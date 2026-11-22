package com.cybershield.services;

import com.cybershield.dao.ActivityDAO;
import com.cybershield.dao.LoginHistoryDAO;
import com.cybershield.logger.AppLogger;
import com.cybershield.security.SessionManager;
import com.cybershield.vault.Vault;
import com.cybershield.vault.VaultSession;

import java.sql.SQLException;

/**
 * Records what each sign-in session does, for the history in Settings. Names of files,
 * logins and notes are encrypted with the open vault's key before they are stored; if
 * the vault is locked, the name is left out rather than stored in the clear. Recording
 * never interrupts the work it records: a failure is only logged.
 *
 * Sign-ins and their activity are kept for {@link #KEEP_DAYS} days.
 */
public final class ActivityLog {

    public static final int KEEP_DAYS = 30;

    /** What was done, with the words shown in Settings. */
    public enum Action {
        VAULT_CREATED("Set up the vault"),
        VAULT_TAKEN_OVER("Moved the shared vault into this account"),
        VAULT_UNLOCKED("Unlocked the vault"),
        VAULT_LOCKED("Locked the vault"),
        VAULT_AUTO_LOCKED("Vault locked itself (no activity)"),
        FILE_OPENED("Opened a protected file"),
        FILES_PROTECTED("Protected files"),
        FILES_RESTORED("Restored files"),
        FILE_EXPORTED("Exported a copy of a file"),
        VAULT_CHECKED("Checked the protected files"),
        STORE_BACKED_UP("Backed up the protected files"),
        STORE_RESTORED("Restored protected files from a backup"),
        STORE_RECOVERED("Recovered the list of protected files"),
        LOGIN_ADDED("Saved a login"),
        LOGIN_UPDATED("Changed a login"),
        LOGIN_DELETED("Deleted a login"),
        LOGIN_SHOWN("Showed a password"),
        PASSWORD_COPIED("Copied a password"),
        USERNAME_COPIED("Copied a username"),
        LOGINS_IMPORTED("Encrypted saved logins"),
        NOTE_CREATED("Created a note"),
        NOTE_OPENED("Opened a note"),
        NOTE_SAVED("Saved a note"),
        NOTE_DELETED("Deleted a note"),
        NOTE_COPIED("Copied text from a note"),
        TEXT_ENCRYPTED("Encrypted text with a password"),
        TEXT_DECRYPTED("Decrypted text with a password"),
        FILE_ENCRYPTED("Encrypted a file to share"),
        FILE_DECRYPTED("Decrypted a shared file"),
        HASH_MADE("Made a checksum"),
        REPORT_CREATED("Created a report"),
        REPORT_DELETED("Deleted a report"),
        SESSION_REPORT_SHARED("Shared a sign-in report"),
        SESSION_REPORT_DELETED("Deleted sign-in reports"),
        STEGO_HIDDEN("Hid something in a picture"),
        STEGO_REVEALED("Revealed something from a picture"),
        FILES_SHREDDED("Shredded files"),
        CLIPBOARD_CLEARED("Cleared the clipboard"),
        PHOTO_DETAILS_REMOVED("Removed details from photos"),
        TRACES_CLEANED("Cleaned Windows traces"),
        SETTINGS_SAVED("Changed auto-lock or clipboard settings"),
        THEME_CHANGED("Changed the theme"),
        ACCOUNT_PASSWORD_CHANGED("Changed the account password"),
        MASTER_PASSWORD_CHANGED("Changed the master password"),
        HISTORY_CLEARED("Cleared the sign-in history");

        private final String words;

        Action(String words) {
            this.words = words;
        }

        public String words() {
            return words;
        }

        /** The words for a stored action name, or the name itself if it is unknown. */
        public static String wordsFor(String name) {

            try {
                return valueOf(name).words();
            } catch (IllegalArgumentException | NullPointerException e) {
                return String.valueOf(name);
            }

        }

    }

    private static final ActivityDAO DAO = new ActivityDAO();

    private ActivityLog() {
    }

    /** Records an action with no name attached. */
    public static void record(Action action) {

        write(action, null, false);

    }

    /** Records an action with the name of the file, login or note it concerns (stored encrypted). */
    public static void record(Action action, String name) {

        write(action, name, true);

    }

    /** Records an action with a detail that isn't private (for example a setting's new value). */
    public static void recordPlain(Action action, String detail) {

        write(action, detail, false);

    }

    private static void write(Action action, String detail, boolean secret) {

        int userId = SessionManager.currentUserId();

        // Nobody signed in (for example during tests): nothing to record against.
        if (userId < 0) {
            return;
        }

        try {

            String stored = null;

            int vaultId = 0;

            if (detail != null && !detail.isBlank()) {

                if (!secret) {

                    stored = detail;

                } else if (VaultSession.isUnlocked() && VaultSession.current().userId() == userId) {

                    Vault vault = VaultSession.current();

                    stored = EncryptionService.getInstance().encrypt(detail);

                    vaultId = vault.id();

                }

            }

            DAO.insert(userId, SessionManager.historyId(), action.name(), stored, vaultId);

        } catch (SQLException | RuntimeException e) {

            AppLogger.error("Couldn't record activity " + action + ": " + e.getMessage());

        }

    }

    /**
     * The detail of an event as text to show: plain details as they are, encrypted ones
     * decrypted when their vault is open, otherwise a note that the vault is locked.
     */
    public static String readable(ActivityDAO.Event event) {

        if (event.detail() == null) {
            return "";
        }

        if (event.vaultId() == 0) {
            return event.detail();
        }

        if (VaultSession.isUnlocked() && VaultSession.current().id() == event.vaultId()) {

            try {
                return EncryptionService.getInstance().decrypt(event.detail());
            } catch (RuntimeException e) {
                return "(can't be read)";
            }

        }

        return "(unlock the vault to see the name)";

    }

    /** Removes sign-ins and activity older than {@link #KEEP_DAYS} days. Safe to call often. */
    public static void purgeOld() {

        try {

            int events = DAO.purgeOlderThan(KEEP_DAYS);

            int signIns = new LoginHistoryDAO().purgeOlderThan(KEEP_DAYS);

            if (events + signIns > 0) {
                AppLogger.info("Removed " + signIns + " sign-ins and " + events + " activity records older than " + KEEP_DAYS + " days.");
            }

        } catch (SQLException e) {

            AppLogger.error("Couldn't remove old history: " + e.getMessage());

        }

    }

}
