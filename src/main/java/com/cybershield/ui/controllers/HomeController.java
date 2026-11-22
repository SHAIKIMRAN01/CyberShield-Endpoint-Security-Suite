package com.cybershield.ui.controllers;

import com.cybershield.dao.LoginHistoryDAO;
import com.cybershield.dao.SettingsDAO;
import com.cybershield.files.FileVaultDAO;
import com.cybershield.logger.AppLogger;
import com.cybershield.models.User;
import com.cybershield.password.PasswordDAO;
import com.cybershield.security.SessionManager;
import com.cybershield.services.SecurityScore;
import com.cybershield.ui.Ui;
import com.cybershield.utils.DisplayFormat;
import com.cybershield.vault.Vault;
import com.cybershield.vault.VaultService;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.sql.SQLException;
import java.time.LocalTime;

/** The home screen: vault state, what is protected, and the last sign-in. None of it needs the vault key. */
public class HomeController {

    @FXML
    private Label greeting;

    @FXML
    private HBox vaultBanner;

    @FXML
    private StackPane bannerIcon;

    @FXML
    private Label bannerTitle;

    @FXML
    private Label bannerText;

    @FXML
    private Button bannerButton;

    @FXML
    private StackPane passwordIcon;

    @FXML
    private Label passwordCount;

    @FXML
    private Label passwordCaption;

    @FXML
    private StackPane filesIcon;

    @FXML
    private Label fileCount;

    @FXML
    private Label fileCaption;

    @FXML
    private StackPane accountIcon;

    @FXML
    private Label lastSignIn;

    @FXML
    private Label lastDevice;

    @FXML
    private Label failedAttempts;

    @FXML
    private Label scoreValue;

    @FXML
    private Label scoreGrade;

    @FXML
    private ProgressBar scoreBar;

    @FXML
    private VBox hintsBox;

    @FXML
    private Button lockAction;

    private final VaultService vaultService = new VaultService();

    private DashboardController dashboard;

    private VaultService.Status status = VaultService.Status.LOCKED;

    @FXML
    public void initialize() {

        passwordIcon.getChildren().setAll(Ui.icon(Ui.ICON_KEY, 22, "card-icon"));

        filesIcon.getChildren().setAll(Ui.icon(Ui.ICON_FOLDER, 22, "card-icon"));

        accountIcon.getChildren().setAll(Ui.icon(Ui.ICON_SLIDERS, 22, "card-icon"));

        refresh();

    }

    public void setDashboard(DashboardController dashboard) {

        this.dashboard = dashboard;

    }

    /** Reads everything again, for example after the vault was locked or unlocked. */
    public void refresh() {

        User user = SessionManager.getCurrentUser();

        int userId = SessionManager.currentUserId();

        greeting.setText(greetingFor(LocalTime.now()) + (user == null ? "" : ", " + user.getUsername()));

        Vault vault = null;

        try {

            status = vaultService.status(userId);

            vault = vaultService.find(userId);

        } catch (IllegalStateException | SQLException e) {

            AppLogger.error("Couldn't read the vault for the home screen: " + e.getMessage());

        }

        showBanner(new SettingsDAO().load(userId).autoLockMinutes());

        showCounts(vault);

        showLastSignIn(userId);

        showScore(userId);

    }

    private void showScore(int userId) {

        SecurityScore.Score score = SecurityScore.compute(userId);

        scoreValue.setText(score.percent() + "");

        scoreGrade.setText(score.grade());

        scoreGrade.getStyleClass().removeAll("plain", "good", "warn", "bad");
        scoreGrade.getStyleClass().add(score.percent() >= 60 ? "good" : score.percent() >= 35 ? "warn" : "bad");

        scoreBar.setProgress(score.percent() / 100.0);

        hintsBox.getChildren().clear();

        score.hints().stream().limit(3).forEach(hint -> {
            Label line = new Label("• " + hint);
            line.getStyleClass().add("hint");
            line.setWrapText(true);
            hintsBox.getChildren().add(line);
        });

        if (score.hints().isEmpty()) {
            Label line = new Label("Everything checked is in good shape.");
            line.getStyleClass().add("hint");
            hintsBox.getChildren().add(line);
        }

        lockAction.setDisable(status != VaultService.Status.UNLOCKED);

    }

    @FXML
    private void lockNow() {

        if (dashboard != null) {
            dashboard.lockNow();
        }

    }

    @FXML
    private void openSecureNotes() {

        if (dashboard != null) {
            dashboard.openSecureNotes();
        }

    }

    private void showBanner(int autoLockMinutes) {

        vaultBanner.getStyleClass().removeAll("locked", "unlocked");

        boolean open = status == VaultService.Status.UNLOCKED;

        vaultBanner.getStyleClass().add(open ? "unlocked" : "locked");

        bannerIcon.getChildren().setAll(Ui.icon(Ui.ICON_LOCK, 30, "card-icon"));

        bannerButton.getStyleClass().remove("primary-button");

        switch (status) {

            case NEEDS_SETUP -> {
                bannerTitle.setText("Set up your vault");
                bannerText.setText("Choose a master password. It encrypts your passwords and files, and only you know it.");
                bannerButton.setText("Set up vault");
            }

            case CAN_TAKE_OVER_SHARED -> {
                bannerTitle.setText("Move your vault into this account");
                bannerText.setText("Your saved items are in the vault this PC used before every account had its own. "
                        + "Unlock it once with its master password to make it yours.");
                bannerButton.setText("Unlock vault");
            }

            case LOCKED -> {
                bannerTitle.setText("Your vault is locked");
                bannerText.setText("Unlock it with your master password to see your passwords and protected files.");
                bannerButton.setText("Unlock vault");
            }

            case UNLOCKED -> {
                bannerTitle.setText("Your vault is unlocked");
                bannerText.setText("It locks itself after " + autoLockMinutes + (autoLockMinutes == 1 ? " minute" : " minutes")
                        + " without mouse or keyboard use.");
                bannerButton.setText("Lock now");
            }

        }

        if (!open) {
            bannerButton.getStyleClass().add("primary-button");
        }

    }

    private void showCounts(Vault vault) {

        if (vault == null) {

            passwordCount.setText("–");
            passwordCaption.setText(status == VaultService.Status.CAN_TAKE_OVER_SHARED
                    ? "Unlock the vault to bring your saved logins into this account."
                    : "Set up your vault to start saving logins.");

            fileCount.setText("–");
            fileCaption.setText("Protected files appear here once your vault is set up.");

            return;

        }

        try {

            int logins = new PasswordDAO().count(vault.id());

            FileVaultDAO.Counts files = new FileVaultDAO().counts(vault.id());

            passwordCount.setText(String.valueOf(logins));
            passwordCaption.setText(logins == 1 ? "saved login, encrypted" : "saved logins, encrypted");

            fileCount.setText(String.valueOf(files.items()));
            fileCaption.setText((files.items() == 1 ? "protected item" : "protected items")
                    + " · " + files.files() + (files.files() == 1 ? " file hidden" : " files hidden"));

        } catch (SQLException e) {

            AppLogger.error("Couldn't count the vault's items: " + e.getMessage());

            passwordCount.setText("?");
            fileCount.setText("?");

        }

    }

    private void showLastSignIn(int userId) {

        LoginHistoryDAO history = new LoginHistoryDAO();

        try {

            LoginHistoryDAO.Entry previous = history.previousSuccess(userId, SessionManager.historyId());

            if (previous == null) {

                lastSignIn.setText("This is your first sign-in");
                lastDevice.setText("Earlier sign-ins will be listed in Settings.");
                failedAttempts.setText("");

                return;

            }

            lastSignIn.setText(DisplayFormat.timestamp(previous.signedInAt()));

            lastDevice.setText("on " + (previous.device() == null ? "an unknown device" : previous.device()));

            int failures = history.failuresSince(userId, previous.id());

            failedAttempts.setText(failures == 0
                    ? "No failed attempts since then."
                    : failures + (failures == 1 ? " failed attempt" : " failed attempts") + " since then. Check Settings.");

            failedAttempts.getStyleClass().removeAll("message", "bad");

            if (failures > 0) {
                failedAttempts.getStyleClass().addAll("message", "bad");
            }

        } catch (SQLException e) {

            AppLogger.error("Couldn't read the sign-in history: " + e.getMessage());

            lastSignIn.setText("Unavailable");

        }

    }

    private static String greetingFor(LocalTime time) {

        if (time.getHour() < 12) {
            return "Good morning";
        }

        return time.getHour() < 18 ? "Good afternoon" : "Good evening";

    }

    @FXML
    private void bannerAction() {

        if (dashboard == null) {
            return;
        }

        if (status == VaultService.Status.UNLOCKED) {
            dashboard.lockNow();
        } else {
            dashboard.unlockThenHome();
        }

    }

    @FXML
    private void openPasswordVault() {

        if (dashboard != null) {
            dashboard.openPasswordVault();
        }

    }

    @FXML
    private void openFileSecurity() {

        if (dashboard != null) {
            dashboard.openFileSecurity();
        }

    }

    @FXML
    private void openSettings() {

        if (dashboard != null) {
            dashboard.openSettings();
        }

    }

}
