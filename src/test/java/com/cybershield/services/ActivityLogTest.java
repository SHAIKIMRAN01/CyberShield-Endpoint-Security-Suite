package com.cybershield.services;

import com.cybershield.TestSupport;
import com.cybershield.dao.ActivityDAO;
import com.cybershield.dao.LoginHistoryDAO;
import com.cybershield.security.SessionManager;
import com.cybershield.vault.VaultService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.cybershield.TestSupport.ACCOUNT_PASSWORD;
import static com.cybershield.TestSupport.MASTER_PASSWORD;
import static com.cybershield.TestSupport.count;
import static com.cybershield.TestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What each sign-in did: recorded with names encrypted, kept 30 days, cleared only with the master password. */
class ActivityLogTest {

    @TempDir
    Path tempDir;

    private final AuthenticationService auth = new AuthenticationService();

    private final VaultService vaults = new VaultService();

    private final ActivityDAO activity = new ActivityDAO();

    private int alice;

    @BeforeEach
    void setUp() {

        TestSupport.freshEnvironment(tempDir);

        AuthenticationService.resetUnknownNameWaits();

        alice = TestSupport.newUser("alice");

        assertTrue(auth.login("alice", ACCOUNT_PASSWORD).ok());

    }

    @AfterEach
    void signOut() {
        vaults.lockVault();
        auth.logout();
    }

    @Test
    void namesAreStoredEncryptedAndReadOnlyWithTheVaultOpen() throws Exception {

        vaults.createVault(alice, MASTER_PASSWORD);

        ActivityLog.record(ActivityLog.Action.FILE_OPENED, "C:\\Holiday\\beach.jpg");

        ActivityLog.recordPlain(ActivityLog.Action.SETTINGS_SAVED, "Auto-lock 5 min");

        assertEquals(0, count("SELECT COUNT(*) FROM activity_log WHERE detail LIKE '%beach%'"), "no name in the clear");

        List<ActivityDAO.Event> events = activity.forSession(alice, SessionManager.historyId());

        List<String> actions = events.stream().map(ActivityDAO.Event::action).toList();

        assertEquals(List.of("VAULT_CREATED", "FILE_OPENED", "SETTINGS_SAVED"), actions);

        ActivityDAO.Event opened = events.get(1);

        assertEquals("C:\\Holiday\\beach.jpg", ActivityLog.readable(opened));

        assertEquals("Auto-lock 5 min", ActivityLog.readable(events.get(2)));

        vaults.lockVault();

        assertTrue(ActivityLog.readable(opened).contains("unlock"), "locked: the name stays hidden");

        assertEquals("VAULT_LOCKED", activity.forSession(alice, SessionManager.historyId()).get(3).action());

    }

    @Test
    void withTheVaultLockedTheNameIsLeftOutRatherThanStoredPlain() throws Exception {

        ActivityLog.record(ActivityLog.Action.FILE_OPENED, "secret.pdf");

        assertEquals(1, count("SELECT COUNT(*) FROM activity_log WHERE detail IS NULL AND action = 'FILE_OPENED'"));

    }

    @Test
    void clearingKeepsOnlyTheCurrentSignIn() throws Exception {

        long first = SessionManager.historyId();

        ActivityLog.recordPlain(ActivityLog.Action.SETTINGS_SAVED, "earlier");

        auth.logout();

        assertTrue(auth.login("alice", ACCOUNT_PASSWORD).ok());

        long current = SessionManager.historyId();

        ActivityLog.recordPlain(ActivityLog.Action.SETTINGS_SAVED, "now");

        activity.clear(alice, current);

        new LoginHistoryDAO().clear(alice, current);

        assertTrue(activity.forSession(alice, first).isEmpty());

        assertEquals(1, activity.forSession(alice, current).size());

        assertEquals(1, count("SELECT COUNT(*) FROM login_history WHERE user_id = ?", alice));

    }

    @Test
    void historyOlderThanThirtyDaysIsRemoved() throws Exception {

        ActivityLog.recordPlain(ActivityLog.Action.SETTINGS_SAVED, "old");

        ActivityLog.recordPlain(ActivityLog.Action.SETTINGS_SAVED, "recent");

        execute("UPDATE activity_log SET occurred_at = datetime('now', '-31 days') WHERE detail = 'old'");

        execute("INSERT INTO login_history (user_id, login_time, status) VALUES (?, datetime('now', '-40 days'), 'SUCCESS')", alice);

        ActivityLog.purgeOld();

        assertEquals(0, count("SELECT COUNT(*) FROM activity_log WHERE detail = 'old'"));

        assertEquals(1, count("SELECT COUNT(*) FROM activity_log WHERE detail = 'recent'"));

        assertEquals(0, count("SELECT COUNT(*) FROM login_history WHERE login_time < datetime('now', '-30 days')"));

    }

    @Test
    void clearingNeedsTheRightMasterPassword() {

        vaults.createVault(alice, MASTER_PASSWORD);

        vaults.lockVault();

        assertTrue(vaults.verifyMasterPassword(alice, MASTER_PASSWORD));

        assertFalse(vaults.verifyMasterPassword(alice, "Wrong-Master-1!"));

        assertFalse(vaults.isUnlocked(), "checking opens nothing");

    }

}
