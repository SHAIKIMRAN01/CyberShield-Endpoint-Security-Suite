package com.cybershield.ui.controllers;

import com.cybershield.ui.Ui;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Security Scan screen loads and wires up. No scan is run here — that would read the
 * real machine; the scan engines have their own tests. This only proves the FXML and the
 * controller agree and the screen opens in a sensible resting state.
 */
class ScanScreenTest {

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
    }

    @Test
    void theScreenOpensReadyToScan() throws Exception {

        assumeTrue(toolkitReady, "JavaFX can't start here");

        Ui.Loaded<ScanController> loaded = onFx(() -> {
            Ui.Loaded<ScanController> made = Ui.load("/fxml/scan/security_scan.fxml");
            new Scene(made.root(), 1100, 700);
            return made;
        });

        assertNotNull(loaded.controller(), "the controller should be wired");
        Parent root = loaded.root();

        onFx(() -> {
            Button quick = (Button) root.lookup("#quickScanButton");
            Button deep = (Button) root.lookup("#deepScanButton");

            assertNotNull(quick);
            assertNotNull(deep);
            assertFalse(quick.isDisabled(), "quick scan is ready");
            assertFalse(deep.isDisabled(), "deep scan is ready");

            // progress strip and summary are tucked away until a scan runs
            assertFalse(root.lookup("#progressBox").isVisible());
            assertFalse(root.lookup("#summaryLabel").isVisible());

            TableView<?> table = (TableView<?>) root.lookup("#resultsTable");
            assertTrue(table.getItems().isEmpty(), "no results before a scan");
            assertNotNull(table.getPlaceholder(), "an empty table explains itself");
            assertEquals(4, table.getColumns().size());

            // leaving the screen is harmless even with no scan running
            loaded.controller().dispose();
            return null;
        });
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

}
