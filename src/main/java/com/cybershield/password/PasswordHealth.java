package com.cybershield.password;

import com.cybershield.utils.PasswordStrengthUtil;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks saved passwords: weak ones (a strength score under 5 of 7, for example short,
 * or only letters and digits), ones reused for several logins, and ones not changed in
 * a year. Works on decrypted entries in memory.
 */
public final class PasswordHealth {

    public static final int OLD_AFTER_DAYS = 365;

    public static final int STRONG_SCORE = 5;

    public enum Issue {

        WEAK("Weak"),
        REUSED("Reused"),
        OLD("Old");

        private final String words;

        Issue(String words) {
            this.words = words;
        }

        public String words() {
            return words;
        }

    }

    public record Summary(int total, int weak, int reused, int old) {

        public int healthy(Map<Integer, EnumSet<Issue>> issues) {
            return (int) issues.values().stream().filter(EnumSet::isEmpty).count();
        }

        public boolean allGood() {
            return weak == 0 && reused == 0 && old == 0;
        }

    }

    private static final DateTimeFormatter DB_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private PasswordHealth() {
    }

    /** The problems of each entry, by entry id. Entries without problems get an empty set. */
    public static Map<Integer, EnumSet<Issue>> check(List<PasswordEntry> entries) {

        return check(entries, LocalDateTime.now(java.time.ZoneOffset.UTC));

    }

    static Map<Integer, EnumSet<Issue>> check(List<PasswordEntry> entries, LocalDateTime nowUtc) {

        Map<String, Integer> uses = new HashMap<>();

        for (PasswordEntry entry : entries) {
            uses.merge(entry.getPassword() == null ? "" : entry.getPassword(), 1, Integer::sum);
        }

        Map<Integer, EnumSet<Issue>> issues = new HashMap<>();

        for (PasswordEntry entry : entries) {

            EnumSet<Issue> found = EnumSet.noneOf(Issue.class);

            String password = entry.getPassword() == null ? "" : entry.getPassword();

            if (PasswordStrengthUtil.calculateScore(password) < STRONG_SCORE) {
                found.add(Issue.WEAK);
            }

            if (uses.get(password) > 1) {
                found.add(Issue.REUSED);
            }

            LocalDateTime updated = time(entry.getUpdatedAt());

            if (updated != null && updated.isBefore(nowUtc.minusDays(OLD_AFTER_DAYS))) {
                found.add(Issue.OLD);
            }

            issues.put(entry.getId(), found);

        }

        return issues;

    }

    public static Summary summarize(Map<Integer, EnumSet<Issue>> issues) {

        int weak = 0;
        int reused = 0;
        int old = 0;

        for (EnumSet<Issue> found : issues.values()) {
            weak += found.contains(Issue.WEAK) ? 1 : 0;
            reused += found.contains(Issue.REUSED) ? 1 : 0;
            old += found.contains(Issue.OLD) ? 1 : 0;
        }

        return new Summary(issues.size(), weak, reused, old);

    }

    /** "Weak, reused" or "OK". */
    public static String describe(EnumSet<Issue> found) {

        if (found == null || found.isEmpty()) {
            return "OK";
        }

        StringBuilder words = new StringBuilder();

        for (Issue issue : found) {
            words.append(words.isEmpty() ? issue.words() : ", " + issue.words().toLowerCase());
        }

        return words.toString();

    }

    private static LocalDateTime time(String value) {

        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return LocalDateTime.parse(value, DB_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }

    }

}
