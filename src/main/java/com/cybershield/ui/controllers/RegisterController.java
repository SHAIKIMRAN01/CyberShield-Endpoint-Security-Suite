package com.cybershield.ui.controllers;

import com.cybershield.services.AuthenticationService;
import com.cybershield.ui.Ui;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;

/** Registration, using AuthenticationService.register and the ValidationUtil rules. */
public class RegisterController {

    @FXML
    private TextField usernameField;

    @FXML
    private TextField emailField;

    @FXML
    private PasswordField passwordField;

    @FXML
    private PasswordField confirmField;

    @FXML
    private ProgressBar strengthBar;

    @FXML
    private Label strengthLabel;

    @FXML
    private Label messageLabel;

    @FXML
    private Button createButton;

    private final AuthenticationService auth = new AuthenticationService();

    @FXML
    public void initialize() {

        passwordField.textProperty().addListener((observable, old, password) ->
                Ui.strength(strengthBar, strengthLabel, password));

        Platform.runLater(usernameField::requestFocus);

    }

    @FXML
    private void createAccount() {

        String username = usernameField.getText().trim();

        String email = emailField.getText().trim();

        String password = passwordField.getText();

        if (username.isEmpty() || email.isEmpty() || password.isEmpty() || confirmField.getText().isEmpty()) {
            Ui.message(messageLabel, "Fill in every field.", "bad");
            return;
        }

        if (!password.equals(confirmField.getText())) {
            Ui.message(messageLabel, "The two passwords don't match.", "bad");
            confirmField.clear();
            confirmField.requestFocus();
            return;
        }

        createButton.setDisable(true);

        Ui.message(messageLabel, "Creating your account…", null);

        Ui.background("register", () -> auth.register(username, email, password), result -> {

            createButton.setDisable(false);

            if (!result.ok()) {
                Ui.message(messageLabel, result.message(), "bad");
                return;
            }

            Ui.Loaded<LoginController> login = Ui.load("/fxml/login.fxml");

            login.controller().showAccountCreated(username, result.message());

            usernameField.getScene().setRoot(login.root());

        }, error -> {

            createButton.setDisable(false);

            Ui.message(messageLabel, "The account couldn't be created. See the console for details.", "bad");

        });

    }

    @FXML
    private void backToSignIn() {

        usernameField.getScene().setRoot(Ui.<LoginController>load("/fxml/login.fxml").root());

    }

}
