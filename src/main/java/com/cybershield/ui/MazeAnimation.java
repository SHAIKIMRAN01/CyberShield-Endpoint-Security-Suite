package com.cybershield.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.RotateTransition;
import javafx.animation.SequentialTransition;
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
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * The flourish shown when the Encryption Center opens: a little maze of locked puzzles, which
 * then breaks apart and is thrown away — the puzzle is solved, the way is clear. Purely
 * decorative, drawn from shapes, skippable with a click.
 */
public final class MazeAnimation {

    private static final double CANVAS = 256;
    private static final String SEQ_KEY = "maze-seq";

    private static final Color WALL = Color.web("#9A5A2A");
    private static final Color WALL_EDGE = Color.web("#6E3F1C");
    private static final Color PIECE = Color.web("#C98A3C");
    private static final Color PIECE_EDGE = Color.web("#8A5A24");

    private MazeAnimation() {
    }

    /** Plays the maze-breaking flourish over {@code host} and removes it when done. */
    public static void playOver(StackPane host) {

        Parts parts = build();
        host.getChildren().add(parts.root);
        parts.root.setOnMouseClicked(e -> skip(host, parts.root));

        ParallelTransition scatter = new ParallelTransition();
        int i = 0;
        for (Node piece : parts.pieces) {
            double[] c = center(piece);
            double dx = c[0] - 128;
            double dy = c[1] - 128;
            double len = Math.max(1, Math.hypot(dx, dy));
            TranslateTransition fly = new TranslateTransition(Duration.millis(2200), piece);
            fly.setByX(dx / len * 240);
            fly.setByY(dy / len * 240);
            fly.setInterpolator(Interpolator.EASE_IN);
            RotateTransition spin = new RotateTransition(Duration.millis(2200), piece);
            spin.setByAngle((i % 2 == 0 ? 1 : -1) * (160 + i * 20));
            FadeTransition fade = new FadeTransition(Duration.millis(2200), piece);
            fade.setFromValue(1);
            fade.setToValue(0);
            fade.setDelay(Duration.millis(400));
            scatter.getChildren().addAll(fly, spin, fade);
            i++;
        }

        FadeTransition out = new FadeTransition(Duration.millis(500), parts.root);
        out.setFromValue(1);
        out.setToValue(0);
        out.setDelay(Duration.millis(200));

        SequentialTransition sequence = new SequentialTransition(scatter, out);
        sequence.setOnFinished(e -> host.getChildren().remove(parts.root));
        parts.root.getProperties().put(SEQ_KEY, sequence);
        sequence.play();
    }

    /** Adds the maze posed part-scattered (no animation) and returns it, for a screenshot or a test. */
    public static Node addStill(StackPane host) {
        Parts parts = build();
        int i = 0;
        for (Node piece : parts.pieces) {
            double[] c = center(piece);
            double dx = c[0] - 128;
            double dy = c[1] - 128;
            double len = Math.max(1, Math.hypot(dx, dy));
            piece.setTranslateX(dx / len * 26);
            piece.setTranslateY(dy / len * 26);
            piece.setRotate((i % 2 == 0 ? 1 : -1) * 14);
            i++;
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

    private static double[] center(Node node) {
        return new double[] {
                node.getLayoutBounds().getMinX() + node.getLayoutBounds().getWidth() / 2,
                node.getLayoutBounds().getMinY() + node.getLayoutBounds().getHeight() / 2
        };
    }

    private record Parts(StackPane root, List<Node> pieces) {
    }

    private static Parts build() {

        StackPane root = new StackPane();
        root.getStyleClass().add("maze-overlay");
        root.setStyle("-fx-background-color: rgba(43,29,21,0.92);");
        root.setPickOnBounds(true);

        Pane stage = new Pane();
        stage.setMinSize(CANVAS, CANVAS);
        stage.setPrefSize(CANVAS, CANVAS);
        stage.setMaxSize(CANVAS, CANVAS);
        stage.setScaleX(1.3);
        stage.setScaleY(1.3);

        List<Node> pieces = new ArrayList<>();

        // the maze walls: a border with a gap, and a couple of inner walls
        double[][] walls = {
                {56, 56, 144, 12}, {56, 188, 70, 12}, {150, 188, 50, 12},  // top, bottom (with a gap)
                {56, 56, 12, 144}, {188, 56, 12, 100},                     // left, right (with a gap)
                {92, 92, 12, 76}, {128, 92, 60, 12}, {128, 140, 12, 48}    // inner walls
        };
        for (double[] w : walls) {
            Rectangle wall = new Rectangle(w[0], w[1], w[2], w[3]);
            wall.setFill(WALL);
            wall.setStroke(WALL_EDGE);
            wall.setStrokeWidth(2);
            wall.setArcWidth(5);
            wall.setArcHeight(5);
            pieces.add(wall);
        }

        // a few jigsaw puzzle pieces sitting in the maze
        double[][] spots = {{108, 108}, {150, 118}, {112, 158}};
        for (double[] s : spots) {
            pieces.add(puzzlePiece(s[0], s[1]));
        }

        stage.getChildren().addAll(pieces);

        Label caption = new Label("Puzzle solved — the way is clear.");
        caption.setStyle("-fx-text-fill: #F6EEE4; -fx-font-size: 16px;");
        StackPane.setAlignment(caption, Pos.BOTTOM_CENTER);
        StackPane.setMargin(caption, new Insets(0, 0, 54, 0));

        root.getChildren().addAll(stage, caption);

        return new Parts(root, pieces);
    }

    private static Group puzzlePiece(double x, double y) {
        Group piece = new Group();
        Rectangle body = new Rectangle(x, y, 24, 24);
        body.setFill(PIECE);
        body.setStroke(PIECE_EDGE);
        body.setStrokeWidth(2);
        body.setArcWidth(5);
        body.setArcHeight(5);
        Circle knob = new Circle(x + 24, y + 12, 6, PIECE);
        knob.setStroke(PIECE_EDGE);
        knob.setStrokeWidth(2);
        piece.getChildren().addAll(body, knob);
        return piece;
    }

}
