package com.cybershield.password;

import com.cybershield.services.EncryptionService;
import com.cybershield.vault.Vault;
import com.cybershield.vault.VaultSession;

import java.util.ArrayList;
import java.util.List;

/**
 * Saved logins of the open vault. Passwords are encrypted with the vault key
 * before they reach the database. Every method needs the vault unlocked, and
 * works only on that vault's entries.
 */
public class PasswordService {

    private final PasswordDAO dao =
            new PasswordDAO();

    private final EncryptionService encryptionService =
            EncryptionService.getInstance();

    public boolean savePassword(PasswordEntry entry) {

        Vault vault = VaultSession.current();

        PasswordEntry encryptedEntry = encryptedCopy(entry, vault);

        return dao.save(encryptedEntry);

    }

    public List<PasswordEntry> getAllPasswords() {

        Vault vault = VaultSession.current();

        List<PasswordEntry> decryptedList =
                new ArrayList<>();

        for (PasswordEntry entry : dao.getPasswords(vault.id())) {

            PasswordEntry decrypted =
                    new PasswordEntry();

            decrypted.setId(entry.getId());

            decrypted.setUserId(entry.getUserId());

            decrypted.setVaultId(entry.getVaultId());

            decrypted.setWebsite(entry.getWebsite());

            decrypted.setUsername(entry.getUsername());

            decrypted.setPassword(

                    encryptionService.decrypt(
                            entry.getPassword()
                    )

            );

            decrypted.setNotes(entry.getNotes());

            decrypted.setUpdatedAt(entry.getUpdatedAt());

            decryptedList.add(decrypted);

        }

        return decryptedList;

    }

    public boolean updatePassword(PasswordEntry entry) {

        Vault vault = VaultSession.current();

        // Encrypt into a copy so the caller's entry keeps its plain-text
        // password, the same way savePassword works.
        PasswordEntry encryptedEntry = encryptedCopy(entry, vault);

        encryptedEntry.setId(entry.getId());

        return dao.update(encryptedEntry);

    }

    public boolean deletePassword(int id) {

        return dao.delete(id, VaultSession.current().id());

    }

    private PasswordEntry encryptedCopy(PasswordEntry entry, Vault vault) {

        PasswordEntry encryptedEntry =
                new PasswordEntry();

        // The entry always belongs to the open vault and its account, whatever the caller set.
        encryptedEntry.setUserId(vault.userId());

        encryptedEntry.setVaultId(vault.id());

        encryptedEntry.setWebsite(entry.getWebsite());

        encryptedEntry.setUsername(entry.getUsername());

        encryptedEntry.setPassword(

                encryptionService.encrypt(
                        entry.getPassword()
                )

        );

        encryptedEntry.setNotes(entry.getNotes());

        return encryptedEntry;

    }

}
