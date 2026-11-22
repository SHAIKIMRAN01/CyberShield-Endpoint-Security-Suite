package com.cybershield.ui;

import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A keyboard on the screen, for typing a password with the mouse: keyloggers that record
 * key presses get nothing. "Mix the keys" puts them in a new order, against tools that
 * record where the mouse clicks.
 */
public final class OnScreenKeyboard {

    private static final String[] LOWER = {"`1234567890-=", "qwertyuiop[]\\", "asdfghjkl;'", "zxcvbnm,./"};

    private static final String[] UPPER = {"~!@#$%^&*()_+", "QWERTYUIOP{}|", "ASDFGHJKL:\"", "ZXCVBNM<>?"};

    // The keyboard icon: a rounded box with rows of small keys.
    private static final String ICON = "M4 5.5 H20 A2 2 0 0 1 22 7.5 V16.5 A2 2 0 0 1 20 18.5 H4 A2 2 0 0 1 2 16.5 V7.5 A2 2 0 0 1 4 5.5 Z "
            + "M4 7.5 V16.5 H20 V7.5 Z M5.5 9 H7.5 V11 H5.5 Z M9 9 H11 V11 H9 Z M12.5 9 H14.5 V11 H12.5 Z M16 9 H18 V11 H16 Z "
            + "M5.5 12.5 H7.5 V14.5 H5.5 Z M9 12.5 H15 V14.5 H9 Z M16 12.5 H18 V14.5 H16 Z";

    private static final SecureRandom RANDOM = new SecureRandom();

    // The keyboard is a popup of its own, outside the window's stylesheets: its colours are
    // set here, from the theme's palette (sand keys, ink text, card background, line border).
    private static final String KEY_SHAPE = "-fx-background-radius: 6; -fx-font-size: 14px; -fx-min-width: 34; -fx-pref-width: 34; "
            + "-fx-min-height: 32; -fx-padding: 0; -fx-cursor: hand;";

    private static final String WIDE_SHAPE = "-fx-background-radius: 6; -fx-font-size: 12px; -fx-min-height: 32; "
            + "-fx-padding: 0 12 0 12; -fx-cursor: hand;";

    private static String keyStyle() {
        return (Theme.isDark() ? "-fx-background-color: #322720; -fx-text-fill: #EFE5DA; " : "-fx-background-color: #F2E9DD; -fx-text-fill: #2B1D15; ")
                + KEY_SHAPE;
    }

    private static String wideStyle() {
        return (Theme.isDark() ? "-fx-background-color: #3D3027; -fx-text-fill: #EFE5DA; " : "-fx-background-color: #E6D9C8; -fx-text-fill: #2B1D15; ")
                + WIDE_SHAPE;
    }

    private static String panelStyle() {
        return (Theme.isDark() ? "-fx-background-color: #271E18; -fx-border-color: #4A3B30; " : "-fx-background-color: #FFFDF9; -fx-border-color: #DDD0BE; ")
                + "-fx-background-radius: 10; -fx-border-radius: 10; -fx-effect: dropshadow(gaussian, rgba(46,31,22,0.18), 14, 0.1, 0, 4);";
    }

    private final TextInputControl target;

    private final Popup popup = new Popup();

    private final VBox rows = new VBox(5);

    private final List<int[]> order = new ArrayList<>();

    private boolean shifted;

    private boolean mixed;

    private OnScreenKeyboard(TextInputControl target) {

        this.target = target;

        for (String row : LOWER) {
            int[] places = new int[row.length()];
            for (int i = 0; i < places.length; i++) {
                places[i] = i;
            }
            order.add(places);
        }

        rows.setPadding(new Insets(10));

        popup.getContent().add(rows);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);

        draw();

    }

    /** Puts a keyboard button right after field, in the same place in the layout. */
    public static Button attach(TextInputControl field) {

        OnScreenKeyboard keyboard = new OnScreenKeyboard(field);

        Button button = new Button();

        button.setGraphic(Ui.icon(ICON, 18, "keyboard-icon"));
        button.setTooltip(new Tooltip("Type with the mouse (against keyloggers)"));
        button.setFocusTraversable(false);
        button.getStyleClass().add("keyboard-button");
        button.setMinWidth(Button.USE_PREF_SIZE);
        button.setOnAction(event -> keyboard.toggle());

        Parent parent = field.getParent();

        if (parent instanceof Pane pane) {

            int index = pane.getChildren().indexOf(field);

            HBox row = new HBox(6);
            row.setAlignment(Pos.CENTER_LEFT);

            if (parent instanceof GridPane) {
                GridPane.setRowIndex(row, GridPane.getRowIndex(field));
                GridPane.setColumnIndex(row, GridPane.getColumnIndex(field));
                GridPane.setColumnSpan(row, GridPane.getColumnSpan(field));
            }

            VBox.setVgrow(row, VBox.getVgrow(field));

            pane.getChildren().set(index, row);

            field.setMaxWidth(Double.MAX_VALUE);

            HBox.setHgrow(field, Priority.ALWAYS);

            row.getChildren().addAll(field, button);

        }

        return button;

    }

    private void toggle() {

        if (popup.isShowing()) {
            popup.hide();
            return;
        }

        Bounds bounds = target.localToScreen(target.getBoundsInLocal());

        if (bounds == null) {
            return;
        }

        draw();

        popup.show(target, bounds.getMinX(), bounds.getMaxY() + 6);

        target.requestFocus();

    }

    private void draw() {

        rows.setStyle(panelStyle());

        rows.getChildren().clear();

        String[] keys = shifted ? UPPER : LOWER;

        for (int r = 0; r < keys.length; r++) {

            HBox row = new HBox(5);

            row.setAlignment(Pos.CENTER);

            for (int place : order.get(r)) {

                String character = String.valueOf(keys[r].charAt(place));

                row.getChildren().add(key(character, keyStyle(), () -> type(character)));

            }

            if (r == 0) {
                row.getChildren().add(key("⌫", wideStyle(), this::backspace));
            }

            rows.getChildren().add(row);

        }

        HBox bottom = new HBox(5);

        bottom.setAlignment(Pos.CENTER);

        Button shift = key(shifted ? "Shift (on)" : "Shift", wideStyle(), () -> {
            shifted = !shifted;
            draw();
        });

        Button space = key("Space", wideStyle(), () -> type(" "));
        space.setMinWidth(160);

        Button mix = key(mixed ? "Keys in order" : "Mix the keys", wideStyle(), this::toggleMix);

        Button done = key("Done", wideStyle(), popup::hide);

        bottom.getChildren().addAll(shift, space, mix, done);

        rows.getChildren().add(bottom);

    }

    private Button key(String text, String style, Runnable action) {

        Button key = new Button(text);

        key.setStyle(style);
        key.setFocusTraversable(false);
        key.setMnemonicParsing(false);
        key.setOnAction(event -> {
            action.run();
            target.requestFocus();
        });

        return key;

    }

    private void type(String character) {

        target.replaceSelection(character);

    }

    private void backspace() {

        if (target.getSelection().getLength() > 0) {
            target.replaceSelection("");
        } else {
            target.deletePreviousChar();
        }

    }

    private void toggleMix() {

        mixed = !mixed;

        for (int r = 0; r < order.size(); r++) {

            List<Integer> places = new ArrayList<>();

            for (int i = 0; i < LOWER[r].length(); i++) {
                places.add(i);
            }

            if (mixed) {
                Collections.shuffle(places, RANDOM);
            }

            int[] row = order.get(r);

            for (int i = 0; i < row.length; i++) {
                row[i] = places.get(i);
            }

        }

        draw();

    }

    // ------------------------------------------------------------- for the tests

    /** The keys as shown now, row by row (without the Backspace and the bottom row). */
    List<String> shownKeys() {

        List<String> shown = new ArrayList<>();

        for (Node row : rows.getChildren().subList(0, LOWER.length)) {

            StringBuilder text = new StringBuilder();

            for (Node node : ((HBox) row).getChildren()) {
                String label = ((Button) node).getText();
                if (label.length() == 1 && !label.equals("⌫")) {
                    text.append(label);
                }
            }

            shown.add(text.toString());

        }

        return shown;

    }

    /** Presses the key showing this text, as a click would. */
    void press(String label) {

        for (Node row : rows.getChildren()) {
            for (Node node : ((HBox) row).getChildren()) {
                if (node instanceof Button key && key.getText().equals(label)) {
                    key.fire();
                    return;
                }
            }
        }

        throw new IllegalArgumentException("No key " + label);

    }

    /** A keyboard for field without a button, for the tests. */
    static OnScreenKeyboard forTest(TextInputControl field) {
        return new OnScreenKeyboard(field);
    }

}
