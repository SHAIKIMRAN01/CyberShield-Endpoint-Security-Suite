package com.cybershield.ui;

import javafx.application.Platform;
import javafx.scene.layout.StackPane;
import javafx.scene.transform.Scale;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The File Security drawer reveal: it adds a top-anchored vertical scale to the screen. */
class DrawerAnimationTest {

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
    void openingAddsAVerticalScaleAnchoredAtTheTop() throws Exception {
        assumeTrue(toolkitReady, "JavaFX can't start here");
        onFx(() -> {
            StackPane view = new StackPane();
            DrawerAnimation.openDownward(view);
            assertEquals(1, view.getTransforms().size());
            Scale scale = (Scale) view.getTransforms().get(0);
            assertEquals(0, scale.getPivotY(), 0.0001, "it opens from the top edge");
            assertTrue(scale.getY() < 1, "it starts closed and grows open");
            return null;
        });
    }

    @Test
    void theHalfOpenPoseIsHalfHeight() throws Exception {
        assumeTrue(toolkitReady, "JavaFX can't start here");
        onFx(() -> {
            StackPane view = new StackPane();
            Scale scale = DrawerAnimation.poseHalfOpen(view);
            assertEquals(0.5, scale.getY(), 0.0001);
            return null;
        });
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
        return result.get(20, TimeUnit.SECONDS);
    }

}
