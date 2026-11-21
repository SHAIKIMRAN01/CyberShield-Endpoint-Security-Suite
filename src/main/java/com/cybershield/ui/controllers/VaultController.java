package com.cybershield.ui.controllers;

import com.cybershield.utils.ValidationUtil;
import com.cybershield.vault.VaultService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;

/**
 * Controller for both vault_setup.fxml and vault_unlock.fxml.
 * The unlock screen has no confirmPasswordField, so it stays null there.
 */
public class VaultController {

    @FXML
    private PasswordField passwordField;

    @FXML
    private PasswordField confirmPasswordField;

    @FXML
    private Label statusLabel;

    private final VaultService vaultService =
            new VaultService();

    // Set by the dashboard so it can open the Password Vault once unlocked.
    private Runnable onUnlocked = () -> { };

    public void setOnUnlocked(Runnable onUnlocked) {

        this.onUnlocked = onUnlocked;

    }

    @FXML
    public void initialize() {

        // Let the user type straight away once the screen is shown.
        Platform.runLater(passwordField::requestFocus);

    }

    @FXML
    private void createVault() {

        String password = passwordField.getText();
        String confirm = confirmPasswordField.getText();

        if (password.isBlank() || confirm.isBlank()) {

            statusLabel.setText("Fill all fields.");
            return;

        }

        if (!password.equals(confirm)) {

            statusLabel.setText("Passwords do not match.");
            return;

        }

        if (!ValidationUtil.isValidMasterPassword(password)) {

            statusLabel.setText("That master password is too weak. See the rules above.");
            return;

        }

        if (!vaultService.createVault(password)) {

            statusLabel.setText("Could not create the vault. It may already exist.");
            return;

        }

        passwordField.clear();
        confirmPasswordField.clear();

        if (vaultService.unlockVault(password)) {

            onUnlocked.run();

        } else {

            statusLabel.setText("Vault created. Open Password Vault again to unlock it.");

        }

    }

    @FXML
    private void unlockVault() {

        String password = passwordField.getText();

        if (password.isBlank()) {

            statusLabel.setText("Enter your master password.");
            return;

        }

        boolean unlocked = vaultService.unlockVault(password);

        passwordField.clear();

        if (!unlocked) {

            statusLabel.setText("Wrong master password.");
            return;

        }

        onUnlocked.run();

    }

}
