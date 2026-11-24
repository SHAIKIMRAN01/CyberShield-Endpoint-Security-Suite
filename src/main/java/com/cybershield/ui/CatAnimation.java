package com.cybershield.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.QuadCurve;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * The flourish shown when Privacy Tools opens: a cat strolls in slowly, pausing to look around,
 * reaches a bowl of milk, takes a few sips, then wanders off — and the tools come into view.
 * Unhurried on purpose (about fifteen seconds), drawn from shapes, and skippable with a click.
 */
public final class CatAnimation {

    private static final String SEQ_KEY = "cat-seq";

    private static final Color CAT = Color.web("#D98A3C");
    private static final Color CAT_DARK = Color.web("#B06A2F");
    private static final Color CAT_LIGHT = Color.web("#F0CF9E");
    private static final Color BOWL = Color.web("#6F4428");
    private static final Color MILK = Color.web("#FBF7F0");

    private CatAnimation() {
    }

    /** Plays the cat flourish over {@code host} and removes it when done. */
    public static void playOver(StackPane host) {

        Parts parts = build();
        host.getChildren().add(parts.root);
        parts.root.setOnMouseClicked(e -> skip(host, parts.root));

        Group cat = parts.cat;
        cat.setTranslateX(-520);

        FadeTransition bowlIn = new FadeTransition(Duration.millis(700), parts.bowl);
        bowlIn.setFromValue(0);
        bowlIn.setToValue(1);

        TranslateTransition walkIn = slide(cat, -120, 3200);        // stroll in
        TranslateTransition approach = slide(cat, -36, 2200);       // up to the bowl
        TranslateTransition leave = slide(cat, 540, 3200);          // wander off

        SequentialTransition sips = new SequentialTransition(dip(cat), dip(cat), dip(cat));

        FadeTransition catFade = new FadeTransition(Duration.millis(1400), cat);
        catFade.setDelay(Duration.millis(1600));
        catFade.setFromValue(1);
        catFade.setToValue(0);

        FadeTransition out = new FadeTransition(Duration.millis(700), parts.root);
        out.setFromValue(1);
        out.setToValue(0);

        SequentialTransition sequence = new SequentialTransition(
                walkIn, pause(2000),          // stop and look
                approach, pause(1400),
                sips, pause(900),
                new ParallelTransition(leave, catFade), out);
        sequence.setOnFinished(e -> host.getChildren().remove(parts.root));
        parts.root.getProperties().put(SEQ_KEY, sequence);
        bowlIn.play();
        sequence.play();
    }

    /** Adds the cat posed at the bowl (no animation) and returns it, for a screenshot or a test. */
    public static Node addStill(StackPane host) {
        Parts parts = build();
        parts.bowl.setOpacity(1);
        parts.cat.setTranslateX(-36);
        host.getChildren().add(parts.root);
        return parts.root;
    }

    private static TranslateTransition slide(Node node, double toX, double ms) {
        TranslateTransition t = new TranslateTransition(Duration.millis(ms), node);
        t.setToX(toX);
        t.setInterpolator(Interpolator.EASE_BOTH);
        return t;
    }

    private static TranslateTransition dip(Node node) {
        TranslateTransition t = new TranslateTransition(Duration.millis(450), node);
        t.setByY(12);
        t.setAutoReverse(true);
        t.setCycleCount(2);
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

    private record Parts(StackPane root, Group cat, Group bowl) {
    }

    private static Parts build() {

        StackPane root = new StackPane();
        root.getStyleClass().add("cat-overlay");
        root.setStyle("-fx-background-color: rgba(43,29,21,0.9);");
        root.setPickOnBounds(true);

        Group bowl = bowl();
        StackPane.setAlignment(bowl, Pos.BOTTOM_CENTER);
        StackPane.setMargin(bowl, new Insets(0, 0, 150, 0));
        bowl.setOpacity(0);

        Group cat = cat();
        StackPane.setAlignment(cat, Pos.BOTTOM_CENTER);
        StackPane.setMargin(cat, new Insets(0, 0, 168, 0));

        Label caption = new Label("A curious visitor stops by…");
        caption.setStyle("-fx-text-fill: #F6EEE4; -fx-font-size: 16px;");
        StackPane.setAlignment(caption, Pos.BOTTOM_CENTER);
        StackPane.setMargin(caption, new Insets(0, 0, 54, 0));

        root.getChildren().addAll(bowl, cat, caption);

        return new Parts(root, cat, bowl);
    }

    private static Group bowl() {
        Group bowl = new Group();
        Ellipse milk = new Ellipse(0, 0, 34, 10);
        milk.setFill(MILK);
        Arc dish = new Arc(0, 0, 40, 20, 180, 180);
        dish.setType(ArcType.ROUND);
        dish.setFill(BOWL);
        bowl.getChildren().addAll(dish, milk);
        return bowl;
    }

    private static Group cat() {

        Group cat = new Group();

        QuadCurve tail = new QuadCurve(-44, -2, -96, -18, -80, -62);
        tail.setStroke(CAT);
        tail.setStrokeWidth(10);
        tail.setFill(Color.TRANSPARENT);

        Ellipse body = new Ellipse(-6, -14, 46, 26);
        body.setFill(CAT);

        for (double x : new double[] {-34, 20}) {
            Rectangle leg = new Rectangle(x, 2, 11, 18);
            leg.setFill(CAT_DARK);
            leg.setArcWidth(8);
            leg.setArcHeight(8);
            cat.getChildren().add(leg);
        }

        Circle head = new Circle(38, -24, 22, CAT);
        Polygon earL = new Polygon(24, -44, 34, -58, 40, -42);
        Polygon earR = new Polygon(40, -46, 50, -60, 54, -42);
        earL.setFill(CAT_DARK);
        earR.setFill(CAT_DARK);
        Circle eyeL = new Circle(32, -26, 3.5, Color.web("#2B1D15"));
        Circle eyeR = new Circle(46, -26, 3.5, Color.web("#2B1D15"));
        Circle nose = new Circle(39, -18, 3, Color.web("#7A1F22"));
        Ellipse cheek = new Ellipse(39, -12, 10, 6);
        cheek.setFill(CAT_LIGHT);

        cat.getChildren().addAll(tail, body, head, earL, earR, cheek, eyeL, eyeR, nose);
        return cat;
    }

}
