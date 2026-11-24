package com.cybershield.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
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
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Rotate;
import javafx.util.Duration;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The flourish shown while signing out: a hooded figure raises an axe and swings it down, as
 * if cutting the session short, then fades — and the sign-out goes through. Purely decorative;
 * the real sign-out happens when the flourish finishes, and a click skips straight to it.
 *
 * <p>Drawn from shapes, so it needs no image files. {@link #playOver} runs it and then calls
 * {@code onDone}; {@link #addStill} poses it mid-swing for a screenshot.
 */
public final class GrimReaperAnimation {

    private static final double CANVAS = 256;

    private static final Color CLOAK = Color.web("#15120E");
    private static final Color CLOAK_EDGE = Color.web("#2A2118");
    private static final Color EMBER = Color.web("#E68A33");
    private static final Color WOOD = Color.web("#6F4428");
    private static final Color STEEL = Color.web("#AFA9A0");
    private static final Color STEEL_EDGE = Color.web("#6E6860");

    private GrimReaperAnimation() {
    }

    /** Plays the sign-out flourish over {@code host}, then runs {@code onDone} (the real sign-out). */
    public static void playOver(StackPane host, Runnable onDone) {

        Scene scene = build();
        host.getChildren().add(scene.root);

        AtomicBoolean finished = new AtomicBoolean(false);
        Runnable finish = () -> {
            if (finished.compareAndSet(false, true)) {
                host.getChildren().remove(scene.root);
                if (onDone != null) {
                    onDone.run();
                }
            }
        };

        FadeTransition appear = new FadeTransition(Duration.millis(600), scene.figure);
        appear.setFromValue(0);
        appear.setToValue(1);

        Rotate swing = new Rotate(14, 176, 176);
        scene.axe.getTransforms().add(swing);
        Timeline chop = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(swing.angleProperty(), 14)),
                new KeyFrame(Duration.millis(950), new KeyValue(swing.angleProperty(), -62, Interpolator.EASE_OUT)),
                new KeyFrame(Duration.millis(1450), new KeyValue(swing.angleProperty(), 54, Interpolator.EASE_IN)));

        PauseTransition hold = new PauseTransition(Duration.millis(700));

        FadeTransition out = new FadeTransition(Duration.millis(700), scene.root);
        out.setFromValue(1);
        out.setToValue(0);

        SequentialTransition sequence = new SequentialTransition(new ParallelTransition(appear, chop), hold, out);
        sequence.setOnFinished(e -> finish.run());

        scene.root.setOnMouseClicked(e -> {
            sequence.stop();
            finish.run();
        });

        sequence.play();
    }

    /** Adds the flourish posed mid-swing (no animation) and returns it, for a screenshot or a test. */
    public static Node addStill(StackPane host) {
        Scene scene = build();
        scene.axe.getTransforms().add(new Rotate(-52, 176, 176));
        host.getChildren().add(scene.root);
        return scene.root;
    }

    private static final class Scene {
        final StackPane root;
        final Group figure;
        final Group axe;

        Scene(StackPane root, Group figure, Group axe) {
            this.root = root;
            this.figure = figure;
            this.axe = axe;
        }
    }

    private static Scene build() {

        StackPane root = new StackPane();
        root.getStyleClass().add("grim-reaper-overlay");
        root.setStyle("-fx-background-color: rgba(9,7,5,0.92);");
        root.setPickOnBounds(true);

        Pane stage = new Pane();
        stage.setMinSize(CANVAS, CANVAS);
        stage.setPrefSize(CANVAS, CANVAS);
        stage.setMaxSize(CANVAS, CANVAS);
        stage.setScaleX(1.35);
        stage.setScaleY(1.35);

        Group figure = new Group();

        // the cloak: a bell-shaped robe
        Polygon cloak = new Polygon(88, 96, 168, 96, 202, 216, 54, 216);
        cloak.setFill(CLOAK);
        cloak.setStroke(CLOAK_EDGE);
        cloak.setStrokeWidth(3);

        // the hood over the head
        SVGPath hood = new SVGPath();
        hood.setContent("M90 100 Q86 40 128 32 Q170 40 166 100 Q150 86 128 86 Q106 86 90 100 Z");
        hood.setFill(CLOAK);
        hood.setStroke(CLOAK_EDGE);
        hood.setStrokeWidth(3);

        // the dark face void, with two embers for eyes
        Ellipse face = new Ellipse(128, 80, 26, 30);
        face.setFill(Color.web("#070605"));
        Circle eyeL = new Circle(118, 80, 5, EMBER);
        Circle eyeR = new Circle(138, 80, 5, EMBER);

        figure.getChildren().addAll(cloak, hood, face, eyeL, eyeR);

        // the axe: a wooden handle and a steel head, held to the figure's side
        Group axe = new Group();
        Rectangle handle = new Rectangle(170, 58, 9, 124);
        handle.setArcWidth(8);
        handle.setArcHeight(8);
        handle.setFill(WOOD);
        SVGPath blade = new SVGPath();
        blade.setContent("M178 58 L214 46 Q228 72 210 96 L178 88 Z");
        blade.setFill(STEEL);
        blade.setStroke(STEEL_EDGE);
        blade.setStrokeWidth(2);
        axe.getChildren().addAll(handle, blade);

        stage.getChildren().addAll(figure, axe);

        Label caption = new Label("Signing out…");
        caption.setStyle("-fx-text-fill: #E8DCCB; -fx-font-size: 16px;");
        StackPane.setAlignment(caption, Pos.BOTTOM_CENTER);
        StackPane.setMargin(caption, new Insets(0, 0, 54, 0));

        root.getChildren().addAll(stage, caption);

        return new Scene(root, figure, axe);
    }

}
