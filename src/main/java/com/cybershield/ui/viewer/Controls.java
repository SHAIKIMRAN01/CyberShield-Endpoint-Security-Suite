package com.cybershield.ui.viewer;

import javafx.scene.control.Button;
import javafx.util.Duration;

import java.util.Locale;

/** Small helpers shared by the viewers. */
final class Controls {

    private Controls() {
    }

    static Button button(String text, Runnable action) {

        Button button = new Button(text);

        button.setOnAction(event -> action.run());

        return button;

    }

    /** 0:42, 3:05 or 1:02:09. */
    static String time(Duration duration) {

        if (duration == null || duration.isUnknown() || duration.isIndefinite()) {
            return "0:00";
        }

        long seconds = (long) duration.toSeconds();

        return seconds >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);

    }

}
