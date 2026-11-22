package com.cybershield.ui;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PathTransition;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.PerspectiveCamera;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.CubicCurveTo;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Plays before the sign-in screen: the wolf guards its chest, a golden key flies into the
 * lock and turns, the wolf steps back, the lid swings open and bright light pours out
 * until the screen turns the off-white of the app. About three and a half seconds; a
 * click or any key skips it.
 */
public final class OpeningAnimation {

    public static final double WIDTH = 960;

    public static final double HEIGHT = 600;

    // Logo canvas units to pixels.
    private static final double SCALE = 1.45;

    private static final Color PAPER = Color.web("#FAF6F0");

    private static final Color GOLD = Color.web("#E2A93B");

    private static final Color GOLD_LIGHT = Color.web("#F6D27A");

    private final Pane root = new Pane();

    private final ParallelTransition animation = new ParallelTransition();

    private final double left = (WIDTH - Logo.CANVAS * SCALE) / 2;

    private final double top = (HEIGHT - Logo.CANVAS * SCALE) / 2 - 34;

    private Runnable whenDone = () -> { };

    private boolean finished;

    public OpeningAnimation() {

        build();

    }

    /** Shows the animation in stage, then the scene next makes. A click or any key skips to the end. */
    public static void playIn(Stage stage, Supplier<Scene> next) {

        OpeningAnimation intro = new OpeningAnimation();

        // No depth buffer: shapes draw in order, so the lid stays in front of the background as it swings back.
        Scene scene = new Scene(intro.root(), WIDTH, HEIGHT, false, SceneAntialiasing.BALANCED);

        scene.setFill(Color.web("#1C120B"));

        // Lets the lid swing open in depth.
        scene.setCamera(new PerspectiveCamera());

        scene.setOnMouseClicked(event -> intro.skip());
        scene.setOnKeyPressed(event -> intro.skip());

        stage.setScene(scene);

        stage.show();

        intro.play(() -> {

            Scene after = next.get();

            stage.setScene(after);

            FadeTransition in = new FadeTransition(Duration.millis(260), after.getRoot());
            in.setFromValue(0.35);
            in.setToValue(1);
            in.play();

        });

    }

    public Parent root() {
        return root;
    }

    /** The whole animation, for tests and for drawing single moments of it. */
    public Animation animation() {
        return animation;
    }

    public void play(Runnable whenDone) {

        this.whenDone = whenDone;

        animation.setOnFinished(event -> finish());

        animation.play();

    }

    public void skip() {

        animation.stop();

        finish();

    }

    private void finish() {

        if (!finished) {
            finished = true;
            whenDone.run();
        }

    }

    // ------------------------------------------------------------- the scene

    private double x(double canvasX) {
        return left + canvasX * SCALE;
    }

    private double y(double canvasY) {
        return top + canvasY * SCALE;
    }

    private void build() {

        root.setPrefSize(WIDTH, HEIGHT);

        Rectangle background = new Rectangle(WIDTH, HEIGHT, new RadialGradient(0, 0, 0.5, 0.42, 0.8, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#4A3020")), new Stop(1, Color.web("#1C120B"))));

        // Where the light comes from: the chest's opening.
        double lightX = x(128);
        double lightY = y(148);

        Group rays = rays(lightX, lightY);
        rays.setOpacity(0);

        Ellipse shadow = new Ellipse(x(128), y(216), 125, 13);
        shadow.setFill(Color.rgb(0, 0, 0, 0.35));

        Group wolf = Logo.group(concat(Logo.WOLF, Logo.PAWS));

        Group body = Logo.group(Logo.CHEST_BODY);

        Group inside = Logo.group(List.of(
                new Logo.Part("inside", "M54 132 H202 V152 H54 Z", Color.web("#2A1A0F"), Color.web("#2B1A10"), 4),
                new Logo.Part("coins", "M70 150 Q90 136 110 148 Q128 134 148 148 Q168 136 188 150 Z", GOLD_LIGHT, null, 0)));

        Group lid = Logo.group(Logo.CHEST_LID);

        Rotate hinge = new Rotate(0, 128, 112, 0, Rotate.X_AXIS);

        lid.getTransforms().add(hinge);

        Group lock = Logo.group(Logo.LOCK);

        Group chest = new Group(wolf, body, inside, lid, lock);

        chest.getTransforms().add(new Scale(SCALE, SCALE, 0, 0));

        Group placed = new Group(chest);

        placed.setLayoutX(left);
        placed.setLayoutY(top);
        placed.setOpacity(0);

        Circle glow = new Circle(lightX, lightY, 70, new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#FFFDF5")), new Stop(0.35, Color.web("#FBE7A6", 0.95)), new Stop(1, Color.web("#F6D27A", 0))));
        glow.setOpacity(0);
        glow.setScaleX(0.3);
        glow.setScaleY(0.3);

        Text name = new Text("CyberShield");
        name.setFont(Font.font("Segoe UI", FontWeight.BOLD, 30));
        name.setFill(Color.web("#F6EEE4"));

        Text tagline = new Text("Endpoint Security Suite");
        tagline.setFont(Font.font("Segoe UI", 13));
        tagline.setFill(Color.web("#CDB8A3"));

        for (Text text : List.of(name, tagline)) {
            text.setTextAlignment(TextAlignment.CENTER);
            text.setOpacity(0);
        }

        name.setLayoutX(WIDTH / 2 - name.getLayoutBounds().getWidth() / 2);
        name.setLayoutY(y(246));
        tagline.setLayoutX(WIDTH / 2 - tagline.getLayoutBounds().getWidth() / 2);
        tagline.setLayoutY(y(246) + 24);

        // The flight, the turn and the fading move the holder; the little hop moves the key in it.
        Group keyShape = key();
        Group key = new Group(keyShape);
        key.setOpacity(0);
        key.setRotate(-35);

        // Where the key starts, and where its tip sits in the keyhole.
        double startX = x(-70);
        double startY = y(180);
        double finalScale = 0.62;
        double endX = x(128) - 66 * finalScale + 6;
        double endY = y(170);

        // A path moves a node by its centre from where it is laid out (here the origin), so start it there too.
        double centerX = key.getLayoutBounds().getCenterX();
        double centerY = key.getLayoutBounds().getCenterY();
        key.setTranslateX(startX - centerX);
        key.setTranslateY(startY - centerY);

        Rectangle flash = new Rectangle(WIDTH, HEIGHT, PAPER);
        flash.setOpacity(0);
        flash.setMouseTransparent(true);

        root.getChildren().addAll(background, rays, shadow, placed, glow, name, tagline, key, flash);

        // 1. The wolf and its chest appear, then the name.
        animation.getChildren().addAll(
                fade(placed, 0, 1, 0, 500),
                scale(placed, 0.92, 1, 0, 600, Interpolator.EASE_OUT),
                fade(name, 0, 1, 450, 500),
                fade(tagline, 0, 0.9, 600, 500));

        // 2. The key appears with a little hop.
        TranslateTransition hop = new TranslateTransition(Duration.millis(380), keyShape);
        hop.setDelay(Duration.millis(420));
        hop.setFromY(14);
        hop.setToY(0);
        hop.setInterpolator(Interpolator.EASE_OUT);

        animation.getChildren().addAll(fade(key, 0, 1, 420, 320), hop);

        // 3. It flies into the lock, levelling out and shrinking to fit.
        Path flight = new Path(new MoveTo(startX, startY),
                new CubicCurveTo(startX + 120, startY - 170, endX - 190, endY - 120, endX, endY));

        PathTransition fly = new PathTransition(Duration.millis(1000), flight, key);
        fly.setDelay(Duration.millis(900));
        fly.setInterpolator(Interpolator.EASE_BOTH);

        RotateTransition level = new RotateTransition(Duration.millis(1000), key);
        level.setDelay(Duration.millis(900));
        level.setFromAngle(-35);
        level.setToAngle(0);
        level.setInterpolator(Interpolator.EASE_BOTH);

        animation.getChildren().addAll(fly, level, scale(key, 1, finalScale, 900, 1000, Interpolator.EASE_BOTH));

        // 4. It turns (seen side-on, it narrows and widens) and the lock clicks.
        ScaleTransition turn = new ScaleTransition(Duration.millis(170), key);
        turn.setDelay(Duration.millis(1950));
        turn.setFromY(finalScale);
        turn.setToY(finalScale * 0.15);
        turn.setAutoReverse(true);
        turn.setCycleCount(2);

        TranslateTransition click = new TranslateTransition(Duration.millis(45), lock);
        click.setDelay(Duration.millis(2160));
        click.setByX(2.2);
        click.setAutoReverse(true);
        click.setCycleCount(4);

        animation.getChildren().addAll(turn, click);

        // 5. The wolf steps back and the key goes as the lid swings open.
        TranslateTransition stepBack = new TranslateTransition(Duration.millis(450), wolf);
        stepBack.setDelay(Duration.millis(2250));
        stepBack.setByY(-26);
        stepBack.setInterpolator(Interpolator.EASE_IN);

        Timeline open = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(hinge.angleProperty(), 0)),
                new KeyFrame(Duration.millis(620), new KeyValue(hinge.angleProperty(), 108, Interpolator.EASE_IN)));
        open.setDelay(Duration.millis(2380));

        animation.getChildren().addAll(stepBack, fade(wolf, 1, 0, 2250, 420), fade(key, 1, 0, 2350, 200), open);

        // 6. Light pours out and fills the screen with the app's off-white.
        RotateTransition spin = new RotateTransition(Duration.millis(1300), rays);
        spin.setDelay(Duration.millis(2550));
        spin.setByAngle(32);

        animation.getChildren().addAll(
                fade(rays, 0, 0.9, 2550, 420),
                scale(rays, 0.4, 2.3, 2550, 1000, Interpolator.EASE_OUT),
                spin,
                fade(glow, 0, 1, 2620, 320),
                scale(glow, 0.3, 9.5, 2620, 900, Interpolator.EASE_IN),
                fade(flash, 0, 1, 3050, 450));

    }

    /** A golden key pointing right, about 140 × 44, centred on its own origin. */
    private static Group key() {

        SVGPath shape = new SVGPath();

        // The bow (a ring), the shaft and two teeth; the hole is cut out by the even-odd rule.
        shape.setContent("M-52 -20 A20 20 0 1 1 -52.01 20 A20 20 0 1 1 -52 -20 Z "
                + "M-52 -8 A8 8 0 1 0 -51.99 8 A8 8 0 1 0 -52 -8 Z "
                + "M-33 -5 H66 V5 H-33 Z "
                + "M44 5 H58 V20 H44 Z "
                + "M26 5 H36 V14 H26 Z");
        shape.setFillRule(FillRule.EVEN_ODD);
        shape.setFill(GOLD);
        shape.setStroke(Color.web("#2B1A10"));
        shape.setStrokeWidth(3);
        shape.setStrokeLineJoin(StrokeLineJoin.ROUND);

        SVGPath shine = new SVGPath();
        shine.setContent("M-28 -2.5 H60");
        shine.setStroke(GOLD_LIGHT);
        shine.setStrokeWidth(2);

        Circle sparkle = new Circle(-60, -14, 3, Color.web("#FFF6D8"));

        return new Group(shape, shine, sparkle);

    }

    /** Thin golden beams around (x, y). */
    private static Group rays(double x, double y) {

        Group rays = new Group();

        int count = 14;

        double length = 520;

        for (int i = 0; i < count; i++) {

            double angle = Math.toRadians(i * 360.0 / count);

            double spread = Math.toRadians(i % 2 == 0 ? 4.5 : 2.5);

            Polygon beam = new Polygon(
                    0, 0,
                    length * Math.cos(angle - spread), length * Math.sin(angle - spread),
                    length * Math.cos(angle + spread), length * Math.sin(angle + spread));

            beam.setFill(Color.web("#F6D27A", i % 2 == 0 ? 0.55 : 0.35));

            rays.getChildren().add(beam);

        }

        rays.setLayoutX(x);
        rays.setLayoutY(y);

        return rays;

    }

    private static FadeTransition fade(Node node, double from, double to, double delayMillis, double millis) {

        FadeTransition fade = new FadeTransition(Duration.millis(millis), node);

        fade.setDelay(Duration.millis(delayMillis));
        fade.setFromValue(from);
        fade.setToValue(to);

        return fade;

    }

    private static ScaleTransition scale(Node node, double from, double to, double delayMillis, double millis, Interpolator interpolator) {

        ScaleTransition scale = new ScaleTransition(Duration.millis(millis), node);

        scale.setDelay(Duration.millis(delayMillis));
        scale.setFromX(from);
        scale.setFromY(from);
        scale.setToX(to);
        scale.setToY(to);
        scale.setInterpolator(interpolator);

        return scale;

    }

    private static List<Logo.Part> concat(List<Logo.Part> first, List<Logo.Part> second) {

        List<Logo.Part> all = new ArrayList<>(first);

        all.addAll(second);

        return all;

    }

}
