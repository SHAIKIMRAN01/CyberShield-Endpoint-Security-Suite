package com.cybershield.app;

import com.cybershield.config.AppConfig;
import com.cybershield.database.DatabaseInitializer;
import com.cybershield.logger.AppLogger;
import com.cybershield.ui.Ui;
import com.cybershield.ui.controllers.LoginController;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

public class Launcher extends Application {

    @Override
    public void start(Stage stage) {

        AppLogger.info("----------------------------------------");
        AppLogger.info(AppConfig.APP_NAME);
        AppLogger.info("Version : " + AppConfig.VERSION);
        AppLogger.info("----------------------------------------");

        try {

            DatabaseInitializer.initialize();

            // Sign-ins and activity are kept for 30 days.
            com.cybershield.services.ActivityLog.purgeOld();

        } catch (IllegalStateException e) {

            AppLogger.error(e.getMessage());

            Alert alert = new Alert(Alert.AlertType.ERROR,
                    e.getMessage() + "\n\nCyberShield can't start without its database.");

            alert.setHeaderText("The database couldn't be opened");

            alert.showAndWait();

            Platform.exit();

            return;

        }

        stage.setTitle(AppConfig.APP_NAME);

        stage.getIcons().setAll(com.cybershield.ui.Logo.windowIcons());

        stage.setMinWidth(LoginController.MIN_WIDTH);
        stage.setMinHeight(LoginController.MIN_HEIGHT);

        // The key opens the treasure chest, then the sign-in screen appears (-Dcybershield.noIntro=true skips it).
        if (Boolean.getBoolean("cybershield.noIntro")) {

            stage.setScene(new Scene(Ui.load("/fxml/login.fxml").root()));

            stage.show();

        } else {

            com.cybershield.ui.OpeningAnimation.playIn(stage, () -> new Scene(Ui.load("/fxml/login.fxml").root()));

        }

        AppLogger.success("JavaFX Started Successfully.");

    }

    /** Closing the window ends the sign-in: keep its report while the vault key is still open. */
    @Override
    public void stop() {

        if (com.cybershield.security.SessionManager.isLoggedIn() && com.cybershield.vault.VaultSession.isUnlocked()) {

            try {
                new com.cybershield.reports.SessionReports().saveCurrent(false);
            } catch (java.sql.SQLException | java.io.IOException | RuntimeException e) {
                AppLogger.error("Couldn't keep this sign-in's report: " + e.getMessage());
            }

        }

    }

}
