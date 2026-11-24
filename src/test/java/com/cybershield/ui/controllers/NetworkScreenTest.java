package com.cybershield.ui.controllers;

import com.cybershield.ui.Ui;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Network Analyzer screen loads and wires up. It does not read the real machine — that
 * happens only when Refresh is pressed, and the reader has its own tests. This checks the
 * screen opens in a sensible resting state.
 */
class NetworkScreenTest {

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
    void theScreenOpensReadyToRefresh() throws Exception {

        assumeTrue(toolkitReady, "JavaFX can't start here");

        Ui.Loaded<NetworkController> loaded = onFx(() -> {
            Ui.Loaded<NetworkController> made = Ui.load("/fxml/network/network.fxml");
            new Scene(made.root(), 1100, 700);
            return made;
        });

        assertNotNull(loaded.controller(), "the controller should be wired");
        Parent root = loaded.root();

        onFx(() -> {
            Button refresh = (Button) root.lookup("#refreshButton");
            assertNotNull(refresh);
            assertFalse(refresh.isDisabled(), "Refresh is ready");

            // progress strip and the DNS list stay hidden until there is something to show
            assertFalse(root.lookup("#progressBox").isVisible());
            assertFalse(root.lookup("#dnsBox").isVisible());

            TableView<?> table = (TableView<?>) root.lookup("#connectionsTable");
            assertTrue(table.getItems().isEmpty(), "no connections before a refresh");
            assertNotNull(table.getPlaceholder(), "an empty table explains itself");
            assertEquals(5, table.getColumns().size());

            TableView<?> dns = (TableView<?>) root.lookup("#dnsTable");
            assertEquals(2, dns.getColumns().size());

            loaded.controller().dispose();
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
