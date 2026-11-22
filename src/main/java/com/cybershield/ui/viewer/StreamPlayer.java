package com.cybershield.ui.viewer;

import com.cybershield.logger.AppLogger;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyLongWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Plays video and sound with FFmpeg, reading the file through a stream that decrypts it a
 * piece at a time, so a file of any size plays without being decrypted to disk or held in
 * memory whole. Picture and sound each have their own reader and decoder thread, so neither
 * can hold the other up; the sound card's clock keeps them in step, or the system clock when
 * there is no sound. Decoded pictures wait in a small pool of buffers and are shown on the
 * JavaFX thread when their time comes.
 */
final class StreamPlayer implements AutoCloseable {

    /** Opens a new, independent stream over the same file each time it is called. */
    interface Source {

        InputStream open() throws IOException;

    }

    // How far the picture decoder may run ahead of the clock.
    private static final long READ_AHEAD_MICROS = 300_000;

    private static final int FRAME_POOL = 12;

    // Bigger videos are scaled down while decoding; the screen can't show more anyway.
    private static final int MAX_WIDTH = 1920;

    private static final int MAX_HEIGHT = 1080;

    private static final int SOUND_BUFFER_MILLIS = 300;

    // A frame is shown when it is due within this much.
    private static final long SHOW_EARLY_MICROS = 8_000;

    private static volatile Boolean available;

    private final FFmpegFrameGrabber videoGrabber;

    private final FFmpegFrameGrabber soundGrabber;

    private final SourceDataLine line;

    // What the file says, until playing to the end shows the real length (headers can be off).
    private volatile long duration;

    // When the picture on screen was due, for learning the real length at the end.
    private long lastShown;

    private final int width;

    private final int height;

    private final double rotation;

    private final int channels;

    private final String note;

    private final WritableImage image;

    private final BlockingQueue<byte[]> freeFrames;

    private final BlockingQueue<VideoFrame> readyFrames;

    private final Clock clock;

    private final AtomicLong videoSeek = new AtomicLong(-1);

    private final AtomicLong soundSeek = new AtomicLong(-1);

    private final ReadOnlyBooleanWrapper playingProperty = new ReadOnlyBooleanWrapper(false);

    private final ReadOnlyLongWrapper position = new ReadOnlyLongWrapper(0);

    private final ReadOnlyStringWrapper problem = new ReadOnlyStringWrapper();

    private volatile boolean playing;

    private volatile boolean closed;

    private volatile boolean videoEnded;

    private volatile boolean soundEnded;

    // Show the next decoded picture straight away, even ahead of time: after opening or seeking.
    private volatile boolean showNextNow = true;

    private volatile double volume = 1;

    private volatile boolean muted;

    private final Thread videoThread;

    private final Thread soundThread;

    private AnimationTimer renderer;

    // Raised by every seek; frames decoded before the seek are dropped instead of shown.
    private final AtomicLong generation = new AtomicLong();

    private record VideoFrame(long pts, long generation, byte[] pixels) {
    }

    /** Loads FFmpeg once. False if it can't run here; the viewer then uses JavaFX's player. */
    static boolean available() {

        Boolean known = available;

        if (known != null) {
            return known;
        }

        synchronized (StreamPlayer.class) {

            if (available == null) {

                try {

                    Loader.load(org.bytedeco.ffmpeg.global.avformat.class);

                    avutil.av_log_set_level(avutil.AV_LOG_FATAL);

                    available = true;

                } catch (Throwable e) {

                    AppLogger.warn("FFmpeg can't be loaded, so the viewer uses JavaFX's player: " + e);

                    available = false;

                }

            }

            return available;

        }

    }

    /**
     * Opens the file's picture and sound. Runs on a background thread: it reads the start
     * of the file (and the end, where some MP4 files keep their index).
     */
    static StreamPlayer open(Source source) throws IOException {

        if (!available()) {
            throw new IOException("Video playback isn't available on this PC.");
        }

        FFmpegFrameGrabber picture = null;

        FFmpegFrameGrabber sound = null;

        SourceDataLine line = null;

        try {

            picture = start(source, grabber -> grabber.setPixelFormat(avutil.AV_PIX_FMT_BGRA));

            boolean hasVideo = picture.hasVideo() && picture.getImageWidth() > 0 && picture.getImageHeight() > 0;

            boolean hasSound = picture.hasAudio() && picture.getAudioChannels() > 0 && picture.getSampleRate() > 0;

            long duration = Math.max(0, picture.getLengthInTime());

            int channels = hasSound ? Math.min(2, picture.getAudioChannels()) : 0;

            int sampleRate = hasSound ? picture.getSampleRate() : 0;

            if (!hasVideo && !hasSound) {
                throw new IOException("This file has no picture or sound that can be played.");
            }

            int width = 0;

            int height = 0;

            double rotation = 0;

            if (hasVideo) {

                width = picture.getImageWidth();
                height = picture.getImageHeight();

                double scale = Math.min(1, Math.min((double) MAX_WIDTH / width, (double) MAX_HEIGHT / height));

                if (scale < 1) {
                    width = Math.max(2, (int) Math.round(width * scale) & ~1);
                    height = Math.max(2, (int) Math.round(height * scale) & ~1);
                    picture.setImageWidth(width);
                    picture.setImageHeight(height);
                }

                rotation = turnOf(picture);

            } else {

                picture.close();

                picture = null;

            }

            String note = null;

            if (hasSound) {

                line = openLine(sampleRate, channels);

                if (line == null) {
                    note = "No sound: this PC has no sound output that can play it.";
                } else {
                    sound = start(source, grabber -> {
                        grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
                        grabber.setAudioChannels(channels);
                        grabber.setSampleRate(sampleRate);
                    });
                }

            }

            if (picture == null && sound == null) {
                throw new IOException("This song can't be played: " + note);
            }

            return new StreamPlayer(picture, sound, line, duration, width, height, rotation, channels, sampleRate, note);

        } catch (IOException | RuntimeException e) {

            closeQuietly(picture);

            closeQuietly(sound);

            if (line != null) {
                line.close();
            }

            throw e;

        }

    }

    private static FFmpegFrameGrabber start(Source source, Consumer<FFmpegFrameGrabber> settings) throws IOException {

        InputStream in = source.open();

        // A maximum size above zero is what turns on seeking in a stream.
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(in, Integer.MAX_VALUE);

        grabber.setCloseInputStream(true);

        settings.accept(grabber);

        try {

            grabber.start();

            return grabber;

        } catch (Exception e) {

            closeQuietly(grabber);

            in.close();

            throw new IOException("This file can't be played: " + reason(e), e);

        }

    }

    /** Degrees to turn the picture clockwise, from the phone's note in the file. */
    private static double turnOf(FFmpegFrameGrabber grabber) {

        try {

            double counterClockwise = grabber.getDisplayRotation();

            if (Double.isNaN(counterClockwise)) {
                return 0;
            }

            double turn = Math.round(-counterClockwise / 90.0) * 90.0;

            return ((turn % 360) + 360) % 360;

        } catch (RuntimeException e) {

            return 0;

        }

    }

    private static SourceDataLine openLine(int sampleRate, int channels) {

        try {

            AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);

            SourceDataLine line = AudioSystem.getSourceDataLine(format);

            line.open(format, sampleRate * channels * 2 * SOUND_BUFFER_MILLIS / 1000);

            return line;

        } catch (Exception | LinkageError e) {

            AppLogger.warn("No sound output for " + sampleRate + " Hz, " + channels + " channels: " + e.getMessage());

            return null;

        }

    }

    private StreamPlayer(FFmpegFrameGrabber videoGrabber, FFmpegFrameGrabber soundGrabber, SourceDataLine line,
                         long duration, int width, int height, double rotation, int channels, int sampleRate, String note) {

        this.videoGrabber = videoGrabber;
        this.soundGrabber = soundGrabber;
        this.line = soundGrabber != null ? line : null;
        this.duration = duration;
        this.width = width;
        this.height = height;
        this.rotation = rotation;
        this.channels = channels;
        this.note = note;

        if (line != null && soundGrabber == null) {
            line.close();
        }

        this.clock = new Clock(this.line, sampleRate);

        if (videoGrabber != null) {

            this.image = new WritableImage(width, height);
            this.freeFrames = new ArrayBlockingQueue<>(FRAME_POOL);
            this.readyFrames = new ArrayBlockingQueue<>(FRAME_POOL);

            for (int i = 0; i < FRAME_POOL; i++) {
                freeFrames.add(new byte[width * height * 4]);
            }

        } else {

            this.image = null;
            this.freeFrames = null;
            this.readyFrames = null;

        }

        this.soundEnded = soundGrabber == null;

        this.videoEnded = videoGrabber == null;

        this.videoThread = videoGrabber == null ? null : daemon("cybershield-video", this::videoLoop);

        this.soundThread = soundGrabber == null ? null : daemon("cybershield-sound", this::soundLoop);

    }

    private static Thread daemon(String name, Runnable work) {

        Thread thread = new Thread(work, name);

        thread.setDaemon(true);

        thread.start();

        return thread;

    }

    // ------------------------------------------------------------- what the view needs

    boolean hasVideo() {
        return image != null;
    }

    boolean hasSound() {
        return line != null;
    }

    /** The picture, updated while rendering; null for sound only. */
    WritableImage image() {
        return image;
    }

    /** Degrees to turn the picture clockwise: 0, 90, 180 or 270. */
    double rotation() {
        return rotation;
    }

    /** In microseconds; 0 when the file doesn't say. */
    long duration() {
        return duration;
    }

    /** Why something is missing, such as the sound, or null. */
    String note() {
        return note;
    }

    ReadOnlyBooleanProperty playingProperty() {
        return playingProperty.getReadOnlyProperty();
    }

    boolean isPlaying() {
        return playing;
    }

    /** Where playback is, in microseconds. Updated on the JavaFX thread. */
    ReadOnlyLongProperty positionProperty() {
        return position.getReadOnlyProperty();
    }

    /** A message when decoding failed part-way, or null. */
    ReadOnlyStringProperty problemProperty() {
        return problem.getReadOnlyProperty();
    }

    void setVolume(double volume) {
        this.volume = Math.max(0, Math.min(1, volume));
    }

    void setMuted(boolean muted) {
        this.muted = muted;
    }

    // ------------------------------------------------------------- controls (JavaFX thread)

    /** Starts showing pictures. Call once, on the JavaFX thread. */
    void startRendering() {

        if (renderer != null || closed) {
            return;
        }

        renderer = new AnimationTimer() {

            @Override
            public void handle(long now) {
                render();
            }

        };

        renderer.start();

    }

    void play() {

        if (closed) {
            return;
        }

        if (ended()) {
            seek(0);
        }

        playing = true;

        clock.play();

        if (line != null) {
            line.start();
        }

        playingProperty.set(true);

    }

    void pause() {

        playing = false;

        clock.pause();

        if (line != null) {
            line.stop();
        }

        playingProperty.set(false);

    }

    /** Jumps to a time in microseconds. */
    void seek(long micros) {

        if (closed) {
            return;
        }

        long target = Math.max(0, duration > 0 ? Math.min(micros, duration) : micros);

        clock.seek(target);

        position.set(target);

        generation.incrementAndGet();

        showNextNow = true;

        if (videoGrabber != null) {
            videoEnded = false;
            videoSeek.set(target);
        }

        if (soundGrabber != null) {
            soundEnded = false;
            soundSeek.set(target);
        }

    }

    /** True once both picture and sound have run out. */
    boolean ended() {

        boolean pictureDone = videoGrabber == null || (videoEnded && readyFrames.isEmpty() && videoSeek.get() < 0);

        // The sound has run out once the decoder is done and the line has played what it holds.
        boolean soundDone = soundGrabber == null
                || (soundEnded && soundSeek.get() < 0 && line.available() >= line.getBufferSize() - channels * 2);

        return pictureDone && soundDone;

    }

    private void render() {

        if (closed) {
            return;
        }

        long now = clock.now();

        if (image != null) {

            VideoFrame show = null;

            VideoFrame head;

            long current = generation.get();

            while ((head = readyFrames.peek()) != null && (head.generation() != current || showNextNow
                    || head.pts() <= now + SHOW_EARLY_MICROS)) {

                readyFrames.poll();

                if (head.generation() != current) {
                    freeFrames.offer(head.pixels());
                    continue;
                }

                if (show != null) {
                    freeFrames.offer(show.pixels());
                }

                show = head;

                if (showNextNow) {
                    showNextNow = false;
                    break;
                }

            }

            if (show != null) {
                image.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getByteBgraPreInstance(), show.pixels(), 0, width * 4);
                lastShown = show.pts();
                freeFrames.offer(show.pixels());
            }

        }

        long shown = Math.max(0, duration > 0 ? Math.min(now, duration) : now);

        if (Math.abs(shown - position.get()) >= 50_000) {
            position.set(shown);
        }

        if (playing && ended()) {

            pause();

            // The end is where picture and sound really ran out.
            long end = Math.max(lastShown, now);

            if (duration <= 0 || end < duration) {
                duration = end;
            }

            position.set(duration);

        }

    }

    // ------------------------------------------------------------- decoder threads

    private void videoLoop() {

        boolean firstAfterSeek = true;

        long frameGeneration = generation.get();

        try {

            while (!closed) {

                long target = videoSeek.getAndSet(-1);

                if (target >= 0) {

                    frameGeneration = generation.get();

                    dropReadyFrames();

                    videoGrabber.setTimestamp(target, true);

                    videoEnded = false;

                    firstAfterSeek = true;

                }

                if (videoEnded) {
                    rest(10);
                    continue;
                }

                Frame frame = videoGrabber.grabImage();

                if (frame == null) {
                    videoEnded = true;
                    continue;
                }

                if (frame.image == null) {
                    continue;
                }

                long pts = frame.timestamp;

                // Don't run far ahead of what is being shown: the pool of buffers is small.
                while (!closed && videoSeek.get() < 0 && !firstAfterSeek && pts - clock.now() > READ_AHEAD_MICROS) {
                    rest(5);
                }

                if (closed || videoSeek.get() >= 0) {
                    continue;
                }

                byte[] pixels = null;

                while (!closed && videoSeek.get() < 0 && pixels == null) {
                    pixels = freeFrames.poll(20, TimeUnit.MILLISECONDS);
                }

                if (pixels == null) {
                    continue;
                }

                if (copyPicture(frame, pixels)) {
                    readyFrames.add(new VideoFrame(pts, frameGeneration, pixels));
                    firstAfterSeek = false;
                } else {
                    freeFrames.offer(pixels);
                }

            }

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

        } catch (Exception | LinkageError e) {

            report("The picture stopped: " + reason(e));

        } finally {

            closeQuietly(videoGrabber);

        }

    }

    private boolean copyPicture(Frame frame, byte[] pixels) {

        if (frame.imageWidth != width || frame.imageHeight != height || !(frame.image[0] instanceof ByteBuffer data)) {
            return false;
        }

        int rowBytes = width * 4;

        int stride = frame.imageStride > 0 ? frame.imageStride : rowBytes;

        if (stride == rowBytes) {
            data.get(0, pixels, 0, rowBytes * height);
        } else {
            for (int y = 0; y < height; y++) {
                data.get(y * stride, pixels, y * rowBytes, rowBytes);
            }
        }

        return true;

    }

    private void soundLoop() {

        byte[] bytes = new byte[0];

        boolean anchorNext = true;

        try {

            while (!closed) {

                long target = soundSeek.getAndSet(-1);

                if (target >= 0) {

                    line.stop();

                    line.flush();

                    soundGrabber.setTimestamp(target, true);

                    soundEnded = false;

                    anchorNext = true;

                    if (playing) {
                        line.start();
                    }

                }

                if (!playing || soundEnded) {
                    rest(10);
                    continue;
                }

                Frame frame = soundGrabber.grabSamples();

                if (frame == null) {
                    soundEnded = true;
                    clock.soundEnded();
                    continue;
                }

                if (frame.samples == null || frame.samples.length == 0 || !(frame.samples[0] instanceof ShortBuffer samples)) {
                    continue;
                }

                int count = samples.limit();

                if (bytes.length < count * 2) {
                    bytes = new byte[count * 2];
                }

                double gain = muted ? 0 : volume;

                for (int i = 0; i < count; i++) {

                    int value = gain == 1 ? samples.get(i) : (int) Math.round(samples.get(i) * gain);

                    bytes[2 * i] = (byte) value;
                    bytes[2 * i + 1] = (byte) (value >> 8);

                }

                if (anchorNext) {
                    clock.anchor(frame.timestamp);
                    anchorNext = false;
                }

                write(bytes, count * 2);

            }

        } catch (Exception | LinkageError e) {

            report("The sound stopped: " + reason(e));

            clock.soundEnded();

        } finally {

            closeQuietly(soundGrabber);

            line.close();

            Arrays.fill(bytes, (byte) 0);

        }

    }

    /** Writes only what the line has room for, so pausing, seeking and closing never wait on it. */
    private void write(byte[] bytes, int length) {

        int frameBytes = channels * 2;

        int offset = 0;

        while (offset < length && !closed && soundSeek.get() < 0) {

            int room = playing ? line.available() : 0;

            int piece = Math.min(room, length - offset);

            piece -= piece % frameBytes;

            if (piece <= 0) {
                rest(4);
                continue;
            }

            offset += line.write(bytes, offset, piece);

        }

    }

    private void dropReadyFrames() {

        VideoFrame frame;

        while ((frame = readyFrames.poll()) != null) {
            freeFrames.offer(frame.pixels());
        }

    }

    private void report(String message) {

        if (closed) {
            return;
        }

        AppLogger.error(message);

        Platform.runLater(() -> problem.set(message));

    }

    // ------------------------------------------------------------- closing

    /** Stops playback, ends the threads and wipes the decoded pictures. Safe to call more than once. */
    @Override
    public void close() {

        if (closed) {
            return;
        }

        closed = true;

        playing = false;

        if (renderer != null) {
            renderer.stop();
        }

        playingProperty.set(false);

        // Each thread releases its own decoder as it ends, so none is freed while in use.
        join(videoThread);

        join(soundThread);

        if (freeFrames != null) {

            dropReadyFrames();

            for (byte[] pixels : freeFrames) {
                Arrays.fill(pixels, (byte) 0);
            }

            freeFrames.clear();

        }

    }

    private static void join(Thread thread) {

        if (thread == null) {
            return;
        }

        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

    }

    private static void rest(long millis) {

        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

    }

    private static void closeQuietly(FFmpegFrameGrabber grabber) {

        if (grabber == null) {
            return;
        }

        try {
            grabber.close();
        } catch (Exception | LinkageError ignored) {
            // Already closed, or never started.
        }

    }

    static String reason(Throwable error) {

        Throwable cause = error;

        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }

        String message = cause.getMessage() != null ? cause.getMessage() : error.getMessage();

        return message == null ? cause.getClass().getSimpleName() : message;

    }

    /**
     * Media time in microseconds. Follows the sound card while sound is playing, since the
     * picture has to keep up with what is heard; otherwise the system clock.
     */
    private static final class Clock {

        private final SourceDataLine line;

        private final int sampleRate;

        private boolean running;

        private long wallBase;

        private long wallStart;

        private boolean anchored;

        private long anchorMedia;

        private long anchorFrames;

        Clock(SourceDataLine line, int sampleRate) {
            this.line = line;
            this.sampleRate = sampleRate;
        }

        synchronized long now() {

            if (anchored) {
                long frames = line.getLongFramePosition() - anchorFrames;
                return anchorMedia + Math.max(0, frames) * 1_000_000L / sampleRate;
            }

            return running ? wallBase + (System.nanoTime() - wallStart) / 1_000 : wallBase;

        }

        synchronized void play() {

            if (!running) {
                wallStart = System.nanoTime();
                running = true;
            }

        }

        synchronized void pause() {

            if (running) {
                if (!anchored) {
                    wallBase = now();
                }
                running = false;
            }

        }

        synchronized void seek(long target) {

            anchored = false;
            wallBase = target;
            wallStart = System.nanoTime();

        }

        /** The sound written next starts at mediaMicros. */
        synchronized void anchor(long mediaMicros) {

            if (line == null) {
                return;
            }

            anchorMedia = mediaMicros;
            anchorFrames = line.getLongFramePosition();
            anchored = true;

        }

        /** The sound has run out: carry on with the system clock from here. */
        synchronized void soundEnded() {

            if (anchored) {
                long here = now();
                anchored = false;
                wallBase = here;
                wallStart = System.nanoTime();
            }

        }

    }

}
