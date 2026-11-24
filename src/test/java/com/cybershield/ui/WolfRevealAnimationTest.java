package com.cybershield.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The wolf-in-sheep's-clothing reveal: it lays an overlay over its host, posed or animated. */
class WolfRevealAnimationTest {

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
    void aStillPoseIsAddedOverTheHost() throws Exception {
        assumeTrue(toolkitReady, "JavaFX can't start here");
        onFx(() -> {
            StackPane host = new StackPane();
            Node overlay = WolfRevealAnimation.addStill(host);
            assertEquals(1, host.getChildren().size());
            assertTrue(host.getChildren().contains(overlay));
            return null;
        });
    }

    @Test
    void playingAddsAnOverlayOnTop() throws Exception {
        assumeTrue(toolkitReady, "JavaFX can't start here");
        onFx(() -> {
            StackPane host = new StackPane();
            new Scene(host, 500, 400);
            WolfRevealAnimation.playOver(host);
            assertEquals(1, host.getChildren().size(), "the reveal sits over the screen while it plays");
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
