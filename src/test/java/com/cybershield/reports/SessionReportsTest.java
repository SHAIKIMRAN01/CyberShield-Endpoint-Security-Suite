package com.cybershield.reports;

import com.cybershield.TestSupport;
import com.cybershield.database.DatabaseManager;
import com.cybershield.services.ActivityLog;
import com.cybershield.services.AuthenticationService;
import com.cybershield.vault.VaultService;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static com.cybershield.TestSupport.ACCOUNT_PASSWORD;
import static com.cybershield.TestSupport.MASTER_PASSWORD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A PDF for every sign-in: kept encrypted, opened only with the vault, shared and deleted only with the master password. */
class SessionReportsTest {

    private static final String COPY_PASSWORD = "Copy-Pass-2468";

    @TempDir
    Path tempDir;

    private final AuthenticationService auth = new AuthenticationService();

    private final VaultService vaults = new VaultService();

    private final SessionReports reports = new SessionReports();

    private int alice;

    @BeforeEach
    void setUp() {

        TestSupport.freshEnvironment(tempDir);

        alice = TestSupport.newUser("alice");

    }

    @AfterEach
    void signOut() {
        vaults.lockVault();
        auth.logout();
    }

    /**
     * Three sign-ins: the first signs out with the vault open (its report is kept then), the
     * second is closed without signing out, and the third is the one in progress.
     */
    private void threeSignIns() throws Exception {

        assertTrue(auth.login("alice", ACCOUNT_PASSWORD).ok());
        vaults.createVault(alice, MASTER_PASSWORD);
        ActivityLog.record(ActivityLog.Action.FILE_OPENED, "C:\\Holiday\\beach.jpg");
        ActivityLog.record(ActivityLog.Action.NOTE_SAVED, "Diary of June");
        reports.saveCurrent(true);
        vaults.lockVault();
        auth.logout();

        assertTrue(auth.login("alice", ACCOUNT_PASSWORD).ok());
        vaults.unlock(alice, MASTER_PASSWORD);
        ActivityLog.record(ActivityLog.Action.LOGIN_ADDED, "bank.example");
        vaults.lockVault();

        assertTrue(auth.login("alice", ACCOUNT_PASSWORD).ok());
        vaults.unlock(alice, MASTER_PASSWORD);

    }

    @Test
    void everyFinishedSignInGetsAnEncryptedPdfThatNamesWhatWasDone() throws Exception {

        threeSignIns();

        assertEquals(1, reports.catchUp(), "the sign-in closed without signing out gets its report now");
        assertEquals(0, reports.catchUp(), "and only once");

        List<SessionReports.Session> list = reports.list();

        assertEquals(3, list.size());
        assertTrue(list.get(0).current(), "the sign-in in progress comes first");
        assertFalse(list.get(1).current());

        for (byte[] stored : storedPdfs()) {
            String raw = new String(stored, StandardCharsets.ISO_8859_1);
            assertFalse(raw.startsWith("%PDF"), "stored encrypted, not as a PDF");
            assertFalse(raw.contains("beach") || raw.contains("bank.example"), "no names in the database");
        }

        String first = text(reports.pdf(list.get(2)));

        assertTrue(first.contains("Sign-in report"));
        assertTrue(first.contains("Account: alice"));
        assertTrue(first.contains("Opened a protected file"));
        assertTrue(first.contains("beach.jpg"), "the names are in the opened report");
        assertTrue(first.contains("Diary of June"));
        assertFalse(first.contains("bank.example"), "only that sign-in's actions");

        String second = text(reports.pdf(list.get(1)));

        assertTrue(second.contains("bank.example"));
        assertTrue(second.contains("closed without signing out"));
        assertEquals(1, list.get(1).actions() - 2, "unlocked, saved a login, locked: " + list.get(1).actions());

        assertTrue(text(reports.pdf(list.get(0))).contains("Still signed in"), "the current one is made fresh");

    }

    @Test
    void sharingNeedsTheMasterPasswordAndTheCopyItsOwnPassword() throws Exception {

        threeSignIns();

        SessionReports.Session first = reports.list().get(1);

        Path target = tempDir.resolve("shared").resolve("report.pdf");

        assertEquals(SessionReports.Outcome.WRONG_PASSWORD, reports.share(first, "Not-The-Master-1!", COPY_PASSWORD, target));
        assertFalse(Files.exists(target));

        assertThrows(IllegalArgumentException.class, () -> reports.share(first, MASTER_PASSWORD, "short", target));
        assertThrows(IllegalArgumentException.class, () -> reports.share(first, MASTER_PASSWORD, MASTER_PASSWORD, target),
                "the copy's password can't be the master password");

        assertEquals(SessionReports.Outcome.DONE, reports.share(first, MASTER_PASSWORD, COPY_PASSWORD, target));

        assertThrows(InvalidPasswordException.class, () -> Loader.loadPDF(target.toFile()), "no password, no reading");
        assertThrows(InvalidPasswordException.class, () -> Loader.loadPDF(target.toFile(), MASTER_PASSWORD));

        try (PDDocument copy = Loader.loadPDF(target.toFile(), COPY_PASSWORD)) {
            assertTrue(copy.isEncrypted());
            assertTrue(new PDFTextStripper().getText(copy).contains("beach.jpg"));
        }

        assertEquals(1, TestSupport.count("SELECT COUNT(*) FROM activity_log WHERE action = 'SESSION_REPORT_SHARED'"));

    }

    @Test
    void deletingNeedsTheMasterPasswordAndIsForGood() throws Exception {

        threeSignIns();

        reports.catchUp();

        List<SessionReports.Session> list = reports.list();

        assertThrows(IllegalArgumentException.class, () -> reports.delete(list.get(0), MASTER_PASSWORD),
                "the sign-in in progress has nothing stored yet");

        assertEquals(SessionReports.Outcome.WRONG_PASSWORD, reports.delete(list.get(1), "Not-The-Master-1!"));
        assertEquals(3, reports.list().size());

        assertEquals(SessionReports.Outcome.DONE, reports.delete(list.get(1), MASTER_PASSWORD));
        assertEquals(2, reports.list().size());

        assertEquals(0, reports.catchUp(), "a deleted report isn't made again");
        assertThrows(java.io.IOException.class, () -> reports.pdf(list.get(1)));

        assertEquals(SessionReports.Outcome.WRONG_PASSWORD, reports.deleteAll("Not-The-Master-1!"));
        assertEquals(SessionReports.Outcome.DONE, reports.deleteAll(MASTER_PASSWORD));

        assertEquals(1, reports.list().size(), "only the sign-in in progress is left");
        assertTrue(storedPdfs().isEmpty(), "no PDF is left in the database");

    }

    @Test
    void anotherAccountCantSeeOrOpenThem() throws Exception {

        threeSignIns();

        reports.catchUp();

        SessionReports.Session alices = reports.list().get(1);

        vaults.lockVault();

        auth.logout();

        int bob = TestSupport.newUser("bob");

        assertTrue(auth.login("bob", ACCOUNT_PASSWORD).ok());

        vaults.createVault(bob, "Bobs-Master-Pass-3!");

        reports.catchUp();

        assertEquals(1, reports.list().size(), "bob sees only his own sign-in");
        assertThrows(java.io.IOException.class, () -> reports.pdf(alices));
        assertEquals(SessionReports.Outcome.DONE, reports.deleteAll("Bobs-Master-Pass-3!"));

        vaults.lockVault();
        auth.logout();

        assertTrue(auth.login("alice", ACCOUNT_PASSWORD).ok());
        vaults.unlock(alice, MASTER_PASSWORD);

        assertTrue(text(reports.pdf(alices)).contains("bank.example"), "bob's delete all left alice's reports alone");

    }

    private static List<byte[]> storedPdfs() throws Exception {

        List<byte[]> pdfs = new ArrayList<>();

        try (Connection connection = DatabaseManager.getConnection();
             ResultSet rs = connection.createStatement().executeQuery("SELECT pdf FROM session_reports WHERE pdf IS NOT NULL")) {
            while (rs.next()) {
                pdfs.add(rs.getBytes(1));
            }
        }

        return pdfs;

    }

    private static String text(byte[] pdf) throws Exception {

        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }

    }

}
