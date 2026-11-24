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
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The per-screen flourishes (chest, tome, maze, rat, cat): each lays one overlay over its host. */
class ScreenFlourishTest {

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
    void eachStillAddsExactlyOneOverlay() throws Exception {
        assumeTrue(toolkitReady, "JavaFX can't start here");
        assertStill(ChestLockAnimation::addStill);
        assertStill(CursedTomeAnimation::addStill);
        assertStill(MazeAnimation::addStill);
        assertStill(RatDetectiveAnimation::addStill);
        assertStill(CatAnimation::addStill);
    }

    @Test
    void eachPlaysWithoutErrorAndSitsOverTheScreen() throws Exception {
        assumeTrue(toolkitReady, "JavaFX can't start here");
        onFx(() -> {
            play(ChestLockAnimation::playOver);
            play(CursedTomeAnimation::playOver);
            play(MazeAnimation::playOver);
            play(RatDetectiveAnimation::playOver);
            play(CatAnimation::playOver);
            return null;
        });
    }

    private void assertStill(Function<StackPane, Node> still) throws Exception {
        onFx(() -> {
            StackPane host = new StackPane();
            Node overlay = still.apply(host);
            assertEquals(1, host.getChildren().size());
            assertTrue(host.getChildren().contains(overlay));
            return null;
        });
    }

    private void play(java.util.function.Consumer<StackPane> playOver) {
        StackPane host = new StackPane();
        new Scene(host, 600, 450);
        playOver.accept(host);
        assertEquals(1, host.getChildren().size(), "the flourish sits over the screen while it plays");
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
