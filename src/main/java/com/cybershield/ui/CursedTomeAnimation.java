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
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Rotate;
import javafx.util.Duration;

/**
 * An original flourish shown when Secure Notes opens: a dark tome creaks open and two hooded
 * spirit-wisps rise from its pages, then drift away. It keeps the eerie "forbidden book" mood
 * with original art — no copyrighted characters, fonts or designs. Purely decorative, drawn
 * from shapes, skippable with a click.
 */
public final class CursedTomeAnimation {

    private static final double CANVAS = 256;
    private static final String SEQ_KEY = "cursed-tome-seq";

    private static final Color COVER = Color.web("#241C15");
    private static final Color COVER_EDGE = Color.web("#3C2F22");
    private static final Color PAGE = Color.web("#E7DCC4");
    private static final Color PAGE_LINE = Color.web("#C9BB9D");
    private static final Color EMBER = Color.web("#C9822F");
    private static final Color WISP = Color.web("#17130F");

    private CursedTomeAnimation() {
    }

    /** Plays the tome-opening flourish over {@code host} and removes it when done. */
    public static void playOver(StackPane host) {

        Parts parts = build();
        host.getChildren().add(parts.root);
        parts.root.setOnMouseClicked(e -> skip(host, parts.root));

        Timeline open = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(parts.cover.angleProperty(), 0)),
                new KeyFrame(Duration.millis(1500), new KeyValue(parts.cover.angleProperty(), -128, Interpolator.EASE_OUT)));

        ParallelTransition wisps = new ParallelTransition();
        for (Group wisp : parts.wisps) {
            FadeTransition in = new FadeTransition(Duration.millis(1600), wisp);
            in.setFromValue(0);
            in.setToValue(0.9);
            TranslateTransition rise = new TranslateTransition(Duration.millis(1900), wisp);
            rise.setByY(-66);
            FadeTransition away = new FadeTransition(Duration.millis(700), wisp);
            away.setDelay(Duration.millis(1500));
            away.setFromValue(0.9);
            away.setToValue(0);
            wisps.getChildren().addAll(in, rise, away);
        }

        PauseTransition hold = new PauseTransition(Duration.millis(500));
        FadeTransition out = new FadeTransition(Duration.millis(800), parts.root);
        out.setFromValue(1);
        out.setToValue(0);

        SequentialTransition sequence = new SequentialTransition(new ParallelTransition(open, wisps), hold, out);
        sequence.setOnFinished(e -> host.getChildren().remove(parts.root));
        parts.root.getProperties().put(SEQ_KEY, sequence);
        sequence.play();
    }

    /** Adds the tome posed part-open (no animation) and returns it, for a screenshot or a test. */
    public static Node addStill(StackPane host) {
        Parts parts = build();
        parts.cover.setAngle(-104);
        for (Group wisp : parts.wisps) {
            wisp.setOpacity(0.9);
            wisp.setTranslateY(-40);
        }
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

    private record Parts(StackPane root, Rotate cover, java.util.List<Group> wisps) {
    }

    private static Parts build() {

        StackPane root = new StackPane();
        root.getStyleClass().add("cursed-tome-overlay");
        root.setStyle("-fx-background-color: rgba(18,14,10,0.93);");
        root.setPickOnBounds(true);

        Pane stage = new Pane();
        stage.setMinSize(CANVAS, CANVAS);
        stage.setPrefSize(CANVAS, CANVAS);
        stage.setMaxSize(CANVAS, CANVAS);
        stage.setScaleX(1.35);
        stage.setScaleY(1.35);

        // two wisps behind the book, which will rise
        Group wispL = wisp(104, 150);
        Group wispR = wisp(156, 150);
        wispL.setOpacity(0);
        wispR.setOpacity(0);

        // the open pages
        Rectangle pages = new Rectangle(88, 96, 86, 86);
        pages.setFill(PAGE);
        pages.setArcWidth(8);
        pages.setArcHeight(8);
        Group pageLines = new Group();
        for (int i = 0; i < 4; i++) {
            Line line = new Line(100, 112 + i * 16, 162, 112 + i * 16);
            line.setStroke(PAGE_LINE);
            line.setStrokeWidth(2);
            pageLines.getChildren().add(line);
        }

        // the back cover behind the pages
        Rectangle back = new Rectangle(82, 90, 98, 98);
        back.setFill(COVER);
        back.setStroke(COVER_EDGE);
        back.setStrokeWidth(3);
        back.setArcWidth(10);
        back.setArcHeight(10);

        // the front cover, closed over the pages, opening on its spine
        Group front = new Group();
        Rectangle frontPlate = new Rectangle(88, 92, 90, 94);
        frontPlate.setFill(COVER);
        frontPlate.setStroke(COVER_EDGE);
        frontPlate.setStrokeWidth(3);
        frontPlate.setArcWidth(10);
        frontPlate.setArcHeight(10);
        Circle sigil = new Circle(133, 139, 18, Color.TRANSPARENT);
        sigil.setStroke(EMBER);
        sigil.setStrokeWidth(3);
        Line s1 = new Line(133, 118, 133, 160);
        Line s2 = new Line(112, 139, 154, 139);
        s1.setStroke(EMBER);
        s1.setStrokeWidth(3);
        s2.setStroke(EMBER);
        s2.setStrokeWidth(3);
        front.getChildren().addAll(frontPlate, sigil, s1, s2);
        Rotate cover = new Rotate(0, 90, 139, 0, Rotate.Y_AXIS);
        front.getTransforms().add(cover);

        stage.getChildren().addAll(wispL, wispR, back, pages, pageLines, front);

        Label caption = new Label("Your secrets are sealed in the book.");
        caption.setStyle("-fx-text-fill: #E8DCCB; -fx-font-size: 16px;");
        StackPane.setAlignment(caption, Pos.BOTTOM_CENTER);
        StackPane.setMargin(caption, new Insets(0, 0, 54, 0));

        root.getChildren().addAll(stage, caption);

        return new Parts(root, cover, java.util.List.of(wispL, wispR));
    }

    private static Group wisp(double x, double y) {
        Group wisp = new Group();
        SVGPath body = new SVGPath();
        body.setContent("M0 34 Q-16 34 -16 6 Q-16 -22 0 -22 Q16 -22 16 6 Q16 34 6 30 Q3 40 0 30 Q-3 40 -6 30 Q0 34 0 34 Z");
        body.setFill(WISP);
        Circle eyeL = new Circle(-6, -2, 3, EMBER);
        Circle eyeR = new Circle(6, -2, 3, EMBER);
        wisp.getChildren().addAll(body, eyeL, eyeR);
        wisp.setTranslateX(x);
        wisp.setTranslateY(y);
        return wisp;
    }

}
