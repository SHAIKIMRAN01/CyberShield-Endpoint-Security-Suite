package com.cybershield.ui.controllers;

import com.cybershield.models.User;
import com.cybershield.services.AuthenticationService;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

public class LoginController {

    @FXML
    private TextField usernameField;

    @FXML
    private PasswordField passwordField;

    @FXML
    private void login() {

        AuthenticationService auth =
                new AuthenticationService();

        User user =
                auth.login(
                        usernameField.getText(),
                        passwordField.getText()
                );

        if (user == null) {

            System.out.println("Login Failed");
            return;

        }

        try {

            FXMLLoader loader =
                    new FXMLLoader(
                            getClass().getResource("/fxml/dashboard.fxml")
                    );

            Scene scene =
                    new Scene(loader.load());

            Stage stage =
                    (Stage) usernameField
                            .getScene()
                            .getWindow();

            stage.setScene(scene);

            // The dashboard is much bigger than the login form.
            stage.sizeToScene();

            stage.centerOnScreen();

        } catch (Exception e) {

            e.printStackTrace();

        }

    }

}