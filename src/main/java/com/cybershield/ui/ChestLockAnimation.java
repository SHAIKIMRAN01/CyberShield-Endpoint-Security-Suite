package com.cybershield.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.transform.Rotate;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * The flourish shown when a backup finishes: the wolf's treasure chest (CyberShield's own logo
 * art) swings its lid shut and the lock snaps on — your copy is safely stored away. Purely
 * decorative, skippable with a click. {@link #playOver} runs it; {@link #addStill} poses it.
 */
public final class ChestLockAnimation {

    private static final double CANVAS = 256;
    private static final String SEQ_KEY = "chest-lock-seq";

    private ChestLockAnimation() {
    }

    /** Plays the chest-closing flourish over {@code host} and removes it when done. */
    public static void playOver(StackPane host) {

        Parts parts = build();
        host.getChildren().add(parts.root);
        parts.root.setOnMouseClicked(e -> skip(host, parts.root));

        Timeline closing = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(parts.hinge.angleProperty(), -82)),
                new KeyFrame(Duration.millis(1300), new KeyValue(parts.hinge.angleProperty(), 0, Interpolator.EASE_IN)));

        FadeTransition lockIn = new FadeTransition(Duration.millis(300), parts.lock);
        lockIn.setFromValue(0);
        lockIn.setToValue(1);
        ScaleTransition lockPop = new ScaleTransition(Duration.millis(300), parts.lock);
        lockPop.setFromX(1.5);
        lockPop.setFromY(1.5);
        lockPop.setToX(1);
        lockPop.setToY(1);

        PauseTransition hold = new PauseTransition(Duration.millis(900));
        FadeTransition out = new FadeTransition(Duration.millis(800), parts.root);
        out.setFromValue(1);
        out.setToValue(0);

        SequentialTransition sequence = new SequentialTransition(
                closing, new ParallelTransition(lockIn, lockPop), hold, out);
        sequence.setOnFinished(e -> host.getChildren().remove(parts.root));
        parts.root.getProperties().put(SEQ_KEY, sequence);
        sequence.play();
    }

    /** Adds the chest posed mostly closed (no animation) and returns it, for a screenshot or a test. */
    public static Node addStill(StackPane host) {
        Parts parts = build();
        parts.hinge.setAngle(-14);
        parts.lock.setOpacity(1);
        host.getChildren().add(parts.root);
        return parts.root;
    }

    private static void skip(StackPane host, Node overlay) {
        Object sequence = overlay.getProperties().get(SEQ_KEY);
        if (sequence instanceof javafx.animation.Animation animation) {
            animation.stop();
        }
        host.getChildren().remove(overlay);
    }

    private record Parts(StackPane root, Rotate hinge, Group lock) {
    }

    private static Parts build() {

        StackPane root = new StackPane();
        root.getStyleClass().add("chest-lock-overlay");
        root.setStyle("-fx-background-color: rgba(43,29,21,0.92);");
        root.setPickOnBounds(true);

        Pane stage = new Pane();
        stage.setMinSize(CANVAS, CANVAS);
        stage.setPrefSize(CANVAS, CANVAS);
        stage.setMaxSize(CANVAS, CANVAS);
        stage.setScaleX(1.4);
        stage.setScaleY(1.4);

        List<Logo.Part> wolfParts = new ArrayList<>(Logo.WOLF);
        wolfParts.addAll(Logo.PAWS);
        Group wolf = Logo.group(wolfParts);

        Group body = Logo.group(Logo.CHEST_BODY);

        Group lid = Logo.group(Logo.CHEST_LID);
        Rotate hinge = new Rotate(-82, 128, 112, 0, Rotate.X_AXIS);
        lid.getTransforms().add(hinge);

        Group lock = Logo.group(Logo.LOCK);
        lock.setOpacity(0);

        stage.getChildren().addAll(wolf, body, lid, lock);

        Label caption = new Label("Your backup is locked away safely.");
        caption.setStyle("-fx-text-fill: #F6EEE4; -fx-font-size: 16px;");
        StackPane.setAlignment(caption, Pos.BOTTOM_CENTER);
        StackPane.setMargin(caption, new Insets(0, 0, 54, 0));

        root.getChildren().addAll(stage, caption);

        return new Parts(root, hinge, lock);
    }

}
