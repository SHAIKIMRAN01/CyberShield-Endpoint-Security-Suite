package com.cybershield.ui;

import com.cybershield.logger.AppLogger;
import com.cybershield.utils.PasswordStrengthUtil;

import javafx.concurrent.Task;
import javafx.fxml.FXMLLoader;
import javafx.scene.Group;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.paint.Color;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;

import java.io.IOException;
import java.net.URL;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Small helpers shared by the screens. */
public final class Ui {

    /** Shield outline used as the CyberShield mark (24 x 24 view box). */
    public static final String SHIELD =
            "M12 1.5 L21 5 V11.2 C21 16.6 17.3 21 12 22.5 C6.7 21 3 16.6 3 11.2 V5 Z";

    /** A keyhole cut into the shield, drawn on top of it. */
    public static final String KEYHOLE =
            "M12 7.2 A2.6 2.6 0 0 1 13.4 12 L14.2 16.4 H9.8 L10.6 12 A2.6 2.6 0 0 1 12 7.2 Z";

    // Sidebar and card icons (24 x 24, even-odd fill so inner shapes cut holes).
    public static final String ICON_HOME = "M3 11 L12 3 L21 11 V21 H14.5 V15 H9.5 V21 H3 Z";

    public static final String ICON_KEY =
            "M7.5 8 A4.5 4.5 0 1 1 7.49 8 Z M7.5 10.5 A2 2 0 1 0 7.51 10.5 Z M11.2 11 H21 V14 H19 V17 H16 V14 H11.2 Z";

    public static final String ICON_NOTE =
            "M5 2.5 H15 L19.5 7 V21.5 H5 Z M8 10 H16.5 V11.6 H8 Z M8 13.8 H16.5 V15.4 H8 Z M8 17.6 H13.5 V19.2 H8 Z";

    public static final String ICON_FOLDER =
            "M2.5 5 H10 L12 7.5 H21.5 V20 H2.5 Z M10.5 12.5 V11.5 A1.5 1.5 0 0 1 13.5 11.5 V12.5 H14.5 V17 H9.5 V12.5 Z";

    public static final String ICON_LOCK =
            "M6.5 10 V7.5 A5.5 5.5 0 0 1 17.5 7.5 V10 H19.5 V21.5 H4.5 V10 Z M9 10 H15 V7.5 A3 3 0 0 0 9 7.5 Z";

    public static final String ICON_IMAGE =
            "M2.5 4.5 H21.5 V19.5 H2.5 Z M4.5 17.5 L9.5 11 L13 15 L15.5 12.5 L19.5 17.5 Z M15.5 7 A1.8 1.8 0 1 1 15.49 7 Z";

    public static final String ICON_EYE =
            "M1 12 C4 6.5 7.8 4.5 12 4.5 C16.2 4.5 20 6.5 23 12 C20 17.5 16.2 19.5 12 19.5 C7.8 19.5 4 17.5 1 12 Z "
                    + "M12 8 A4 4 0 1 0 12.01 8 Z";

    public static final String ICON_CHART =
            "M3 20 H21 V22 H3 Z M5 12 H8.5 V18.5 H5 Z M10.25 6 H13.75 V18.5 H10.25 Z M15.5 9.5 H19 V18.5 H15.5 Z";

    public static final String ICON_SLIDERS =
            "M3 5.5 H21 V7.5 H3 Z M3 11 H21 V13 H3 Z M3 16.5 H21 V18.5 H3 Z "
                    + "M7.5 3.5 H10.5 V9.5 H7.5 Z M14 9 H17 V15 H14 Z M5.5 14.5 H8.5 V20.5 H5.5 Z";

    // A magnifier: the usual sign for scanning or searching.
    public static final String ICON_SCAN =
            "M10 2.5 A7.5 7.5 0 1 0 10.01 2.5 Z M10 5.5 A4.5 4.5 0 1 0 10.01 5.5 Z "
                    + "M15 16.4 L16.4 15 L21.8 20.4 L20.4 21.8 Z";

    // A globe with an equator and meridian: the usual sign for a network.
    public static final String ICON_NETWORK =
            "M12 2.5 A9.5 9.5 0 1 0 12.01 2.5 Z M12 4.4 A7.6 7.6 0 1 0 12.01 4.4 Z "
                    + "M3.5 11 H20.5 V13 H3.5 Z M11 3 H13 V21 H11 Z";

    // Three bars: the usual sign for a menu or sidebar.
    public static final String ICON_MENU =
            "M4.2 4.8 H19.8 A1.3 1.3 0 0 1 19.8 7.4 H4.2 A1.3 1.3 0 0 1 4.2 4.8 Z "
            + "M4.2 10.7 H19.8 A1.3 1.3 0 0 1 19.8 13.3 H4.2 A1.3 1.3 0 0 1 4.2 10.7 Z "
            + "M4.2 16.6 H19.8 A1.3 1.3 0 0 1 19.8 19.2 H4.2 A1.3 1.3 0 0 1 4.2 16.6 Z";

    public static final String ICON_CLOSE =
            "M6.4 5 L12 10.6 L17.6 5 L19 6.4 L13.4 12 L19 17.6 L17.6 19 L12 13.4 L6.4 19 L5 17.6 L10.6 12 L5 6.4 Z";

    public static final String ICON_SIGN_OUT =
            "M11 3 H4 V21 H11 V18.5 H6.5 V5.5 H11 Z M15 7 L20.5 12 L15 17 V13.5 H9 V10.5 H15 Z";

    private Ui() {
    }

    public record Loaded<T>(Parent root, T controller) {
    }

    /** Loads an FXML file from the resources, for example "/fxml/login.fxml". */
    public static <T> Loaded<T> load(String fxml) {

        URL resource = Ui.class.getResource(fxml);

        if (resource == null) {
            throw new IllegalStateException("Missing screen " + fxml);
        }

        FXMLLoader loader = new FXMLLoader(resource);

        try {

            Parent root = loader.load();

            return new Loaded<>(root, loader.getController());

        } catch (IOException e) {

            throw new IllegalStateException("Couldn't open " + fxml + ": " + e.getMessage(), e);

        }

    }

    /** Shows a message under a form. tone: "good", "bad", "warn", or null for plain. Blank text hides it. */
    public static void message(Label label, String text, String tone) {

        label.getStyleClass().removeAll("good", "bad", "warn");

        if (tone != null) {
            label.getStyleClass().add(tone);
        }

        boolean show = text != null && !text.isBlank();

        label.setText(show ? text : "");
        label.setVisible(show);
        label.setManaged(show);

    }

    /** Fills a strength bar and its label for the password typed so far. */
    public static void strength(ProgressBar bar, Label label, String password) {

        int score = PasswordStrengthUtil.calculateScore(password);

        bar.setProgress(password == null || password.isEmpty() ? 0 : Math.min(1.0, (score + 1) / 8.0));

        label.setText(password == null || password.isEmpty() ? "" : PasswordStrengthUtil.calculate(password));

    }

    /**
     * An icon from an SVG path drawn in a 24 x 24 view box, scaled to size. Every icon
     * takes exactly size x size, so labels next to different icons line up.
     */
    public static Group icon(String path, double size, String styleClass) {

        SVGPath shape = new SVGPath();

        shape.setContent(path);

        shape.setFillRule(FillRule.EVEN_ODD);

        if (styleClass != null) {
            shape.getStyleClass().add(styleClass);
        }

        Rectangle box = new Rectangle(24, 24, Color.TRANSPARENT);

        Group scaled = new Group(box, shape);

        scaled.setScaleX(size / 24);
        scaled.setScaleY(size / 24);

        // The outer group's bounds include the inner one's scale, so layout sees the real size.
        return new Group(scaled);

    }

    /**
     * Copies text and clears it from the clipboard after the given seconds, unless
     * something else has been copied meanwhile.
     */
    public static void copyAndClearLater(String text, int seconds) {

        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();

        content.putString(text);

        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);

        javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(seconds));

        delay.setOnFinished(event -> {

            javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();

            if (text.equals(clipboard.getString())) {
                clipboard.clear();
            }

        });

        delay.play();

    }

    /**
     * Runs slow work (PBKDF2, BCrypt, database) off the JavaFX thread, then hands
     * the result back on it. A failure is logged and passed to onError.
     */
    public static <T> void background(String name, Supplier<T> work, Consumer<T> onDone, Consumer<Throwable> onError) {

        Task<T> task = new Task<>() {

            @Override
            protected T call() {
                return work.get();
            }

        };

        task.setOnSucceeded(event -> onDone.accept(task.getValue()));

        task.setOnFailed(event -> {

            Throwable error = task.getException();

            AppLogger.error(name + " failed: " + (error == null ? "unknown error" : error.getMessage()));

            onError.accept(error);

        });

        Thread thread = new Thread(task, "cybershield-" + name);

        thread.setDaemon(true);

        thread.start();

    }

}
