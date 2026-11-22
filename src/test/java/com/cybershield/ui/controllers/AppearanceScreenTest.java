package com.cybershield.ui.controllers;

import com.cybershield.TestSupport;
import com.cybershield.dao.SettingsDAO;
import com.cybershield.services.AuthenticationService;
import com.cybershield.ui.Theme;
import com.cybershield.ui.Ui;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.layout.Background;
import javafx.scene.layout.Region;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.cybershield.TestSupport.ACCOUNT_PASSWORD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Light or dark: chosen in Settings, shown at once, kept for the account and the sign-in screen. */
class AppearanceScreenTest {

    private static boolean toolkitReady;

    @TempDir
    Path dir;

    private final AuthenticationService auth = new AuthenticationService();

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
    void backToLight() throws Exception {

        if (toolkitReady) {
            onFx(() -> {
                Theme.use(Theme.Mode.LIGHT);
                return null;
            });
        }

        auth.logout();

    }

    @Test
    void storedNamesAreReadSafely() {

        assertEquals(Theme.Mode.DARK, Theme.Mode.of("DARK"));
        assertEquals(Theme.Mode.DARK, Theme.Mode.of(" dark "));
        assertEquals(Theme.Mode.LIGHT, Theme.Mode.of("LIGHT"));
        assertEquals(Theme.Mode.LIGHT, Theme.Mode.of(null), "nothing chosen is light");
        assertEquals(Theme.Mode.LIGHT, Theme.Mode.of("purple"));

    }

    @Test
    void theDarkSheetGoesLastOnTheRootOnceAndComesOffAgain() throws Exception {

        assumeTrue(toolkitReady, "JavaFX can't start here");

        onFx(() -> {

            Parent login = Ui.load("/fxml/login.fxml").root();

            Scene scene = new Scene(login, 900, 600);

            int sheets = login.getStylesheets().size();

            Theme.use(Theme.Mode.DARK);

            Theme.style(scene);
            Theme.style(scene);

            assertEquals(sheets + 1, login.getStylesheets().size(), "added once, however often it is styled");
            assertTrue(login.getStylesheets().get(sheets).endsWith("/css/theme-dark.css"), "last, so it wins");

            login.applyCss();

            assertEquals(Color.web("#1D1611"), background((Region) login), "the page is dark");

            Theme.use(Theme.Mode.LIGHT);

            Theme.style(scene);

            assertEquals(sheets, login.getStylesheets().size());

            login.applyCss();

            assertEquals(Color.web("#FAF6F0"), background((Region) login), "and light again");

            return null;

        });

    }

    @Test
    void choosingInSettingsSwitchesAtOnceAndIsKept() throws Exception {

        assumeTrue(toolkitReady, "JavaFX can't start here");

        TestSupport.freshEnvironment(dir);

        int sam = TestSupport.newUser("sam");

        assertTrue(auth.login("sam", ACCOUNT_PASSWORD).ok());

        SettingsDAO settings = new SettingsDAO();

        Ui.Loaded<SettingsController> loaded = onFx(() -> {
            Ui.Loaded<SettingsController> made = Ui.load("/fxml/settings/settings.fxml");
            new Scene(new StackPane(made.root()), 1000, 680);
            return made;
        });

        Parent root = loaded.root();

        onFx(() -> {

            ((Button) root.lookup("#appearanceTab")).fire();

            assertTrue(root.lookup("#appearancePanel").isVisible());
            assertTrue(((RadioButton) root.lookup("#lightChoice")).isSelected(), "light to begin with");

            ((RadioButton) root.lookup("#darkChoice")).fire();

            return null;

        });

        assertEquals(Theme.Mode.DARK, Theme.current(), "on at once");
        assertEquals("DARK", settings.theme(sam), "kept for the account");
        assertEquals("DARK", settings.appTheme(), "and for the sign-in screen");
        assertTrue(onFx(() -> ((Label) root.lookup("#appearanceMessage")).getText()).startsWith("Dark theme on"));
        assertEquals(1, TestSupport.count("SELECT COUNT(*) FROM activity_log WHERE user_id = ? AND action = 'THEME_CHANGED'", sam));

        onFx(() -> {
            ((RadioButton) root.lookup("#lightChoice")).fire();
            return null;
        });

        assertEquals(Theme.Mode.LIGHT, Theme.current());
        assertEquals("LIGHT", settings.theme(sam));
        assertFalse(onFx(() -> ((Label) root.lookup("#appearanceMessage")).getText()).isEmpty());

    }

    private static Color background(Region region) {

        Background background = region.getBackground();

        return (Color) background.getFills().get(background.getFills().size() - 1).getFill();

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
