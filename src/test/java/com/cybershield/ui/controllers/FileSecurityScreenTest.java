package com.cybershield.ui.controllers;

import com.cybershield.TestSupport;
import com.cybershield.files.FileVaultService;
import com.cybershield.ui.Ui;
import com.cybershield.ui.viewer.FilePreview;
import com.cybershield.ui.viewer.JavaFxPlayback;
import com.cybershield.ui.viewer.MediaPreview;
import com.cybershield.ui.viewer.PreviewKind;
import com.cybershield.ui.viewer.Previews;
import com.cybershield.vault.VaultService;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.media.MediaPlayer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.cybershield.TestSupport.MASTER_PASSWORD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * File Security parts that were only tried by hand before: the tray path that drag and
 * drop and the Add dialogs feed, the full-screen copy of a preview, and an MP4 video
 * played from memory. The operating system's own drag and drop, file dialogs and the
 * full-screen window itself still need a person at the keyboard.
 */
class FileSecurityScreenTest {

    private static final FileVaultService.JobListener QUIET = new FileVaultService.JobListener() { };

    @TempDir
    static Path tempDir;

    private static boolean toolkitReady;

    private static Path store;

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

        store = TestSupport.freshEnvironment(tempDir.resolve("app"));

        VAULTS.createVault(TestSupport.newUser("alex"), MASTER_PASSWORD);

    }

    @AfterAll
    static void lock() {
        VAULTS.lockVault();
    }

    @Test
    void droppedOrPickedPathsStackInTheTrayNewestFirst() throws Exception {

        assumeTrue(toolkitReady, "JavaFX can't start here");

        Path folder = Files.createDirectories(tempDir.resolve("picked").resolve("Holiday"));
        Files.writeString(folder.resolve("a.txt"), "a");
        Path single = Files.writeString(tempDir.resolve("picked").resolve("single.txt"), "b");
        Path other = Files.writeString(Files.createDirectories(tempDir.resolve("elsewhere")).resolve("c.txt"), "c");

        Ui.Loaded<FileSecurityController> screen = onFx(() -> Ui.load("/fxml/files/file_security.fxml"));

        Scene scene = onFx(() -> new Scene(screen.root(), 1000, 700));

        // What a drop from Explorer or the Add files dialog hands over: several paths at once.
        onFx(() -> {
            screen.controller().addToSelection(List.of(folder, single));
            screen.controller().addToSelection(List.of(other));
            return null;
        });

        @SuppressWarnings("unchecked")
        ListView<Path> tray = (ListView<Path>) scene.lookup("#trayList");

        assertEquals(List.of(other, single, folder), onFx(() -> List.copyOf(tray.getItems())), "newest picks on top");

        onFx(() -> {
            screen.controller().addToSelection(List.of(folder.resolve("a.txt")));
            return null;
        });

        assertEquals(3, onFx(() -> tray.getItems().size()), "a file inside a picked folder isn't added twice");

        onFx(() -> {
            screen.controller().dispose();
            return null;
        });

    }

    @Test
    void textPicturesAndPdfsHaveAFullScreenCopy() throws Exception {

        assumeTrue(toolkitReady, "JavaFX can't start here");

        Path folder = Files.createDirectories(tempDir.resolve("for-full-screen"));
        Files.writeString(folder.resolve("notes.txt"), "a note" + System.lineSeparator() + "second line");
        Files.write(folder.resolve("picture.png"), com.cybershield.files.TestMediaAccess.png());
        Files.write(folder.resolve("pages.pdf"), com.cybershield.files.TestMediaAccess.pdf());

        FileVaultService service = new FileVaultService(store);

        service.protect(service.scan(List.of(folder), QUIET), QUIET);

        var item = service.listItems().stream().filter(i -> i.name().equals("for-full-screen")).findFirst().orElseThrow();

        for (var file : service.listFiles(item)) {

            Previews.Prepared prepared = Previews.prepare(PreviewKind.of(file.extension()), service, file);

            FilePreview preview = onFx(prepared::create);

            FilePreview copy = onFx(preview::fullScreenCopy);

            assertNotNull(copy, file.name() + " can be shown full screen");

            assertNotNull(onFx(copy::view));

            onFx(() -> {
                copy.dispose();
                preview.dispose();
                return null;
            });

        }

    }

    @Test
    void anMp4VideoPlaysFromTheEncryptedStore() throws Exception {

        assumeTrue(toolkitReady, "JavaFX can't start here");

        // Only where JavaFX can play an H.264 file at all: build servers may have no video decoders.
        assumeTrue(JavaFxPlayback.canPlayVideo(), "JavaFX can't play H.264 video on this computer");

        // An original 1.6-second H.264 clip (video only, 320 x 240) made for this test.
        Path video = Files.createDirectories(tempDir.resolve("videos")).resolve("colours.mp4");

        try (var in = FileSecurityScreenTest.class.getResourceAsStream("/media/colours.mp4")) {
            Files.copy(in, video);
        }

        FileVaultService service = new FileVaultService(store);

        service.protect(service.scan(List.of(video.getParent()), QUIET), QUIET);

        var item = service.listItems().stream().filter(i -> i.name().equals("videos")).findFirst().orElseThrow();

        var file = service.listFiles(item).get(0);

        // JavaFX's own decoders on Windows (Media Foundation) sometimes fail to start a video
        // that is fine: on the development PC about one run in four, with "media invalid".
        // The bytes handed over are the same every time, so a fault of ours fails every try;
        // up to three fresh tries keep this test about our code, not that decoder.
        MediaPreview preview = null;

        List<String> problems = new java.util.ArrayList<>();

        for (int attempt = 1; attempt <= 3 && preview == null; attempt++) {

            // Each try gets its own copy: a preview wipes its bytes when it is closed.
            ByteBuffer buffer = service.readFileForPlayback(file, 512L << 20);

            CountDownLatch ready = new CountDownLatch(1);

            AtomicReference<String> problem = new AtomicReference<>();

            MediaPreview opened = onFx(() -> {

                MediaPreview made = MediaPreview.open(buffer, "mp4");

                MediaPlayer player = made.player();

                player.setOnReady(ready::countDown);

                player.setOnError(() -> {
                    problem.set(String.valueOf(player.getError()));
                    ready.countDown();
                });

                return made;

            });

            boolean inTime = ready.await(20, TimeUnit.SECONDS);

            if (inTime && problem.get() == null) {

                preview = opened;

            } else {

                problems.add(inTime ? problem.get() : "not ready after 20 seconds");

                onFx(() -> {
                    opened.dispose();
                    return null;
                });

            }

        }

        assertNotNull(preview, "JavaFX could play the decrypted MP4 in one of three tries: " + problems);

        MediaPreview playing = preview;

        double[] facts = onFx(() -> new double[] {
                playing.player().getMedia().getWidth(),
                playing.player().getMedia().getHeight(),
                playing.player().getMedia().getDuration().toSeconds()
        });

        assertEquals(320, facts[0], "its picture is 320 pixels wide");

        assertEquals(240, facts[1], "and 240 high");

        assertTrue(facts[2] > 0, "it has a length");

        assertNotNull(onFx(playing::fullScreenCopy), "videos can be shown full screen");

        onFx(() -> {
            playing.dispose();
            return null;
        });

        assertTrue(Files.exists(store.resolve(file.id())), "the video stays protected");

        assertTrue(!Files.exists(video), "the original was removed when it was protected");

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
