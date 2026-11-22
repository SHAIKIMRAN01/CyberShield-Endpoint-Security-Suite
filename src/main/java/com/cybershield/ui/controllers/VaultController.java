package com.cybershield.ui.controllers;

import com.cybershield.security.SessionManager;
import com.cybershield.ui.Ui;
import com.cybershield.vault.VaultService;
import com.cybershield.vault.VaultService.Result;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;

/**
 * Controller for vault_setup.fxml, vault_unlock.fxml and vault_takeover.fxml.
 * Fields a screen doesn't have stay null. PBKDF2 runs off the JavaFX thread.
 */
public class VaultController {

    @FXML
    private PasswordField passwordField;

    @FXML
    private PasswordField confirmPasswordField;

    @FXML
    private ProgressBar strengthBar;

    @FXML
    private Label strengthLabel;

    @FXML
    private Label statusLabel;

    @FXML
    private Label sharedWarning;

    @FXML
    private Button actionButton;

    private final VaultService vaultService =
            new VaultService();

    // Set by the dashboard: what to open once the vault is unlocked.
    private Runnable onUnlocked = () -> { };

    // Set by the dashboard: the take-over screen's "start a new vault instead".
    private Runnable onStartNewVault = () -> { };

    public void setOnUnlocked(Runnable onUnlocked) {

        this.onUnlocked = onUnlocked;

    }

    public void setOnStartNewVault(Runnable onStartNewVault) {

        this.onStartNewVault = onStartNewVault;

    }

    /** On the set-up screen, after the user chose not to take over the shared vault. */
    public void warnAboutSharedVault() {

        if (sharedWarning != null) {
            sharedWarning.setVisible(true);
            sharedWarning.setManaged(true);
        }

    }

    @FXML
    public void initialize() {

        if (strengthBar != null) {
            passwordField.textProperty().addListener((observable, old, password) ->
                    Ui.strength(strengthBar, strengthLabel, password));
        }

        // The master password can be typed with the mouse, out of reach of keyloggers.
        com.cybershield.ui.OnScreenKeyboard.attach(passwordField);

        if (confirmPasswordField != null) {
            com.cybershield.ui.OnScreenKeyboard.attach(confirmPasswordField);
        }

        // Let the user type straight away once the screen is shown.
        Platform.runLater(passwordField::requestFocus);

    }

    @FXML
    private void createVault() {

        String password = passwordField.getText();

        String confirm = confirmPasswordField.getText();

        if (password.isBlank() || confirm.isBlank()) {
            Ui.message(statusLabel, "Fill in both fields.", "bad");
            return;
        }

        if (!password.equals(confirm)) {
            Ui.message(statusLabel, "The two passwords don't match.", "bad");
            confirmPasswordField.clear();
            confirmPasswordField.requestFocus();
            return;
        }

        int userId = SessionManager.currentUserId();

        run("Creating your vault…", () -> vaultService.createVault(userId, password), result -> {

            switch (result) {
                case OK -> done();
                case WEAK_PASSWORD -> Ui.message(statusLabel, "That master password is too weak. See the rules above.", "bad");
                case NOT_ALLOWED -> Ui.message(statusLabel, "This account already has a vault.", "bad");
                default -> Ui.message(statusLabel, "The vault couldn't be created. Details are in CyberShield's log file.", "bad");
            }

        });

    }

    @FXML
    private void unlockVault() {

        String password = passwordField.getText();

        if (password.isBlank()) {
            Ui.message(statusLabel, "Enter your master password.", "bad");
            return;
        }

        int userId = SessionManager.currentUserId();

        run("Unlocking…", () -> vaultService.unlock(userId, password), this::afterUnlock);

    }

    @FXML
    private void takeOverSharedVault() {

        String password = passwordField.getText();

        if (password.isBlank()) {
            Ui.message(statusLabel, "Enter the vault's master password.", "bad");
            return;
        }

        int userId = SessionManager.currentUserId();

        run("Moving the vault into your account…", () -> vaultService.takeOverSharedVault(userId, password), this::afterUnlock);

    }

    @FXML
    private void startNewVault() {

        onStartNewVault.run();

    }

    private void afterUnlock(Result result) {

        switch (result) {
            case OK -> done();
            case WRONG_PASSWORD -> {
                Ui.message(statusLabel, "Wrong master password.", "bad");
                passwordField.requestFocus();
            }
            case NOT_ALLOWED -> Ui.message(statusLabel, "This vault can't be opened from this account.", "bad");
            default -> Ui.message(statusLabel, "The vault couldn't be opened. Details are in CyberShield's log file.", "bad");
        }

    }

    private void done() {

        passwordField.clear();

        if (confirmPasswordField != null) {
            confirmPasswordField.clear();
        }

        onUnlocked.run();

    }

    /** Runs a vault operation in the background with the button disabled and a progress message. */
    private void run(String working, java.util.function.Supplier<Result> work, java.util.function.Consumer<Result> after) {

        actionButton.setDisable(true);

        Ui.message(statusLabel, working, null);

        Ui.background("vault", work, result -> {

            actionButton.setDisable(false);

            passwordField.clear();

            if (result != Result.OK) {
                Ui.message(statusLabel, "", null);
            }

            after.accept(result);

        }, error -> {

            actionButton.setDisable(false);

            Ui.message(statusLabel, "Something went wrong. Details are in CyberShield's log file.", "bad");

        });

    }

}
