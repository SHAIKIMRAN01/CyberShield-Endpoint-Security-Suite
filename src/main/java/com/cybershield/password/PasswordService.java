package com.cybershield.password;

import com.cybershield.services.EncryptionService;

import java.util.ArrayList;
import java.util.List;

public class PasswordService {

    private final PasswordDAO dao =
            new PasswordDAO();

    private final EncryptionService encryptionService =
            EncryptionService.getInstance();

    public boolean savePassword(PasswordEntry entry) {

        if (!encryptionService.isInitialized()) {

            throw new IllegalStateException(
                    "Vault is locked."
            );

        }

        PasswordEntry encryptedEntry =
                new PasswordEntry();

        encryptedEntry.setUserId(entry.getUserId());

        encryptedEntry.setWebsite(entry.getWebsite());

        encryptedEntry.setUsername(entry.getUsername());

        encryptedEntry.setPassword(

                encryptionService.encrypt(
                        entry.getPassword()
                )

        );

        encryptedEntry.setNotes(entry.getNotes());

        return dao.save(encryptedEntry);

    }

    public List<PasswordEntry> getAllPasswords() {

        List<PasswordEntry> encryptedList =
                dao.getAllPasswords();

        List<PasswordEntry> decryptedList =
                new ArrayList<>();

        for (PasswordEntry entry : encryptedList) {

            PasswordEntry decrypted =
                    new PasswordEntry();

            decrypted.setId(entry.getId());

            decrypted.setUserId(entry.getUserId());

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

        if (!encryptionService.isInitialized()) {

            throw new IllegalStateException(
                    "Vault is locked."
            );

        }

        // Encrypt into a copy so the caller's entry keeps its plain-text
        // password, the same way savePassword works.
        PasswordEntry encryptedEntry =
                new PasswordEntry();

        encryptedEntry.setId(entry.getId());

        encryptedEntry.setUserId(entry.getUserId());

        encryptedEntry.setWebsite(entry.getWebsite());

        encryptedEntry.setUsername(entry.getUsername());

        encryptedEntry.setPassword(

                encryptionService.encrypt(
                        entry.getPassword()
                )

        );

        encryptedEntry.setNotes(entry.getNotes());

        return dao.update(encryptedEntry);

    }

    public boolean deletePassword(int id) {

        return dao.delete(id);

    }

}