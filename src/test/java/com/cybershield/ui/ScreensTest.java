package com.cybershield.ui;

import com.cybershield.TestSupport;
import com.cybershield.files.FileVaultService;
import com.cybershield.password.PasswordEntry;
import com.cybershield.password.PasswordService;
import com.cybershield.security.SessionManager;
import com.cybershield.services.AuthenticationService;
import com.cybershield.ui.controllers.DashboardController;
import com.cybershield.vault.VaultService;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TabPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

        realHome = System.getProperty("user.home");

    }

    @AfterAll
    static void restoreHome() {

        System.setProperty("user.home", realHome);

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

        // Home with everything filled in, and a module that isn't built yet
        run(dashboard.controller()::openDashboard);
        snapshot("13-home", scene);

        run(() -> ((Button) scene.lookup("#notesButton")).fire());
        snapshot("14-coming-soon", scene);

        // The take-over screen, shown to accounts whose items are in the old shared vault
        snapshot("15-vault-takeover", onFx(() -> {
            Parent takeover = Ui.load("/fxml/vault/vault_takeover.fxml").root();
            takeover.getStylesheets().add(0, Ui.class.getResource("/css/theme.css").toExternalForm());
            return new Scene(takeover, WIDTH - 236, HEIGHT - 90);
        }));

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
