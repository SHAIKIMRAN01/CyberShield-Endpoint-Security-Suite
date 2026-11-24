package com.cybershield.ui;

import com.cybershield.TestSupport;
import com.cybershield.files.FileVaultService;
import com.cybershield.notes.Note;
import com.cybershield.notes.NoteService;
import com.cybershield.password.PasswordEntry;
import com.cybershield.password.PasswordService;
import com.cybershield.privacy.TestPhotos;
import com.cybershield.privacy.TraceCleaner;
import com.cybershield.reports.ReportService;
import com.cybershield.security.SessionManager;
import com.cybershield.services.AuthenticationService;
import com.cybershield.backup.BackupScan;
import com.cybershield.network.NetworkReader;
import com.cybershield.ui.controllers.BackupController;
import com.cybershield.ui.controllers.DashboardController;
import com.cybershield.ui.controllers.NetworkController;
import com.cybershield.ui.controllers.ScanController;
import com.cybershield.ui.controllers.ScreenDriver;
import com.cybershield.vault.VaultService;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static com.cybershield.TestSupport.ACCOUNT_PASSWORD;
import static com.cybershield.TestSupport.MASTER_PASSWORD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opens every screen with JavaFX (in windows that are never shown) and walks the
 * main flows: sign-in, vault set-up and unlock, each module, Settings. Any FXML or
 * controller error fails the build. With -Dcybershield.snapshots=folder it also
 * saves a picture of each screen there, for checking the design.
 */
class ScreensTest {

    private static final double WIDTH = 1200;

    private static final double HEIGHT = 740;

    @TempDir
    static Path tempDir;

    private static boolean toolkitReady;

    private static String realHome;

    @BeforeAll
    static void startJavaFx() {

        try {
            Platform.startup(() -> { });
            toolkitReady = true;
        } catch (IllegalStateException alreadyRunning) {
            toolkitReady = true;
        } catch (RuntimeException | Error noDisplay) {
            toolkitReady = false;
        }

        Platform.setImplicitExit(false);

        // the File Security vault-opening flourish plays live in the app; off here so the screens
        // are captured as they rest, with one posed shot taken on purpose below
        System.setProperty("cybershield.noIntro", "true");

        realHome = System.getProperty("user.home");

    }

    @AfterAll
    static void restoreHome() {

        System.setProperty("user.home", realHome);

        // don't let the "no intro" switch leak into other tests
        System.clearProperty("cybershield.noIntro");

        new VaultService().lockVault();

        SessionManager.logout();

    }

    @Test
    void everyScreenOpens() throws Exception {

        assumeTrue(toolkitReady, "JavaFX can't start here (no display)");

        Path store = TestSupport.freshEnvironment(tempDir.resolve("app"));

        Path home = Files.createDirectories(tempDir.resolve("home"));

        Path documents = Files.createDirectories(home.resolve("Documents"));
        Files.writeString(documents.resolve("budget.csv"), "month,amount\nJanuary,1200\nFebruary,980\n");
        Files.writeString(documents.resolve("letter.txt"), "Dear Sam,\nThanks for the visit.\n");
        Files.createDirectories(home.resolve("Pictures"));
        Files.write(home.resolve("Pictures").resolve("garden.png"), sampleImage());

        // Signed out
        snapshot("01-sign-in", onFx(() -> new Scene(Ui.load("/fxml/login.fxml").root(), 960, 600)));
        snapshot("02-register", onFx(() -> new Scene(Ui.load("/fxml/register.fxml").root(), 960, 600)));

        // Signed in, no vault yet
        int alex = TestSupport.newUser("alex");
        AuthenticationService auth = new AuthenticationService();
        auth.login("alex", "Wrong-Pass-1!");
        assertTrue(auth.login("alex", ACCOUNT_PASSWORD).ok());

        Ui.Loaded<DashboardController> dashboard = onFx(() -> Ui.load("/fxml/dashboard.fxml"));
        Scene scene = onFx(() -> new Scene(dashboard.root(), WIDTH, HEIGHT));
        snapshot("03-home-new-account", scene);

        run(dashboard.controller()::openPasswordVault);
        assertNotNull(scene.lookup("#confirmPasswordField"), "a new account is asked to set up its vault");
        snapshot("04-vault-setup", scene);

        // A vault with a few saved logins, then locked
        VaultService vaults = new VaultService();
        assertEquals(VaultService.Result.OK, vaults.createVault(alex, MASTER_PASSWORD));
        PasswordService passwords = new PasswordService();
        passwords.savePassword(new PasswordEntry(alex, "github.com", "alex@example.test", "Gh!x7-long-password", "Work account"));
        passwords.savePassword(new PasswordEntry(alex, "mail.example.test", "alex", "Mail#2026-strong", null));
        passwords.savePassword(new PasswordEntry(alex, "bank.example.test", "alex.k", "B4nk!-very-long-one", "Card ending 0000"));
        vaults.lockVault();

        run(dashboard.controller()::openPasswordVault);
        assertNotNull(scene.lookup("#passwordField"));
        snapshot("05-vault-unlock", scene);

        assertEquals(VaultService.Result.OK, vaults.unlock(alex, MASTER_PASSWORD));

        run(dashboard.controller()::openPasswordVault);
        assertNotNull(scene.lookup("#passwordTable"), "the unlocked vault opens the Password Vault");
        snapshot("06-password-vault", scene);

        // File Security: protect a folder, then look at it in the viewer
        FileVaultService files = new FileVaultService(store);
        FileVaultService.JobListener quiet = new FileVaultService.JobListener() { };
        files.protect(files.scan(List.of(home.resolve("Pictures")), quiet), quiet);

        // File Security opens the home folder first: show it a made-up one. Only for opening the
        // screen, because JavaFX unpacks its native libraries under the home folder.
        System.setProperty("user.home", home.toString());
        try {
            run(dashboard.controller()::openFileSecurity);
        } finally {
            System.setProperty("user.home", realHome);
        }
        Thread.sleep(600);
        snapshot("07-file-security", scene);

        // the vault-opening flourish, posed half-open over File Security
        javafx.scene.Node vaultStill = onFx(() -> ScreenDriver.vaultOpeningStill(dashboard.controller()));
        snapshot("50-vault-opening", scene);
        run(() -> ScreenDriver.removeOverlay(dashboard.controller(), vaultStill));

        run(() -> ((TabPane) scene.lookup(".tab-pane")).getSelectionModel().select(1));
        Thread.sleep(600);
        run(() -> selectFirstFile(scene));
        Thread.sleep(1200);
        snapshot("08-protected-items", scene);

        // Settings and its sections
        run(dashboard.controller()::openSettings);
        snapshot("09-settings", scene);

        for (String[] tab : new String[][] {{"#accountTab", "10-settings-account"}, {"#masterTab", "11-settings-master"},
                {"#historyTab", "12-settings-history"}}) {
            run(() -> ((Button) scene.lookup(tab[0])).fire());
            snapshot(tab[1], scene);
        }

        // Home with everything filled in
        run(dashboard.controller()::openDashboard);
        snapshot("13-home", scene);

        // Secure Notes, with a note open
        NoteService notes = new NoteService();
        notes.save(new Note(0, "Trip plan", "Train at 08:10, hotel near the station." + System.lineSeparator()
                + "Pack the charger and the passports.", null, null));
        notes.save(new Note(0, "Cabin Wi-Fi", "Network: Cabin-5G", null, null));
        run(() -> ((Button) scene.lookup("#notesButton")).fire());
        assertNotNull(scene.lookup("#noteList"), "Secure Notes opens behind the unlocked vault");
        run(() -> ((ListView<?>) scene.lookup("#noteList")).getSelectionModel().select(1));
        snapshot("14-secure-notes", scene);

        // Encryption Center
        run(dashboard.controller()::openEncryptionCenter);
        assertNotNull(scene.lookup("#strengthTab"));
        snapshot("15-encryption", scene);
        run(() -> ((Button) scene.lookup("#generatorTab")).fire());
        run(() -> ((RadioButton) scene.lookup("#wordsMode")).setSelected(true));
        run(() -> ((Button) scene.lookup("#generateButton")).fire());
        snapshot("41-encryption-generator", scene);
        run(() -> ((Button) scene.lookup("#recoveryTab")).fire());
        snapshot("42-encryption-recovery", scene);
        run(() -> ((Button) scene.lookup("#strengthTab")).fire());

        // File Security's tools
        System.setProperty("user.home", home.toString());
        try {
            run(dashboard.controller()::openFileSecurity);
        } finally {
            System.setProperty("user.home", realHome);
        }
        Thread.sleep(400);
        run(() -> ((TabPane) scene.lookup(".tab-pane")).getSelectionModel().select(2));
        assertNotNull(scene.lookup("#backupButton"));
        snapshot("16-file-tools", scene);

        // Reports, with one saved report
        new ReportService(Files::deleteIfExists).create(tempDir.resolve("reports").resolve("CyberShield security report.pdf"));
        run(() -> ((Button) scene.lookup("#reportsButton")).fire());
        assertNotNull(scene.lookup("#reportTable"), "Reports opens behind the unlocked vault");
        snapshot("17-reports", scene);

        // The sign-ins of chosen days
        run(() -> ((TabPane) scene.lookup("#tabs")).getSelectionModel().select(1));
        run(() -> ScreenDriver.reportDays(dashboard.controller(), LocalDate.now().minusDays(6), LocalDate.now()));
        waitFor(() -> label(scene, "#historyCounts").contains("sign-in"), "the sign-ins are counted");
        snapshot("24-reports-sign-ins-by-date", scene);

        // Saved reports, with the monthly report switch
        run(() -> ((TabPane) scene.lookup("#tabs")).getSelectionModel().select(2));
        run(() -> ((javafx.scene.control.TableView<?>) scene.lookup("#reportTable")).getSelectionModel().select(0));
        assertTrue(text(scene, "#monthlyFolderLabel").contains("CyberShield reports"), text(scene, "#monthlyFolderLabel"));
        snapshot("18-reports-saved", scene);

        // A PDF for every sign-in: the one in progress is listed first
        run(() -> ((TabPane) scene.lookup("#tabs")).getSelectionModel().select(3));
        Thread.sleep(1500);
        run(() -> ((javafx.scene.control.TableView<?>) scene.lookup("#sessionTable")).getSelectionModel().select(0));
        assertTrue(onFx(() -> ((javafx.scene.control.TableView<?>) scene.lookup("#sessionTable")).getItems().size()) >= 1,
                "the sign-in in progress is listed");
        snapshot("19-sign-in-reports", scene);

        // Steganography: a message about to be hidden in a picture, then before and after
        Path pictures = Files.createDirectories(tempDir.resolve("pictures"));
        Path garden = Files.write(pictures.resolve("garden.png"), sampleImage());
        run(() -> ((Button) scene.lookup("#stegoButton")).fire());
        assertNotNull(scene.lookup("#hidePanel"), "Steganography opens");
        run(() -> ScreenDriver.stegoCover(dashboard.controller(), garden));
        waitFor(() -> label(scene, "#coverLabel").contains("800 × 520"), "the picture is read");
        run(() -> {
            ((TextArea) scene.lookup("#messageArea")).setText("Meet at the old oak at six. Bring the blue notebook.");
            ((PasswordField) scene.lookup("#hidePassword")).setText("Garden-Pass-77");
            ((PasswordField) scene.lookup("#hidePasswordAgain")).setText("Garden-Pass-77");
        });
        snapshot("20-steganography", scene);
        run(() -> ScreenDriver.stegoHideTo(dashboard.controller(), pictures.resolve("garden (hidden).png")));
        waitFor(() -> scene.lookup("#previewGrid").isVisible(), "before and after are shown");
        snapshot("25-steganography-preview", scene);

        // the wolf-in-sheep's-clothing reveal, posed mid-lift over Steganography
        javafx.scene.Node wolfStill = onFx(() -> ScreenDriver.wolfRevealStill(dashboard.controller()));
        snapshot("52-wolf-reveal", scene);
        run(() -> ScreenDriver.removeOverlay(dashboard.controller(), wolfStill));

        // Privacy Tools, on made-up photos and made-up Windows folders: never this PC's own
        run(() -> ((Button) scene.lookup("#privacyButton")).fire());
        assertNotNull(scene.lookup("#shredPanel"), "Privacy Tools opens");
        snapshot("26-privacy-shred", scene);

        Path beach = Files.write(pictures.resolve("beach.jpg"), TestPhotos.phonePhoto(TestPhotos.jpeg(TestPhotos.picture(640, 420))));
        run(() -> ((Button) scene.lookup("#photoTab")).fire());
        run(() -> ScreenDriver.privacyPhotos(dashboard.controller(), List.of(beach, garden)));
        waitFor(() -> label(scene, "#photoMessage").contains("details to remove"), "the photos are read");
        snapshot("27-privacy-photo-details", scene);

        Path windows = Files.createDirectories(tempDir.resolve("made-up windows"));
        Path recent = Files.createDirectories(windows.resolve("Recent"));
        Path jumpLists = Files.createDirectories(recent.resolve("AutomaticDestinations"));
        Path explorer = Files.createDirectories(windows.resolve("Explorer"));
        Path temp = Files.createDirectories(windows.resolve("Temp"));
        for (String shortcut : List.of("budget.csv.lnk", "letter.txt.lnk", "trip plan.docx.lnk")) {
            Files.writeString(recent.resolve(shortcut), "shortcut " + shortcut);
        }
        Files.writeString(jumpLists.resolve("5f7b5f1e01b83767.automaticDestinations-ms"), "jump list");
        Files.write(explorer.resolve("thumbcache_256.db"), new byte[48_000]);
        Path leftOver = Files.write(temp.resolve("setup.tmp"), new byte[120_000]);
        Files.setLastModifiedTime(leftOver, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3 * 86_400_000L));
        TraceCleaner madeUp = new TraceCleaner(new TraceCleaner.Places(recent, jumpLists, recent.resolve("CustomDestinations"), explorer, temp));
        run(() -> ((Button) scene.lookup("#tracesTab")).fire());
        run(() -> ScreenDriver.privacyTraces(dashboard.controller(), madeUp));
        waitFor(() -> ((VBox) scene.lookup("#traceChoices")).getChildren().size() == 4, "what was found is listed");
        snapshot("28-privacy-traces", scene);

        run(() -> ((Button) scene.lookup("#keyboardTab")).fire());
        snapshot("29-privacy-keyboard", scene);

        // Security Scan: the resting screen, then with a made-up set of results (never this PC's own)
        run(dashboard.controller()::openSecurityScan);
        assertNotNull(scene.lookup("#resultsTable"), "Security Scan opens");
        snapshot("43-security-scan", scene);
        run(() -> ScreenDriver.securityScan(dashboard.controller(), demoScanRows(),
                "Found 1 setting to look at · 1 file worth a look · 1 file to be aware of. See below."));
        snapshot("44-security-scan-results", scene);

        // Network Analyzer: a made-up snapshot of connections and look-ups (never this PC's own)
        run(dashboard.controller()::openNetworkAnalyzer);
        assertNotNull(scene.lookup("#connectionsTable"), "Network Analyzer opens");
        run(() -> ScreenDriver.network(dashboard.controller(), demoNetworkRows(), demoDnsRows(),
                "9 connections across 7 apps · 5 to the Internet · 3 listening · 4 names looked up recently."));
        snapshot("46-network-analyzer", scene);

        // Backup: a made-up set of chosen folders and a previewed plan (never this PC's own files)
        run(dashboard.controller()::openBackup);
        assertNotNull(scene.lookup("#sourceList"), "Backup opens");
        run(() -> ScreenDriver.backup(dashboard.controller(), demoBackupSources(),
                "Ready to back up 1,284 files (3.4 GB) from 5 folders.  Left out: 3 too big, 1,120 older than the limit.  Press Back up now to make the copy."));
        snapshot("48-backup", scene);

        // the per-screen flourishes, each posed over its screen (never seen live here: the intro switch is off)
        javafx.scene.Node chestStill = onFx(() -> ScreenDriver.chestLockStill(dashboard.controller()));
        snapshot("54-backup-chest", scene);
        run(() -> ScreenDriver.removeOverlay(dashboard.controller(), chestStill));

        run(() -> ((Button) scene.lookup("#notesButton")).fire());
        javafx.scene.Node tomeStill = onFx(() -> ScreenDriver.cursedTomeStill(dashboard.controller()));
        snapshot("55-secure-notes-tome", scene);
        run(() -> ScreenDriver.removeOverlay(dashboard.controller(), tomeStill));

        run(dashboard.controller()::openEncryptionCenter);
        javafx.scene.Node mazeStill = onFx(() -> ScreenDriver.mazeStill(dashboard.controller()));
        snapshot("56-encryption-maze", scene);
        run(() -> ScreenDriver.removeOverlay(dashboard.controller(), mazeStill));

        run(dashboard.controller()::openSecurityScan);
        javafx.scene.Node ratStill = onFx(() -> ScreenDriver.ratStill(dashboard.controller()));
        snapshot("57-security-scan-rat", scene);
        run(() -> ScreenDriver.removeOverlay(dashboard.controller(), ratStill));

        run(dashboard.controller()::openPrivacyTools);
        javafx.scene.Node catStill = onFx(() -> ScreenDriver.catStill(dashboard.controller()));
        snapshot("58-privacy-cat", scene);
        run(() -> ScreenDriver.removeOverlay(dashboard.controller(), catStill));

        // Settings: backup and restore, with the vault unlocked
        run(dashboard.controller()::openSettings);
        run(() -> ((Button) scene.lookup("#backupTab")).fire());
        waitFor(() -> label(scene, "#backupStatus").contains("protected item"), "the backup section shows what there is");
        snapshot("30-settings-backup", scene);

        // The dark theme, chosen in Settings, on the main screens
        run(() -> ((Button) scene.lookup("#appearanceTab")).fire());
        run(() -> ((javafx.scene.control.RadioButton) scene.lookup("#darkChoice")).fire());
        run(() -> Theme.style(scene));
        assertEquals(Theme.Mode.DARK, Theme.current());
        snapshot("31-dark-settings-appearance", scene);
        run(dashboard.controller()::openDashboard);
        snapshot("32-dark-home", scene);
        run(dashboard.controller()::openPasswordVault);
        snapshot("33-dark-password-vault", scene);
        run(() -> ((Button) scene.lookup("#notesButton")).fire());
        run(() -> ((ListView<?>) scene.lookup("#noteList")).getSelectionModel().select(1));
        snapshot("34-dark-secure-notes", scene);
        System.setProperty("user.home", home.toString());
        try {
            run(dashboard.controller()::openFileSecurity);
        } finally {
            System.setProperty("user.home", realHome);
        }
        Thread.sleep(400);
        run(() -> ((TabPane) scene.lookup(".tab-pane")).getSelectionModel().select(1));
        Thread.sleep(600);
        run(() -> selectFirstFile(scene));
        Thread.sleep(1200);
        snapshot("35-dark-file-security", scene);
        run(dashboard.controller()::openEncryptionCenter);
        snapshot("36-dark-encryption", scene);
        run(() -> ((Button) scene.lookup("#stegoButton")).fire());
        run(() -> ScreenDriver.stegoCover(dashboard.controller(), garden));
        waitFor(() -> label(scene, "#coverLabel").contains("800 × 520"), "the picture is read");
        snapshot("37-dark-steganography", scene);
        run(() -> ((Button) scene.lookup("#privacyButton")).fire());
        run(() -> ((Button) scene.lookup("#tracesTab")).fire());
        run(() -> ScreenDriver.privacyTraces(dashboard.controller(), madeUp));
        waitFor(() -> ((VBox) scene.lookup("#traceChoices")).getChildren().size() == 4, "what was found is listed");
        snapshot("38-dark-privacy", scene);
        run(() -> ((Button) scene.lookup("#reportsButton")).fire());
        snapshot("39-dark-reports", scene);
        run(dashboard.controller()::openSecurityScan);
        run(() -> ScreenDriver.securityScan(dashboard.controller(), demoScanRows(),
                "Found 1 setting to look at · 1 file worth a look · 1 file to be aware of. See below."));
        snapshot("45-dark-security-scan", scene);
        run(dashboard.controller()::openNetworkAnalyzer);
        run(() -> ScreenDriver.network(dashboard.controller(), demoNetworkRows(), demoDnsRows(),
                "9 connections across 7 apps · 5 to the Internet · 3 listening · 4 names looked up recently."));
        snapshot("47-dark-network-analyzer", scene);
        run(dashboard.controller()::openBackup);
        run(() -> ScreenDriver.backup(dashboard.controller(), demoBackupSources(),
                "Ready to back up 1,284 files (3.4 GB) from 5 folders.  Left out: 3 too big, 1,120 older than the limit.  Press Back up now to make the copy."));
        snapshot("49-dark-backup", scene);
        snapshot("40-dark-sign-in", onFx(() -> Theme.style(new Scene(Ui.load("/fxml/login.fxml").root(), 960, 600))));
        run(dashboard.controller()::openSettings);
        run(() -> ((Button) scene.lookup("#appearanceTab")).fire());
        run(() -> ((javafx.scene.control.RadioButton) scene.lookup("#lightChoice")).fire());
        run(() -> Theme.style(scene));
        assertEquals(Theme.Mode.LIGHT, Theme.current(), "back to light");

        // The sidebar shrunk to its icons, then closed (the ☰ button brings it back)
        run(() -> dashboard.controller().showSidebar(DashboardController.SidebarMode.RAIL, false));
        run(dashboard.controller()::openDashboard);
        snapshot("22-sidebar-icons", scene);
        run(() -> dashboard.controller().showSidebar(DashboardController.SidebarMode.CLOSED, false));
        snapshot("23-sidebar-closed", scene);
        run(() -> ((Button) scene.lookup("#menuButton")).fire());
        Thread.sleep(400);
        assertEquals(DashboardController.SidebarMode.OPEN, dashboard.controller().sidebarMode(), "☰ brings the sidebar back");

        // The take-over screen, shown to accounts whose items are in the old shared vault
        snapshot("21-vault-takeover", onFx(() -> {
            Parent takeover = Ui.load("/fxml/vault/vault_takeover.fxml").root();
            takeover.getStylesheets().add(0, Ui.class.getResource("/css/theme.css").toExternalForm());
            return new Scene(takeover, WIDTH - 236, HEIGHT - 90);
        }));

        // the sign-out grim reaper, posed mid-swing over the current screen
        run(dashboard.controller()::openDashboard);
        javafx.scene.Node reaperStill = onFx(() -> ScreenDriver.grimReaperStill(dashboard.controller()));
        snapshot("53-sign-out", scene);
        run(() -> ScreenDriver.removeOverlay(dashboard.controller(), reaperStill));

        run(() -> ((Button) scene.lookup("#signOutButton")).fire());
        assertTrue(!SessionManager.isLoggedIn(), "signing out ends the session");

    }

    private static void selectFirstFile(Scene scene) {

        @SuppressWarnings("unchecked")
        TreeView<Object> tree = (TreeView<Object>) scene.lookup("#vaultTree");

        if (tree == null || tree.getRoot() == null) {
            return;
        }

        TreeItem<Object> item = tree.getRoot();

        while (!item.getChildren().isEmpty()) {
            item.setExpanded(true);
            item = item.getChildren().get(0);
        }

        tree.getSelectionModel().select(item);

    }

    // ------------------------------------------------------------- JavaFX thread helpers

    private static <T> T onFx(Callable<T> work) throws Exception {

        CompletableFuture<T> result = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });

        return result.get(30, TimeUnit.SECONDS);

    }

    /** A label's text; call on the JavaFX thread (waitFor's conditions run there). */
    private static String label(Scene scene, String id) {

        String text = ((Label) scene.lookup(id)).getText();

        return text == null ? "" : text;

    }

    private static String text(Scene scene, String id) throws Exception {

        return onFx(() -> label(scene, id));

    }

    private static void waitFor(BooleanSupplier condition, String what) throws Exception {

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);

        while (System.nanoTime() < deadline) {

            if (onFx(condition::getAsBoolean)) {
                return;
            }

            Thread.sleep(50);

        }

        throw new AssertionError("timed out waiting: " + what);

    }

    private static void run(Runnable work) throws Exception {

        onFx(() -> {
            work.run();
            return null;
        });

        // Let runLater work queued by the screen finish.
        onFx(() -> null);

    }

    /** Lays the scene out; with -Dcybershield.snapshots=folder also saves it as a PNG. */
    private static void snapshot(String name, Scene scene) throws Exception {

        String folder = System.getProperty("cybershield.snapshots");

        WritableImage image = onFx(() -> {

            Parent root = scene.getRoot();

            root.applyCss();
            root.layout();

            return folder == null ? null : scene.snapshot(null);

        });

        if (folder == null) {
            return;
        }

        int width = (int) image.getWidth();
        int height = (int) image.getHeight();

        int[] pixels = new int[width * height];

        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);

        BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        picture.setRGB(0, 0, width, height, pixels, 0, width);

        Path out = Files.createDirectories(Path.of(folder)).resolve(name + ".png");

        ImageIO.write(picture, "png", out.toFile());

    }

    /** A made-up set of scan results for the screenshots, so no real file or setting is shown. */
    private static List<ScanController.Row> demoScanRows() {
        return List.of(
                new ScanController.Row("Needs attention", "Firewall",
                        "The firewall is off, so the laptop is more open on networks."
                                + "    \u2192  Turn the Windows Defender Firewall back on for all network types.",
                        "Windows setting"),
                new ScanController.Row("Worth a look", "Hidden file type",
                        "Named like a document but is really a program \u2014 e.g. photo.jpg.exe. A common disguise."
                                + "    \u00B7  SHA-256 eb201af5aaf0d60629d3d2a61e466cfc0fedb517add831ecac5235e1daa963d6",
                        "C:\\Users\\sam\\Downloads\\invoice.pdf.exe"),
                new ScanController.Row("Good to know", "Installer in Downloads",
                        "A program or installer sitting in Downloads. Fine if you put it there \u2014 delete it once used.",
                        "C:\\Users\\sam\\Downloads\\setup.exe"),
                new ScanController.Row("Good", "Antivirus", "Antivirus is on.", "Windows setting"),
                new ScanController.Row("For information", "Start-up programs",
                        "3 programs run at sign-in: OneDrive, Spotify, Updater."
                                + "    \u2192  Disable any you don't recognise in Task Manager \u203A Start-up apps.",
                        "Windows setting"));
    }

    /** A made-up set of connections for the screenshots, so no real connection is shown.
     *  Grouped by app, with each remote address resolved to its website name. */
    private static List<NetworkController.ConnRow> demoNetworkRows() {
        return List.of(
                row("chrome", "The Internet", "secure web (HTTPS)", "www.google.com : 443",
                        "chrome is connected to www.google.com (142.250.80.46) on port 443 (secure web (HTTPS)) — out on the Internet."),
                row("chrome", "The Internet", "secure web (HTTPS)", "fonts.gstatic.com : 443",
                        "chrome is connected to fonts.gstatic.com (142.250.180.3) on port 443 (secure web (HTTPS)) — out on the Internet."),
                row("chrome", "The Internet", "secure web (HTTPS)", "i.ytimg.com : 443",
                        "chrome is connected to i.ytimg.com (142.250.80.118) on port 443 (secure web (HTTPS)) — out on the Internet."),
                row("Spotify", "The Internet", "secure web (HTTPS)", "api.spotify.com : 443",
                        "Spotify is connected to api.spotify.com (35.186.224.25) on port 443 (secure web (HTTPS)) — out on the Internet."),
                row("Outlook", "The Internet", "email (IMAP over TLS)", "outlook.office365.com : 993",
                        "Outlook is connected to outlook.office365.com (52.96.40.10) on port 993 (email (IMAP over TLS)) — out on the Internet."),
                row("explorer", "Local network", "Windows file sharing (SMB)", "192.168.1.20 : 445",
                        "explorer is connected to 192.168.1.20 on port 445 (Windows file sharing (SMB)) — a device on your local network."),
                row("postgres", "All interfaces", "PostgreSQL", ":: : 5432",
                        "postgres is listening on port 5432 (PostgreSQL) — reachable from your network."),
                row("svchost", "Local network", "device discovery (mDNS)", ":: : 5353",
                        "svchost is receiving on UDP port 5353 (device discovery (mDNS)) — on your local network."),
                row("jhi_service", "This computer", "port 49669", "127.0.0.1 : 49669",
                        "jhi_service is listening on port 49669 (port 49669) — only this computer can reach it."));
    }

    private static NetworkController.ConnRow row(String app, String scope, String service, String address, String explanation) {
        return new NetworkController.ConnRow(app, scope, service, address, explanation);
    }

    private static List<NetworkReader.Dns> demoDnsRows() {
        return List.of(
                new NetworkReader.Dns("www.google.com", "142.250.80.46"),
                new NetworkReader.Dns("api.spotify.com", "35.186.224.25"),
                new NetworkReader.Dns("outlook.office365.com", "52.96.40.10"),
                new NetworkReader.Dns("graph.microsoft.com", "13.107.42.12"));
    }

    /** A made-up set of chosen folders for the screenshots, so no real path is shown. */
    private static List<BackupScan.Source> demoBackupSources() {
        return List.of(
                new BackupScan.Source(Path.of("C:\\Users\\sam\\Pictures"), "Pictures"),
                new BackupScan.Source(Path.of("C:\\Users\\sam\\Documents"), "Documents"),
                new BackupScan.Source(Path.of("C:\\Users\\sam\\Downloads"), "Downloads"),
                new BackupScan.Source(Path.of("C:\\Users\\sam\\Videos"), "Videos"),
                new BackupScan.Source(Path.of("C:\\Users\\sam\\Contacts"), "Contacts"));
    }

    private static byte[] sampleImage() throws Exception {

        BufferedImage image = new BufferedImage(800, 520, BufferedImage.TYPE_INT_RGB);

        java.awt.Graphics2D g = image.createGraphics();

        g.setPaint(new java.awt.GradientPaint(0, 0, new java.awt.Color(214, 190, 160), 800, 520, new java.awt.Color(90, 59, 39)));
        g.fillRect(0, 0, 800, 520);
        g.setColor(new java.awt.Color(250, 246, 240));
        g.fillOval(520, 80, 160, 160);
        g.setColor(new java.awt.Color(61, 42, 30));
        g.fillPolygon(new int[] {0, 260, 520, 800, 800, 0}, new int[] {520, 260, 380, 220, 520, 520}, 6);
        g.dispose();

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        ImageIO.write(image, "png", out);

        return out.toByteArray();

    }

}
