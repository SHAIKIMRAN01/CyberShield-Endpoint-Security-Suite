package com.cybershield.ui.controllers;

import com.cybershield.files.FileVaultService;
import com.cybershield.logger.AppLogger;
import com.cybershield.models.User;
import com.cybershield.security.SessionManager;
import com.cybershield.services.AuthenticationService;
import com.cybershield.ui.Ui;
import com.cybershield.vault.AutoLock;
import com.cybershield.vault.VaultService;

import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;
import java.util.function.BooleanSupplier;

/** The main window: sidebar navigation, header, the open screen, status bar, and auto-lock. */
public class DashboardController {

    public static final double MIN_WIDTH = 1100;

    public static final double MIN_HEIGHT = 680;

    // Encrypting or restoring files counts as activity, so auto-lock waits for it.
    private static final BooleanSupplier FILE_JOBS = FileVaultService::isJobRunning;

    @FXML
    private Label screenTitle;

    @FXML
    private Label vaultChip;

    @FXML
    private Label userLabel;

    @FXML
    private Label statusVault;

    @FXML
    private Label statusUser;

    @FXML
    private StackPane contentPane;

    @FXML
    private Button homeButton;

    @FXML
    private Button passwordButton;

    @FXML
    private Button notesButton;

    @FXML
    private Button filesButton;

    @FXML
    private Button encryptionButton;

    @FXML
    private Button stegoButton;

    @FXML
    private Button privacyButton;

    @FXML
    private Button reportsButton;

    @FXML
    private Button settingsButton;

    @FXML
    private Button signOutButton;

    private final VaultService vaultService =
            new VaultService();

    private final AuthenticationService auth =
            new AuthenticationService();

    // The controller of the screen in the content area, and how to reopen it.
    private Object currentScreen;

    private Runnable currentReopen;

    private List<Button> navButtons;

    @FXML
    public void initialize() {

        navButtons = List.of(homeButton, passwordButton, notesButton, filesButton, encryptionButton,
                stegoButton, privacyButton, reportsButton, settingsButton);

        setIcon(homeButton, Ui.ICON_HOME);
        setIcon(passwordButton, Ui.ICON_KEY);
        setIcon(notesButton, Ui.ICON_NOTE);
        setIcon(filesButton, Ui.ICON_FOLDER);
        setIcon(encryptionButton, Ui.ICON_LOCK);
        setIcon(stegoButton, Ui.ICON_IMAGE);
        setIcon(privacyButton, Ui.ICON_EYE);
        setIcon(reportsButton, Ui.ICON_CHART);
        setIcon(settingsButton, Ui.ICON_SLIDERS);
        setIcon(signOutButton, Ui.ICON_SIGN_OUT);

        User user = SessionManager.getCurrentUser();

        String name = user == null ? "Guest" : user.getUsername();

        userLabel.setText(name);

        statusUser.setText("Signed in as " + name);

        AutoLock autoLock = AutoLock.getInstance();

        autoLock.keepOpenWhile(FILE_JOBS);

        autoLock.start(AutoLock.configuredTimeoutMillis(), this::onAutoLocked);

        contentPane.sceneProperty().addListener((observable, oldScene, scene) -> autoLock.watch(scene));

        autoLock.secondsLeftProperty().addListener((observable, old, left) -> showVaultState(left.intValue()));

        showVaultState(autoLock.secondsLeftProperty().get());

        openDashboard();

    }

    private static void setIcon(Button button, String path) {

        button.setGraphic(Ui.icon(path, 17, "nav-icon"));

        button.setGraphicTextGap(11);

    }

    // =====================================================
    // NAVIGATION
    // =====================================================

    @FXML
    public void openDashboard() {

        select(homeButton, "Home");

        Ui.Loaded<HomeController> home = Ui.load("/fxml/home.fxml");

        home.controller().setDashboard(this);

        replaceContent(home.root(), home.controller());

    }

    @FXML
    public void openPasswordVault() {

        select(passwordButton, "Password Vault");

        openVaultScreen("/fxml/password/password_vault.fxml", this::openPasswordVault);

    }

    @FXML
    public void openFileSecurity() {

        select(filesButton, "File Security");

        openVaultScreen("/fxml/files/file_security.fxml", this::openFileSecurity);

    }

    @FXML
    public void openSettings() {

        select(settingsButton, "Settings");

        Ui.Loaded<SettingsController> settings = Ui.load("/fxml/settings/settings.fxml");

        replaceContent(settings.root(), settings.controller());

    }

    /** From the home screen's banner: unlock (or set up) the vault, then come back home. */
    public void unlockThenHome() {

        select(homeButton, "Unlock vault");

        openVaultScreen(null, this::openDashboard);

    }

    /** From the home screen's banner. */
    public void lockNow() {

        disposeCurrentScreen();

        vaultService.lockVault();

        showVaultState(-1);

        AppLogger.info("Vault locked from the home screen.");

        openDashboard();

    }

    @FXML
    private void openSecureNotes() {

        select(notesButton, "Secure Notes");

        showComingSoon(Ui.ICON_NOTE, "Secure Notes", 2,
                "Private notes encrypted with your vault key, with search and autosave. They disappear from the screen when the vault locks.");

    }

    @FXML
    private void openEncryptionCenter() {

        select(encryptionButton, "Encryption Center");

        showComingSoon(Ui.ICON_LOCK, "Encryption Center", 3,
                "Encrypt text or files with a password you share, so they can be opened on another computer. Separate from your vault.");

    }

    @FXML
    private void openSteganography() {

        select(stegoButton, "Steganography");

        showComingSoon(Ui.ICON_IMAGE, "Steganography", 4,
                "Hide a short message or a small file inside an ordinary picture, and get it back with a password.");

    }

    @FXML
    private void openPrivacyTools() {

        select(privacyButton, "Privacy Tools");

        showComingSoon(Ui.ICON_EYE, "Privacy Tools", 4,
                "A secure file shredder, clipboard clearing, photo metadata removal and a Windows traces cleaner.");

    }

    @FXML
    private void openReports() {

        select(reportsButton, "Reports");

        showComingSoon(Ui.ICON_CHART, "Reports", 3,
                "A security summary you can save as PDF, without any passwords or note text in it.");

    }

    private void select(Button active, String title) {

        showVaultState(AutoLock.getInstance().secondsLeftProperty().get());

        for (Button button : navButtons) {
            button.getStyleClass().remove("active");
        }

        if (active != null) {
            active.getStyleClass().add("active");
        }

        screenTitle.setText(title);

    }

    private void showComingSoon(String icon, String name, int phase, String description) {

        VBox box = new VBox();

        box.getStyleClass().addAll("card", "coming-soon");

        box.setMaxWidth(460);
        box.setMaxHeight(VBox.USE_PREF_SIZE);

        Label title = new Label(name + " is on its way");
        title.getStyleClass().add("section-heading");

        Label when = new Label("Planned for phase " + phase + " of the roadmap.");
        when.getStyleClass().addAll("chip", "plain");

        Label text = new Label(description);
        text.getStyleClass().add("hint");
        text.setWrapText(true);
        text.setAlignment(Pos.CENTER);
        text.setStyle("-fx-text-alignment: center;");

        box.getChildren().addAll(Ui.icon(icon, 44, "card-icon"), title, when, text);

        StackPane holder = new StackPane(box);

        holder.setStyle("-fx-padding: 40;");

        StackPane.setAlignment(box, Pos.TOP_CENTER);

        replaceContent(holder, null);

    }

    // =====================================================
    // VAULT SCREENS
    // =====================================================

    /** After the vault locked itself, swap an open vault screen for the unlock screen. */
    private void onAutoLocked() {

        showVaultState(-1);

        if (currentScreen instanceof LockableScreen && currentReopen != null) {

            currentReopen.run();

        } else if (currentScreen instanceof HomeController home) {

            home.refresh();

        }

    }

    /**
     * Opens a screen that needs the vault, after asking for what the account still
     * needs: a new master password, taking over the shared vault, or unlocking.
     *
     * @param fxml   the screen, or null to only unlock and then run reopen
     * @param reopen opens the same screen again: used after unlocking, and after
     *               locking (which then shows the unlock screen)
     */
    private void openVaultScreen(String fxml, Runnable reopen) {

        VaultService.Status status;

        try {

            status = vaultService.status(SessionManager.currentUserId());

        } catch (IllegalStateException e) {

            AppLogger.error(e.getMessage());

            replaceContent(errorView("Your vault couldn't be read. See the console for details."), null);

            return;

        }

        switch (status) {

            case NEEDS_SETUP -> showVaultPrompt("/fxml/vault/vault_setup.fxml", reopen);

            case CAN_TAKE_OVER_SHARED -> showVaultPrompt("/fxml/vault/vault_takeover.fxml", reopen);

            case LOCKED -> showVaultPrompt("/fxml/vault/vault_unlock.fxml", reopen);

            case UNLOCKED -> {

                if (fxml == null) {
                    reopen.run();
                    return;
                }

                Object controller = showView(fxml);

                if (controller instanceof LockableScreen screen) {

                    screen.setOnLocked(() -> {
                        showVaultState(-1);
                        reopen.run();
                    });

                    currentReopen = reopen;

                }

            }

        }

    }

    private void showVaultPrompt(String fxml, Runnable afterUnlock) {

        Object controller = showView(fxml);

        if (controller instanceof VaultController vault) {

            vault.setOnUnlocked(() -> {
                showVaultState(AutoLock.getInstance().secondsLeftProperty().get());
                afterUnlock.run();
            });

            // From the take-over screen: start a new, empty vault instead.
            vault.setOnStartNewVault(() -> {

                Object setup = showView("/fxml/vault/vault_setup.fxml");

                if (setup instanceof VaultController newVault) {
                    newVault.warnAboutSharedVault();
                    newVault.setOnUnlocked(() -> {
                        showVaultState(AutoLock.getInstance().secondsLeftProperty().get());
                        afterUnlock.run();
                    });
                }

            });

        }

    }

    private void showVaultState(int secondsLeft) {

        boolean open = vaultService.isUnlocked();

        vaultChip.setText(open ? "Vault unlocked" : "Vault locked");

        vaultChip.getStyleClass().removeAll("good", "warn");
        vaultChip.getStyleClass().add(open ? "good" : "warn");

        if (!open) {
            statusVault.setText("Vault locked");
        } else if (secondsLeft > 0) {
            statusVault.setText("Vault unlocked · locks in " + (secondsLeft / 60) + ":" + String.format("%02d", secondsLeft % 60));
        } else {
            statusVault.setText("Vault unlocked");
        }

    }

    /**
     * Loads an FXML view into the content area and returns its
     * controller, or null if the view could not be loaded.
     */
    private <T> T showView(String fxml) {

        try {

            Ui.Loaded<T> view = Ui.load(fxml);

            replaceContent(view.root(), view.controller());

            return view.controller();

        } catch (RuntimeException e) {

            AppLogger.error("Could not open " + fxml + ": " + e.getMessage());

            e.printStackTrace();

            replaceContent(errorView("Could not open this screen. Check the console for details."), null);

            return null;

        }

    }

    private static Node errorView(String text) {

        Label label = new Label(text);

        label.getStyleClass().addAll("message", "bad");

        StackPane holder = new StackPane(label);

        holder.setStyle("-fx-padding: 40;");

        return holder;

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

    // =====================================================
    // SIGN OUT
    // =====================================================

    @FXML
    private void logout() {

        disposeCurrentScreen();

        AutoLock.getInstance().stop();

        vaultService.lockVault();

        auth.logout();

        try {

            Scene scene = contentPane.getScene();

            if (scene.getWindow() instanceof Stage stage) {

                stage.setMinWidth(LoginController.MIN_WIDTH);
                stage.setMinHeight(LoginController.MIN_HEIGHT);

                stage.setScene(new Scene(Ui.load("/fxml/login.fxml").root()));

                if (!stage.isMaximized()) {
                    stage.sizeToScene();
                    stage.centerOnScreen();
                }

            } else {

                scene.setRoot(Ui.load("/fxml/login.fxml").root());

            }

            AppLogger.info("User logged out.");

        } catch (RuntimeException e) {

            AppLogger.error("Could not return to the login screen: " + e.getMessage());

            e.printStackTrace();

        }

    }

}
