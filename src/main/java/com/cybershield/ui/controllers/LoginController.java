package com.cybershield.ui.controllers;

import com.cybershield.services.AuthenticationService;
import com.cybershield.services.AuthenticationService.LoginResult;
import com.cybershield.ui.Ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Sign-in. Mistakes show under the form; Enter signs in; repeated failures make the form wait. */
public class LoginController {

    public static final double MIN_WIDTH = 860;

    public static final double MIN_HEIGHT = 560;

    @FXML
    private TextField usernameField;

    @FXML
    private PasswordField passwordField;

    @FXML
    private Label messageLabel;

    @FXML
    private Button signInButton;

    private final AuthenticationService auth = new AuthenticationService();

    private Timeline countdown;

    @FXML
    public void initialize() {

        // Type the password with the mouse, out of reach of keyloggers.
        com.cybershield.ui.OnScreenKeyboard.attach(passwordField);

        Platform.runLater(usernameField::requestFocus);

    }

    /** Shown after registration: the new username is filled in. */
    public void showAccountCreated(String username, String message) {

        usernameField.setText(username);

        Ui.message(messageLabel, message, "good");

        Platform.runLater(passwordField::requestFocus);

    }

    @FXML
    private void login() {

        if (countdown != null) {
            return;
        }

        String username = usernameField.getText().trim();

        String password = passwordField.getText();

        if (username.isEmpty() || password.isEmpty()) {

            Ui.message(messageLabel, "Enter your username and password.", "bad");

            (username.isEmpty() ? usernameField : passwordField).requestFocus();

            return;

        }

        signInButton.setDisable(true);

        Ui.message(messageLabel, "Signing in…", null);

        // BCrypt is deliberately slow, so check the password off the JavaFX thread.
        Ui.background("sign-in", () -> auth.login(username, password), this::handle, error -> {

            signInButton.setDisable(false);

            Ui.message(messageLabel, "Signing in failed. See the console for details.", "bad");

        });

    }

    private void handle(LoginResult result) {

        passwordField.clear();

        switch (result.status()) {

            case SUCCESS -> openDashboard();

            case INVALID -> {

                if (result.waitSeconds() > 0) {
                    startWait("Wrong username or password.", result.waitSeconds());
                } else {
                    signInButton.setDisable(false);
                    Ui.message(messageLabel, "Wrong username or password.", "bad");
                    passwordField.requestFocus();
                }

            }

            case WAIT -> startWait("Too many failed attempts.", result.waitSeconds());

            case DISABLED -> {
                signInButton.setDisable(false);
                Ui.message(messageLabel, "This account is turned off.", "bad");
            }

            case ERROR -> {
                signInButton.setDisable(false);
                Ui.message(messageLabel, "Couldn't sign in because the database had a problem. See the console.", "bad");
            }

        }

    }

    /** Keeps Sign in disabled and counts down until the account may try again. */
    private void startWait(String reason, int seconds) {

        int[] left = {seconds};

        signInButton.setDisable(true);

        Ui.message(messageLabel, reason + " Try again in " + format(left[0]) + ".", "warn");

        countdown = new Timeline(new KeyFrame(Duration.seconds(1), event -> {

            left[0]--;

            if (left[0] > 0) {

                Ui.message(messageLabel, reason + " Try again in " + format(left[0]) + ".", "warn");

            } else {

                countdown.stop();
                countdown = null;

                signInButton.setDisable(false);

                Ui.message(messageLabel, "You can try again now.", null);

                passwordField.requestFocus();

            }

        }));

        countdown.setCycleCount(Animation.INDEFINITE);

        countdown.play();

    }

    private static String format(int seconds) {

        return seconds < 60 ? seconds + " s" : (seconds / 60) + " min " + (seconds % 60) + " s";

    }

    @FXML
    private void openRegister() {

        stopCountdown();

        Ui.Loaded<RegisterController> register = Ui.load("/fxml/register.fxml");

        usernameField.getScene().setRoot(register.root());

    }

    private void openDashboard() {

        stopCountdown();

        Ui.Loaded<DashboardController> dashboard = Ui.load("/fxml/dashboard.fxml");

        Stage stage = (Stage) usernameField.getScene().getWindow();

        stage.setScene(new Scene(dashboard.root()));

        stage.setMinWidth(DashboardController.MIN_WIDTH);
        stage.setMinHeight(DashboardController.MIN_HEIGHT);

        if (!stage.isMaximized()) {
            stage.sizeToScene();
            stage.centerOnScreen();
        }

    }

    private void stopCountdown() {

        if (countdown != null) {
            countdown.stop();
            countdown = null;
        }

    }

}
