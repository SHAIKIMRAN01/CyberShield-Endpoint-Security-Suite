package com.cybershield.ui.controllers;

import com.cybershield.ui.Ui;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The Steganography screen: hide a message, see before and after, reveal it from the saved picture. */
class SteganographyScreenTest {

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
    void hidesAMessageShowsBeforeAndAfterAndRevealsIt() throws Exception {

        assumeTrue(toolkitReady, "JavaFX can't start here");

        Path picture = dir.resolve("garden.png");

        ImageIO.write(cover(), "png", picture.toFile());

        Ui.Loaded<SteganographyController> loaded = onFx(() -> {
            Ui.Loaded<SteganographyController> made = Ui.load("/fxml/stego/steganography.fxml");
            new Scene(made.root(), 980, 640);
            return made;
        });

        SteganographyController screen = loaded.controller();

        Parent root = loaded.root();

        onFx(() -> {
            screen.useCover(picture);
            return null;
        });

        waitFor(() -> text(root, "#coverLabel").contains("320 × 200"), 5000);

        onFx(() -> {
            ((TextArea) root.lookup("#messageArea")).setText("The key is under the third stone.");
            ((PasswordField) root.lookup("#hidePassword")).setText("Garden-Pass-77");
            ((PasswordField) root.lookup("#hidePasswordAgain")).setText("Garden-Pass-77");
            return null;
        });

        assertTrue(text(root, "#roomLabel").startsWith("Uses "), text(root, "#roomLabel"));

        Path saved = dir.resolve("garden (hidden).jpg");

        onFx(() -> {
            screen.hideTo(saved);
            return null;
        });

        Path png = dir.resolve("garden (hidden).png");

        waitFor(() -> onFxQuietly(() -> root.lookup("#previewGrid").isVisible()), 15000);

        assertTrue(Files.exists(png), "always saved as PNG, even when a JPEG name was given");
        assertTrue(onFx(() -> ((ImageView) root.lookup("#afterView")).getImage() != null), "the after picture shows");
        assertTrue(text(root, "#previewNote").contains("changed"), text(root, "#previewNote"));

        onFx(() -> {
            screen.useHiddenPicture(png);
            ((PasswordField) root.lookup("#revealPassword")).setText("Wrong-Pass-123");
            screen.reveal();
            return null;
        });

        waitFor(() -> text(root, "#revealMessage").startsWith("That password"), 15000);

        assertTrue(text(root, "#revealMessage").startsWith("That password doesn't open it"));

        onFx(() -> {
            ((PasswordField) root.lookup("#revealPassword")).setText("Garden-Pass-77");
            screen.reveal();
            return null;
        });

        waitFor(() -> !onFxQuietly(() -> ((TextArea) root.lookup("#revealedText")).getText().isEmpty()), 15000);

        assertEquals("The key is under the third stone.", onFx(() -> ((TextArea) root.lookup("#revealedText")).getText()));

        onFx(() -> {
            screen.dispose();
            return null;
        });

        assertEquals("", onFx(() -> ((TextArea) root.lookup("#revealedText")).getText()), "the message is dropped when the screen closes");
        assertTrue(!onFx(() -> ((GridPane) root.lookup("#previewGrid")).isVisible()));

    }

    private static BufferedImage cover() {

        BufferedImage picture = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);

        Graphics2D g = picture.createGraphics();

        g.setPaint(new GradientPaint(0, 0, new Color(214, 190, 160), 320, 200, new Color(90, 59, 39)));
        g.fillRect(0, 0, 320, 200);
        g.dispose();

        return picture;

    }

    private static String text(Parent root, String id) {
        return onFxQuietly(() -> ((Label) root.lookup(id)).getText());
    }

    private static void waitFor(BooleanSupplier condition, long millis) throws InterruptedException {

        long until = System.currentTimeMillis() + millis;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < until) {
            Thread.sleep(40);
        }

    }

    private static <T> T onFxQuietly(Callable<T> work) {

        try {
            return onFx(work);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

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
