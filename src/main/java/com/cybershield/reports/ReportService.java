package com.cybershield.reports;

import com.cybershield.dao.ActivityDAO;
import com.cybershield.dao.LoginHistoryDAO;
import com.cybershield.dao.SettingsDAO;
import com.cybershield.dao.UserDAO;
import com.cybershield.files.FileVaultDAO;
import com.cybershield.models.User;
import com.cybershield.notes.NoteService;
import com.cybershield.password.PasswordEntry;
import com.cybershield.password.PasswordHealth;
import com.cybershield.password.PasswordService;
import com.cybershield.security.SessionManager;
import com.cybershield.services.ActivityLog;
import com.cybershield.services.SecurityScore;
import com.cybershield.utils.DisplayFormat;
import com.cybershield.vault.Vault;
import com.cybershield.vault.VaultSession;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The security summary of the signed-in account: its score, password health, vault,
 * protected files, sign-ins and activity, saved as a PDF and kept in a list. A report
 * holds scores, counts and times only, never a password, note text, website name or
 * file name, so it can be kept or shared. Everything here needs the vault unlocked,
 * because password health is worked out from the decrypted passwords.
 */
public class ReportService {

    public static final String TYPE_SUMMARY = "SECURITY_SUMMARY";

    /** How far back sign-ins and activity go: as far as the activity log keeps them. */
    public static final int DAYS = ActivityLog.KEEP_DAYS;

    /** The newest sign-ins listed one by one; the rest are only counted. */
    public static final int SIGN_INS_LISTED = 12;

    static final String PRIVACY_NOTE =
            "Holds scores, counts and times only: no passwords, note text, website names or file names.";

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm");

    /** Takes a deleted report's PDF away: to the Recycle Bin in the app, deleted outright in the tests. */
    public interface Remover {

        void remove(Path file) throws IOException;

    }

    public static final Remover RECYCLE_BIN = file -> {

        if (Desktop.isDesktopSupported()
                && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)
                && Desktop.getDesktop().moveToTrash(file.toFile())) {
            return;
        }

        Files.deleteIfExists(file);

    };

    /** One sign-in attempt as listed in the report, with its times already in local time. */
    public record SignIn(String when, String result, String device, String signedOut) {
    }

    public record Summary(
            String account,
            String createdAt,
            SecurityScore.Score score,
            PasswordHealth.Summary passwords,
            int passwordsNeedingAttention,
            int notes,
            int protectedItems,
            int protectedFiles,
            String lastCheck,
            String lastBackup,
            int autoLockMinutes,
            int unlocks,
            int locks,
            int autoLocks,
            int signIns,
            int failedSignIns,
            List<SignIn> recentSignIns,
            Map<String, Integer> activity) {
    }

    public record Saved(ReportDAO.Row row, Summary summary) {
    }

    private final ReportDAO dao = new ReportDAO();

    private final Remover remover;

    public ReportService() {
        this(RECYCLE_BIN);
    }

    public ReportService(Remover remover) {
        this.remover = remover;
    }

    // =====================================================
    // THE SUMMARY
    // =====================================================

    /** @throws IllegalStateException while the vault is locked */
    public Summary summarize() throws SQLException {

        Vault vault = VaultSession.current();

        int userId = vault.userId();

        User user = new UserDAO().findById(userId);

        List<PasswordEntry> entries = new PasswordService().getAllPasswords();

        Map<Integer, EnumSet<PasswordHealth.Issue>> issues = PasswordHealth.check(entries);

        int attention = (int) issues.values().stream().filter(found -> !found.isEmpty()).count();

        FileVaultDAO.Counts files = new FileVaultDAO().counts(vault.id());

        ActivityDAO activity = new ActivityDAO();

        ActivityDAO.Event check = activity.latest(userId, ActivityLog.Action.VAULT_CHECKED.name());

        ActivityDAO.Event backup = activity.latest(userId, ActivityLog.Action.STORE_BACKED_UP.name());

        // A check's detail is plain counts ("12 files: 0 damaged, 0 missing"); a backup's names a folder, so only its time is used.
        String lastCheck = check == null ? null : DisplayFormat.timestamp(check.occurredAt())
                + (check.vaultId() == 0 && check.detail() != null ? ", " + check.detail() : "");

        String lastBackup = backup == null ? null : DisplayFormat.timestamp(backup.occurredAt());

        Map<String, Integer> done = new LinkedHashMap<>();

        activity.countsSince(userId, DAYS).forEach((action, times) -> done.merge(ActivityLog.Action.wordsFor(action), times, Integer::sum));

        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(DAYS);

        List<LoginHistoryDAO.Entry> attempts = new LoginHistoryDAO().recent(userId, 2000).stream()
                .filter(entry -> isAfter(entry.signedInAt(), since))
                .toList();

        int succeeded = (int) attempts.stream().filter(entry -> LoginHistoryDAO.SUCCESS.equals(entry.status())).count();

        return new Summary(
                user == null ? "Account " + userId : user.getUsername(),
                DisplayFormat.dateTime(System.currentTimeMillis()),
                SecurityScore.compute(userId),
                PasswordHealth.summarize(issues),
                attention,
                new NoteService().count(),
                files.items(),
                files.files(),
                lastCheck,
                lastBackup,
                new SettingsDAO().load(userId).autoLockMinutes(),
                activity.countSince(userId, ActivityLog.Action.VAULT_UNLOCKED.name(), DAYS),
                activity.countSince(userId, ActivityLog.Action.VAULT_LOCKED.name(), DAYS),
                activity.countSince(userId, ActivityLog.Action.VAULT_AUTO_LOCKED.name(), DAYS),
                succeeded,
                attempts.size() - succeeded,
                attempts.stream().limit(SIGN_INS_LISTED).map(ReportService::signIn).toList(),
                done);

    }

    private static boolean isAfter(String utcTimestamp, LocalDateTime since) {

        try {
            return utcTimestamp != null && LocalDateTime.parse(utcTimestamp.trim().replace(' ', 'T')).isAfter(since);
        } catch (DateTimeParseException e) {
            return false;
        }

    }

    private static SignIn signIn(LoginHistoryDAO.Entry entry) {

        boolean succeeded = LoginHistoryDAO.SUCCESS.equals(entry.status());

        String result = switch (entry.status() == null ? "" : entry.status()) {
            case LoginHistoryDAO.SUCCESS -> "Signed in";
            case LoginHistoryDAO.FAILED -> "Wrong password";
            case LoginHistoryDAO.LOCKED -> "Refused: waiting";
            default -> entry.status();
        };

        String signedOut = !succeeded ? ""
                : entry.id() == SessionManager.historyId() ? "Signed in now"
                : entry.signedOutAt() == null ? "Closed without signing out"
                : DisplayFormat.timestamp(entry.signedOutAt());

        return new SignIn(DisplayFormat.timestamp(entry.signedInAt()), result, entry.device() == null ? "" : entry.device(), signedOut);

    }

    // =====================================================
    // SAVED REPORTS
    // =====================================================

    /** Makes the summary, saves it as a PDF at target (replacing a file already there) and lists it. */
    public Saved create(Path target) throws IOException, SQLException {

        Summary summary = summarize();

        int userId = VaultSession.current().userId();

        Path file = target.toAbsolutePath().normalize();

        Path folder = Files.createDirectories(file.getParent());

        // Written beside the target first, so a failure never leaves half a PDF under the chosen name.
        Path part = folder.resolve(file.getFileName() + ".part");

        try {

            writePdf(summary, part);

            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        } finally {

            Files.deleteIfExists(part);

        }

        // Saving over an earlier report replaces it in the list too.
        for (ReportDAO.Row row : dao.forUser(userId)) {
            if (samePath(row.path(), file)) {
                dao.delete(userId, row.id());
            }
        }

        String name = file.getFileName().toString();

        long id = dao.insert(userId, name, TYPE_SUMMARY, file.toString());

        ActivityLog.record(ActivityLog.Action.REPORT_CREATED, name);

        ReportDAO.Row row = dao.forUser(userId).stream().filter(saved -> saved.id() == id).findFirst()
                .orElse(new ReportDAO.Row(id, name, null, TYPE_SUMMARY, file.toString()));

        return new Saved(row, summary);

    }

    /** The open vault's account's reports, newest first. */
    public List<ReportDAO.Row> history() throws SQLException {

        return dao.forUser(VaultSession.current().userId());

    }

    /** Takes a report off the list, and its PDF to the Recycle Bin if it is still where it was saved. */
    public void delete(ReportDAO.Row row) throws IOException, SQLException {

        int userId = VaultSession.current().userId();

        boolean ours = dao.forUser(userId).stream().anyMatch(saved -> saved.id() == row.id());

        if (!ours) {
            throw new IllegalArgumentException("That report isn't in this account's list.");
        }

        if (row.fileExists()) {
            remover.remove(Path.of(row.path()));
        }

        dao.delete(userId, row.id());

        ActivityLog.record(ActivityLog.Action.REPORT_DELETED, row.name());

    }

    /** "CyberShield security report 2026-11-22 1405.pdf" */
    public static String defaultFileName() {

        return "CyberShield security report " + LocalDateTime.now().format(FILE_STAMP) + ".pdf";

    }

    /** Where the save dialog opens: beside the last report, else Documents, else the home folder. */
    public Path suggestedFolder() {

        try {

            for (ReportDAO.Row row : history()) {

                Path parent = Path.of(row.path()).getParent();

                if (parent != null && Files.isDirectory(parent)) {
                    return parent;
                }

            }

        } catch (SQLException | RuntimeException e) {
            // Fall back to the usual folders.
        }

        Path home = Path.of(System.getProperty("user.home"));

        Path documents = home.resolve("Documents");

        return Files.isDirectory(documents) ? documents : home;

    }

    private static boolean samePath(String stored, Path file) {

        try {
            return stored != null && Path.of(stored).toAbsolutePath().normalize().toString().equalsIgnoreCase(file.toString());
        } catch (InvalidPathException e) {
            return false;
        }

    }

    // =====================================================
    // THE PDF
    // =====================================================

    static void writePdf(Summary s, Path target) throws IOException {

        try (ReportPdf pdf = new ReportPdf("CyberShield security report",
                "CyberShield security report  ·  " + s.account() + "  ·  " + s.createdAt())) {

            pdf.addPage();

            pdf.banner("CYBERSHIELD", "Security report", List.of(
                    "Account: " + s.account(),
                    "Made " + s.createdAt(),
                    "Sign-ins and activity of the last " + DAYS + " days"));

            SecurityScore.Score score = s.score();

            int percent = score.percent();

            PasswordHealth.Summary passwords = s.passwords();

            pdf.scoreAndFigures(percent, score.grade(),
                    percent >= 60 ? ReportPdf.GOOD : percent >= 35 ? ReportPdf.WARN : ReportPdf.BAD,
                    new String[][] {
                            {"Saved logins", String.valueOf(passwords.total()),
                                    s.passwordsNeedingAttention() == 0 ? "All in good shape" : s.passwordsNeedingAttention() + " need attention"},
                            {"Protected items", String.valueOf(s.protectedItems()), plural(s.protectedFiles(), "file", "files") + " hidden"},
                            {"Sign-ins", String.valueOf(s.signIns()),
                                    s.failedSignIns() == 0 ? "No failed attempts" : plural(s.failedSignIns(), "failed attempt", "failed attempts")},
                    });

            pdf.heading("What to do next");

            if (score.hints().isEmpty()) {
                pdf.paragraph("Nothing to fix: everything checked is in good shape.", ReportPdf.GOOD);
            } else {
                pdf.bullets(score.hints());
            }

            pdf.heading("Passwords");

            pdf.row("Saved logins", String.valueOf(passwords.total()), ReportPdf.INK);
            pdf.row("In good shape", String.valueOf(passwords.total() - s.passwordsNeedingAttention()), ReportPdf.INK);
            pdf.row("Weak", String.valueOf(passwords.weak()), passwords.weak() > 0 ? ReportPdf.BAD : ReportPdf.INK);
            pdf.row("Used for more than one login", String.valueOf(passwords.reused()), passwords.reused() > 0 ? ReportPdf.BAD : ReportPdf.INK);
            pdf.row("Not changed for over a year", String.valueOf(passwords.old()), passwords.old() > 0 ? ReportPdf.WARN : ReportPdf.INK);

            pdf.heading("Vault and notes");

            pdf.row("Auto-lock", "After " + plural(s.autoLockMinutes(), "minute", "minutes") + " without activity", ReportPdf.INK);
            pdf.row("Unlocked", times(s.unlocks()), ReportPdf.INK);
            pdf.row("Locked by you", times(s.locks()), ReportPdf.INK);
            pdf.row("Locked itself (no activity)", times(s.autoLocks()), ReportPdf.INK);
            pdf.row("Secure notes", String.valueOf(s.notes()), ReportPdf.INK);

            pdf.heading("Protected files");

            pdf.row("Protected", plural(s.protectedItems(), "item", "items") + ", " + plural(s.protectedFiles(), "file", "files"), ReportPdf.INK);
            pdf.row("Last check", s.lastCheck() == null ? "Not checked in the last " + DAYS + " days" : s.lastCheck(),
                    s.lastCheck() == null && s.protectedItems() > 0 ? ReportPdf.WARN : ReportPdf.INK);
            pdf.row("Last backup", s.lastBackup() == null ? "No backup in the last " + DAYS + " days" : s.lastBackup(),
                    s.lastBackup() == null && s.protectedItems() > 0 ? ReportPdf.WARN : ReportPdf.INK);

            // The heading, the two counts and the start of the table stay on one page.
            pdf.ensure(44 + 2 * 18 + 18 * (Math.min(s.recentSignIns().size(), 4) + 1));

            pdf.heading("Sign-ins in the last " + DAYS + " days");

            pdf.row("Successful", String.valueOf(s.signIns()), ReportPdf.INK);
            pdf.row("Failed or refused", String.valueOf(s.failedSignIns()), s.failedSignIns() > 0 ? ReportPdf.BAD : ReportPdf.INK);

            if (!s.recentSignIns().isEmpty()) {

                List<String[]> rows = new ArrayList<>();

                for (SignIn signIn : s.recentSignIns()) {
                    rows.add(new String[] {signIn.when(), signIn.result(), signIn.device(), signIn.signedOut()});
                }

                pdf.table(new String[] {"When", "Result", "Device", "Signed out"}, new float[] {0.25f, 0.2f, 0.25f, 0.3f}, rows);

            }

            pdf.heading("Activity in the last " + DAYS + " days");

            if (s.activity().isEmpty()) {

                pdf.paragraph("Nothing was recorded.", ReportPdf.MUTED);

            } else {

                List<String[]> rows = new ArrayList<>();

                s.activity().forEach((what, count) -> rows.add(new String[] {what, String.valueOf(count)}));

                pdf.table(new String[] {"What was done", "Times"}, new float[] {0.8f, 0.2f}, rows);

            }

            pdf.paragraph("Each sign-in's own list of what was done is in Settings, under Sign-in history.", ReportPdf.MUTED);

            pdf.save(target, "Made by CyberShield. " + PRIVACY_NOTE);

        }

    }

    private static String plural(int count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }

    private static String times(int count) {
        return count == 1 ? "Once" : count + " times";
    }

}
