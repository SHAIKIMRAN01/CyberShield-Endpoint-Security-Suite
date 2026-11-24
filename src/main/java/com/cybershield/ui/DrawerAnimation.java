package com.cybershield.ui;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.transform.Scale;
import javafx.util.Duration;

/**
 * Opens a screen downward from its top edge, like a drawer sliding out to cover the space —
 * used when File Security shows its files. It is purely decorative: the screen is already
 * laid out, this only animates how it appears, and the transform is removed when it finishes.
 */
public final class DrawerAnimation {

    private static final double CLOSED = 0.02;

    private DrawerAnimation() {
    }

    /** Slides {@code view} open downward from its top, slowly, then takes the transform away. */
    public static void openDownward(Node view) {
        Scale scale = new Scale(1, CLOSED, 0, 0);
        view.getTransforms().add(scale);

        Timeline timeline = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(scale.yProperty(), CLOSED)),
                new KeyFrame(Duration.millis(1200), new KeyValue(scale.yProperty(), 1, Interpolator.EASE_OUT)));
        timeline.setOnFinished(e -> view.getTransforms().remove(scale));
        timeline.play();
    }

    /** Poses the drawer part-open (no animation) and returns the transform, for a screenshot or a test. */
    public static Scale poseHalfOpen(Node view) {
        Scale scale = new Scale(1, 0.5, 0, 0);
        view.getTransforms().add(scale);
        return scale;
    }

}
