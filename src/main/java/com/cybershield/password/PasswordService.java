package com.cybershield.password;

import com.cybershield.logger.AppLogger;
import com.cybershield.services.ActivityLog;
import com.cybershield.services.EncryptionService;
import com.cybershield.vault.Vault;
import com.cybershield.vault.VaultSession;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Saved logins of the open vault. The website, username, password, notes and category
 * are all encrypted with the vault key before they reach the database. Entries from
 * before every field was encrypted are converted the first time they are read. Every
 * method needs the vault unlocked, and works only on that vault's entries.
 */
public class PasswordService {

    private final PasswordDAO dao =
            new PasswordDAO();

    private final EncryptionService encryptionService =
            EncryptionService.getInstance();

    public boolean savePassword(PasswordEntry entry) {

        Vault vault = VaultSession.current();

        boolean saved = dao.save(encryptedCopy(entry, vault));

        if (saved) {
            ActivityLog.record(ActivityLog.Action.LOGIN_ADDED, entry.getWebsite());
        }

        return saved;

    }

    /** The open vault's entries, decrypted, newest first. */
    public List<PasswordEntry> getAllPasswords() {

        Vault vault = VaultSession.current();

        encryptOlderEntries(vault);

        List<PasswordEntry> decryptedList = new ArrayList<>();

        for (PasswordEntry entry : dao.getPasswords(vault.id())) {

            PasswordEntry decrypted = new PasswordEntry();

            decrypted.setId(entry.getId());
            decrypted.setUserId(entry.getUserId());
            decrypted.setVaultId(entry.getVaultId());
            decrypted.setWebsite(open(entry.getWebsite(), entry.isFieldsEncrypted()));
            decrypted.setUsername(open(entry.getUsername(), entry.isFieldsEncrypted()));
            decrypted.setPassword(encryptionService.decrypt(entry.getPassword()));
            decrypted.setNotes(open(entry.getNotes(), entry.isFieldsEncrypted()));
            decrypted.setCategory(open(entry.getCategory(), entry.isFieldsEncrypted()));
            decrypted.setFavourite(entry.isFavourite());
            decrypted.setFieldsEncrypted(true);
            decrypted.setUpdatedAt(entry.getUpdatedAt());

            decryptedList.add(decrypted);

        }

        return decryptedList;

    }

    public boolean updatePassword(PasswordEntry entry) {

        Vault vault = VaultSession.current();

        // Encrypt into a copy so the caller's entry keeps its plain text.
        PasswordEntry encryptedEntry = encryptedCopy(entry, vault);

        encryptedEntry.setId(entry.getId());

        boolean updated = dao.update(encryptedEntry);

        if (updated) {
            ActivityLog.record(ActivityLog.Action.LOGIN_UPDATED, entry.getWebsite());
        }

        return updated;

    }

    public boolean deletePassword(int id) {

        return dao.delete(id, VaultSession.current().id());

    }

    public boolean deletePassword(PasswordEntry entry) {

        boolean deleted = deletePassword(entry.getId());

        if (deleted) {
            ActivityLog.record(ActivityLog.Action.LOGIN_DELETED, entry.getWebsite());
        }

        return deleted;

    }

    public boolean setFavourite(PasswordEntry entry, boolean favourite) throws SQLException {

        return dao.setFavourite(entry.getId(), VaultSession.current().id(), favourite);

    }

    /** Converts entries saved before every field was encrypted. Runs once per entry. */
    private void encryptOlderEntries(Vault vault) {

        try {

            int converted = dao.encryptPlainFields(vault.id(), encryptionService::encrypt);

            if (converted > 0) {
                AppLogger.success("Encrypted the website, username and notes of " + converted + " older saved logins.");
                ActivityLog.recordPlain(ActivityLog.Action.LOGINS_IMPORTED, converted + (converted == 1 ? " saved login" : " saved logins"));
            }

        } catch (SQLException e) {

            AppLogger.error("Couldn't encrypt older saved logins: " + e.getMessage());

        }

    }

    private String open(String stored, boolean encrypted) {

        if (stored == null) {
            return null;
        }

        return encrypted ? encryptionService.decrypt(stored) : stored;

    }

    private String seal(String plain) {

        return plain == null || plain.isEmpty() ? null : encryptionService.encrypt(plain);

    }

    private PasswordEntry encryptedCopy(PasswordEntry entry, Vault vault) {

        PasswordEntry encryptedEntry = new PasswordEntry();

        // The entry always belongs to the open vault and its account, whatever the caller set.
        encryptedEntry.setUserId(vault.userId());
        encryptedEntry.setVaultId(vault.id());

        encryptedEntry.setWebsite(encryptionService.encrypt(entry.getWebsite() == null ? "" : entry.getWebsite()));
        encryptedEntry.setUsername(encryptionService.encrypt(entry.getUsername() == null ? "" : entry.getUsername()));
        encryptedEntry.setPassword(encryptionService.encrypt(entry.getPassword()));
        encryptedEntry.setNotes(seal(entry.getNotes()));
        encryptedEntry.setCategory(seal(entry.getCategory()));
        encryptedEntry.setFavourite(entry.isFavourite());

        return encryptedEntry;

    }

}
