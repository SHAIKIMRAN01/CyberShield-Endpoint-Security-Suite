package com.cybershield.ui.viewer;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The photo viewer: fitting doesn't shake, pictures turn, and phone photos come upright. */
class ImagePreviewTest {

    private static boolean toolkitReady;

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

    @Test
    void fittingABigPhotoSettlesInsteadOfShaking() throws Exception {

        assumeTrue(toolkitReady);

        // A 4000 x 3000 photo fits at about 14 % in this window: the size that used to shake.
        ImagePreview preview = onFx(() -> new ImagePreview(new WritableImage(4000, 3000)));

        BorderPane root = onFx(() -> new BorderPane(preview.view(), null, null, new HBox(preview.controls().toArray(new javafx.scene.Node[0])), null));

        Scene scene = onFx(() -> new Scene(root, 600, 480));

        List<Double> scales = new ArrayList<>();

        for (int pass = 0; pass < 12; pass++) {
            scales.add(onFx(() -> {
                root.applyCss();
                root.layout();
                return preview.scale();
            }));
        }

        assertTrue(scales.get(11) < 0.2, "fitted, not full size: " + scales.get(11));

        assertEquals(scales.get(6), scales.get(11), 1e-9, "the fitted size stops changing: " + scales);

        ScrollPane scroll = (ScrollPane) preview.view();

        assertEquals(ScrollPane.ScrollBarPolicy.NEVER, scroll.getVbarPolicy(), "no scroll bars while fitting");

        assertTrue(scene.getRoot() == root);

    }

    @Test
    void turningUsesTheTurnedShapeToFit() throws Exception {

        assumeTrue(toolkitReady);

        ImagePreview preview = onFx(() -> new ImagePreview(new WritableImage(1800, 300)));

        BorderPane root = onFx(() -> new BorderPane(preview.view()));

        onFx(() -> new Scene(root, 900, 600));

        double wide = onFx(() -> {
            root.applyCss();
            root.layout();
            return preview.scale();
        });

        Button turnRight = (Button) preview.controls().get(1);

        double tall = onFx(() -> {
            turnRight.fire();
            root.applyCss();
            root.layout();
            return preview.scale();
        });

        assertEquals(90, preview.degrees());

        assertEquals(0.5, wide, 0.02, "1800 wide fits 900 at half size");

        assertEquals(1 / 3.0, tall, 0.02, "turned, it is 1800 tall and fits 600 at a third");

    }

    @Test
    void phonePhotosAreTurnedFromTheirExifOrientation() {

        assertEquals(1, ExifOrientation.read(new byte[] {1, 2, 3}), "not a JPEG");

        for (int orientation = 1; orientation <= 8; orientation++) {
            assertEquals(orientation, ExifOrientation.read(jpegWithOrientation(orientation, true)));
            assertEquals(orientation, ExifOrientation.read(jpegWithOrientation(orientation, false)));
        }

        assertEquals(new ExifOrientation.Turn(90, false), ExifOrientation.of(6));
        assertEquals(new ExifOrientation.Turn(270, false), ExifOrientation.of(8));
        assertEquals(new ExifOrientation.Turn(180, false), ExifOrientation.of(3));
        assertEquals(ExifOrientation.Turn.NONE, ExifOrientation.of(1));

    }

    /** The start of a JPEG with an APP1 EXIF block holding one orientation entry. */
    private static byte[] jpegWithOrientation(int orientation, boolean littleEndian) {

        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(64);

        b.put((byte) 0xFF).put((byte) 0xD8);                 // SOI
        b.put((byte) 0xFF).put((byte) 0xE1);                 // APP1
        b.putShort((short) 36);                              // length of the APP1 block
        b.put("Exif".getBytes()).put((byte) 0).put((byte) 0);

        java.nio.ByteOrder order = littleEndian ? java.nio.ByteOrder.LITTLE_ENDIAN : java.nio.ByteOrder.BIG_ENDIAN;

        int tiff = b.position();
        b.put(littleEndian ? (byte) 'I' : (byte) 'M').put(littleEndian ? (byte) 'I' : (byte) 'M');
        b.order(order).putShort((short) 42).putInt(8);       // TIFF header, first directory at 8
        b.putShort((short) 1);                               // one entry
        b.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        b.putInt(0);
        b.order(java.nio.ByteOrder.BIG_ENDIAN);
        b.put((byte) 0xFF).put((byte) 0xDA);                 // start of scan

        assertTrue(b.position() - tiff > 0);

        return java.util.Arrays.copyOf(b.array(), b.position());

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
