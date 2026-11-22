package com.cybershield.services;

import com.cybershield.TestSupport;
import com.cybershield.dao.LoginHistoryDAO;
import com.cybershield.security.SessionManager;
import com.cybershield.services.AuthenticationService.LoginResult;
import com.cybershield.services.AuthenticationService.LoginStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.cybershield.TestSupport.ACCOUNT_PASSWORD;
import static com.cybershield.TestSupport.count;
import static com.cybershield.TestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticationServiceTest {

    @TempDir
    Path tempDir;

    private final AuthenticationService auth = new AuthenticationService();

    private int alice;

    @BeforeEach
    void setUp() {

        TestSupport.freshEnvironment(tempDir);

        AuthenticationService.resetUnknownNameWaits();

        alice = TestSupport.newUser("alice");

    }

    // ------------------------------------------------------------- registration

    @Test
    void registrationChecksEveryRule() {

        assertFalse(auth.register("ab", "ab@example.test", ACCOUNT_PASSWORD).ok(), "username too short");
        assertFalse(auth.register("has space", "x@example.test", ACCOUNT_PASSWORD).ok(), "space in username");
        assertFalse(auth.register("carol", "not-an-email", ACCOUNT_PASSWORD).ok(), "bad email");
        assertFalse(auth.register("carol", "carol@example.test", "weakpass").ok(), "weak password");
        assertFalse(auth.register("alice", "other@example.test", ACCOUNT_PASSWORD).ok(), "username taken");
        assertFalse(auth.register("ALICE", "other@example.test", ACCOUNT_PASSWORD).ok(), "taken in another case");
        assertFalse(auth.register("carol", "ALICE@example.test", ACCOUNT_PASSWORD).ok(), "email taken in another case");

        AuthenticationService.Result ok = auth.register("carol", "carol@example.test", ACCOUNT_PASSWORD);

        assertTrue(ok.ok(), ok.message());

    }

    @Test
    void passwordsAreStoredAsBcryptHashes() throws Exception {

        String hash = TestSupport.text("SELECT password_hash FROM users WHERE id = ?", alice);

        assertTrue(hash.startsWith("$2a$"), "BCrypt hash expected");

        assertFalse(hash.contains(ACCOUNT_PASSWORD));

    }

    // ------------------------------------------------------------- sign-in and history

    @Test
    void signingInStartsASessionAndIsRecorded() throws Exception {

        LoginResult result = auth.login("alice", ACCOUNT_PASSWORD);

        assertEquals(LoginStatus.SUCCESS, result.status());

        assertEquals(alice, SessionManager.currentUserId());

        assertTrue(SessionManager.historyId() > 0);

        assertEquals(1, count("SELECT COUNT(*) FROM login_history WHERE user_id = ? AND status = 'SUCCESS'", alice));

        assertNotNull(TestSupport.text("SELECT device_name FROM login_history WHERE user_id = ?", alice));

        long historyId = SessionManager.historyId();

        auth.logout();

        assertFalse(SessionManager.isLoggedIn());

        assertNotNull(TestSupport.text("SELECT logout_time FROM login_history WHERE id = ?", historyId), "sign-out time recorded");

    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameAnswer() throws Exception {

        LoginResult wrongPassword = auth.login("alice", "Wrong-Pass-1!");

        LoginResult unknownUser = auth.login("nobody", "Wrong-Pass-1!");

        assertEquals(LoginStatus.INVALID, wrongPassword.status());

        assertEquals(LoginStatus.INVALID, unknownUser.status());

        assertNull(wrongPassword.user());

        assertFalse(SessionManager.isLoggedIn());

        assertEquals(1, count("SELECT COUNT(*) FROM login_history WHERE user_id = ? AND status = 'FAILED'", alice));

    }

    @Test
    void fiveFailuresMakeTheAccountWait() throws Exception {

        for (int attempt = 1; attempt <= 4; attempt++) {

            LoginResult result = auth.login("alice", "Wrong-Pass-1!");

            assertEquals(LoginStatus.INVALID, result.status());

            assertEquals(0, result.waitSeconds(), "no wait before the fifth failure");

        }

        LoginResult fifth = auth.login("alice", "Wrong-Pass-1!");

        assertEquals(LoginStatus.INVALID, fifth.status());

        assertEquals(30, fifth.waitSeconds(), "the fifth failure starts a 30-second wait");

        LoginResult refused = auth.login("alice", ACCOUNT_PASSWORD);

        assertEquals(LoginStatus.WAIT, refused.status(), "even the right password waits");

        assertTrue(refused.waitSeconds() > 0 && refused.waitSeconds() <= 30);

        assertEquals(1, count("SELECT COUNT(*) FROM login_history WHERE user_id = ? AND status = 'LOCKED'", alice));

        // Let the wait pass by moving the failures half a minute into the past.
        execute("UPDATE login_history SET login_time = datetime(login_time, '-31 seconds') WHERE user_id = ?", alice);

        assertEquals(LoginStatus.SUCCESS, auth.login("alice", ACCOUNT_PASSWORD).status());

        auth.logout();

        assertEquals(LoginStatus.INVALID, auth.login("alice", "Wrong-Pass-1!").status(),
                "a success starts the count again");

        assertEquals(0, auth.login("alice", "Wrong-Pass-1!").waitSeconds());

    }

    @Test
    void unknownUsernamesWaitTheSameWay() {

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertEquals(0, auth.login("ghost", "Wrong-Pass-1!").waitSeconds());
        }

        assertEquals(30, auth.login("ghost", "Wrong-Pass-1!").waitSeconds());

        assertEquals(LoginStatus.WAIT, auth.login("ghost", "Wrong-Pass-1!").status());

    }

    @Test
    void waitsDoubleUpToFifteenMinutes() {

        assertEquals(0, AuthenticationService.waitAfter(4));
        assertEquals(30, AuthenticationService.waitAfter(5));
        assertEquals(60, AuthenticationService.waitAfter(6));
        assertEquals(120, AuthenticationService.waitAfter(7));
        assertEquals(900, AuthenticationService.waitAfter(10));
        assertEquals(900, AuthenticationService.waitAfter(50));

    }

    @Test
    void turnedOffAccountsCantSignIn() throws Exception {

        execute("UPDATE users SET active = 0 WHERE id = ?", alice);

        assertEquals(LoginStatus.DISABLED, auth.login("alice", ACCOUNT_PASSWORD).status());

        assertFalse(SessionManager.isLoggedIn());

    }

    @Test
    void historyListsNewestFirst() throws Exception {

        auth.login("alice", "Wrong-Pass-1!");

        auth.login("alice", ACCOUNT_PASSWORD);

        var entries = new LoginHistoryDAO().recent(alice, 10);

        assertEquals(2, entries.size());

        assertEquals(LoginHistoryDAO.SUCCESS, entries.get(0).status());

        assertEquals(LoginHistoryDAO.FAILED, entries.get(1).status());

    }

    // ------------------------------------------------------------- password change

    @Test
    void changingTheAccountPassword() {

        assertFalse(auth.changePassword(alice, "Wrong-Pass-1!", "Brand-New-Pass-2!").ok(), "wrong current password");

        assertFalse(auth.changePassword(alice, ACCOUNT_PASSWORD, "weak").ok(), "weak new password");

        assertFalse(auth.changePassword(alice, ACCOUNT_PASSWORD, ACCOUNT_PASSWORD).ok(), "same as before");

        assertTrue(auth.changePassword(alice, ACCOUNT_PASSWORD, "Brand-New-Pass-2!").ok());

        assertEquals(LoginStatus.INVALID, auth.login("alice", ACCOUNT_PASSWORD).status());

        assertEquals(LoginStatus.SUCCESS, auth.login("alice", "Brand-New-Pass-2!").status());

        auth.logout();

    }

}
