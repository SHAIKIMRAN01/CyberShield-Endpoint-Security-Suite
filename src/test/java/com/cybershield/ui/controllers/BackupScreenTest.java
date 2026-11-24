package com.cybershield.ui.controllers;

import com.cybershield.ui.Ui;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;

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
 * The Backup screen loads and wires up, with sensible defaults (7 days, 500 MB, apps list on)
 * and nothing copied until Back up now is pressed. It reads no real files — the engines have
 * their own tests — this only proves the FXML and controller agree.
 */
class BackupScreenTest {

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
    void theScreenOpensWithSafeDefaults() throws Exception {

        assumeTrue(toolkitReady, "JavaFX can't start here");

        Ui.Loaded<BackupController> loaded = onFx(() -> {
            Ui.Loaded<BackupController> made = Ui.load("/fxml/backup/backup.fxml");
            new Scene(made.root(), 1100, 700);
            return made;
        });

        assertNotNull(loaded.controller(), "the controller should be wired");
        Parent root = loaded.root();

        onFx(() -> {
            // the filters the user asked for, as defaults
            assertTrue(((CheckBox) root.lookup("#recentCheck")).isSelected());
            assertEquals(7, ((Spinner<?>) root.lookup("#recentDaysSpinner")).getValue());
            assertTrue(((CheckBox) root.lookup("#sizeCheck")).isSelected());
            assertEquals(500, ((Spinner<?>) root.lookup("#sizeMbSpinner")).getValue());
            assertTrue(((CheckBox) root.lookup("#appsCheck")).isSelected());

            // a destination is shown, and nothing can be copied until a preview is run
            assertFalse(((Label) root.lookup("#destLabel")).getText().isBlank());
            assertTrue(((Button) root.lookup("#backupButton")).isDisabled(), "Back up waits for a preview");
            assertFalse(root.lookup("#previewButton").isDisabled(), "Preview is ready");

            // the running-only bits stay hidden at rest
            assertFalse(root.lookup("#progressBox").isVisible());
            assertFalse(root.lookup("#summaryLabel").isVisible());
            assertFalse(root.lookup("#stopButton").isVisible());
            assertFalse(root.lookup("#openFolderButton").isVisible());

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
