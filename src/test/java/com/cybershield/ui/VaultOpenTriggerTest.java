package com.cybershield.ui;

import com.cybershield.TestSupport;
import com.cybershield.security.SessionManager;
import com.cybershield.services.AuthenticationService;
import com.cybershield.ui.controllers.DashboardController;
import com.cybershield.vault.VaultService;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static com.cybershield.TestSupport.ACCOUNT_PASSWORD;
import static com.cybershield.TestSupport.MASTER_PASSWORD;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves the vault-opening flourish plays when the master vault is unlocked through the UI —
 * whichever screen asked for it, not only when File Security is opened. Drives the real unlock
 * prompt with the correct master password and waits for the overlay to appear.
 */
class VaultOpenTriggerTest {

    @TempDir
    static Path tempDir;

    private static boolean toolkitReady;

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
        // the flourish is gated by this switch; make sure it is off so it can play here
        System.clearProperty("cybershield.noIntro");
    }

    @AfterAll
    static void signOut() {
        new VaultService().lockVault();
        SessionManager.logout();
    }

    @Test
    void unlockingTheMasterVaultPlaysTheFlourish() throws Exception {

        assumeTrue(toolkitReady, "JavaFX can't start here");

        TestSupport.freshEnvironment(tempDir.resolve("app"));
        int alex = TestSupport.newUser("alex");
        assertTrue(new AuthenticationService().login("alex", ACCOUNT_PASSWORD).ok(), "signed in");

        VaultService vaults = new VaultService();
        vaults.createVault(alex, MASTER_PASSWORD);
        vaults.lockVault();

        Ui.Loaded<DashboardController> dashboard = onFx(() -> Ui.load("/fxml/dashboard.fxml"));
        Scene scene = onFx(() -> new Scene(dashboard.root(), 1200, 740));

        // ask for a vault screen while locked → the unlock prompt appears
        run(dashboard.controller()::openPasswordVault);
        waitFor(() -> scene.lookup("#passwordField") != null, "the unlock prompt is shown");

        // unlock with the correct master password, through the UI
        run(() -> {
            ((PasswordField) scene.lookup("#passwordField")).setText(MASTER_PASSWORD);
            ((Button) scene.lookup("#actionButton")).fire();
        });

        // the flourish appears over whatever screen we unlocked into
        waitFor(() -> scene.lookup(".vault-open-overlay") != null,
                "the vault-opening flourish plays on unlock");
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
        return result.get(20, TimeUnit.SECONDS);
    }

    private static void run(Runnable work) throws Exception {
        onFx(() -> {
            work.run();
            return null;
        });
        onFx(() -> null);
    }

    private static void waitFor(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (onFx(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting: " + what);
    }

}
