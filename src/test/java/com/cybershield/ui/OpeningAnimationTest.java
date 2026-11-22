package com.cybershield.ui;

import javafx.application.Platform;
import javafx.scene.image.Image;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The wolf-and-chest logo at every size, and the opening animation handing over to the app. */
class OpeningAnimationTest {

    private static boolean toolkitReady;

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

    @Test
    void theLogoMakesWindowIconsAndAnSvg() throws Exception {

        assumeTrue(toolkitReady, "JavaFX can't start here");

        List<Image> icons = onFx(Logo::windowIcons);

        assertEquals(List.of(16.0, 24.0, 32.0, 48.0, 64.0, 128.0, 256.0), icons.stream().map(Image::getWidth).toList());

        Image big = icons.get(icons.size() - 1);

        int middle = onFx(() -> big.getPixelReader().getArgb(128, 190));

        assertTrue((middle >>> 24) == 0xFF, "the chest is drawn solid in the middle");

        int corner = onFx(() -> big.getPixelReader().getArgb(2, 2));

        assertEquals(0, corner >>> 24, "the corners outside the round badge are clear");

        assertTrue(onFx(Logo::windowIcons) == icons, "made once and kept");

        String svg = Logo.svg();

        assertTrue(svg.startsWith("<svg") && svg.contains("viewBox=\"0 0 256 256\""));
        assertTrue(svg.split("<path").length > 30, "every part of the drawing is there");

        LogoView view = onFx(() -> {
            LogoView made = new LogoView();
            made.setSize(104);
            return made;
        });

        assertEquals(104, view.getPrefWidth());

    }

    @Test
    void theAnimationHandsOverOnceWhetherItFinishesOrIsSkipped() throws Exception {

        assumeTrue(toolkitReady, "JavaFX can't start here");

        AtomicInteger handedOver = new AtomicInteger();

        CountDownLatch done = new CountDownLatch(1);

        OpeningAnimation played = onFx(() -> {
            OpeningAnimation intro = new OpeningAnimation();
            intro.animation().setRate(12);
            intro.play(() -> {
                handedOver.incrementAndGet();
                done.countDown();
            });
            return intro;
        });

        double seconds = played.animation().getTotalDuration().toSeconds();

        assertTrue(seconds > 3 && seconds < 4.5, "about three and a half seconds: " + seconds);

        assertTrue(done.await(5, TimeUnit.SECONDS), "played to the end");

        onFx(() -> {
            played.skip();
            return null;
        });

        assertEquals(1, handedOver.get(), "a skip after the end doesn't hand over twice");

        AtomicInteger skipped = new AtomicInteger();

        onFx(() -> {
            OpeningAnimation intro = new OpeningAnimation();
            intro.play(skipped::incrementAndGet);
            intro.skip();
            intro.skip();
            return null;
        });

        assertEquals(1, skipped.get(), "skipping hands over at once, and only once");

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
