package com.cybershield.ui.viewer;

import com.cybershield.vault.AutoLock;
import com.sun.media.jfxmedia.locator.LocatorCache;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
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
import java.io.InputStream;
import java.lang.ref.Reference;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
 * <p>On PCs where JavaFX can't decode AAC sound (most MP4 videos and M4A songs),
 * {@link Mp4Sound} decodes it in Java instead: the picture plays from the MP4 with
 * its sound track switched off, and a second player plays the decoded WAV, kept in
 * step with the picture ({@link #openSplit}).
 *
 * <p>LocatorCache is internal to JavaFX. When JavaFX runs as modules (as with
 * mvn javafx:run) the JVM needs
 * {@code --add-exports javafx.media/com.sun.media.jfxmedia.locator=ALL-UNNAMED},
 * which pom.xml passes.
 */
public final class MediaPreview implements FilePreview {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final byte[] ZEROS = new byte[64 * 1024];

    // The sound player is pulled back in step when it drifts further than this from the picture.
    private static final double MAX_DRIFT_MILLIS = 150;

    private static volatile Boolean aacPlays;

    private final ByteBuffer buffer;

    private final ByteBuffer soundBuffer;

    // Hold JavaFX's cache entries for as long as the media is open.
    private Object cacheEntry;

    private Object soundCacheEntry;

    private final MediaPlayer player;

    // Only for split playback: the decoded sound, following the picture.
    private final MediaPlayer soundPlayer;

    private final boolean video;

    // MP4 and M4A files usually carry AAC sound, which some Windows PCs can't decode for JavaFX.
    private final boolean mayUseAac;

    // A line under the picture, for example why there is no sound.
    private final String note;

    private final StackPane view = new StackPane();

    private final ControlBar controlBar;

    private final BooleanSupplier playing;

    private Timeline keepInStep;

    private boolean disposed;

    /**
     * Wraps decrypted media bytes in a player. Takes ownership of the buffer,
     * which is wiped on dispose.
     */
    public static MediaPreview open(ByteBuffer buffer, String extension) throws IOException {

        Object[] entry = new Object[1];

        MediaPlayer player = newPlayer(buffer, extension, entry);

        return new MediaPreview(buffer, entry[0], player, null, null, null,
                PreviewKind.isVideo(extension), usesAac(extension), null);

    }

    /**
     * Plays a video whose sound tracks are switched off, together with its sound decoded
     * to a WAV. Takes ownership of both buffers. sound may be null: the video then plays
     * silently, with the note saying why.
     */
    public static MediaPreview openSplit(ByteBuffer video, ByteBuffer sound, String extension, String note) throws IOException {

        Object[] entry = new Object[1];

        MediaPlayer player = newPlayer(video, extension, entry);

        if (sound == null) {
            return new MediaPreview(video, entry[0], player, null, null, null, true, false, note);
        }

        Object[] soundEntry = new Object[1];

        MediaPlayer soundPlayer;

        try {
            soundPlayer = newPlayer(sound, "wav", soundEntry);
        } catch (IOException | RuntimeException e) {
            player.dispose();
            throw e;
        }

        return new MediaPreview(video, entry[0], player, sound, soundEntry[0], soundPlayer, true, false, note);

    }

    private static MediaPlayer newPlayer(ByteBuffer buffer, String extension, Object[] entry) throws IOException {

        String type = PreviewKind.mediaType(extension);

        if (type == null) {
            throw new IOException("This kind of media can't be played here.");
        }

        URI address = address(extension);

        try {

            entry[0] = LocatorCache.locatorCache().registerURICache(address, buffer, type);

        } catch (IllegalAccessError | NoClassDefFoundError e) {

            throw new IOException("Private playback isn't available when CyberShield is started this way. "
                    + "Start it with mvn javafx:run or from your IDE.", e);

        }

        try {

            return new MediaPlayer(new Media(address.toString()));

        } catch (RuntimeException e) {

            throw new IOException("This file can't be played: " + e.getMessage(), e);

        }

    }

    private static URI address(String extension) {

        byte[] token = new byte[16];

        RANDOM.nextBytes(token);

        // Port 9 is the "discard" port: nothing answers there, so the media can only come from the cache.
        return URI.create("http://127.0.0.1:9/" + HexFormat.of().formatHex(token) + "/media." + extension);

    }

    private MediaPreview(ByteBuffer buffer, Object cacheEntry, MediaPlayer player,
                         ByteBuffer soundBuffer, Object soundCacheEntry, MediaPlayer soundPlayer,
                         boolean video, boolean mayUseAac, String note) {

        this.buffer = buffer;
        this.cacheEntry = cacheEntry;
        this.player = player;
        this.soundBuffer = soundBuffer;
        this.soundCacheEntry = soundCacheEntry;
        this.soundPlayer = soundPlayer;
        this.video = video;
        this.mayUseAac = mayUseAac;
        this.note = note;

        view.setMinSize(0, 0);
        view.getChildren().add(mediaViewFor(view));

        if (!video) {
            Label noteLabel = new Label("♪");
            noteLabel.getStyleClass().add("media-note");
            view.getChildren().add(noteLabel);
        }

        // JavaFX leaves the status at PLAYING after the last frame, and ignores pause()
        // there. Stopping rewinds to the start and shows Play again.
        player.setOnEndOfMedia(() -> {
            player.stop();
            if (soundPlayer != null) {
                soundPlayer.stop();
            }
        });

        if (soundPlayer != null) {

            soundPlayer.setOnEndOfMedia(soundPlayer::stop);

            // Every half second, pull the sound back in step if it has drifted.
            keepInStep = new Timeline(new KeyFrame(Duration.millis(500), event -> keepSoundInStep()));
            keepInStep.setCycleCount(Animation.INDEFINITE);
            keepInStep.play();

        }

        controlBar = new ControlBar();

        // Watching or listening counts as activity, so auto-lock doesn't stop it halfway.
        // A clip sitting at its end doesn't count, even if JavaFX still says PLAYING.
        playing = () -> player.getStatus() == MediaPlayer.Status.PLAYING && !atEnd();

        AutoLock.getInstance().keepOpenWhile(playing);

    }

    /** Wipes decrypted media that was never turned into a preview. */
    public static void wipe(ByteBuffer buffer) {

        if (buffer == null) {
            return;
        }

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
     * Whether JavaFX can play AAC sound on this PC, found out once by playing a tiny
     * clip from memory. Call it off the JavaFX thread: it waits for the answer.
     */
    public static boolean aacPlays() {

        Boolean known = aacPlays;

        if (known != null) {
            return known;
        }

        if (Platform.isFxApplicationThread()) {
            // Can't wait here; decoding in Java works on every PC.
            return false;
        }

        synchronized (MediaPreview.class) {

            if (aacPlays == null) {
                aacPlays = probeAac();
            }

            return aacPlays;

        }

    }

    private static boolean probeAac() {

        byte[] bytes;

        try (InputStream in = MediaPreview.class.getResourceAsStream("/media/aac-probe.m4a")) {

            if (in == null) {
                return false;
            }

            bytes = in.readAllBytes();

        } catch (IOException e) {
            return false;
        }

        ByteBuffer clip = ByteBuffer.allocateDirect(bytes.length).put(bytes).flip();

        CountDownLatch answered = new CountDownLatch(1);

        AtomicBoolean ready = new AtomicBoolean();

        AtomicReference<MediaPlayer> probe = new AtomicReference<>();

        Object[] entry = new Object[1];

        Platform.runLater(() -> {

            try {

                MediaPlayer player = newPlayer(clip, "m4a", entry);

                probe.set(player);

                player.setOnReady(() -> {
                    ready.set(true);
                    answered.countDown();
                });

                player.setOnError(answered::countDown);

            } catch (IOException | RuntimeException e) {

                answered.countDown();

            }

        });

        try {
            answered.await(6, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Platform.runLater(() -> {
            MediaPlayer player = probe.get();
            if (player != null) {
                player.dispose();
            }
        });

        Reference.reachabilityFence(entry);

        return ready.get();

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

    /** The player of the decoded sound in split playback, or null. */
    public MediaPlayer soundPlayer() {
        return soundPlayer;
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

        if (keepInStep != null) {
            keepInStep.stop();
        }

        controlBar.detach();

        player.dispose();

        if (soundPlayer != null) {
            soundPlayer.dispose();
        }

        cacheEntry = null;

        soundCacheEntry = null;

        wipe(buffer);

        wipe(soundBuffer);

    }

    // ------------------------------------------------------------- playback, for one or two players

    private void play() {

        if (atEnd()) {
            seek(Duration.ZERO);
        }

        player.play();

        if (soundPlayer != null) {
            soundPlayer.seek(player.getCurrentTime());
            soundPlayer.play();
        }

    }

    private void pause() {

        player.pause();

        if (soundPlayer != null) {
            soundPlayer.pause();
        }

    }

    private void seek(Duration to) {

        player.seek(to);

        if (soundPlayer != null) {
            soundPlayer.seek(to);
        }

    }

    private void keepSoundInStep() {

        if (soundPlayer == null || player.getStatus() != MediaPlayer.Status.PLAYING) {
            return;
        }

        if (soundPlayer.getStatus() != MediaPlayer.Status.PLAYING) {
            soundPlayer.seek(player.getCurrentTime());
            soundPlayer.play();
            return;
        }

        double drift = Math.abs(soundPlayer.getCurrentTime().toMillis() - player.getCurrentTime().toMillis());

        if (drift > MAX_DRIFT_MILLIS) {
            soundPlayer.seek(player.getCurrentTime());
        }

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

    /** Play/pause, a seek bar, the time and mute, tied to the player (and the sound player, if any). */
    private final class ControlBar {

        final Button playPause = new Button("Play");

        final Slider timeline = new Slider(0, 1, 0);

        final Label time = new Label("0:00 / 0:00");

        final ToggleButton mute = new ToggleButton("Mute");

        final List<Node> nodes;

        private final MediaPlayer audible = soundPlayer != null ? soundPlayer : player;

        private final ChangeListener<MediaPlayer.Status> statusListener =
                (observable, before, status) -> playPause.setText(status == MediaPlayer.Status.PLAYING ? "Pause" : "Play");

        private final InvalidationListener timeListener = observable -> showTime();

        ControlBar() {

            playPause.setId("playButton");

            playPause.setOnAction(event -> {
                if (player.getStatus() == MediaPlayer.Status.PLAYING) {
                    pause();
                } else {
                    play();
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

            mute.selectedProperty().bindBidirectional(audible.muteProperty());

            player.statusProperty().addListener(statusListener);
            player.currentTimeProperty().addListener(timeListener);
            player.totalDurationProperty().addListener(timeListener);

            player.setOnError(() -> time.setText(problem(player.getError(), mayUseAac)));

            if (soundPlayer != null) {
                soundPlayer.setOnError(() -> time.setText("The sound can't be played: " + soundPlayer.getError().getMessage()));
            }

            List<Node> all = new ArrayList<>(List.of(playPause, timeline, time, mute));

            if (note != null) {
                Label noteLabel = new Label(note);
                noteLabel.getStyleClass().add("hint");
                all.add(noteLabel);
            }

            nodes = List.copyOf(all);

            showTime();

        }

        void detach() {

            player.statusProperty().removeListener(statusListener);
            player.currentTimeProperty().removeListener(timeListener);
            player.totalDurationProperty().removeListener(timeListener);
            mute.selectedProperty().unbindBidirectional(audible.muteProperty());

        }

        private void seekToSlider() {

            Duration total = player.getTotalDuration();

            if (total != null && !total.isUnknown() && !total.isIndefinite()) {
                seek(total.multiply(timeline.getValue()));
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
