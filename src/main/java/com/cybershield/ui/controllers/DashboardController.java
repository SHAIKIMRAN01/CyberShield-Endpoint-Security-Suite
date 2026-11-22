package com.cybershield.ui.controllers;

import com.cybershield.files.FileVaultService;
import com.cybershield.logger.AppLogger;
import com.cybershield.models.User;
import com.cybershield.security.SessionManager;
import com.cybershield.services.AuthenticationService;
import com.cybershield.ui.Theme;
import com.cybershield.ui.Ui;
import com.cybershield.vault.AutoLock;
import com.cybershield.vault.VaultService;

import javafx.fxml.FXML;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import java.util.ArrayList;
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

    // A short note, such as "This month's security report is saved", that goes after a while.
    @FXML
    private Label statusNote;

    private final javafx.animation.PauseTransition noteTimer = new javafx.animation.PauseTransition(Duration.seconds(12));

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

    @FXML
    private VBox sidebar;

    @FXML
    private HBox brandRow;

    @FXML
    private VBox brandText;

    @FXML
    private Region brandGap;

    @FXML
    private Button closeSidebarButton;

    @FXML
    private Button menuButton;

    /** Open with names, shrunk to its icons, or closed. Remembered while the app runs. */
    public enum SidebarMode { OPEN, RAIL, CLOSED }

    private static final double OPEN_WIDTH = 236;

    private static final double RAIL_WIDTH = 70;

    private static SidebarMode sidebarMode = SidebarMode.OPEN;

    private Timeline sidebarMotion;

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

        setUpSidebar();

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
    // SIDEBAR: OPEN, SHRUNK TO ICONS, OR CLOSED
    // =====================================================

    private void setUpSidebar() {

        menuButton.setGraphic(Ui.icon(Ui.ICON_MENU, 20, "menu-icon"));

        closeSidebarButton.setGraphic(Ui.icon(Ui.ICON_CLOSE, 13, "nav-icon"));
        closeSidebarButton.setTooltip(new Tooltip("Close the sidebar"));

        // Nothing spills out while the sidebar slides.
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(sidebar.widthProperty());
        clip.heightProperty().bind(sidebar.heightProperty());
        sidebar.setClip(clip);

        sidebar.sceneProperty().addListener((observable, before, scene) -> {
            if (scene != null) {
                scene.getAccelerators().put(new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN), this::toggleSidebar);
            }
        });

        showSidebar(sidebarMode, false);

    }

    /** ☰: shrinks the open sidebar to its icons, widens it again, or brings it back once closed. */
    @FXML
    public void toggleSidebar() {

        showSidebar(sidebarMode == SidebarMode.OPEN ? SidebarMode.RAIL : SidebarMode.OPEN, true);

    }

    @FXML
    public void closeSidebar() {

        showSidebar(SidebarMode.CLOSED, true);

    }

    public SidebarMode sidebarMode() {
        return sidebarMode;
    }

    /** Also used by the tests, without the animation. */
    public void showSidebar(SidebarMode mode, boolean animate) {

        sidebarMode = mode;

        boolean names = mode == SidebarMode.OPEN;

        List<Button> buttons = new ArrayList<>(navButtons);
        buttons.add(signOutButton);

        for (Button button : buttons) {
            button.setContentDisplay(names ? ContentDisplay.LEFT : ContentDisplay.GRAPHIC_ONLY);
            button.setTooltip(names ? null : new Tooltip(button.getText()));
        }

        for (Node group : sidebar.lookupAll(".nav-group")) {
            group.setVisible(names);
            group.setManaged(names);
        }

        for (Node part : List.of(brandText, brandGap, closeSidebarButton)) {
            part.setVisible(names);
            part.setManaged(names);
        }

        brandRow.setAlignment(names ? Pos.CENTER_LEFT : Pos.CENTER);

        sidebar.getStyleClass().remove("rail");

        if (mode == SidebarMode.RAIL) {
            sidebar.getStyleClass().add("rail");
        }

        menuButton.setTooltip(new Tooltip(switch (mode) {
            case OPEN -> "Shrink the sidebar to its icons (Ctrl+B)";
            case RAIL -> "Widen the sidebar (Ctrl+B)";
            case CLOSED -> "Show the sidebar (Ctrl+B)";
        }));

        double target = switch (mode) {
            case OPEN -> OPEN_WIDTH;
            case RAIL -> RAIL_WIDTH;
            case CLOSED -> 0;
        };

        if (sidebarMotion != null) {
            sidebarMotion.stop();
        }

        if (mode != SidebarMode.CLOSED) {
            sidebar.setVisible(true);
            sidebar.setManaged(true);
        }

        if (!animate) {
            setSidebarWidth(target);
            sidebar.setVisible(mode != SidebarMode.CLOSED);
            sidebar.setManaged(mode != SidebarMode.CLOSED);
            return;
        }

        DoubleProperty width = new SimpleDoubleProperty(sidebar.getWidth() > 0 ? sidebar.getWidth() : sidebar.getPrefWidth());

        width.addListener((observable, before, now) -> setSidebarWidth(now.doubleValue()));

        sidebarMotion = new Timeline(new KeyFrame(Duration.millis(190), new KeyValue(width, target, Interpolator.EASE_BOTH)));

        sidebarMotion.setOnFinished(event -> {
            if (sidebarMode == SidebarMode.CLOSED) {
                sidebar.setVisible(false);
                sidebar.setManaged(false);
            }
        });

        sidebarMotion.play();

    }

    private void setSidebarWidth(double width) {

        sidebar.setMinWidth(width);
        sidebar.setPrefWidth(width);
        sidebar.setMaxWidth(width);

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

        // Backups need the vault: unlock it, then come back to the backup section.
        settings.controller().setOnUnlockVault(() -> openVaultScreen(null, this::openBackupSettings));

        replaceContent(settings.root(), settings.controller());

    }

    private void openBackupSettings() {

        openSettings();

        if (currentScreen instanceof SettingsController settings) {
            settings.showBackup();
        }

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
    public void openSecureNotes() {

        select(notesButton, "Secure Notes");

        openVaultScreen("/fxml/notes/secure_notes.fxml", this::openSecureNotes);

    }

    @FXML
    public void openEncryptionCenter() {

        select(encryptionButton, "Encryption Center");

        showView("/fxml/encryption/encryption.fxml");

    }

    @FXML
    public void openSteganography() {

        select(stegoButton, "Steganography");

        showView("/fxml/stego/steganography.fxml");

    }

    @FXML
    public void openPrivacyTools() {

        select(privacyButton, "Privacy Tools");

        showView("/fxml/privacy/privacy.fxml");

    }

    @FXML
    private void openReports() {

        select(reportsButton, "Reports");

        openVaultScreen("/fxml/reports/reports.fxml", this::openReports);

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

            replaceContent(errorView("Your vault couldn't be read. Details are in CyberShield's log file."), null);

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
                com.cybershield.reports.SessionReports.catchUpInBackground();
                makeMonthlyReportIfDue();
                afterUnlock.run();
            });

            // From the take-over screen: start a new, empty vault instead.
            vault.setOnStartNewVault(() -> {

                Object setup = showView("/fxml/vault/vault_setup.fxml");

                if (setup instanceof VaultController newVault) {
                    newVault.warnAboutSharedVault();
                    newVault.setOnUnlocked(() -> {
                        showVaultState(AutoLock.getInstance().secondsLeftProperty().get());
                        com.cybershield.reports.SessionReports.catchUpInBackground();
                        makeMonthlyReportIfDue();
                        afterUnlock.run();
                    });
                }

            });

        }

    }

    /** The first unlock of a month makes that month's report, if the account turned it on. */
    private void makeMonthlyReportIfDue() {

        com.cybershield.reports.MonthlyReports.makeIfDueInBackground(row -> javafx.application.Platform.runLater(() -> {

            showNote("This month's security report is saved.");

            if (currentScreen instanceof ReportsController reports) {
                reports.monthlyReportMade(row);
            }

        }));

    }

    /** The open screen's controller (for the tests). */
    Object currentScreen() {

        return currentScreen;

    }

    /** Shows a note in the status bar for a few seconds. */
    void showNote(String text) {

        if (statusNote == null) {
            return;
        }

        statusNote.setText(text);

        noteTimer.setOnFinished(event -> statusNote.setText(""));

        noteTimer.playFromStart();

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

            AppLogger.error("Could not open " + fxml, e);

            replaceContent(errorView("Could not open this screen. Details are in CyberShield's log file."), null);

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

        // The sign-in's report needs the vault key for the names in it: keep it before locking.
        if (vaultService.isUnlocked()) {
            try {
                new com.cybershield.reports.SessionReports().saveCurrent(true);
            } catch (java.sql.SQLException | java.io.IOException | RuntimeException e) {
                AppLogger.error("Couldn't keep this sign-in's report: " + e.getMessage());
            }
        }

        vaultService.lockVault();

        auth.logout();

        try {

            Scene scene = contentPane.getScene();

            if (scene.getWindow() instanceof Stage stage) {

                stage.setMinWidth(LoginController.MIN_WIDTH);
                stage.setMinHeight(LoginController.MIN_HEIGHT);

                stage.setScene(Theme.style(new Scene(Ui.load("/fxml/login.fxml").root())));

                if (!stage.isMaximized()) {
                    stage.sizeToScene();
                    stage.centerOnScreen();
                }

            } else {

                scene.setRoot(Ui.load("/fxml/login.fxml").root());

                Theme.style(scene);

            }

            AppLogger.info("User logged out.");

        } catch (RuntimeException e) {

            AppLogger.error("Could not return to the login screen", e);

        }

    }

}
