package com.cybershield.ui.controllers;

import com.cybershield.encryption.SharedCipher;
import com.cybershield.ui.Ui;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The Encryption Center's two new tabs: the 3-mode generator, and recovering a file password. */
class EncryptionGeneratorScreenTest {

    private static boolean toolkitReady;

    @TempDir
    Path dir;

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
    void generatesAPasswordInEachMode() throws Exception {

        assumeTrue(toolkitReady, "JavaFX can't start here");

        Parent root = load().root();

        onFx(() -> {
            ((Button) root.lookup("#generatorTab")).fire();
            return null;
        });

        // Random
        String random = generate(root, "#randomMode");
        assertEquals(16, random.length(), random);

        // Memorable words
        String words = generate(root, "#wordsMode");
        assertTrue(words.split("[-]").length >= 4, words);
        assertTrue(text(root, "#generatorStrength").contains("bits"), text(root, "#generatorStrength"));

        // PIN
        String pin = generate(root, "#pinMode");
        assertTrue(pin.chars().allMatch(Character::isDigit), pin);
        assertEquals(6, pin.length());

    }

    @Test
    void recoversACyberShieldFilePasswordFromAHint() throws Exception {

        assumeTrue(toolkitReady, "JavaFX can't start here");

        Path secret = Files.writeString(dir.resolve("notes.txt"), "remember the milk");

        Path locked = dir.resolve("notes.txt.cshield");

        SharedCipher.encryptFile(secret, locked, "rex12", SharedCipher.Progress.NONE);

        Ui.Loaded<EncryptionController> loaded = load();

        Parent root = loaded.root();

        onFx(() -> {

            ((Button) root.lookup("#recoveryTab")).fire();

            loaded.controller().useRecoveryFile(locked);

            ((TextArea) root.lookup("#recoveryHints")).setText("rex");

            // The common list is slow against a .cshield (each try derives a key); the hint is enough.
            ((CheckBox) root.lookup("#includeCommon")).setSelected(false);

            ((Button) root.lookup("#startRecoveryButton")).fire();

            return null;

        });

        waitFor(() -> labelText(root, "#recoveryMessage").startsWith("Found it"), "the password is found");

        assertEquals("rex12", onFx(() -> ((TextField) root.lookup("#recoveredPassword")).getText()));
        assertTrue(onFx(() -> root.lookup("#recoveredPassword").isVisible()), "the recovered password is shown");

    }

    // ------------------------------------------------------------- helpers

    private Ui.Loaded<EncryptionController> load() throws Exception {

        return onFx(() -> {
            Ui.Loaded<EncryptionController> made = Ui.load("/fxml/encryption/encryption.fxml");
            new Scene(new StackPane(made.root()), 1000, 680);
            return made;
        });

    }

    private String generate(Parent root, String modeId) throws Exception {

        return onFx(() -> {
            ((RadioButton) root.lookup(modeId)).setSelected(true);
            ((Button) root.lookup("#generateButton")).fire();
            return ((TextField) root.lookup("#generatedPassword")).getText();
        });

    }

    private static String text(Parent root, String id) throws Exception {

        return onFx(() -> labelText(root, id));

    }

    /** Reads a label directly; call on the JavaFX thread (waitFor conditions run there). */
    private static String labelText(Parent root, String id) {

        Label node = (Label) root.lookup(id);

        String value = node == null ? null : node.getText();

        return value == null ? "" : value;

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
