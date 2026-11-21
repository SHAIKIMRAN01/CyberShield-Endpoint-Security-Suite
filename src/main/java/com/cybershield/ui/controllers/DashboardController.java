package com.cybershield.ui.controllers;

import com.cybershield.files.FileVaultService;
import com.cybershield.logger.AppLogger;
import com.cybershield.security.SessionManager;
import com.cybershield.vault.AutoLock;
import com.cybershield.vault.VaultService;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.function.BooleanSupplier;

public class DashboardController {

    // Encrypting or restoring files counts as activity, so auto-lock waits for it.
    private static final BooleanSupplier FILE_JOBS = FileVaultService::isJobRunning;

    @FXML
    private Label welcomeLabel;

    @FXML
    private StackPane contentPane;

    private final VaultService vaultService =
            new VaultService();

    // The controller of the screen in the content area, and how to reopen it.
    private Object currentScreen;

    private Runnable currentReopen;

    @FXML
    public void initialize() {

        if (SessionManager.isLoggedIn()) {

            welcomeLabel.setText(
                    "Welcome, "
                            + SessionManager
                            .getCurrentUser()
                            .getUsername()
            );

        } else {

            welcomeLabel.setText("Guest");

        }

        AutoLock autoLock = AutoLock.getInstance();

        autoLock.keepOpenWhile(FILE_JOBS);

        autoLock.start(AutoLock.configuredTimeoutMillis(), this::onAutoLocked);

        contentPane.sceneProperty().addListener((observable, oldScene, scene) -> autoLock.watch(scene));

        loadDashboard();

    }

    private void loadDashboard() {

        Label label = new Label();

        label.setText(
                "CyberShield Dashboard\n\nSelect a module from the left."
        );

        replaceContent(label, null);

    }

    /** After the vault locked itself, swap an open vault screen for the unlock screen. */
    private void onAutoLocked() {

        if (currentScreen instanceof LockableScreen && currentReopen != null) {

            currentReopen.run();

        }

    }

    @FXML
    private void openPasswordVault() {

        openVaultScreen("/fxml/password/password_vault.fxml", this::openPasswordVault);

    }

    @FXML
    private void openFileSecurity() {

        openVaultScreen("/fxml/files/file_security.fxml", this::openFileSecurity);

    }

    /**
     * Opens a screen that needs the vault. Asks for a new master password first
     * if there is no vault yet, or for the master password if it is locked.
     *
     * @param reopen opens the same screen again: used after unlocking, and after
     *               locking (which then shows the unlock screen)
     */
    private void openVaultScreen(String fxml, Runnable reopen) {

        if (!vaultService.vaultExists()) {

            showVaultPrompt("/fxml/vault/vault_setup.fxml", reopen);

        } else if (!vaultService.isUnlocked()) {

            showVaultPrompt("/fxml/vault/vault_unlock.fxml", reopen);

        } else {

            Object controller = showView(fxml);

            if (controller instanceof LockableScreen screen) {

                screen.setOnLocked(reopen);

                currentReopen = reopen;

            }

        }

    }

    private void showVaultPrompt(String fxml, Runnable afterUnlock) {

        Object controller = showView(fxml);

        if (controller instanceof VaultController vault) {

            vault.setOnUnlocked(afterUnlock);

        }

    }

    /**
     * Loads an FXML view into the content area and returns its
     * controller, or null if the view could not be loaded.
     */
    private <T> T showView(String fxml) {

        try {

            FXMLLoader loader =
                    new FXMLLoader(getClass().getResource(fxml));

            Parent view = loader.load();

            T controller = loader.getController();

            replaceContent(view, controller);

            return controller;

        } catch (Exception e) {

            AppLogger.error("Could not open " + fxml + ": " + e.getMessage());

            e.printStackTrace();

            replaceContent(new Label("Could not open this screen. Check the console for details."), null);

            return null;

        }

    }

    /** Shows a new screen, first letting the old one drop decrypted data and close its windows. */
    private void replaceContent(Node view, Object controller) {

        disposeCurrentScreen();

        contentPane.getChildren().setAll(view);

        currentScreen = controller;

        currentReopen = null;

    }

    private void disposeCurrentScreen() {

        if (currentScreen instanceof LockableScreen screen) {
            screen.dispose();
        }

        currentScreen = null;

    }

    @FXML
    private void logout() {

        disposeCurrentScreen();

        AutoLock.getInstance().stop();

        vaultService.lockVault();

        SessionManager.logout();

        try {

            Parent login = FXMLLoader.load(
                    getClass().getResource("/fxml/login.fxml")
            );

            Stage stage =
                    (Stage) contentPane.getScene().getWindow();

            stage.setScene(new Scene(login));

            stage.sizeToScene();

            stage.centerOnScreen();

            AppLogger.info("User logged out.");

        } catch (Exception e) {

            AppLogger.error("Could not return to the login screen: " + e.getMessage());

            e.printStackTrace();

        }

    }

    @FXML
    private void openDashboard() {

        loadDashboard();

    }

@FXML
private void openSecureNotes() {

    System.out.println("Secure Notes");

}

@FXML
private void openEncryptionCenter() {

    System.out.println("Encryption Center");

}

@FXML
private void openSteganography() {

    System.out.println("Steganography");

}

@FXML
private void openPrivacyTools() {

    System.out.println("Privacy Tools");

}

@FXML
private void openReports() {

    System.out.println("Reports");

}

@FXML
private void openSettings() {

    System.out.println("Settings");

}

}
