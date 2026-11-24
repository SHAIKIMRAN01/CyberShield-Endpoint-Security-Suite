package com.cybershield.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Rotate;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * The flourish shown when a hidden message is revealed in Steganography: a sheep covers a
 * wolf, then a hand lifts the sheep away from its bottom-right corner — slowly, like pulling
 * back a sheet — to show the wolf underneath. A wolf in sheep's clothing: the cover looked
 * innocent, but something was hidden beneath it. Purely decorative, and a click skips it.
 *
 * <p>The wolf reuses CyberShield's own logo art ({@link Logo#WOLF}); the sheep and hand are
 * drawn from shapes. {@link #playOver} runs it; {@link #addStill} poses it mid-lift.
 */
public final class WolfRevealAnimation {

    private static final double CANVAS = 256;

    private static final Color WOOL = Color.web("#FBF7F0");
    private static final Color WOOL_EDGE = Color.web("#D9CFC0");
    private static final Color FACE = Color.web("#3A2E28");
    private static final Color LEG = Color.web("#3A2E28");
    private static final Color SKIN = Color.web("#E6B087");
    private static final Color SKIN_EDGE = Color.web("#C98E63");

    private static final String SEQ_KEY = "wolf-reveal-seq";

    private WolfRevealAnimation() {
    }

    /** Plays the reveal over {@code host} and removes it when done. */
    public static void playOver(StackPane host) {

        Overlay overlay = build("A wolf in sheep's clothing — the hidden message is out.");
        host.getChildren().add(overlay.root);
        overlay.root.setOnMouseClicked(e -> skip(host, overlay.root));

        Rotate lift = new Rotate(0, 44, 46);
        overlay.sheep.getTransforms().add(lift);

        Timeline rotate = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(lift.angleProperty(), 0)),
                new KeyFrame(Duration.millis(2200), new KeyValue(lift.angleProperty(), -46, Interpolator.EASE_IN)));

        TranslateTransition slide = new TranslateTransition(Duration.millis(2200), overlay.sheep);
        slide.setByX(70);
        slide.setByY(-36);

        FadeTransition sheepFade = new FadeTransition(Duration.millis(2200), overlay.sheep);
        sheepFade.setFromValue(1);
        sheepFade.setToValue(0.08);

        ParallelTransition lifting = new ParallelTransition(rotate, slide, sheepFade);

        PauseTransition hold = new PauseTransition(Duration.millis(1000));

        FadeTransition out = new FadeTransition(Duration.millis(900), overlay.root);
        out.setFromValue(1);
        out.setToValue(0);

        SequentialTransition sequence = new SequentialTransition(lifting, hold, out);
        sequence.setOnFinished(e -> host.getChildren().remove(overlay.root));
        overlay.root.getProperties().put(SEQ_KEY, sequence);
        sequence.play();
    }

    /**
     * Plays the hide (the reverse): the sheep is lowered back over the wolf to cover it, like
     * laying a sheet down, then held in place — the message is now hidden under an innocent cover.
     */
    public static void playHideOver(StackPane host) {

        Overlay overlay = build("Hidden under an innocent cover — a wolf in sheep's clothing.");
        host.getChildren().add(overlay.root);
        overlay.root.setOnMouseClicked(e -> skip(host, overlay.root));

        Rotate lower = new Rotate(-46, 44, 46);
        overlay.sheep.getTransforms().add(lower);
        overlay.sheep.setTranslateX(70);
        overlay.sheep.setTranslateY(-36);
        overlay.sheep.setOpacity(0.08);

        Timeline rotate = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(lower.angleProperty(), -46)),
                new KeyFrame(Duration.millis(2200), new KeyValue(lower.angleProperty(), 0, Interpolator.EASE_OUT)));

        TranslateTransition slide = new TranslateTransition(Duration.millis(2200), overlay.sheep);
        slide.setToX(0);
        slide.setToY(0);

        FadeTransition sheepFade = new FadeTransition(Duration.millis(2200), overlay.sheep);
        sheepFade.setFromValue(0.08);
        sheepFade.setToValue(1);

        ParallelTransition covering = new ParallelTransition(rotate, slide, sheepFade);
        PauseTransition hold = new PauseTransition(Duration.millis(1000));

        FadeTransition out = new FadeTransition(Duration.millis(900), overlay.root);
        out.setFromValue(1);
        out.setToValue(0);

        SequentialTransition sequence = new SequentialTransition(covering, hold, out);
        sequence.setOnFinished(e -> host.getChildren().remove(overlay.root));
        overlay.root.getProperties().put(SEQ_KEY, sequence);
        sequence.play();
    }

    /** Adds the reveal posed mid-lift (no animation) and returns it, for a screenshot or a test. */
    public static Node addStill(StackPane host) {
        Overlay overlay = build("A wolf in sheep's clothing — the hidden message is out.");
        overlay.sheep.getTransforms().add(new Rotate(-28, 44, 46));
        overlay.sheep.setTranslateX(44);
        overlay.sheep.setTranslateY(-22);
        host.getChildren().add(overlay.root);
        return overlay.root;
    }

    private static void skip(StackPane host, Node overlay) {
        Object sequence = overlay.getProperties().get(SEQ_KEY);
        if (sequence instanceof javafx.animation.Animation animation) {
            animation.stop();
        }
        host.getChildren().remove(overlay);
    }

    private record Overlay(StackPane root, Group sheep) {
    }

    private static Overlay build(String captionText) {

        StackPane root = new StackPane();
        root.getStyleClass().add("wolf-reveal-overlay");
        root.setStyle("-fx-background-color: rgba(43,29,21,0.93);");
        root.setPickOnBounds(true);

        Pane stage = new Pane();
        stage.setMinSize(CANVAS, CANVAS);
        stage.setPrefSize(CANVAS, CANVAS);
        stage.setMaxSize(CANVAS, CANVAS);
        stage.setScaleX(1.3);
        stage.setScaleY(1.3);

        // the wolf, from the app's own logo art, sitting underneath — centred so the sheep hides it
        List<Logo.Part> wolfParts = new ArrayList<>(Logo.WOLF);
        wolfParts.addAll(Logo.PAWS);
        Group wolf = Logo.group(wolfParts);
        javafx.geometry.Bounds wb = wolf.getLayoutBounds();
        wolf.setTranslateX(128 - (wb.getMinX() + wb.getWidth() / 2));
        wolf.setTranslateY(116 - (wb.getMinY() + wb.getHeight() / 2));

        Group sheep = sheep();

        stage.getChildren().addAll(wolf, sheep);

        Label caption = new Label(captionText);
        caption.setStyle("-fx-text-fill: #F6EEE4; -fx-font-size: 16px;");
        StackPane.setAlignment(caption, Pos.BOTTOM_CENTER);
        StackPane.setMargin(caption, new Insets(0, 0, 54, 0));

        root.getChildren().addAll(stage, caption);

        return new Overlay(root, sheep);
    }

    /** A plain, friendly sheep drawn to cover the wolf, with a little hand pinching its corner. */
    private static Group sheep() {

        Group sheep = new Group();

        // fluffy wool body: a cluster of soft white circles
        double[][] wool = {
                {128, 112, 76}, {74, 104, 40}, {182, 104, 40},
                {92, 150, 38}, {164, 150, 38}, {128, 64, 44}, {128, 158, 46}
        };
        for (double[] c : wool) {
            Circle puff = new Circle(c[0], c[1], c[2], WOOL);
            puff.setStroke(WOOL_EDGE);
            puff.setStrokeWidth(3);
            sheep.getChildren().add(puff);
        }

        // legs
        for (double x : new double[] {96, 124, 152, 180}) {
            Rectangle leg = new Rectangle(x, 168, 12, 44);
            leg.setFill(LEG);
            leg.setArcWidth(10);
            leg.setArcHeight(10);
            sheep.getChildren().add(leg);
        }

        // face
        Ellipse face = new Ellipse(128, 150, 34, 40);
        face.setFill(FACE);
        sheep.getChildren().add(face);
        Ellipse earL = new Ellipse(96, 140, 16, 11);
        earL.setFill(FACE);
        Ellipse earR = new Ellipse(160, 140, 16, 11);
        earR.setFill(FACE);
        sheep.getChildren().addAll(earL, earR);
        Circle eyeL = new Circle(118, 146, 5, WOOL);
        Circle eyeR = new Circle(138, 146, 5, WOOL);
        sheep.getChildren().addAll(eyeL, eyeR);

        // a hand pinching the sheep's bottom-right corner, to lift it
        sheep.getChildren().add(hand());

        return sheep;
    }

    private static Group hand() {
        Group hand = new Group();
        Ellipse palm = new Ellipse(196, 182, 20, 15);
        palm.setFill(SKIN);
        palm.setStroke(SKIN_EDGE);
        palm.setStrokeWidth(2);
        hand.getChildren().add(palm);
        for (int i = 0; i < 4; i++) {
            Rectangle finger = new Rectangle(182 + i * 9, 168, 8, 20);
            finger.setFill(SKIN);
            finger.setArcWidth(8);
            finger.setArcHeight(8);
            finger.setStroke(SKIN_EDGE);
            finger.setStrokeWidth(1.5);
            hand.getChildren().add(finger);
        }
        return hand;
    }

}
