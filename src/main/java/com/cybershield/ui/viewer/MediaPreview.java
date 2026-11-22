package com.cybershield.ui.viewer;

import com.cybershield.vault.AutoLock;
import com.sun.media.jfxmedia.locator.LocatorCache;

import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Plays audio or video straight from memory.
 *
 * <p>JavaFX's player normally reads from a file or the network, and for
 * network sources it keeps a buffer that can spill to a temporary file.
 * Instead, the decrypted bytes are registered in JavaFX's internal in-memory
 * media cache under a random address that nothing listens on, so the player
 * reads them from memory with full seeking and nothing touches the disk.
 *
 * <p>LocatorCache is internal to JavaFX. When JavaFX runs as modules (as with
 * mvn javafx:run) the JVM needs
 * {@code --add-exports javafx.media/com.sun.media.jfxmedia.locator=ALL-UNNAMED},
 * which pom.xml passes.
 */
public final class MediaPreview implements FilePreview {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final byte[] ZEROS = new byte[64 * 1024];

    private final ByteBuffer buffer;

    // Holds JavaFX's cache entry for as long as the media is open.
    private Object cacheEntry;

    private final MediaPlayer player;

    private final boolean video;

    // MP4 and M4A files usually carry AAC sound, which some Windows PCs can't decode for JavaFX.
    private final boolean mayUseAac;

    private final StackPane view = new StackPane();

    private final ControlBar controlBar;

    private final BooleanSupplier playing;

    private boolean disposed;

    /**
     * Wraps decrypted media bytes in a player. Takes ownership of the buffer,
     * which is wiped on dispose.
     */
    public static MediaPreview open(ByteBuffer buffer, String extension) throws IOException {

        String type = PreviewKind.mediaType(extension);

        if (type == null) {
            throw new IOException("This kind of media can't be played here.");
        }

        byte[] token = new byte[16];
        RANDOM.nextBytes(token);

        // Port 9 is the "discard" port: nothing answers there, so the media can only come from the cache.
        URI address = URI.create("http://127.0.0.1:9/" + HexFormat.of().formatHex(token) + "/media." + extension);

        Object entry;

        try {

            entry = LocatorCache.locatorCache().registerURICache(address, buffer, type);

        } catch (IllegalAccessError | NoClassDefFoundError e) {

            throw new IOException("Private playback isn't available when CyberShield is started this way. "
                    + "Start it with mvn javafx:run or from your IDE.", e);

        }

        try {

            MediaPlayer player = new MediaPlayer(new Media(address.toString()));

            return new MediaPreview(buffer, entry, player, PreviewKind.isVideo(extension), usesAac(extension));

        } catch (RuntimeException e) {

            throw new IOException("This file can't be played: " + e.getMessage(), e);

        }

    }

    private MediaPreview(ByteBuffer buffer, Object cacheEntry, MediaPlayer player, boolean video, boolean mayUseAac) {

        this.buffer = buffer;
        this.cacheEntry = cacheEntry;
        this.player = player;
        this.video = video;
        this.mayUseAac = mayUseAac;

        view.setMinSize(0, 0);
        view.getChildren().add(mediaViewFor(view));

        if (!video) {
            Label note = new Label("♪");
            note.getStyleClass().add("media-note");
            view.getChildren().add(note);
        }

        // JavaFX leaves the status at PLAYING after the last frame, and ignores pause()
        // there. Stopping rewinds to the start and shows Play again.
        player.setOnEndOfMedia(player::stop);

        controlBar = new ControlBar();

        // Watching or listening counts as activity, so auto-lock doesn't stop it halfway.
        // A clip sitting at its end doesn't count, even if JavaFX still says PLAYING.
        playing = () -> player.getStatus() == MediaPlayer.Status.PLAYING && !atEnd();

        AutoLock.getInstance().keepOpenWhile(playing);

    }

    /** Wipes decrypted media that was never turned into a preview. */
    public static void wipe(ByteBuffer buffer) {

        buffer.clear();

        while (buffer.hasRemaining()) {
            buffer.put(ZEROS, 0, Math.min(ZEROS.length, buffer.remaining()));
        }

        buffer.clear();

    }

    /** Extensions whose files normally carry AAC sound. */
    static boolean usesAac(String extension) {

        return switch (extension == null ? "" : extension.toLowerCase(java.util.Locale.ROOT)) {
            case "mp4", "m4v", "m4a", "aac" -> true;
            default -> false;
        };

    }

    /**
     * What to tell the user when playback fails. On some Windows PCs JavaFX can't decode
     * AAC sound (its DirectShow decoder fails), which stops most MP4 videos and M4A songs,
     * even though the picture would decode. Saying so beats a bare error code.
     */
    static String problem(MediaException error, boolean mayUseAac) {

        String message = error == null ? "" : String.valueOf(error.getMessage());

        if (mayUseAac && message.contains("MEDIA_INVALID")) {
            return "Can't play it here: this PC can't decode the AAC sound that most MP4 videos and M4A songs use. "
                    + "Use Export a copy to play it in another app.";
        }

        return "Can't play: " + (message.isBlank() ? "unknown error" : message);

    }

    public MediaPlayer player() {
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

        MediaView copyMedia = mediaViewFor(copyView);

        copyView.getChildren().add(copyMedia);

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
                copyMedia.setMediaPlayer(null);
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

        player.dispose();

        cacheEntry = null;

        wipe(buffer);

    }

    private boolean atEnd() {

        Duration total = player.getTotalDuration();

        return total != null && !total.isUnknown() && !total.isIndefinite()
                && player.getCurrentTime().greaterThanOrEqualTo(total.subtract(Duration.millis(250)));

    }

    private MediaView mediaViewFor(StackPane parent) {

        MediaView mediaView = new MediaView(player);

        mediaView.setId("mediaView");
        mediaView.setPreserveRatio(true);

        if (video) {
            mediaView.fitWidthProperty().bind(parent.widthProperty());
            mediaView.fitHeightProperty().bind(parent.heightProperty());
        } else {
            mediaView.setVisible(false);
            mediaView.setManaged(false);
        }

        return mediaView;

    }

    /** Play/pause, a seek bar, the time and mute, all tied to the player. */
    private final class ControlBar {

        final Button playPause = new Button("Play");

        final Slider timeline = new Slider(0, 1, 0);

        final Label time = new Label("0:00 / 0:00");

        final ToggleButton mute = new ToggleButton("Mute");

        final List<Node> nodes;

        private final ChangeListener<MediaPlayer.Status> statusListener =
                (observable, before, status) -> playPause.setText(status == MediaPlayer.Status.PLAYING ? "Pause" : "Play");

        private final InvalidationListener timeListener = observable -> showTime();

        ControlBar() {

            playPause.setId("playButton");

            playPause.setOnAction(event -> {
                if (player.getStatus() == MediaPlayer.Status.PLAYING) {
                    player.pause();
                } else {
                    if (atEnd()) {
                        player.seek(Duration.ZERO);
                    }
                    player.play();
                }
            });

            HBox.setHgrow(timeline, Priority.ALWAYS);
            timeline.setMinWidth(120);

            // Seek when the user lets go of the slider or clicks on it.
            timeline.valueChangingProperty().addListener((observable, before, changing) -> {
                if (!changing) {
                    seekToSlider();
                }
            });
            timeline.setOnMouseReleased(event -> seekToSlider());

            mute.selectedProperty().bindBidirectional(player.muteProperty());

            player.statusProperty().addListener(statusListener);
            player.currentTimeProperty().addListener(timeListener);
            player.totalDurationProperty().addListener(timeListener);

            player.setOnError(() -> time.setText(problem(player.getError(), mayUseAac)));

            nodes = List.of(playPause, timeline, time, mute);

            showTime();

        }

        void detach() {

            player.statusProperty().removeListener(statusListener);
            player.currentTimeProperty().removeListener(timeListener);
            player.totalDurationProperty().removeListener(timeListener);
            mute.selectedProperty().unbindBidirectional(player.muteProperty());

        }

        private void seekToSlider() {

            Duration total = player.getTotalDuration();

            if (total != null && !total.isUnknown() && !total.isIndefinite()) {
                player.seek(total.multiply(timeline.getValue()));
            }

        }

        private void showTime() {

            Duration current = player.getCurrentTime();

            Duration total = player.getTotalDuration();

            time.setText(Controls.time(current) + " / " + Controls.time(total));

            if (!timeline.isValueChanging() && total != null && total.toMillis() > 0 && !total.isIndefinite()) {
                timeline.setValue(current.toMillis() / total.toMillis());
            }

        }

    }

}
