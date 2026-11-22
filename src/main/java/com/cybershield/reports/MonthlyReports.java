package com.cybershield.reports;

import com.cybershield.database.DatabaseManager;
import com.cybershield.logger.AppLogger;
import com.cybershield.vault.VaultSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The automatic monthly report. When an account turns it on, a security report is made the
 * first time its vault is unlocked in a month (the report needs the vault, for password
 * health), saved in the chosen folder and listed with the other reports. A month whose
 * report couldn't be saved, say because the folder was on a drive that isn't plugged in,
 * is tried again at the next unlock.
 */
public final class MonthlyReports {

    /** An account's choice: on or off, the folder, and the month the last report was made for. */
    public record Schedule(boolean on, Path folder, YearMonth lastMonth) {
    }

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private static final DateTimeFormatter SHOWN_MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    // Why the last automatic try failed, shown on the Reports screen; null once one works.
    private static volatile String lastProblem;

    private final ReportService reports;

    public MonthlyReports() {
        this(new ReportService());
    }

    MonthlyReports(ReportService reports) {
        this.reports = reports;
    }

    /** Documents\CyberShield reports, or a folder of that name in the home folder. */
    public static Path defaultFolder() {

        Path home = Path.of(System.getProperty("user.home"));

        Path documents = home.resolve("Documents");

        return (Files.isDirectory(documents) ? documents : home).resolve("CyberShield reports");

    }

    /** "CyberShield monthly report 2026-11.pdf" */
    public static String fileName(YearMonth month) {

        return "CyberShield monthly report " + MONTH.format(month) + ".pdf";

    }

    /** The folder as shown: "Documents\CyberShield reports" inside the user folder, the whole path elsewhere. */
    public static String shownFolder(Path folder) {

        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();

        Path absolute = folder.toAbsolutePath().normalize();

        return absolute.startsWith(home) && !absolute.equals(home) ? home.relativize(absolute).toString() : absolute.toString();

    }

    /** "November 2026" */
    public static String shown(YearMonth month) {

        return SHOWN_MONTH.format(month);

    }

    /** Why the last automatic report couldn't be made, or null. */
    public static String lastProblem() {

        return lastProblem;

    }

    public Schedule load(int userId) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT monthly, folder, last_month FROM report_schedule WHERE user_id = ?")
        ) {

            statement.setInt(1, userId);

            try (ResultSet rs = statement.executeQuery()) {

                if (rs.next()) {
                    return new Schedule(rs.getInt(1) == 1, folder(rs.getString(2)), month(rs.getString(3)));
                }

            }

        }

        return new Schedule(false, defaultFolder(), null);

    }

    /** Turns the monthly report on or off, and sets where it goes. */
    public void save(int userId, boolean on, Path folder) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO report_schedule (user_id, monthly, folder)
                        VALUES (?, ?, ?)
                        ON CONFLICT(user_id) DO UPDATE SET
                            monthly = excluded.monthly,
                            folder = excluded.folder
                        """)
        ) {

            statement.setInt(1, userId);
            statement.setInt(2, on ? 1 : 0);
            statement.setString(3, folder.toAbsolutePath().normalize().toString());

            statement.executeUpdate();

        }

    }

    /** Notes that month's report is made, so the next unlock doesn't make another. */
    void markMade(int userId, YearMonth month) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE report_schedule SET last_month = ? WHERE user_id = ?")
        ) {

            statement.setString(1, MONTH.format(month));
            statement.setInt(2, userId);

            statement.executeUpdate();

        }

    }

    /**
     * Makes month's report if the open vault's account has the monthly report on and that
     * month's isn't made yet. Returns it, or null when nothing was due.
     *
     * @throws IllegalStateException while the vault is locked
     */
    public ReportDAO.Row makeIfDue(YearMonth month) throws IOException, SQLException {

        int userId = VaultSession.current().userId();

        Schedule schedule = load(userId);

        if (!schedule.on() || month.equals(schedule.lastMonth())) {
            return null;
        }

        ReportService.Saved saved = reports.create(schedule.folder().resolve(fileName(month)), ReportService.TYPE_MONTHLY);

        markMade(userId, month);

        return saved.row();

    }

    /**
     * Runs makeIfDue for this month on a background thread, if the vault is open. onMade
     * hears of a report made, on that thread. Only one runs at a time.
     */
    public static void makeIfDueInBackground(Consumer<ReportDAO.Row> onMade) {

        if (!VaultSession.isUnlocked() || !RUNNING.compareAndSet(false, true)) {
            return;
        }

        Thread thread = new Thread(() -> {

            try {

                ReportDAO.Row made = VaultSession.isUnlocked() ? new MonthlyReports().makeIfDue(YearMonth.now()) : null;

                lastProblem = null;

                if (made != null) {

                    AppLogger.info("Made this month's security report.");

                    onMade.accept(made);

                }

            } catch (IOException | SQLException | RuntimeException e) {

                lastProblem = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();

                AppLogger.error("Couldn't make this month's security report: " + lastProblem);

            } finally {

                RUNNING.set(false);

            }

        }, "cybershield-monthly-report");

        thread.setDaemon(true);

        thread.start();

    }

    private static Path folder(String stored) {

        try {
            return stored == null || stored.isBlank() ? defaultFolder() : Path.of(stored);
        } catch (InvalidPathException e) {
            return defaultFolder();
        }

    }

    private static YearMonth month(String stored) {

        try {
            return stored == null ? null : YearMonth.parse(stored, MONTH);
        } catch (DateTimeParseException e) {
            return null;
        }

    }

}
