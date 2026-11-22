package com.cybershield.ui;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The on-screen keyboard: typing with clicks, Shift, Backspace, and mixing the keys. */
class OnScreenKeyboardTest {

    private static final List<String> IN_ORDER = List.of("`1234567890-=", "qwertyuiop[]\\", "asdfghjkl;'", "zxcvbnm,./");

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
    void typesWhatIsClicked() throws Exception {

        assumeTrue(toolkitReady, "JavaFX can't start here");

        String typed = onFx(() -> {

            PasswordField field = new PasswordField();

            OnScreenKeyboard keyboard = OnScreenKeyboard.forTest(field);

            keyboard.press("p");
            keyboard.press("w");
            keyboard.press("Shift");
            keyboard.press("!");
            keyboard.press("Q");
            keyboard.press("Shift (on)");
            keyboard.press("x");
            keyboard.press("⌫");
            keyboard.press("Space");
            keyboard.press("9");

            return field.getText();

        });

        assertEquals("pw!Q 9", typed);

    }

    @Test
    void mixingPutsTheSameKeysInANewOrder() throws Exception {

        assumeTrue(toolkitReady, "JavaFX can't start here");

        List<List<String>> shown = onFx(() -> {

            PasswordField field = new PasswordField();

            OnScreenKeyboard keyboard = OnScreenKeyboard.forTest(field);

            List<String> before = keyboard.shownKeys();

            keyboard.press("Mix the keys");

            List<String> mixed = keyboard.shownKeys();

            // A mixed key still types its own character.
            keyboard.press(String.valueOf(mixed.get(1).charAt(0)));

            keyboard.press("Keys in order");

            return List.of(before, mixed, keyboard.shownKeys(), List.of(field.getText()));

        });

        assertEquals(IN_ORDER, shown.get(0));
        assertNotEquals(IN_ORDER, shown.get(1), "the keys moved");

        for (int row = 0; row < IN_ORDER.size(); row++) {
            assertEquals(sorted(IN_ORDER.get(row)), sorted(shown.get(1).get(row)), "row " + row + " has the same keys");
        }

        assertEquals(IN_ORDER, shown.get(2), "and back in order");
        assertEquals(String.valueOf(shown.get(1).get(1).charAt(0)), shown.get(3).get(0));

    }

    @Test
    void theButtonGoesRightBesideTheField() throws Exception {

        assumeTrue(toolkitReady, "JavaFX can't start here");

        onFx(() -> {

            Label above = new Label("Master password");
            PasswordField field = new PasswordField();
            Label below = new Label("hint");

            VBox form = new VBox(above, field, below);

            Button button = OnScreenKeyboard.attach(field);

            HBox row = (HBox) form.getChildren().get(1);

            assertEquals(3, form.getChildren().size());
            assertSame(below, form.getChildren().get(2));
            assertEquals(List.of(field, button), row.getChildren());
            assertTrue(button.getStyleClass().contains("keyboard-button"));

            PasswordField inGrid = new PasswordField();

            GridPane grid = new GridPane();
            grid.add(new Label("Confirm"), 0, 2);
            grid.add(inGrid, 1, 2, 2, 1);

            OnScreenKeyboard.attach(inGrid);

            HBox gridRow = (HBox) inGrid.getParent();

            assertSame(grid, gridRow.getParent());
            assertEquals(2, GridPane.getRowIndex(gridRow));
            assertEquals(1, GridPane.getColumnIndex(gridRow));
            assertEquals(2, GridPane.getColumnSpan(gridRow));

            return null;

        });

    }

    private static String sorted(String keys) {

        char[] chars = keys.toCharArray();

        Arrays.sort(chars);

        return new String(chars);

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
