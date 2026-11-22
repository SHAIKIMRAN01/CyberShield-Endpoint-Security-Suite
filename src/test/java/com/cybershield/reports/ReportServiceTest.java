package com.cybershield.reports;

import com.cybershield.TestSupport;
import com.cybershield.files.FileVaultService;
import com.cybershield.notes.Note;
import com.cybershield.notes.NoteService;
import com.cybershield.password.PasswordEntry;
import com.cybershield.password.PasswordService;
import com.cybershield.services.AuthenticationService;
import com.cybershield.vault.VaultService;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.cybershield.TestSupport.ACCOUNT_PASSWORD;
import static com.cybershield.TestSupport.MASTER_PASSWORD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The security report: what it counts, that it holds no secrets, and the list of saved reports. */
class ReportServiceTest {

    private static final FileVaultService.JobListener QUIET = new FileVaultService.JobListener() { };

    // Things that must never appear in a report.
    private static final List<String> SECRETS = List.of(
            "secret-bank", "mail.example", "alice.bank", "Hunter2", "pin hint", "Diary title", "QWERTY", "tax-return");

    @TempDir
    Path tempDir;

    private final AuthenticationService auth = new AuthenticationService();

    private final VaultService vaults = new VaultService();

    private final ReportService reports = new ReportService(Files::deleteIfExists);

    private int alice;

    @BeforeEach
    void setUp() throws Exception {

        Path store = TestSupport.freshEnvironment(tempDir);

        alice = TestSupport.newUser("alice");

        assertFalse(auth.login("alice", "a wrong password").ok(), "one failed attempt for the report to count");

        assertTrue(auth.login("alice", ACCOUNT_PASSWORD).ok());

        vaults.createVault(alice, MASTER_PASSWORD);

        PasswordService passwords = new PasswordService();

        // The same password twice: both are flagged as reused.
        passwords.savePassword(new PasswordEntry(alice, "secret-bank.example", "alice.bank", "Hunter2-Very-Secret!", "pin hint 4321"));
        passwords.savePassword(new PasswordEntry(alice, "mail.example", "alice.mail", "Hunter2-Very-Secret!", null));

        new NoteService().save(new Note(0, "Diary title XYZ", "note body QWERTY-SECRET", null, null));

        Path file = Files.writeString(Files.createDirectories(tempDir.resolve("files")).resolve("tax-return-2026.txt"), "taxes");

        FileVaultService files = new FileVaultService(store);

        files.protect(files.scan(List.of(file), QUIET), QUIET);

        files.checkFiles(QUIET);

    }

    @AfterEach
    void signOut() {
        vaults.lockVault();
        auth.logout();
    }

    @Test
    void theReportCountsEverythingAndNamesNothing() throws Exception {

        Path pdf = tempDir.resolve("out").resolve("report.pdf");

        ReportService.Saved saved = reports.create(pdf);

        assertTrue(Files.isRegularFile(pdf));
        assertFalse(Files.exists(pdf.resolveSibling("report.pdf.part")), "no half-written file is left");

        ReportService.Summary summary = saved.summary();

        assertEquals("alice", summary.account());
        assertEquals(2, summary.passwords().total());
        assertEquals(2, summary.passwords().reused());
        assertEquals(2, summary.passwordsNeedingAttention());
        assertEquals(1, summary.notes());
        assertEquals(1, summary.protectedItems());
        assertEquals(1, summary.protectedFiles());
        assertNotNull(summary.lastCheck(), "the check run in setUp");
        assertTrue(summary.lastCheck().contains("1 files: 0 damaged, 0 missing"), summary.lastCheck());
        assertEquals(1, summary.signIns());
        assertEquals(1, summary.failedSignIns());
        assertEquals("Signed in now", summary.recentSignIns().get(0).signedOut());
        assertTrue(summary.activity().containsKey("Saved a login"), String.valueOf(summary.activity()));
        assertEquals(2, summary.activity().get("Saved a login"));

        String text = textOf(pdf);

        assertTrue(text.contains("Security report"));
        assertTrue(text.contains("Account: alice"));
        assertTrue(text.contains("Used for more than one login"));
        assertTrue(text.contains("Saved a login"));
        assertTrue(text.contains("Page 1 of"));

        for (String secret : SECRETS) {
            assertFalse(text.contains(secret), "the report contains " + secret);
        }

    }

    @Test
    void savedReportsAreListedPerAccountAndCanBeDeleted() throws Exception {

        Path pdf = tempDir.resolve("report.pdf");

        reports.create(pdf);

        List<ReportDAO.Row> rows = reports.history();

        assertEquals(1, rows.size());
        assertEquals("report.pdf", rows.get(0).name());
        assertEquals(ReportService.TYPE_SUMMARY, rows.get(0).type());
        assertTrue(rows.get(0).fileExists());

        reports.create(pdf);

        assertEquals(1, reports.history().size(), "saving over a report replaces it in the list");

        ReportDAO.Row alices = reports.history().get(0);

        vaults.lockVault();

        auth.logout();

        int bob = TestSupport.newUser("bob");

        assertTrue(auth.login("bob", ACCOUNT_PASSWORD).ok());

        vaults.createVault(bob, "Bobs-Master-Pass-3!");

        assertTrue(reports.history().isEmpty(), "bob doesn't see alice's reports");

        assertThrows(IllegalArgumentException.class, () -> reports.delete(alices));

        assertTrue(Files.exists(pdf), "nor delete them");

        vaults.lockVault();

        auth.logout();

        assertTrue(auth.login("alice", ACCOUNT_PASSWORD).ok());

        vaults.unlock(alice, MASTER_PASSWORD);

        reports.delete(reports.history().get(0));

        assertFalse(Files.exists(pdf), "the PDF goes too");
        assertTrue(reports.history().isEmpty());

        // A PDF moved away outside the app: only its entry is taken off the list.
        Path moved = tempDir.resolve("moved.pdf");

        reports.create(moved);

        Files.delete(moved);

        ReportDAO.Row gone = reports.history().get(0);

        assertFalse(gone.fileExists());

        reports.delete(gone);

        assertTrue(reports.history().isEmpty());

        assertEquals(3, TestSupport.count("SELECT COUNT(*) FROM activity_log WHERE user_id = ? AND action = 'REPORT_CREATED'", alice));
        assertEquals(2, TestSupport.count("SELECT COUNT(*) FROM activity_log WHERE user_id = ? AND action = 'REPORT_DELETED'", alice));

        String names = TestSupport.text("SELECT group_concat(detail) FROM activity_log WHERE action = 'REPORT_CREATED'");

        assertFalse(names.contains("report.pdf"), "report names are stored encrypted, like file names");

    }

    @Test
    void aReportNeedsTheVaultUnlocked() {

        vaults.lockVault();

        assertThrows(IllegalStateException.class, reports::summarize);

        assertThrows(IllegalStateException.class, () -> reports.create(tempDir.resolve("locked.pdf")));

        assertFalse(Files.exists(tempDir.resolve("locked.pdf")));

    }

    @Test
    void longTablesCarryOnToMorePagesAndOddCharactersArePrintable() throws Exception {

        PDType1Font helvetica = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

        assertEquals("Zoë – ?? ?", ReportPdf.safe(helvetica, "Zoë – 测试 ✓"));
        assertEquals("a b c", ReportPdf.safe(helvetica, "a\tb\nc"));

        List<String[]> rows = new ArrayList<>();

        for (int i = 1; i <= 120; i++) {
            rows.add(new String[] {"Row " + i, "A cell with far too much text to fit in its column. ".repeat(3),"测"});
        }

        Path pdf = tempDir.resolve("long.pdf");

        try (ReportPdf document = new ReportPdf("Long", "Long table")) {
            document.addPage();
            document.heading("A long table");
            document.table(new String[] {"Name", "Text", "Other"}, new float[] {0.2f, 0.6f, 0.2f}, rows);
            document.save(pdf, "Footer");
        }

        try (PDDocument loaded = Loader.loadPDF(pdf.toFile())) {

            int pages = loaded.getNumberOfPages();

            assertTrue(pages >= 3, "120 rows need several pages, got " + pages);

            String text = new PDFTextStripper().getText(loaded);

            assertTrue(text.contains("Row 120"));
            assertTrue(text.contains("Page " + pages + " of " + pages));
            assertTrue(text.contains("..."), "long cells are cut short: " + text.substring(0, Math.min(400, text.length())));

        }

    }

    private static String textOf(Path pdf) throws Exception {

        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(document);
        }

    }

}
