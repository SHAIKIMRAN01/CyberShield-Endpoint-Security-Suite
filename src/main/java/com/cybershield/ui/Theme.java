package com.cybershield.ui;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Window;

import java.util.List;
import java.util.Locale;

/**
 * Light or dark. theme.css gives every colour a name (-cs-paper, -cs-ink, -cs-heading and
 * so on) and all other stylesheets use the names; theme-dark.css gives the same names dark
 * values. The dark sheet goes after the others on each window's root, so every screen
 * follows. Each account chooses for itself in Settings; the sign-in screen shows the last
 * one used.
 */
public final class Theme {

    public enum Mode {

        LIGHT, DARK;

        /** A stored name, or LIGHT for anything else (including nothing stored). */
        public static Mode of(String stored) {
            return stored != null && stored.trim().toUpperCase(Locale.ROOT).equals("DARK") ? DARK : LIGHT;
        }

    }

    static final String DARK_SHEET = Theme.class.getResource("/css/theme-dark.css").toExternalForm();

    private static volatile Mode current = Mode.LIGHT;

    private Theme() {
    }

    public static Mode current() {
        return current;
    }

    public static boolean isDark() {
        return current == Mode.DARK;
    }

    /** Switches every open window, and those opened later, to mode. Call on the JavaFX thread. */
    public static void use(Mode mode) {

        current = mode == null ? Mode.LIGHT : mode;

        for (Window window : List.copyOf(Window.getWindows())) {
            if (window.getScene() != null) {
                style(window.getScene());
            }
        }

    }

    /** Gives a scene the current theme: the dark sheet last on its root, or not at all. Returns the scene. */
    public static Scene style(Scene scene) {

        Parent root = scene.getRoot();

        if (root != null) {

            root.getStylesheets().remove(DARK_SHEET);

            if (isDark()) {
                root.getStylesheets().add(DARK_SHEET);
            }

        }

        return scene;

    }

    /** The page colour, for the few things drawn in code (the end of the opening animation). */
    public static Color paper() {
        return isDark() ? Color.web("#1D1611") : Color.web("#FAF6F0");
    }

}
