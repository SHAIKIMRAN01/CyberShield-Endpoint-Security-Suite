package com.cybershield.ui.viewer;

import com.cybershield.TestSupport;
import com.cybershield.files.FileVaultService;
import com.cybershield.vault.VaultService;

import javafx.application.Platform;
import javafx.scene.media.MediaPlayer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Videos with AAC sound play even where JavaFX can't decode AAC: the sound is decoded
 * in Java and plays beside the picture. The clip (2 s, 320 x 240, a 440 Hz tone) was
 * made for these tests.
 */
class Mp4SoundTest {

    @TempDir
    static Path tempDir;

    private static boolean toolkitReady;

    private static final VaultService VAULTS = new VaultService();

    @BeforeAll
    static void start() {

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

    @AfterAll
    static void lock() {
        VAULTS.lockVault();
    }

    private static ByteBuffer clip() throws Exception {

        try (InputStream in = Mp4SoundTest.class.getResourceAsStream("/media/colours-tone.mp4")) {

            byte[] bytes = in.readAllBytes();

            return ByteBuffer.allocateDirect(bytes.length).put(bytes).flip();

        }

    }

    @Test
    void findsThePictureAndTheAacSound() throws Exception {

        List<Mp4Sound.Track> tracks = Mp4Sound.tracks(clip());

        assertTrue(Mp4Sound.hasVideo(tracks));

        Mp4Sound.Track aac = Mp4Sound.firstAac(tracks);

        assertNotNull(aac, "an AAC sound track");

        assertTrue(aac.audioConfig().length >= 2, "with its decoder configuration");

        assertTrue(aac.sampleSizes().length > 50, "about 86 AAC frames in 2 seconds");

    }

    @Test
    void decodesTheSoundToAWavWithTheTone() throws Exception {

        ByteBuffer data = clip();

        ByteBuffer wav = Mp4Sound.decodeToWav(data, Mp4Sound.firstAac(Mp4Sound.tracks(data))).order(ByteOrder.LITTLE_ENDIAN);

        int channels = wav.getShort(22);
        int rate = wav.getInt(24);
        int bits = wav.getShort(34);
        int dataBytes = wav.getInt(40);

        assertEquals("RIFF", new String(new byte[] {wav.get(0), wav.get(1), wav.get(2), wav.get(3)}));
        assertEquals(2, channels);
        assertEquals(16, bits);
        assertTrue(rate == 44100 || rate == 48000, "rate " + rate);

        double seconds = dataBytes / (double) (rate * channels * 2);

        assertTrue(seconds > 1.8 && seconds < 2.3, "about 2 seconds of sound, got " + seconds);

        // The middle of the clip is the tone, not silence.
        long sumOfSquares = 0;
        int count = 0;
        for (int at = 44 + dataBytes / 2; at < 44 + dataBytes / 2 + 8000; at += 2) {
            short sample = wav.getShort(at);
            sumOfSquares += (long) sample * sample;
            count++;
        }

        assertTrue(Math.sqrt(sumOfSquares / (double) count) > 1000, "the 440 Hz tone is there");

    }

    @Test
    void switchingTheSoundOffLeavesOnlyThePicture() throws Exception {

        ByteBuffer data = clip();

        List<Mp4Sound.Track> tracks = Mp4Sound.tracks(data);

        Mp4Sound.muteInPlace(data, tracks);

        List<Mp4Sound.Track> after = Mp4Sound.tracks(data);

        assertTrue(Mp4Sound.hasVideo(after));

        assertEquals(null, Mp4Sound.firstAac(after), "no sound track left");

    }

    @Test
    void theViewerPlaysTheVideoWithItsSoundFromTheEncryptedStore() throws Exception {

        assumeTrue(toolkitReady, "JavaFX can't start here");

        Path store = TestSupport.freshEnvironment(tempDir.resolve("app"));

        VAULTS.createVault(TestSupport.newUser("viewer"), TestSupport.MASTER_PASSWORD);

        Path folder = Files.createDirectories(tempDir.resolve("clips"));

        try (InputStream in = Mp4SoundTest.class.getResourceAsStream("/media/colours-tone.mp4")) {
            Files.copy(in, folder.resolve("holiday.mp4"));
        }

        FileVaultService service = new FileVaultService(store);
        FileVaultService.JobListener quiet = new FileVaultService.JobListener() { };

        service.protect(service.scan(List.of(folder), quiet), quiet);

        var item = service.listItems().get(0);

        var file = service.listFiles(item).get(0);

        // The same path the File Security viewer takes: streamed through FFmpeg, sound included.
        if (StreamPlayer.available()) {

            FilePreview streamed = onFx(Previews.prepare(PreviewKind.of(file.extension()), service, file)::create);

            assertTrue(streamed instanceof StreamPreview, "the viewer streams the video");

            StreamPlayer player = ((StreamPreview) streamed).player();

            assertTrue(player.hasVideo());
            assertEquals(320.0, player.image().getWidth());
            assertTrue(player.hasSound() || player.note() != null, "its AAC sound plays, unless the PC has no sound output");

            onFx(() -> {
                streamed.dispose();
                return null;
            });

        }

        // The fallback when FFmpeg can't run: JavaFX's player from memory. Only where JavaFX can
        // play such media at all: build servers may have no video decoders or no sound device.
        assumeTrue(JavaFxPlayback.canPlayVideo(), "JavaFX can't play H.264 video on this computer");

        if (!MediaPreview.aacPlays()) {
            assumeTrue(JavaFxPlayback.canPlaySound(), "JavaFX can't play sound on this computer");
        }

        // JavaFX's own decoders on Windows (Media Foundation) sometimes fail to start a video
        // that is fine: on the development PC about one run in four, with "media invalid".
        // The bytes handed over are the same every time, so a fault of ours fails every try;
        // up to three fresh tries keep this test about our code, not that decoder.
        MediaPreview preview = null;

        for (int attempt = 1; attempt <= 3 && preview == null; attempt++) {

            Previews.Prepared prepared = Previews.prepareMediaInMemory(PreviewKind.of(file.extension()), service, file);

            MediaPreview opened = (MediaPreview) onFx(prepared::create);

            boolean split = !MediaPreview.aacPlays();

            assertEquals(split, opened.soundPlayer() != null,
                    "where AAC can't be decoded by JavaFX, the sound plays from a decoded copy");

            if (ready(opened.player()) && (!split || ready(opened.soundPlayer()))) {
                preview = opened;
            } else {
                onFx(() -> {
                    opened.dispose();
                    return null;
                });
            }

        }

        assertNotNull(preview, "the picture (and the sound) got ready to play in one of three tries");

        MediaPreview ready = preview;

        assertEquals(320.0, onFx(() -> (double) ready.player().getMedia().getWidth()));

        onFx(() -> {
            ready.dispose();
            return null;
        });

    }

    private static boolean ready(MediaPlayer player) throws Exception {

        CountDownLatch done = new CountDownLatch(1);

        boolean[] ok = {false};

        onFx(() -> {

            if (player.getStatus() == MediaPlayer.Status.READY) {
                ok[0] = true;
                done.countDown();
            } else {
                player.statusProperty().addListener((observable, before, status) -> {
                    if (status == MediaPlayer.Status.READY) {
                        ok[0] = true;
                        done.countDown();
                    }
                });
                player.setOnError(done::countDown);
            }

            return null;

        });

        done.await(15, TimeUnit.SECONDS);

        return ok[0];

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
