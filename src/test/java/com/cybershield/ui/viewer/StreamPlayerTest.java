package com.cybershield.ui.viewer;

import com.cybershield.TestSupport;
import com.cybershield.encryption.EncryptedFileReader;
import com.cybershield.encryption.FileEncryptionService;
import com.cybershield.encryption.FileMetadata;
import com.cybershield.files.FileVaultService;
import com.cybershield.services.EncryptionService;
import com.cybershield.vault.VaultService;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static com.cybershield.TestSupport.MASTER_PASSWORD;
import static com.cybershield.TestSupport.random;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Streamed playback with FFmpeg from encrypted files: the formats JavaFX can't play, big
 * pictures, turned phone videos, seeking, sound only, and damaged files. The test media is
 * made here with FFmpeg; its sound is silent, so nothing is heard while the tests run.
 */
class StreamPlayerTest {

    private static final int FPS = 25;

    private static final int RATE = 44100;

    private static boolean toolkitReady;

    private final EncryptionService key = EncryptionService.getInstance();

    private StreamPlayer player;

    @TempDir
    Path dir;

    @BeforeAll
    static void startJavaFx() {

        try {
            Platform.startup(() -> { });
            toolkitReady = true;
        } catch (IllegalStateException alreadyRunning) {
            toolkitReady = true;
        } catch (RuntimeException | Error noDisplay) {
            toolkitReady = false;
        }

        Platform.setImplicitExit(false);

    }

    @AfterEach
    void close() throws Exception {

        if (player != null) {
            onFx(() -> {
                player.close();
                return null;
            });
        }

        key.clear();

    }

    @Test
    void anMkvVideoShowsItsFirstFrameSeeksAndPlaysToTheEnd() throws Exception {

        assumeTrue(toolkitReady && StreamPlayer.available(), "JavaFX or FFmpeg can't run here");

        Path video = makeVideo("clip.mkv", "matroska", 320, 240, 50, avcodec.AV_CODEC_ID_AAC, 0);

        player = StreamPlayer.open(source(encrypt(video)));

        assertTrue(player.hasVideo());
        assertTrue(player.duration() >= 1_900_000, "the file gives its length: " + player.duration());

        onFx(() -> {
            player.startRendering();
            return null;
        });

        assertEquals(0, frameShown(player), 3, "the first frame shows before playing");

        onFx(() -> {
            player.seek(1_200_000);
            return null;
        });

        waitFor(() -> Math.abs(frameIndex(player) - 30) <= 3, 5000);

        assertEquals(30, frameIndex(player), 3, "after seeking to 1.2 s, the frame from 1.2 s shows");

        onFx(() -> {
            player.play();
            return null;
        });

        waitFor(() -> !player.isPlaying(), 8000);

        assertFalse(player.isPlaying(), "it stops at the end");
        assertTrue(frameIndex(player) >= 45, "and the last frames were shown: " + frameIndex(player));
        assertTrue(player.ended());

        // This muxer's header says 3 s; once played through, the real 2 s is used.
        assertTrue(Math.abs(player.duration() - 2_000_000) < 200_000, "the real length: " + player.duration());
        assertEquals(player.duration(), onFx(() -> player.positionProperty().get()), "the time shows the end");

    }

    @Test
    void containersJavaFxCantPlayOpen() throws Exception {

        assumeTrue(toolkitReady && StreamPlayer.available(), "JavaFX or FFmpeg can't run here");

        Object[][] kinds = {
                {"clip.avi", "avi", avcodec.AV_CODEC_ID_PCM_S16LE},
                {"clip.mov", "mov", avcodec.AV_CODEC_ID_AAC},
                {"clip.mp4", "mp4", avcodec.AV_CODEC_ID_AAC},
        };

        for (Object[] kind : kinds) {

            Path video = makeVideo((String) kind[0], (String) kind[1], 320, 240, 25, (Integer) kind[2], 0);

            try (StreamPlayer opened = StreamPlayer.open(source(encrypt(video)))) {

                player = opened;

                assertTrue(opened.hasVideo(), kind[0] + " has a picture");
                assertTrue(opened.duration() > 500_000, kind[0] + " has a length");

                onFx(() -> {
                    opened.startRendering();
                    return null;
                });

                assertEquals(0, frameShown(opened), 3, kind[0] + " shows its first frame");

                onFx(() -> {
                    opened.close();
                    return null;
                });

            }

            player = null;

        }

    }

    @Test
    void bigPicturesAreScaledDownAndPhoneVideosAreTurned() throws Exception {

        assumeTrue(toolkitReady && StreamPlayer.available(), "JavaFX or FFmpeg can't run here");

        player = StreamPlayer.open(source(encrypt(makeVideo("big.mkv", "matroska", 2560, 1440, 5, 0, 0))));

        assertEquals(1920, player.image().getWidth());
        assertEquals(1080, player.image().getHeight());

        onFx(() -> {
            player.startRendering();
            return null;
        });

        assertEquals(0, frameShown(player), 3, "the scaled picture shows");

        onFx(() -> {
            player.close();
            return null;
        });

        // Recorded holding the phone upright: the file says to turn the picture a quarter clockwise.
        player = StreamPlayer.open(source(encrypt(makeVideo("phone.mp4", "mp4", 320, 240, 5, 0, -90))));

        assertEquals(90, player.rotation());

        StreamPlayer turned = player;

        StreamPreview preview = onFx(() -> new StreamPreview(turned));

        ImageView picture = (ImageView) preview.view().lookup("#videoView");

        assertEquals(90, picture.getRotate());

        onFx(() -> {
            preview.dispose();
            return null;
        });

        player = null;

    }

    @Test
    void songsPlayWithoutAPicture() throws Exception {

        assumeTrue(toolkitReady && StreamPlayer.available(), "JavaFX or FFmpeg can't run here");

        Path song = dir.resolve("song.m4a");

        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(song.toFile(), 2)) {

            recorder.setFormat("mp4");
            recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
            recorder.setSampleRate(RATE);
            recorder.setAudioBitrate(96_000);
            recorder.start();

            ShortBuffer silence = ShortBuffer.allocate(RATE / 10 * 2);

            for (int i = 0; i < 8; i++) {
                silence.rewind();
                recorder.recordSamples(RATE, 2, silence);
            }

        }

        player = StreamPlayer.open(source(encrypt(song)));

        assertFalse(player.hasVideo());

        if (!player.hasSound()) {
            return; // No sound device here: opening without failing is all that can be checked.
        }

        onFx(() -> {
            player.startRendering();
            player.play();
            return null;
        });

        waitFor(() -> !player.isPlaying(), 6000);

        assertTrue(player.ended(), "the song plays to its end");

    }

    @Test
    void aDamagedVideoIsRefused() throws Exception {

        assumeTrue(StreamPlayer.available(), "FFmpeg can't run here");

        Path blob = encrypt(makeVideo("clip.mkv", "matroska", 320, 240, 10, 0, 0));

        // The clip fits in one chunk: changing any byte of it makes the whole chunk unreadable.
        try (RandomAccessFile file = new RandomAccessFile(blob.toFile(), "rw")) {
            long position = file.length() - 500;
            file.seek(position);
            int value = file.read();
            file.seek(position);
            file.write(value ^ 0x01);
        }

        IOException refused = assertThrows(IOException.class, () -> StreamPlayer.open(source(blob)));

        assertTrue(refused.getMessage().startsWith("This file can't be played"), refused.getMessage());

    }

    @Test
    void theViewerOpensAProtectedMkvAndPlaysIt() throws Exception {

        assumeTrue(toolkitReady && StreamPlayer.available(), "JavaFX or FFmpeg can't run here");

        assertEquals(PreviewKind.MEDIA, PreviewKind.of("mkv"));
        assertEquals(PreviewKind.MEDIA, PreviewKind.of("mov"));
        assertTrue(PreviewKind.MEDIA.streams(), "media of any size streams");
        assertTrue(PreviewKind.isVideo("avi"));

        Path store = TestSupport.freshEnvironment(dir.resolve("app"));

        VaultService vaults = new VaultService();

        vaults.createVault(TestSupport.newUser("alice"), MASTER_PASSWORD);

        try {

            Path folder = Files.createDirectories(dir.resolve("videos"));

            Path video = makeVideo("videos/holiday.mkv", "matroska", 320, 240, 50, avcodec.AV_CODEC_ID_AAC, 0);

            FileVaultService service = new FileVaultService(store);

            FileVaultService.JobListener quiet = new FileVaultService.JobListener() { };

            service.protect(service.scan(List.of(folder), quiet), quiet);

            assertFalse(Files.exists(video), "the original was protected");

            var item = service.listItems().get(0);

            var file = service.listFiles(item).get(0);

            Previews.Prepared prepared = Previews.prepare(PreviewKind.MEDIA, service, file);

            FilePreview preview = onFx(prepared::create);

            assertTrue(preview instanceof StreamPreview, "media plays through the streaming player");

            Button play = onFx(() -> (Button) preview.controls().stream()
                    .filter(node -> "playButton".equals(node.getId())).findFirst().orElseThrow());

            onFx(() -> {
                play.fire();
                return null;
            });

            waitFor(() -> "Pause".equals(text(play)), 3000);

            assertEquals("Pause", text(play));

            onFx(() -> {
                preview.dispose();
                return null;
            });

        } finally {

            vaults.lockVault();

        }

    }

    // ------------------------------------------------------------- helpers

    /** A video of plain-coloured frames: the blue level rises with the frame number. */
    private Path makeVideo(String name, String format, int width, int height, int frames, int soundCodec, double rotation)
            throws Exception {

        Path out = dir.resolve(name);

        Files.createDirectories(out.getParent());

        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(out.toFile(), width, height, soundCodec == 0 ? 0 : 2)) {

            recorder.setFormat(format);
            recorder.setFrameRate(FPS);
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_MPEG4);
            recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
            recorder.setVideoBitrate(2_000_000);
            recorder.setVideoQuality(2);
            recorder.setGopSize(5);

            if (soundCodec != 0) {
                recorder.setAudioCodec(soundCodec);
                recorder.setSampleRate(RATE);
                recorder.setAudioBitrate(96_000);
            }

            if (rotation != 0) {
                recorder.setDisplayRotation(rotation);
            }

            recorder.start();

            Frame picture = new Frame(width, height, Frame.DEPTH_UBYTE, 3);

            ByteBuffer pixels = (ByteBuffer) picture.image[0];

            ShortBuffer silence = ShortBuffer.allocate(RATE / FPS * 2);

            for (int i = 0; i < frames; i++) {

                int blue = frames == 1 ? 0 : i * 255 / (frames - 1);

                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int at = y * picture.imageStride + x * 3;
                        pixels.put(at, (byte) blue);
                        pixels.put(at + 1, (byte) 60);
                        pixels.put(at + 2, (byte) 120);
                    }
                }

                recorder.record(picture);

                if (soundCodec != 0) {
                    silence.rewind();
                    recorder.recordSamples(RATE, 2, silence);
                }

            }

        }

        return out;

    }

    private Path encrypt(Path plain) throws Exception {

        key.useKey(new SecretKeySpec(random(32), "AES"));

        Path blob = dir.resolve(plain.getFileName() + ".csf");

        FileMetadata metadata = new FileMetadata(plain.toString(), "item", plain.getFileName().toString(), Files.size(plain), 0, 0, 0);

        FileEncryptionService.encrypt(plain, blob, metadata, random(16), key, FileEncryptionService.Progress.NONE);

        return blob;

    }

    private StreamPlayer.Source source(Path blob) {
        return () -> EncryptedFileReader.open(blob, key).stream();
    }

    /** Waits for a picture to be shown, and says which frame it is. */
    private static int frameShown(StreamPlayer player) throws Exception {

        waitFor(() -> frameIndex(player) >= 0, 5000);

        return frameIndex(player);

    }

    /** The frame number read back from the blue level in the middle of the picture, or -1 if none shows yet. */
    private static int frameIndex(StreamPlayer player) {

        try {

            return onFx(() -> {

                WritableImage image = player.image();

                int argb = image.getPixelReader().getArgb((int) image.getWidth() / 2, (int) image.getHeight() / 2);

                if ((argb >>> 24) == 0) {
                    return -1;
                }

                int blue = argb & 0xFF;

                return Math.round(blue * 49f / 255f);

            });

        } catch (Exception e) {

            return -1;

        }

    }

    private static String text(Button button) {

        try {
            return onFx(button::getText);
        } catch (Exception e) {
            return "";
        }

    }

    private static void waitFor(BooleanSupplier condition, long millis) throws InterruptedException {

        long until = System.currentTimeMillis() + millis;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < until) {
            Thread.sleep(40);
        }

    }

    private static <T> T onFx(Callable<T> work) throws Exception {

        CompletableFuture<T> result = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });

        return result.get(30, TimeUnit.SECONDS);

    }

}
