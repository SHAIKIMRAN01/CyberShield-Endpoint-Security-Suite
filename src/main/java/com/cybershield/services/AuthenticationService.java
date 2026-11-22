package com.cybershield.services;

import com.cybershield.dao.LoginHistoryDAO;
import com.cybershield.dao.UserDAO;
import com.cybershield.logger.AppLogger;
import com.cybershield.models.User;
import com.cybershield.security.BCryptUtil;
import com.cybershield.security.SessionManager;
import com.cybershield.utils.ValidationUtil;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accounts: registration, sign-in with a wait after repeated failures, sign-out,
 * and password changes. Every sign-in attempt on an existing account is recorded
 * in login_history.
 */
public class AuthenticationService {

    /** Failed attempts allowed before the account has to wait. */
    public static final int FREE_ATTEMPTS = 5;

    public static final int FIRST_WAIT_SECONDS = 30;

    public static final int MAX_WAIT_SECONDS = 15 * 60;

    // Failures older than this are forgotten.
    private static final int LOOK_BACK_SECONDS = 60 * 60;

    public enum LoginStatus {
        SUCCESS,
        /** Unknown username or wrong password: the same answer for both. */
        INVALID,
        /** Too many failures: try again after waitSeconds. */
        WAIT,
        DISABLED,
        ERROR
    }

    /** @param waitSeconds for WAIT, and for INVALID when this failure starts a wait */
    public record LoginResult(LoginStatus status, User user, int waitSeconds) {

        public boolean ok() {
            return status == LoginStatus.SUCCESS;
        }

    }

    public record Result(boolean ok, String message) {

        static Result success(String message) {
            return new Result(true, message);
        }

        static Result error(String message) {
            return new Result(false, message);
        }

    }

    private record Failures(int count, long lastEpochSeconds) {
    }

    // Unknown usernames are made to wait the same way, in memory, so the waits don't
    // reveal which usernames exist.
    private static final Map<String, Failures> UNKNOWN_NAMES = new ConcurrentHashMap<>();

    // Checked when the username doesn't exist, so that answer takes as long as a real check.
    private static final String TIMING_HASH = BCryptUtil.hashPassword("CyberShield timing check");

    private final UserDAO userDAO = new UserDAO();

    private final LoginHistoryDAO history = new LoginHistoryDAO();

    // =====================================================
    // REGISTER
    // =====================================================

    public Result register(String username, String email, String password) {

        String name = username == null ? "" : username.trim();

        String mail = email == null ? "" : email.trim();

        if (!ValidationUtil.isValidUsername(name)) {
            return Result.error("A username is 3 to 30 letters, digits or underscores.");
        }

        if (!ValidationUtil.isValidEmail(mail)) {
            return Result.error("Enter a valid email address.");
        }

        if (!ValidationUtil.isValidPassword(password)) {
            return Result.error("Use at least 8 characters, with upper and lower case letters, a digit and a symbol.");
        }

        if (userDAO.existsUsername(name) || userDAO.findByUsernameIgnoringCase(name) != null) {
            return Result.error("That username is taken.");
        }

        if (userDAO.existsEmail(mail)) {
            return Result.error("An account with that email already exists.");
        }

        if (!userDAO.saveUser(new User(name, mail, BCryptUtil.hashPassword(password)))) {
            return Result.error("The account couldn't be saved. Try again.");
        }

        AppLogger.success("Account created: " + name);

        return Result.success("Account created. Sign in to continue.");

    }

    // =====================================================
    // SIGN IN AND OUT
    // =====================================================

    public LoginResult login(String username, String password) {

        String name = username == null ? "" : username.trim();

        if (name.isEmpty() || password == null || password.isEmpty()) {
            return new LoginResult(LoginStatus.INVALID, null, 0);
        }

        try {

            User user = userDAO.findByUsername(name);

            if (user == null) {
                return unknownName(name, password);
            }

            LoginHistoryDAO.Failures failures = history.failuresSinceLastSuccess(user.getId(), LOOK_BACK_SECONDS);

            int wait = secondsLeft(failures.count(), failures.lastFailureEpochSeconds());

            if (wait > 0) {

                history.record(user.getId(), LoginHistoryDAO.LOCKED, device());

                return new LoginResult(LoginStatus.WAIT, null, wait);

            }

            if (!passwordMatches(password, user.getPasswordHash())) {

                history.record(user.getId(), LoginHistoryDAO.FAILED, device());

                return new LoginResult(LoginStatus.INVALID, null, waitAfter(failures.count() + 1));

            }

            if (!user.isActive()) {

                history.record(user.getId(), LoginHistoryDAO.FAILED, device());

                return new LoginResult(LoginStatus.DISABLED, null, 0);

            }

            long historyId = history.record(user.getId(), LoginHistoryDAO.SUCCESS, device());

            userDAO.updateLastLogin(user.getId());

            SessionManager.login(user, historyId);

            // Sign-ins and activity are kept for 30 days.
            ActivityLog.purgeOld();

            AppLogger.info("Signed in: " + user.getUsername());

            return new LoginResult(LoginStatus.SUCCESS, user, 0);

        } catch (SQLException | RuntimeException e) {

            AppLogger.error("Sign-in failed: " + e.getMessage());

            return new LoginResult(LoginStatus.ERROR, null, 0);

        }

    }

    /** Ends the session and records the time on its sign-in. The caller locks the vault first. */
    public void logout() {

        long historyId = SessionManager.historyId();

        if (historyId > 0) {

            try {
                history.recordSignOut(historyId);
            } catch (SQLException e) {
                AppLogger.error("Couldn't record the sign-out: " + e.getMessage());
            }

        }

        SessionManager.logout();

    }

    // =====================================================
    // PASSWORD
    // =====================================================

    public Result changePassword(int userId, String currentPassword, String newPassword) {

        User user = userDAO.findById(userId);

        if (user == null) {
            return Result.error("The account wasn't found.");
        }

        if (!passwordMatches(currentPassword, user.getPasswordHash())) {
            return Result.error("The current password is wrong.");
        }

        if (!ValidationUtil.isValidPassword(newPassword)) {
            return Result.error("Use at least 8 characters, with upper and lower case letters, a digit and a symbol.");
        }

        if (passwordMatches(newPassword, user.getPasswordHash())) {
            return Result.error("Choose a password that's different from the current one.");
        }

        if (!userDAO.updatePasswordHash(userId, BCryptUtil.hashPassword(newPassword))) {
            return Result.error("The new password couldn't be saved.");
        }

        AppLogger.success("Account password changed: " + user.getUsername());

        ActivityLog.record(ActivityLog.Action.ACCOUNT_PASSWORD_CHANGED);

        return Result.success("Password changed. Use it the next time you sign in.");

    }

    // =====================================================
    // WAITING AFTER FAILURES
    // =====================================================

    /** How long the account waits after this many failures in a row: 30 s after 5, doubling up to 15 minutes. */
    public static int waitAfter(int failures) {

        if (failures < FREE_ATTEMPTS) {
            return 0;
        }

        int doublings = Math.min(failures - FREE_ATTEMPTS, 10);

        return (int) Math.min(MAX_WAIT_SECONDS, (long) FIRST_WAIT_SECONDS << doublings);

    }

    private static int secondsLeft(int failures, long lastFailureEpochSeconds) {

        int wait = waitAfter(failures);

        if (wait == 0) {
            return 0;
        }

        long left = lastFailureEpochSeconds + wait - Instant.now().getEpochSecond();

        return (int) Math.max(0, left);

    }

    private LoginResult unknownName(String name, String password) {

        Failures failures = UNKNOWN_NAMES.getOrDefault(name, new Failures(0, 0));

        long now = Instant.now().getEpochSecond();

        if (now - failures.lastEpochSeconds() > LOOK_BACK_SECONDS) {
            failures = new Failures(0, 0);
        }

        int wait = secondsLeft(failures.count(), failures.lastEpochSeconds());

        if (wait > 0) {
            return new LoginResult(LoginStatus.WAIT, null, wait);
        }

        passwordMatches(password, TIMING_HASH);

        UNKNOWN_NAMES.put(name, new Failures(failures.count() + 1, now));

        return new LoginResult(LoginStatus.INVALID, null, waitAfter(failures.count() + 1));

    }

    private static boolean passwordMatches(String password, String hash) {

        if (password == null || hash == null) {
            return false;
        }

        try {
            return BCryptUtil.verifyPassword(password, hash);
        } catch (IllegalArgumentException damagedHash) {
            AppLogger.error("A stored password hash isn't valid BCrypt.");
            return false;
        }

    }

    /** The computer's name, as recorded with each sign-in. */
    static String device() {

        String name = System.getenv("COMPUTERNAME");

        return name == null || name.isBlank() ? "This computer" : name;

    }

    /** Forgets the in-memory waits for unknown usernames (used by the tests). */
    static void resetUnknownNameWaits() {

        UNKNOWN_NAMES.clear();

    }

}
