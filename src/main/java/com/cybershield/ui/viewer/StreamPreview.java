package com.cybershield.ui.viewer;

import com.cybershield.vault.AutoLock;

import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.geometry.Pos;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A video or song played by {@link StreamPlayer}: the picture (turned the way the phone
 * recorded it) or a note for sound only, and a bar with play, seek, time and mute.
 */
final class StreamPreview implements FilePreview {

    private final StreamPlayer player;

    private final StackPane view = new StackPane();

    private final ControlBar controlBar;

    private final BooleanSupplier playing;

    private boolean disposed;

    StreamPreview(StreamPlayer player) {

        this.player = player;

        view.setMinSize(0, 0);

        view.getChildren().add(content(view));

        controlBar = new ControlBar();

        player.startRendering();

        // A video doesn't lock the vault halfway through.
        playing = player::isPlaying;

        AutoLock.getInstance().keepOpenWhile(playing);

    }

    private Node content(StackPane parent) {

        if (!player.hasVideo()) {

            Label symbol = new Label("♪");
            symbol.setStyle("-fx-font-size: 64px; -fx-text-fill: #8A6145;");

            Label caption = new Label("Sound only");
            caption.getStyleClass().add("hint");

            VBox box = new VBox(6, symbol, caption);
            box.setAlignment(Pos.CENTER);

            return box;

        }

        ImageView picture = new ImageView(player.image());

        picture.setId("videoView");
        picture.setPreserveRatio(true);
        picture.setSmooth(true);

        double turn = player.rotation();

        picture.setRotate(turn);

        // Turned a quarter, the picture's width runs along the space's height.
        boolean sideways = turn == 90 || turn == 270;

        picture.fitWidthProperty().bind(sideways ? parent.heightProperty() : parent.widthProperty());
        picture.fitHeightProperty().bind(sideways ? parent.widthProperty() : parent.heightProperty());

        return picture;

    }

    /** For the tests. */
    StreamPlayer player() {
        return player;
    }

    @Override
    public Node view() {
        return view;
    }

    @Override
    public List<Node> controls() {
        return controlBar.nodes;
    }

    /** A second picture and set of controls on the same player, for the full-screen window. */
    @Override
    public FilePreview fullScreenCopy() {

        StackPane copyView = new StackPane();

        copyView.setMinSize(0, 0);

        copyView.getChildren().add(content(copyView));

        ControlBar copyBar = new ControlBar();

        return new FilePreview() {

            @Override
            public Node view() {
                return copyView;
            }

            @Override
            public List<Node> controls() {
                return copyBar.nodes;
            }

            @Override
            public void dispose() {
                copyBar.detach();
            }

        };

    }

    @Override
    public void dispose() {

        if (disposed) {
            return;
        }

        disposed = true;

        AutoLock.getInstance().stopKeepingOpen(playing);

        controlBar.detach();

        player.close();

    }

    /** Play/pause, a seek bar, the time and mute, tied to the player. */
    private final class ControlBar {

        final Button playPause = new Button("Play");

        final Slider timeline = new Slider(0, 1, 0);

        final Label time = new Label("0:00 / 0:00");

        final ToggleButton mute = new ToggleButton("Mute");

        final List<Node> nodes;

        private final ChangeListener<Boolean> playingListener =
                (observable, before, now) -> playPause.setText(now ? "Pause" : "Play");

        private final InvalidationListener positionListener = observable -> showTime();

        private final ChangeListener<String> problemListener = (observable, before, message) -> {
            if (message != null) {
                time.setText(message);
            }
        };

        ControlBar() {

            playPause.setId("playButton");

            playPause.setOnAction(event -> {
                if (player.isPlaying()) {
                    player.pause();
                } else {
                    player.play();
                }
            });

            HBox.setHgrow(timeline, Priority.ALWAYS);
            timeline.setMinWidth(120);
            timeline.setDisable(player.duration() <= 0);

            // Seek when the user lets go of the slider or clicks on it.
            timeline.valueChangingProperty().addListener((observable, before, changing) -> {
                if (!changing) {
                    seekToSlider();
                }
            });
            timeline.setOnMouseReleased(event -> seekToSlider());

            mute.setDisable(!player.hasSound());
            mute.selectedProperty().addListener((observable, before, muted) -> player.setMuted(muted));

            player.playingProperty().addListener(playingListener);
            player.positionProperty().addListener(positionListener);
            player.problemProperty().addListener(problemListener);

            playPause.setText(player.isPlaying() ? "Pause" : "Play");

            List<Node> all = new ArrayList<>(List.of(playPause, timeline, time, mute));

            if (player.note() != null) {
                Label noteLabel = new Label(player.note());
                noteLabel.getStyleClass().add("hint");
                all.add(noteLabel);
            }

            nodes = List.copyOf(all);

            showTime();

        }

        void detach() {

            player.playingProperty().removeListener(playingListener);
            player.positionProperty().removeListener(positionListener);
            player.problemProperty().removeListener(problemListener);

        }

        private void seekToSlider() {

            if (player.duration() > 0) {
                player.seek(Math.round(player.duration() * timeline.getValue()));
            }

        }

        private void showTime() {

            long current = player.positionProperty().get();

            long total = player.duration();

            time.setText(Controls.time(Duration.millis(current / 1000.0))
                    + " / " + (total > 0 ? Controls.time(Duration.millis(total / 1000.0)) : "--:--"));

            if (!timeline.isValueChanging() && total > 0) {
                timeline.setValue((double) current / total);
            }

        }

    }

}
