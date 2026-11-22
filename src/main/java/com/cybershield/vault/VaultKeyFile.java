package com.cybershield.vault;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;

/**
 * A copy of a vault's wrapped key, kept with the encrypted files in the hidden
 * store as keys/(vault salt)-(account id).key. If the database is ever lost, the
 * master password and this file are enough to open the protected files again.
 * Like the user_vaults row, it is useless without the master password.
 */
public final class VaultKeyFile {

    public static final String FOLDER = "keys";

    private static final String FORMAT = "1";

    private VaultKeyFile() {
    }

    public static Path pathFor(Path storeDir, Vault vault) {

        return storeDir.resolve(FOLDER).resolve(HexFormat.of().formatHex(vault.vaultSalt()) + "-" + vault.userId() + ".key");

    }

    /** Writes or replaces the key file. The old copy stays until the new one is complete. */
    public static void write(Path storeDir, Vault vault) throws IOException {

        Base64.Encoder base64 = Base64.getEncoder();

        Properties values = new Properties();

        values.setProperty("format", FORMAT);
        values.setProperty("account", String.valueOf(vault.userId()));
        values.setProperty("vault-salt", base64.encodeToString(vault.vaultSalt()));
        values.setProperty("kdf", "PBKDF2WithHmacSHA512");
        values.setProperty("kdf-salt", base64.encodeToString(vault.kdfSalt()));
        values.setProperty("kdf-iterations", String.valueOf(vault.kdfIterations()));
        values.setProperty("wrapped-key", base64.encodeToString(vault.wrappedKey()));
        values.setProperty("verification", vault.verificationData());

        Path target = pathFor(storeDir, vault);

        Files.createDirectories(target.getParent());

        Path temp = target.resolveSibling(target.getFileName() + ".tmp");

        try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            values.store(out, "CyberShield vault key. Opens this account's protected files together with its master password.");
        }

        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }

    }

    /** Reads a key file back as a vault that isn't in the database (id 0). */
    public static Vault read(Path file) throws IOException {

        Properties values = new Properties();

        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            values.load(in);
        }

        if (!FORMAT.equals(values.getProperty("format"))) {
            throw new IOException("Not a CyberShield vault key file, or a newer format: " + file.getFileName());
        }

        try {

            Base64.Decoder base64 = Base64.getDecoder();

            return new Vault(
                    0,
                    Integer.parseInt(values.getProperty("account")),
                    base64.decode(values.getProperty("vault-salt")),
                    base64.decode(values.getProperty("kdf-salt")),
                    Integer.parseInt(values.getProperty("kdf-iterations")),
                    base64.decode(values.getProperty("wrapped-key")),
                    values.getProperty("verification"),
                    null,
                    null
            );

        } catch (RuntimeException e) {

            throw new IOException("The vault key file is damaged: " + file.getFileName(), e);

        }

    }

}
