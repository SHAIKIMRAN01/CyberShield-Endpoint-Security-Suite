package com.cybershield.services;

import com.cybershield.dao.ActivityDAO;
import com.cybershield.dao.LoginHistoryDAO;
import com.cybershield.dao.SettingsDAO;
import com.cybershield.encryption.CryptoConstants;
import com.cybershield.files.FileVaultDAO;
import com.cybershield.logger.AppLogger;
import com.cybershield.password.PasswordHealth;
import com.cybershield.password.PasswordService;
import com.cybershield.security.SessionManager;
import com.cybershield.vault.Vault;
import com.cybershield.vault.VaultDAO;
import com.cybershield.vault.VaultSession;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * A 0 to 100 score of how well an account is protected, with the hints that would raise
 * it most. Password health only counts while the vault is open (it needs the passwords
 * decrypted); otherwise the score is out of the other parts.
 */
public final class SecurityScore {

    /** @param hints what to do next, most valuable first */
    public record Score(int points, int possible, List<String> hints, PasswordHealth.Summary passwords) {

        public int percent() {
            return possible == 0 ? 0 : Math.round(100f * points / possible);
        }

        public String grade() {
            int p = percent();
            return p >= 85 ? "Strong" : p >= 60 ? "Good" : p >= 35 ? "Fair" : "Weak";
        }

    }

    private record Part(int earned, int worth, String hint) {
    }

    private SecurityScore() {
    }

    public static Score compute(int userId) {

        List<Part> parts = new ArrayList<>();

        PasswordHealth.Summary passwords = null;

        try {

            Vault vault = new VaultDAO().find(userId);

            parts.add(vault != null
                    ? new Part(20, 20, null)
                    : new Part(0, 20, "Set up your vault with a master password."));

            if (vault != null) {

                parts.add(vault.kdfIterations() >= CryptoConstants.PBKDF2_ITERATIONS
                        ? new Part(10, 10, null)
                        : new Part(0, 10, "Unlock your vault once to strengthen its key."));

                if (VaultSession.isUnlocked() && VaultSession.current().userId() == userId) {

                    var entries = new PasswordService().getAllPasswords();

                    passwords = PasswordHealth.summarize(PasswordHealth.check(entries));

                    if (entries.isEmpty()) {
                        parts.add(new Part(15, 30, "Save your logins in the Password Vault."));
                    } else {
                        int healthy = (int) PasswordHealth.check(entries).values().stream().filter(java.util.EnumSet::isEmpty).count();
                        int earned = Math.round(30f * healthy / entries.size());
                        parts.add(new Part(earned, 30, earned == 30 ? null
                                : "Fix " + (entries.size() - healthy) + (entries.size() - healthy == 1 ? " password" : " passwords")
                                + " marked weak, reused or old in the Password Vault."));
                    }

                }

                FileVaultDAO.Counts files = new FileVaultDAO().counts(vault.id());

                parts.add(files.items() > 0
                        ? new Part(10, 10, null)
                        : new Part(0, 10, "Protect your private files in File Security."));

                boolean backedUp = new ActivityDAO().countSince(userId, ActivityLog.Action.STORE_BACKED_UP.name(), 30) > 0;

                parts.add(backedUp || files.items() == 0
                        ? new Part(10, 10, null)
                        : new Part(0, 10, "Back up your protected files to another drive."));

            }

            LoginHistoryDAO history = new LoginHistoryDAO();

            LoginHistoryDAO.Entry previous = history.previousSuccess(userId, SessionManager.historyId());

            int failures = previous == null ? 0 : history.failuresSince(userId, previous.id());

            parts.add(failures == 0
                    ? new Part(10, 10, null)
                    : new Part(0, 10, failures + (failures == 1 ? " failed sign-in" : " failed sign-ins")
                    + " since last time: check the history in Settings."));

            int minutes = new SettingsDAO().load(userId).autoLockMinutes();

            parts.add(minutes <= 5
                    ? new Part(10, 10, null)
                    : new Part(minutes <= 15 ? 5 : 0, 10, "Set auto-lock to 5 minutes or less in Settings."));

        } catch (SQLException | RuntimeException e) {

            AppLogger.error("Couldn't work out the security score: " + e.getMessage());

        }

        int points = parts.stream().mapToInt(Part::earned).sum();

        int possible = parts.stream().mapToInt(Part::worth).sum();

        List<String> hints = parts.stream()
                .filter(part -> part.hint() != null)
                .sorted((a, b) -> Integer.compare(b.worth() - b.earned(), a.worth() - a.earned()))
                .map(Part::hint)
                .toList();

        if (!(VaultSession.isUnlocked() && VaultSession.current().userId() == userId)) {
            hints = new ArrayList<>(hints);
            hints.add("Unlock the vault to include your passwords' health.");
        }

        return new Score(points, possible, hints, passwords);

    }

}
