package com.cybershield.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.QuadCurve;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * The flourish shown when the Security Scan opens: a rat in a dark detective suit searches the
 * screen, hops between a couple of spots, then rushes straight at you with a "Gotcha!" — a
 * cheeky stand-in for the hunt for things worth a look. Purely decorative, drawn from shapes,
 * skippable with a click.
 */
public final class RatDetectiveAnimation {

    private static final String SEQ_KEY = "rat-seq";

    private static final Color RAT = Color.web("#8C8C8C");
    private static final Color RAT_DARK = Color.web("#6C6C6C");
    private static final Color PINK = Color.web("#E7A9A9");
    private static final Color SUIT = Color.web("#17171B");
    private static final Color DARK = Color.web("#141414");
    private static final Color GLASS = Color.web("#A9D2E6");

    private RatDetectiveAnimation() {
    }

    /** Plays the detective-rat flourish over {@code host} and removes it when done. */
    public static void playOver(StackPane host) {

        Parts parts = build();
        host.getChildren().add(parts.root);
        parts.root.setOnMouseClicked(e -> skip(host, parts.root));

        Group rat = parts.rat;
        Label gotcha = parts.gotcha;

        TranslateTransition toA = move(rat, -150, -70, 900);
        TranslateTransition toB = move(rat, 140, 60, 900);
        TranslateTransition toC = move(rat, 0, 0, 800);

        ScaleTransition pounce = new ScaleTransition(Duration.millis(800), rat);
        pounce.setToX(2.6);
        pounce.setToY(2.6);
        pounce.setInterpolator(Interpolator.EASE_IN);

        FadeTransition gotchaIn = new FadeTransition(Duration.millis(300), gotcha);
        gotchaIn.setFromValue(0);
        gotchaIn.setToValue(1);

        FadeTransition out = new FadeTransition(Duration.millis(700), parts.root);
        out.setFromValue(1);
        out.setToValue(0);

        SequentialTransition sequence = new SequentialTransition(
                toA, pause(700), toB, pause(700),
                new ParallelTransition(toC, pounce), gotchaIn, pause(900), out);
        sequence.setOnFinished(e -> host.getChildren().remove(parts.root));
        parts.root.getProperties().put(SEQ_KEY, sequence);
        sequence.play();
    }

    /** Adds the rat posed mid-pounce with the "Gotcha!" showing, for a screenshot or a test. */
    public static Node addStill(StackPane host) {
        Parts parts = build();
        parts.rat.setScaleX(2.3);
        parts.rat.setScaleY(2.3);
        parts.gotcha.setOpacity(1);
        host.getChildren().add(parts.root);
        return parts.root;
    }

    private static TranslateTransition move(Node node, double x, double y, double ms) {
        TranslateTransition t = new TranslateTransition(Duration.millis(ms), node);
        t.setToX(x);
        t.setToY(y);
        return t;
    }

    private static PauseTransition pause(double ms) {
        return new PauseTransition(Duration.millis(ms));
    }

    private static void skip(StackPane host, Node overlay) {
        Object sequence = overlay.getProperties().get(SEQ_KEY);
        if (sequence instanceof javafx.animation.Animation animation) {
            animation.stop();
        }
        host.getChildren().remove(overlay);
    }

    private record Parts(StackPane root, Group rat, Label gotcha) {
    }

    private static Parts build() {

        StackPane root = new StackPane();
        root.getStyleClass().add("rat-overlay");
        root.setStyle("-fx-background-color: rgba(20,18,16,0.9);");
        root.setPickOnBounds(true);

        Group rat = rat();

        Label gotcha = new Label("Gotcha!");
        gotcha.setStyle("-fx-text-fill: #F0C23A; -fx-font-size: 54px; -fx-font-weight: bold;");
        gotcha.setOpacity(0);
        StackPane.setAlignment(gotcha, Pos.TOP_CENTER);
        StackPane.setMargin(gotcha, new Insets(70, 0, 0, 0));

        Label caption = new Label("On the hunt for anything worth a look…");
        caption.setStyle("-fx-text-fill: #E8DCCB; -fx-font-size: 16px;");
        StackPane.setAlignment(caption, Pos.BOTTOM_CENTER);
        StackPane.setMargin(caption, new Insets(0, 0, 54, 0));

        root.getChildren().addAll(rat, gotcha, caption);

        return new Parts(root, rat, gotcha);
    }

    private static Group rat() {

        Group rat = new Group();

        // tail, curling out behind
        QuadCurve tail = new QuadCurve(-40, 30, -92, 20, -78, -36);
        tail.setStroke(PINK);
        tail.setStrokeWidth(6);
        tail.setFill(Color.TRANSPARENT);

        Ellipse body = new Ellipse(0, 24, 44, 32);
        body.setFill(RAT);

        // dark detective suit over the body front
        Polygon vest = new Polygon(-22, 6, 22, 6, 14, 48, -14, 48);
        vest.setFill(SUIT);
        Polygon tie = new Polygon(0, 8, 6, 20, 0, 40, -6, 20);
        tie.setFill(Color.web("#7A1F22"));

        Circle head = new Circle(0, -16, 30, RAT);
        Circle earL = new Circle(-20, -40, 13, RAT_DARK);
        Circle earR = new Circle(20, -40, 13, RAT_DARK);
        Circle earLin = new Circle(-20, -40, 7, PINK);
        Circle earRin = new Circle(20, -40, 7, PINK);
        Circle eyeL = new Circle(-10, -20, 4, DARK);
        Circle eyeR = new Circle(10, -20, 4, DARK);
        Circle nose = new Circle(0, -4, 4, PINK);

        // a detective fedora
        Ellipse brim = new Ellipse(0, -44, 34, 9);
        brim.setFill(DARK);
        Rectangle dome = new Rectangle(-18, -66, 36, 24);
        dome.setFill(DARK);
        dome.setArcWidth(16);
        dome.setArcHeight(16);
        Rectangle band = new Rectangle(-18, -50, 36, 6);
        band.setFill(Color.web("#4A3A2A"));

        // a magnifying glass, held to the side
        Group glass = new Group();
        Circle ring = new Circle(46, 2, 15, Color.web("#BFE0F0", 0.45));
        ring.setStroke(Color.web("#3A2A1E"));
        ring.setStrokeWidth(5);
        Rectangle grip = new Rectangle(55, 12, 7, 26);
        grip.setFill(Color.web("#5E3A22"));
        grip.setArcWidth(6);
        grip.setArcHeight(6);
        grip.setRotate(-35);
        Circle glint = new Circle(41, -3, 4, GLASS);
        glass.getChildren().addAll(grip, ring, glint);

        rat.getChildren().addAll(tail, body, vest, tie, head, earL, earR, earLin, earRin,
                brim, dome, band, eyeL, eyeR, nose, glass);

        return rat;
    }

}
