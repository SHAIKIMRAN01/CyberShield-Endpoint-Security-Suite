package com.cybershield.reports;

import com.cybershield.dao.ActivityDAO;
import com.cybershield.dao.LoginHistoryDAO;
import com.cybershield.dao.UserDAO;
import com.cybershield.logger.AppLogger;
import com.cybershield.models.User;
import com.cybershield.security.SessionManager;
import com.cybershield.services.ActivityLog;
import com.cybershield.services.EncryptionService;
import com.cybershield.utils.DisplayFormat;
import com.cybershield.vault.Vault;
import com.cybershield.vault.VaultService;
import com.cybershield.vault.VaultSession;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * A PDF for every sign-in: when it began and ended, on which computer, and everything done
 * in it, naming the files, logins and notes. Because those names are private, every PDF is
 * kept encrypted with the vault key, so only the account's master password opens it, and
 * sharing or deleting one asks for that password again. A shared copy is protected with a
 * password of its own, so the master password never leaves the app.
 */
public class SessionReports {

    /** At least this long, for the password that protects a shared copy. */
    public static final int MIN_COPY_PASSWORD = 8;

    private static final DateTimeFormatter DB_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * One sign-in in the list. The current one has no stored report yet (stored is null):
     * its PDF is made fresh when opened, and kept when the account signs out.
     */
    public record Session(long sessionId, String signedInAt, String signedOutAt, String device, int actions,
                          boolean current, SessionReportDAO.Row stored) {
    }

    public enum Outcome { DONE, WRONG_PASSWORD }

    private final SessionReportDAO dao = new SessionReportDAO();

    private final LoginHistoryDAO history = new LoginHistoryDAO();

    private final ActivityDAO activity = new ActivityDAO();

    private final EncryptionService encryption = EncryptionService.getInstance();

    private final VaultService vaults = new VaultService();

    // =====================================================
    // MAKING THEM
    // =====================================================

    /**
     * Makes the reports of the open vault's finished sign-ins that don't have one yet, for
     * example after the app was closed without signing out. Returns how many were made.
     */
    public int catchUp() throws SQLException, IOException {

        Vault vault = VaultSession.current();

        long current = SessionManager.historyId();

        Set<Long> known = dao.knownSessions(vault.userId());

        int made = 0;

        for (LoginHistoryDAO.Entry entry : history.recent(vault.userId(), 1000)) {

            if (LoginHistoryDAO.SUCCESS.equals(entry.status()) && entry.id() != current && !known.contains(entry.id())) {
                store(vault, entry, entry.signedOutAt());
                made++;
            }

        }

        return made;

    }

    /** Runs catchUp on a background thread, if the vault is open. */
    public static void catchUpInBackground() {

        if (!VaultSession.isUnlocked()) {
            return;
        }

        Thread thread = new Thread(() -> {
            try {
                if (VaultSession.isUnlocked()) {
                    new SessionReports().catchUp();
                }
            } catch (SQLException | IOException | RuntimeException e) {
                AppLogger.error("Couldn't make the sign-in reports: " + e.getMessage());
            }
        }, "cybershield-session-reports");

        thread.setDaemon(true);

        thread.start();

    }

    /**
     * Keeps the report of the sign-in that is ending now: by signing out, or by closing the
     * app (which the history shows as closed without signing out). Call while the vault is open.
     */
    public void saveCurrent(boolean signingOut) throws SQLException, IOException {

        Vault vault = VaultSession.current();

        LoginHistoryDAO.Entry entry = currentEntry(vault.userId());

        if (entry != null) {
            store(vault, entry, signingOut ? LocalDateTime.now(ZoneOffset.UTC).format(DB_TIME) : null);
        }

    }

    private void store(Vault vault, LoginHistoryDAO.Entry entry, String signedOutAt) throws SQLException, IOException {

        List<ActivityDAO.Event> events = activity.forSession(vault.userId(), entry.id());

        byte[] pdf = build(account(vault.userId()), entry.signedInAt(), signedOutAt, entry.device(), false, events);

        try {
            dao.insert(vault.userId(), vault.id(), entry.id(), entry.signedInAt(), signedOutAt, entry.device(), events.size(),
                    encryption.encryptBytes(pdf));
        } finally {
            Arrays.fill(pdf, (byte) 0);
        }

    }

    private LoginHistoryDAO.Entry currentEntry(int userId) throws SQLException {

        long current = SessionManager.historyId();

        if (current <= 0) {
            return null;
        }

        return history.recent(userId, 1000).stream().filter(entry -> entry.id() == current).findFirst().orElse(null);

    }

    // =====================================================
    // LISTING AND OPENING
    // =====================================================

    /** The current sign-in first, then every stored report, newest first. */
    public List<Session> list() throws SQLException {

        int userId = VaultSession.current().userId();

        List<Session> sessions = new ArrayList<>();

        LoginHistoryDAO.Entry now = currentEntry(userId);

        if (now != null) {
            sessions.add(new Session(now.id(), now.signedInAt(), null, now.device(), activity.forSession(userId, now.id()).size(),
                    true, null));
        }

        for (SessionReportDAO.Row row : dao.forUser(userId)) {
            sessions.add(new Session(row.sessionId(), row.signedInAt(), row.signedOutAt(), row.device(), row.actions(), false, row));
        }

        return sessions;

    }

    /** One sign-in's PDF, decrypted in memory. The caller wipes it when done. */
    public byte[] pdf(Session session) throws SQLException, IOException {

        Vault vault = VaultSession.current();

        if (session.current()) {
            return build(account(vault.userId()), session.signedInAt(), null, session.device(), true,
                    activity.forSession(vault.userId(), session.sessionId()));
        }

        byte[] sealed = dao.pdf(vault.userId(), session.stored().id());

        if (sealed == null) {
            throw new IOException("That report was deleted.");
        }

        if (session.stored().vaultId() != vault.id()) {
            throw new IOException("That report was locked with another vault.");
        }

        try {
            return encryption.decryptBytes(sealed);
        } catch (RuntimeException e) {
            throw new IOException("That report can't be read: it is damaged.", e);
        }

    }

    // =====================================================
    // SHARING AND DELETING (master password first)
    // =====================================================

    /**
     * After checking the master password, saves a copy at target that opens in any PDF
     * reader with copyPassword, and only with it.
     */
    public Outcome share(Session session, String masterPassword, String copyPassword, Path target) throws SQLException, IOException {

        if (copyPassword == null || copyPassword.length() < MIN_COPY_PASSWORD) {
            throw new IllegalArgumentException("The copy's password needs at least " + MIN_COPY_PASSWORD + " characters.");
        }

        if (copyPassword.equals(masterPassword)) {
            throw new IllegalArgumentException("Use a different password from your master password: you'll give this one to someone.");
        }

        if (!vaults.verifyMasterPassword(VaultSession.current().userId(), masterPassword)) {
            return Outcome.WRONG_PASSWORD;
        }

        byte[] pdf = pdf(session);

        Path file = target.toAbsolutePath().normalize();

        Path part = file.resolveSibling(file.getFileName() + ".part");

        try (PDDocument document = Loader.loadPDF(pdf)) {

            byte[] owner = new byte[24];

            RANDOM.nextBytes(owner);

            StandardProtectionPolicy policy = new StandardProtectionPolicy(HexFormat.of().formatHex(owner), copyPassword, new AccessPermission());

            policy.setEncryptionKeyLength(256);

            document.protect(policy);

            Files.createDirectories(file.getParent());

            document.save(part.toFile());

            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        } finally {

            Arrays.fill(pdf, (byte) 0);

            Files.deleteIfExists(part);

        }

        ActivityLog.record(ActivityLog.Action.SESSION_REPORT_SHARED, file.getFileName().toString());

        return Outcome.DONE;

    }

    /** After checking the master password, deletes one stored report for good. */
    public Outcome delete(Session session, String masterPassword) throws SQLException {

        if (session.current() || session.stored() == null) {
            throw new IllegalArgumentException("The sign-in in progress has no stored report yet.");
        }

        int userId = VaultSession.current().userId();

        if (!vaults.verifyMasterPassword(userId, masterPassword)) {
            return Outcome.WRONG_PASSWORD;
        }

        if (dao.markDeleted(userId, session.stored().id())) {
            ActivityLog.recordPlain(ActivityLog.Action.SESSION_REPORT_DELETED, "1 report");
        }

        return Outcome.DONE;

    }

    /** After checking the master password, deletes every stored report of the account. */
    public Outcome deleteAll(String masterPassword) throws SQLException {

        int userId = VaultSession.current().userId();

        if (!vaults.verifyMasterPassword(userId, masterPassword)) {
            return Outcome.WRONG_PASSWORD;
        }

        int deleted = dao.markAllDeleted(userId);

        ActivityLog.recordPlain(ActivityLog.Action.SESSION_REPORT_DELETED, deleted + (deleted == 1 ? " report" : " reports"));

        return Outcome.DONE;

    }

    /** "CyberShield sign-in 2026-11-22 1410.pdf", in local time. */
    public static String fileName(Session session) {

        LocalDateTime when = local(session.signedInAt());

        return "CyberShield sign-in " + (when == null ? "report" : when.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm"))) + ".pdf";

    }

    // =====================================================
    // THE PDF
    // =====================================================

    static byte[] build(String account, String signedInAt, String signedOutAt, String device, boolean stillSignedIn,
                        List<ActivityDAO.Event> events) throws IOException {

        String began = DisplayFormat.timestamp(signedInAt);

        long filesOpened = events.stream().filter(e -> ActivityLog.Action.FILE_OPENED.name().equals(e.action())).count();

        try (ReportPdf pdf = new ReportPdf("CyberShield sign-in report", "CyberShield sign-in report  ·  " + account + "  ·  " + began)) {

            pdf.addPage();

            pdf.banner("CYBERSHIELD", "Sign-in report", List.of(
                    "Account: " + account,
                    "Signed in " + began,
                    device == null || device.isBlank() ? "" : "On " + device));

            pdf.figureRow(new String[][] {
                    {"Actions", String.valueOf(events.size()), events.isEmpty() ? "Nothing was done" : "recorded in this sign-in"},
                    {"Files opened", String.valueOf(filesOpened), "in the private viewer"},
                    {"Length", length(signedInAt, signedOutAt, events), stillSignedIn ? "so far" : signedOutAt == null ? "closed without signing out" : "signed in to signed out"},
            });

            pdf.heading("The sign-in");

            pdf.row("Signed in", began, ReportPdf.INK);
            pdf.row("Signed out", stillSignedIn ? "Still signed in when this was made"
                    : signedOutAt == null ? "The app was closed without signing out" : DisplayFormat.timestamp(signedOutAt), ReportPdf.INK);
            pdf.row("Computer", device == null || device.isBlank() ? "Not recorded" : device, ReportPdf.INK);

            pdf.heading("What was done");

            if (events.isEmpty()) {

                pdf.paragraph("Nothing was recorded in this sign-in.", ReportPdf.MUTED);

            } else {

                List<String[]> rows = new ArrayList<>();

                for (ActivityDAO.Event event : events) {
                    LocalDateTime at = local(event.occurredAt());
                    rows.add(new String[] {at == null ? "" : at.format(CLOCK), ActivityLog.Action.wordsFor(event.action()), ActivityLog.readable(event)});
                }

                pdf.table(new String[] {"Time", "What", "File, login or note"}, new float[] {0.13f, 0.37f, 0.5f}, rows);

            }

            return pdf.toBytes("Private: this report names your files, logins and notes. CyberShield keeps it encrypted with your vault key.");

        }

    }

    private static String length(String signedInAt, String signedOutAt, List<ActivityDAO.Event> events) {

        LocalDateTime start = utc(signedInAt);

        LocalDateTime end = signedOutAt != null ? utc(signedOutAt)
                : events.isEmpty() ? null : utc(events.get(events.size() - 1).occurredAt());

        if (start == null || end == null || end.isBefore(start)) {
            return "-";
        }

        long minutes = Duration.between(start, end).toMinutes();

        return minutes < 1 ? "< 1 min" : minutes < 60 ? minutes + " min" : minutes / 60 + " h " + minutes % 60 + " min";

    }

    private static LocalDateTime utc(String value) {

        try {
            return value == null ? null : LocalDateTime.parse(value.trim(), DB_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }

    }

    private static LocalDateTime local(String utcValue) {

        LocalDateTime at = utc(utcValue);

        return at == null ? null : at.atOffset(ZoneOffset.UTC).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime();

    }

    private static String account(int userId) {

        User user = new UserDAO().findById(userId);

        return user == null ? "Account " + userId : user.getUsername();

    }

}
