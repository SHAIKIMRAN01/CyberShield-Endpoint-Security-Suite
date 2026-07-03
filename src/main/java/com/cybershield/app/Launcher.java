package com.cybershield.app;

import com.cybershield.config.AppConfig;
import com.cybershield.database.DatabaseInitializer;
import com.cybershield.logger.AppLogger;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class Launcher extends Application {

    @Override
    public void start(Stage stage) throws Exception {

        AppLogger.info("----------------------------------------");
        AppLogger.info(AppConfig.APP_NAME);
        AppLogger.info("Version : " + AppConfig.VERSION);
        AppLogger.info("----------------------------------------");

        DatabaseInitializer.initialize();

        FXMLLoader loader =
                new FXMLLoader(
                        getClass().getResource("/fxml/login.fxml")
                );

        Scene scene =
                new Scene(loader.load());

        stage.setTitle(AppConfig.APP_NAME);

        stage.setScene(scene);

        stage.setResizable(false);

        stage.show();

        AppLogger.success("JavaFX Started Successfully.");

    }

}