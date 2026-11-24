package com.cybershield.ui;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.SequentialTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Rotate;
import javafx.util.Duration;

/**
 * A short flourish shown over a screen when the vault opens: a steel vault door with a dial
 * that spins, then the door swings open and fades to reveal the screen behind it. It is
 * purely decorative — the real screen is already loaded underneath — and a click skips it.
 *
 * <p>The graphic is drawn from plain shapes, so it needs no image files and stays sharp at
 * any size. {@link #playOver} runs the animation; {@link #addStill} poses it half-open for a
 * screenshot or a test.
 */
public final class VaultOpenAnimation {

    private static final String SEQ_KEY = "vault-open-seq";

    private VaultOpenAnimation() {
    }

    /** Plays the flourish over {@code host} (a stack on top of the screen) and removes it when done. */
    public static void playOver(StackPane host) {

        Overlay overlay = build();
        host.getChildren().add(overlay.root);
        overlay.root.setOnMouseClicked(e -> skip(host, overlay.root));

        RotateTransition spin = new RotateTransition(Duration.millis(1700), overlay.dial);
        spin.setByAngle(900);
        spin.setInterpolator(Interpolator.EASE_BOTH);

        RotateTransition open = new RotateTransition(Duration.millis(1300), overlay.door);
        open.setAxis(Rotate.Y_AXIS);
        open.setFromAngle(0);
        open.setToAngle(78);
        open.setInterpolator(Interpolator.EASE_IN);

        ScaleTransition grow = new ScaleTransition(Duration.millis(1300), overlay.door);
        grow.setToX(1.25);
        grow.setToY(1.25);

        FadeTransition fade = new FadeTransition(Duration.millis(1300), overlay.root);
        fade.setFromValue(1);
        fade.setToValue(0);

        SequentialTransition sequence = new SequentialTransition(spin, new ParallelTransition(open, grow, fade));
        sequence.setOnFinished(e -> host.getChildren().remove(overlay.root));
        overlay.root.getProperties().put(SEQ_KEY, sequence);
        sequence.play();
    }

    /** Adds the overlay posed half-open (no animation) and returns it, for a screenshot or a test. */
    public static Node addStill(StackPane host) {
        Overlay overlay = build();
        overlay.dial.setRotate(165);
        overlay.door.getTransforms().add(new Rotate(40, Rotate.Y_AXIS));
        overlay.root.setOpacity(0.82);
        host.getChildren().add(overlay.root);
        return overlay.root;
    }

    private static void skip(StackPane host, Node overlay) {
        Object sequence = overlay.getProperties().get(SEQ_KEY);
        if (sequence instanceof Animation animation) {
            animation.stop();
        }
        host.getChildren().remove(overlay);
    }

    private record Overlay(StackPane root, Group door, Group dial) {
    }

    private static Overlay build() {

        StackPane root = new StackPane();
        root.getStyleClass().add("vault-open-overlay");
        root.setStyle("-fx-background-color: #2B1D15;");
        root.setPickOnBounds(true);

        Group door = new Group();

        Circle plate = new Circle(0, 0, 120, Color.web("#6E6A64"));
        plate.setStroke(Color.web("#413B35"));
        plate.setStrokeWidth(10);

        Circle ring = new Circle(0, 0, 98, Color.TRANSPARENT);
        ring.setStroke(Color.web("#8A847C"));
        ring.setStrokeWidth(4);

        Group bolts = new Group();
        for (int i = 0; i < 8; i++) {
            double angle = Math.toRadians(i * 45);
            bolts.getChildren().add(new Circle(Math.cos(angle) * 109, Math.sin(angle) * 109, 7, Color.web("#B8B2AA")));
        }

        Group dial = new Group();
        Circle hub = new Circle(0, 0, 48, Color.web("#4A443E"));
        hub.setStroke(Color.web("#2E2A25"));
        hub.setStrokeWidth(4);
        dial.getChildren().add(hub);
        for (int i = 0; i < 3; i++) {
            Rectangle spoke = new Rectangle(-7, -66, 14, 132);
            spoke.setArcWidth(12);
            spoke.setArcHeight(12);
            spoke.setFill(Color.web("#8A847C"));
            spoke.getTransforms().add(new Rotate(i * 60));
            dial.getChildren().add(spoke);
        }
        dial.getChildren().add(new Circle(0, 0, 15, Color.web("#B06A2F")));

        door.getChildren().addAll(plate, ring, bolts, dial);

        Label caption = new Label("Opening your vault…");
        caption.setStyle("-fx-text-fill: #F6EEE4; -fx-font-size: 16px;");
        StackPane.setAlignment(caption, Pos.BOTTOM_CENTER);
        StackPane.setMargin(caption, new Insets(0, 0, 56, 0));

        root.getChildren().addAll(door, caption);

        return new Overlay(root, door, dial);
    }

}
