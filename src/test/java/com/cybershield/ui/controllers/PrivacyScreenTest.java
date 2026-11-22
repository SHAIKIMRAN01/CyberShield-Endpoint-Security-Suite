package com.cybershield.ui.controllers;

import com.cybershield.privacy.TraceCleaner;
import com.cybershield.ui.Ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Privacy Tools screen, on made-up files and folders only. The clipboard button isn't
 * pressed here: it would clear whatever the person running the tests has copied.
 */
class PrivacyScreenTest {

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
    void shredsCleansPhotosAndWindowsTraces() throws Exception {

        assumeTrue(toolkitReady, "JavaFX can't start here");

        Ui.Loaded<PrivacyController> loaded = onFx(() -> {
            Ui.Loaded<PrivacyController> made = Ui.load("/fxml/privacy/privacy.fxml");
            new Scene(made.root(), 1000, 680);
            return made;
        });

        PrivacyController screen = loaded.controller();

        Parent root = loaded.root();

        // ------------------------------------------------------------- the keyboard sits beside the try-it field

        onFx(() -> {
            PasswordField field = (PasswordField) root.lookup("#tryKeyboardField");
            HBox row = (HBox) field.getParent();
            assertTrue(row.getChildren().get(1).getStyleClass().contains("keyboard-button"));
            return null;
        });

        // ------------------------------------------------------------- shred

        Path single = Files.writeString(dir.resolve("old letter.txt"), "dear bank");

        Path folder = Files.createDirectories(dir.resolve("old scans"));

        Files.writeString(folder.resolve("passport.txt"), "scan");

        Path keep = Files.writeString(dir.resolve("keep.txt"), "not chosen");

        onFx(() -> {
            screen.addToShred(List.of(single, folder));
            return null;
        });

        waitFor(() -> text(root, "#shredSummary").startsWith("2 files in 1 folder"), "the plan is shown");

        onFx(() -> {
            ((Button) root.lookup("#shredButton")).fire();
            assertTrue(root.lookup("#shredConfirmBox").isVisible(), "asks first");
            assertTrue(text(root, "#shredConfirmLabel").startsWith("Shred 2 files"));
            screen.confirmShred();
            return null;
        });

        waitFor(() -> text(root, "#shredMessage").startsWith("Shredded 2 files"), "shredded");

        assertFalse(Files.exists(single));
        assertFalse(Files.exists(folder));
        assertEquals("not chosen", Files.readString(keep));

        waitFor(() -> ((ListView<?>) root.lookup("#shredList")).getItems().isEmpty(), "the list empties");

        // ------------------------------------------------------------- photo details

        onFx(() -> {
            ((Button) root.lookup("#photoTab")).fire();
            assertTrue(root.lookup("#photoPanel").isVisible());
            assertFalse(root.lookup("#shredPanel").isVisible());
            return null;
        });

        Path photo = Files.write(dir.resolve("beach.jpg"), jpegWithComment());

        Path plain = Files.write(dir.resolve("plain.jpg"), jpeg());

        onFx(() -> {
            screen.addPhotos(List.of(photo, plain));
            return null;
        });

        waitFor(() -> text(root, "#photoMessage").equals("1 photo has details to remove."), "photos read");

        onFx(() -> {
            TableView<?> table = (TableView<?>) root.lookup("#photoTable");
            assertEquals(2, table.getItems().size());
            screen.cleanPhotos();
            return null;
        });

        waitFor(() -> text(root, "#photoMessage").startsWith("Saved 1 clean copy"), "clean copy saved");

        assertTrue(Files.exists(dir.resolve("beach (no details).jpg")));
        assertFalse(Files.exists(dir.resolve("plain (no details).jpg")), "nothing to remove, no copy");

        // ------------------------------------------------------------- Windows traces, in made-up folders

        Path recent = Files.createDirectories(dir.resolve("Recent"));
        Path automatic = Files.createDirectories(recent.resolve("AutomaticDestinations"));
        Path explorer = Files.createDirectories(dir.resolve("Explorer"));
        Path temp = Files.createDirectories(dir.resolve("Temp"));

        Files.writeString(recent.resolve("letter.txt.lnk"), "shortcut");
        Path jumpList = Files.writeString(automatic.resolve("a.automaticDestinations-ms"), "pinned");

        TraceCleaner cleaner = new TraceCleaner(new TraceCleaner.Places(recent, automatic, recent.resolve("CustomDestinations"), explorer, temp));

        onFx(() -> {
            ((Button) root.lookup("#tracesTab")).fire();
            screen.useTraceCleaner(cleaner);
            screen.scanTraces();
            return null;
        });

        waitFor(() -> ((VBox) root.lookup("#traceChoices")).getChildren().size() == 4, "what was found is listed");

        onFx(() -> {

            List<CheckBox> boxes = ((VBox) root.lookup("#traceChoices")).getChildren().stream()
                    .map(choice -> (CheckBox) ((VBox) choice).getChildren().get(0))
                    .toList();

            assertTrue(boxes.get(0).isSelected(), "recent items are ticked");
            assertFalse(boxes.get(1).isSelected(), "jump lists wait to be ticked: they hold pinned items");
            assertTrue(boxes.get(2).isDisabled(), "nothing found, nothing to tick");
            assertFalse(root.lookup("#cleanTracesButton").isDisabled());

            screen.confirmCleanTraces();

            return null;

        });

        waitFor(() -> text(root, "#tracesMessage").startsWith("Removed 1 item"), "traces removed");

        assertFalse(Files.exists(recent.resolve("letter.txt.lnk")));
        assertTrue(Files.exists(jumpList), "not ticked, not removed");

        // ------------------------------------------------------------- leaving the screen

        onFx(() -> {
            ((PasswordField) root.lookup("#tryKeyboardField")).setText("typed");
            screen.dispose();
            assertEquals("", ((PasswordField) root.lookup("#tryKeyboardField")).getText());
            assertTrue(((TableView<?>) root.lookup("#photoTable")).getItems().isEmpty());
            return null;
        });

    }

    private static byte[] jpeg() throws Exception {

        BufferedImage image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 24; x++) {
                image.setRGB(x, y, (x * 10) << 16 | (y * 10) << 8 | 90);
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ImageIO.write(image, "jpg", out);

        return out.toByteArray();

    }

    /** A JPEG with a comment block in it, which counts as a detail. */
    private static byte[] jpegWithComment() throws Exception {

        byte[] plain = jpeg();

        byte[] comment = "Taken at the beach house".getBytes(StandardCharsets.ISO_8859_1);

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        out.write(plain, 0, 2);
        out.write(0xFF);
        out.write(0xFE);
        out.write((comment.length + 2) >> 8);
        out.write((comment.length + 2) & 0xFF);
        out.write(comment);
        out.write(plain, 2, plain.length - 2);

        return out.toByteArray();

    }

    private static String text(Parent root, String id) {

        Node node = root.lookup(id);

        String text = ((Label) node).getText();

        return text == null ? "" : text;

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
