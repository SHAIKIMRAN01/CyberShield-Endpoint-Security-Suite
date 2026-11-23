package com.cybershield.ui.viewer;

import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Whether JavaFX's own player can play a kind of media on this computer at all, asked of a
 * plain file with none of CyberShield's code involved. Build servers may have no video
 * decoders or no sound device; tests of the JavaFX fallback skip there instead of failing,
 * and still fail wherever JavaFX can play the media but CyberShield's way of handing it
 * over doesn't.
 */
public final class JavaFxPlayback {

    private static final Map<String, Boolean> ANSWERS = new ConcurrentHashMap<>();

    private JavaFxPlayback() {
    }

    /** An H.264 video (the test clip). Call off the JavaFX thread. */
    public static boolean canPlayVideo() {

        return ANSWERS.computeIfAbsent("video", key -> {
            try (InputStream in = JavaFxPlayback.class.getResourceAsStream("/media/colours.mp4")) {
                return in != null && playsFromFile(in.readAllBytes(), ".mp4");
            } catch (IOException e) {
                return false;
            }
        });

    }

    /** Sound: a quarter-second WAV tone. Call off the JavaFX thread. */
    public static boolean canPlaySound() {

        return ANSWERS.computeIfAbsent("sound", key -> playsFromFile(tone(), ".wav"));

    }

    /** Up to three tries, as JavaFX's Windows decoders sometimes fail to start a good file. */
    private static boolean playsFromFile(byte[] media, String extension) {

        Path file = null;

        try {

            file = Files.createTempFile("cybershield-playback-check", extension);

            Files.write(file, media);

            String address = file.toUri().toString();

            for (int attempt = 0; attempt < 3; attempt++) {
                if (getsReady(address)) {
                    return true;
                }
            }

            return false;

        } catch (IOException e) {

            return false;

        } finally {

            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    file.toFile().deleteOnExit();
                }
            }

        }

    }

    private static boolean getsReady(String address) {

        CountDownLatch answered = new CountDownLatch(1);

        AtomicBoolean ready = new AtomicBoolean();

        AtomicReference<MediaPlayer> made = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                MediaPlayer player = new MediaPlayer(new Media(address));
                made.set(player);
                player.setOnReady(() -> {
                    ready.set(true);
                    answered.countDown();
                });
                player.setOnError(answered::countDown);
            } catch (RuntimeException e) {
                answered.countDown();
            }
        });

        try {
            answered.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        CountDownLatch closed = new CountDownLatch(1);

        Platform.runLater(() -> {
            if (made.get() != null) {
                made.get().dispose();
            }
            closed.countDown();
        });

        try {
            closed.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return ready.get();

    }

    /** A 0.25-second 440 Hz tone, 16-bit mono WAV at 22,050 Hz. */
    private static byte[] tone() {

        int rate = 22_050;

        int samples = rate / 4;

        ByteBuffer wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);

        wav.put("RIFF".getBytes()).putInt(36 + samples * 2).put("WAVE".getBytes());
        wav.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2)
                .putShort((short) 2).putShort((short) 16);
        wav.put("data".getBytes()).putInt(samples * 2);

        for (int i = 0; i < samples; i++) {
            wav.putShort((short) (Math.sin(2 * Math.PI * 440 * i / rate) * 8000));
        }

        return wav.array();

    }

}
