package com.cybershield.reports;

import com.cybershield.TestSupport;
import com.cybershield.services.AuthenticationService;
import com.cybershield.vault.VaultService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;

import static com.cybershield.TestSupport.ACCOUNT_PASSWORD;
import static com.cybershield.TestSupport.MASTER_PASSWORD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The automatic monthly report: off until turned on, then one a month, in the chosen folder. */
class MonthlyReportsTest {

    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

    @TempDir
    Path tempDir;

    private final AuthenticationService auth = new AuthenticationService();

    private final VaultService vaults = new VaultService();

    private final ReportService reports = new ReportService(Files::deleteIfExists);

    private final MonthlyReports monthly = new MonthlyReports(reports);

    private int alice;

    @BeforeEach
    void setUp() {

        TestSupport.freshEnvironment(tempDir);

        alice = TestSupport.newUser("alice");

        assertTrue(auth.login("alice", ACCOUNT_PASSWORD).ok());

        vaults.createVault(alice, MASTER_PASSWORD);

    }

    @AfterEach
    void signOut() {
        vaults.lockVault();
        auth.logout();
    }

    @Test
    void offUntilTurnedOnThenOneAMonth() throws Exception {

        MonthlyReports.Schedule start = monthly.load(alice);

        assertFalse(start.on());
        assertEquals(MonthlyReports.defaultFolder(), start.folder());
        assertNull(start.lastMonth());
        assertNull(monthly.makeIfDue(NOVEMBER), "off: nothing is made");

        Path folder = tempDir.resolve("reports");

        monthly.save(alice, true, folder);

        ReportDAO.Row november = monthly.makeIfDue(NOVEMBER);

        assertNotNull(november);
        assertEquals("CyberShield monthly report 2026-11.pdf", november.name());
        assertEquals(ReportService.TYPE_MONTHLY, november.type());
        assertTrue(Files.isRegularFile(folder.resolve(november.name())), "the folder is made when needed");
        assertEquals(NOVEMBER, monthly.load(alice).lastMonth());

        assertNull(monthly.makeIfDue(NOVEMBER), "one a month");

        assertNotNull(monthly.makeIfDue(NOVEMBER.plusMonths(1)));
        assertTrue(Files.isRegularFile(folder.resolve("CyberShield monthly report 2026-12.pdf")));
        assertEquals(2, reports.history().size(), "both are listed");

        monthly.save(alice, false, folder);

        assertNull(monthly.makeIfDue(NOVEMBER.plusMonths(2)), "off again");
        assertEquals(NOVEMBER.plusMonths(1), monthly.load(alice).lastMonth(), "turning it off keeps when the last was made");

    }

    @Test
    void aMonthThatCouldNotBeSavedIsTriedAgain() throws Exception {

        Path inTheWay = Files.writeString(tempDir.resolve("a file, not a folder"), "in the way");

        monthly.save(alice, true, inTheWay.resolve("reports"));

        assertThrows(IOException.class, () -> monthly.makeIfDue(NOVEMBER));

        assertNull(monthly.load(alice).lastMonth(), "not marked as made");
        assertTrue(reports.history().isEmpty());

        monthly.save(alice, true, tempDir.resolve("reports"));

        assertNotNull(monthly.makeIfDue(NOVEMBER), "made at the next try");

    }

    @Test
    void eachAccountChoosesForItself() throws Exception {

        monthly.save(alice, true, tempDir.resolve("alice's reports"));

        int bob = TestSupport.newUser("bob");

        assertFalse(monthly.load(bob).on());
        assertTrue(monthly.load(alice).on());

    }

    @Test
    void needsTheVaultUnlocked() throws Exception {

        monthly.save(alice, true, tempDir.resolve("reports"));

        vaults.lockVault();

        assertThrows(IllegalStateException.class, () -> monthly.makeIfDue(NOVEMBER));

        assertFalse(Files.exists(tempDir.resolve("reports")));

    }

}
