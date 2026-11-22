package com.cybershield.ui.controllers;

import com.cybershield.TestSupport;
import com.cybershield.files.FileVaultService;
import com.cybershield.services.AuthenticationService;
import com.cybershield.ui.Ui;
import com.cybershield.vault.VaultService;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static com.cybershield.TestSupport.ACCOUNT_PASSWORD;
import static com.cybershield.TestSupport.MASTER_PASSWORD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The backup buttons in Settings: unlock first when locked, back up, and bring lost items back. */
class SettingsBackupScreenTest {

    private static final FileVaultService.JobListener QUIET = new FileVaultService.JobListener() { };

    private static boolean toolkitReady;

    @TempDir
    Path tempDir;

    private final AuthenticationService auth = new AuthenticationService();

    private final VaultService vaults = new VaultService();

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

    }

    @AfterEach
    void signOut() {
        vaults.lockVault();
        auth.logout();
    }

    @Test
    void backsUpAndBringsBackWhatWasLost() throws Exception {

        assumeTrue(toolkitReady, "JavaFX can't start here");

        Path store = TestSupport.freshEnvironment(tempDir.resolve("app"));

        int sam = TestSupport.newUser("sam");

        assertTrue(auth.login("sam", ACCOUNT_PASSWORD).ok());

        vaults.createVault(sam, MASTER_PASSWORD);

        FileVaultService files = new FileVaultService(store);

        Path letter = Files.writeString(Files.createDirectories(tempDir.resolve("documents")).resolve("letter.txt"), "dear sam");

        files.protect(files.scan(List.of(letter), QUIET), QUIET);

        vaults.lockVault();

        Ui.Loaded<SettingsController> loaded = onFx(() -> {
            Ui.Loaded<SettingsController> made = Ui.load("/fxml/settings/settings.fxml");
            new Scene(made.root(), 1000, 680);
            made.controller().useFileStore(files);
            return made;
        });

        SettingsController screen = loaded.controller();

        Parent root = loaded.root();

        AtomicInteger unlockAsked = new AtomicInteger();

        // ------------------------------------------------------------- locked: it asks to unlock first

        onFx(() -> {

            screen.setOnUnlockVault(unlockAsked::incrementAndGet);

            screen.showBackup();

            assertTrue(root.lookup("#backupPanel").isVisible());
            assertTrue(text(root, "#backupStatus").startsWith("The vault is locked"), text(root, "#backupStatus"));
            assertFalse(root.lookup("#backUpButton").isVisible());
            assertTrue(root.lookup("#unlockForBackupButton").isVisible());

            ((javafx.scene.control.Button) root.lookup("#unlockForBackupButton")).fire();

            return null;

        });

        assertEquals(1, unlockAsked.get(), "the dashboard is asked to unlock the vault");

        // ------------------------------------------------------------- unlocked: back up

        vaults.unlock(sam, MASTER_PASSWORD);

        onFx(() -> {
            screen.showBackup();
            assertTrue(root.lookup("#backUpButton").isVisible());
            return null;
        });

        waitFor(() -> text(root, "#backupStatus").startsWith("1 protected item, 1 file."), "counts shown");

        assertTrue(text(root, "#backupStatus").contains("No backup in the last"), text(root, "#backupStatus"));

        Path drive = Files.createDirectories(tempDir.resolve("usb"));

        onFx(() -> {
            screen.backUpTo(drive);
            return null;
        });

        waitFor(() -> text(root, "#backupMessage").startsWith("Backed up 1 file to "), "backed up");

        waitFor(() -> text(root, "#backupStatus").contains("Last backup: "), "the last backup is shown");

        Path backup;

        try (Stream<Path> made = Files.list(drive)) {
            backup = made.filter(folder -> folder.getFileName().toString().startsWith("CyberShield backup ")).findFirst().orElseThrow();
        }

        // ------------------------------------------------------------- the list is lost; restoring brings it back

        TestSupport.execute("DELETE FROM protected_files");
        TestSupport.execute("DELETE FROM protected_items");

        assertTrue(files.listItems().isEmpty());

        onFx(() -> {
            screen.restoreBackupFrom(backup);
            return null;
        });

        waitFor(() -> text(root, "#backupMessage").startsWith("Brought back 1 item"), "restored");

        assertEquals(1, files.listItems().size());

        onFx(() -> {
            screen.restoreBackupFrom(backup);
            return null;
        });

        waitFor(() -> text(root, "#backupMessage").startsWith("Nothing to bring back"), "nothing more to restore");

        // ------------------------------------------------------------- a folder that isn't a backup

        onFx(() -> {
            screen.restoreBackupFrom(tempDir.resolve("documents"));
            return null;
        });

        waitFor(() -> text(root, "#backupMessage").contains("isn't a CyberShield backup"), "refused");

    }

    private static String text(Parent root, String id) {

        String text = ((Label) root.lookup(id)).getText();

        return text == null ? "" : text;

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

}
