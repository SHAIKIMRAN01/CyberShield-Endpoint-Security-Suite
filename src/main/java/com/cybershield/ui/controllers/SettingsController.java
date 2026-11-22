package com.cybershield.ui.controllers;

import com.cybershield.dao.ActivityDAO;
import com.cybershield.dao.LoginHistoryDAO;
import com.cybershield.dao.SettingsDAO;
import com.cybershield.files.FileVaultDAO;
import com.cybershield.files.FileVaultService;
import com.cybershield.logger.AppLogger;
import com.cybershield.models.User;
import com.cybershield.security.SessionManager;
import com.cybershield.services.ActivityLog;
import com.cybershield.services.AuthenticationService;
import com.cybershield.ui.Ui;
import com.cybershield.utils.DisplayFormat;
import com.cybershield.vault.AutoLock;
import com.cybershield.vault.Vault;
import com.cybershield.vault.VaultService;
import com.cybershield.vault.VaultSession;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.Path;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Settings of the signed-in account: auto-lock, clipboard, both passwords, its sign-in history, and backups. */
public class SettingsController {

    private static final int HISTORY_ROWS = 100;

    @FXML
    private Button securityTab;

    @FXML
    private Button accountTab;

    @FXML
    private Button masterTab;

    @FXML
    private Button historyTab;

    @FXML
    private Button backupTab;

    @FXML
    private VBox backupPanel;

    @FXML
    private Label backupStatus;

    @FXML
    private Button backUpButton;

    @FXML
    private Button restoreButton;

    @FXML
    private Button unlockForBackupButton;

    @FXML
    private HBox backupProgressBox;

    @FXML
    private ProgressBar backupProgress;

    @FXML
    private Label backupProgressLabel;

    @FXML
    private Label backupMessage;

    @FXML
    private VBox securityPanel;

    @FXML
    private VBox accountPanel;

    @FXML
    private VBox masterPanel;

    @FXML
    private VBox historyPanel;

    @FXML
    private Spinner<Integer> autoLockSpinner;

    @FXML
    private Spinner<Integer> clipboardSpinner;

    @FXML
    private Label securityMessage;

    @FXML
    private Label accountSummary;

    @FXML
    private PasswordField currentPasswordField;

    @FXML
    private PasswordField newPasswordField;

    @FXML
    private PasswordField confirmPasswordField;

    @FXML
    private ProgressBar accountStrengthBar;

    @FXML
    private Label accountStrengthLabel;

    @FXML
    private Label accountMessage;

    @FXML
    private Button changePasswordButton;

    @FXML
    private Label masterSummary;

    @FXML
    private VBox masterForm;

    @FXML
    private PasswordField currentMasterField;

    @FXML
    private PasswordField newMasterField;

    @FXML
    private PasswordField confirmMasterField;

    @FXML
    private ProgressBar masterStrengthBar;

    @FXML
    private Label masterStrengthLabel;

    @FXML
    private Label masterMessage;

    @FXML
    private Button changeMasterButton;

    @FXML
    private Label historySummary;

    @FXML
    private TableView<LoginHistoryDAO.Entry> historyTable;

    @FXML
    private TableColumn<LoginHistoryDAO.Entry, String> timeColumn;

    @FXML
    private TableColumn<LoginHistoryDAO.Entry, String> resultColumn;

    @FXML
    private TableColumn<LoginHistoryDAO.Entry, String> deviceColumn;

    @FXML
    private TableColumn<LoginHistoryDAO.Entry, String> signedOutColumn;

    @FXML
    private Label activityHeading;

    @FXML
    private TableView<ActivityDAO.Event> activityTable;

    @FXML
    private TableColumn<ActivityDAO.Event, String> activityTimeColumn;

    @FXML
    private TableColumn<ActivityDAO.Event, String> activityWhatColumn;

    @FXML
    private TableColumn<ActivityDAO.Event, String> activityDetailColumn;

    @FXML
    private Button clearHistoryButton;

    @FXML
    private HBox clearBox;

    @FXML
    private PasswordField clearMasterField;

    @FXML
    private Button confirmClearButton;

    @FXML
    private Label clearMessage;

    private final ActivityDAO activity = new ActivityDAO();

    private final SettingsDAO settingsDAO = new SettingsDAO();

    private final AuthenticationService auth = new AuthenticationService();

    private final VaultService vaultService = new VaultService();

    private final LoginHistoryDAO history = new LoginHistoryDAO();

    private int userId;

    // The file store a backup copies: the app's own, or the tests' own.
    private FileVaultService fileStore = new FileVaultService();

    private Runnable onUnlockVault = () -> { };

    private final AtomicBoolean stopBackup = new AtomicBoolean();

    private boolean backupRunning;

    /** A backup or restore, given the job's listener. */
    private interface BackupJob<T> {

        T run(FileVaultService.JobListener listener) throws Exception;

    }

    @FXML
    public void initialize() {

        userId = SessionManager.currentUserId();

        SettingsDAO.Settings settings = settingsDAO.load(userId);

        autoLockSpinner.getValueFactory().setValue(settings.autoLockMinutes());

        clipboardSpinner.getValueFactory().setValue(settings.clipboardClearSeconds());

        newPasswordField.textProperty().addListener((observable, old, text) ->
                Ui.strength(accountStrengthBar, accountStrengthLabel, text));

        newMasterField.textProperty().addListener((observable, old, text) ->
                Ui.strength(masterStrengthBar, masterStrengthLabel, text));

        initializeHistoryTable();

        showSecurity();

    }

    // =====================================================
    // SECTIONS
    // =====================================================

    @FXML
    private void showSecurity() {

        show(securityTab, securityPanel);

    }

    @FXML
    private void showAccount() {

        User user = SessionManager.getCurrentUser();

        if (user != null) {
            accountSummary.setText("Signed in as " + user.getUsername() + " (" + user.getEmail() + ").");
        }

        show(accountTab, accountPanel);

    }

    @FXML
    private void showMaster() {

        fillMasterSummary();

        show(masterTab, masterPanel);

    }

    @FXML
    private void showHistory() {

        refreshHistory();

        show(historyTab, historyPanel);

    }

    private void show(Button tab, VBox panel) {

        for (Button each : List.of(securityTab, accountTab, masterTab, historyTab, backupTab)) {
            each.getStyleClass().remove("active");
        }

        tab.getStyleClass().add("active");

        for (VBox each : List.of(securityPanel, accountPanel, masterPanel, historyPanel, backupPanel)) {
            each.setVisible(each == panel);
        }

    }

    @FXML
    public void showBackup() {

        refreshBackupStatus();

        show(backupTab, backupPanel);

    }

    // =====================================================
    // BACKUP AND RESTORE
    // =====================================================

    /** The dashboard's way to unlock the vault and come back to this section. */
    public void setOnUnlockVault(Runnable onUnlockVault) {

        this.onUnlockVault = onUnlockVault;

    }

    /** For the tests: back up a made-up store, never the app's own. */
    void useFileStore(FileVaultService store) {

        this.fileStore = store;

    }

    private void refreshBackupStatus() {

        boolean open = VaultSession.isUnlocked();

        for (Button button : List.of(backUpButton, restoreButton)) {
            button.setVisible(open);
            button.setManaged(open);
            button.setDisable(backupRunning);
        }

        unlockForBackupButton.setVisible(!open);
        unlockForBackupButton.setManaged(!open);

        String last;

        try {

            ActivityDAO.Event backup = activity.latest(userId, ActivityLog.Action.STORE_BACKED_UP.name());

            last = backup == null ? "No backup in the last " + ActivityLog.KEEP_DAYS + " days."
                    : "Last backup: " + DisplayFormat.timestamp(backup.occurredAt()) + ".";

        } catch (SQLException e) {

            last = "";

        }

        if (!open) {
            backupStatus.setText("The vault is locked: unlock it to back up or restore. " + last);
            return;
        }

        String lastBackup = last;

        Ui.background("backup-status", () -> {

            try {
                return new FileVaultDAO().counts(VaultSession.current().id());
            } catch (SQLException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }

        }, counts -> backupStatus.setText(counts.items() + (counts.items() == 1 ? " protected item, " : " protected items, ")
                + counts.files() + (counts.files() == 1 ? " file. " : " files. ") + lastBackup),
                error -> backupStatus.setText(lastBackup));

    }

    @FXML
    private void unlockForBackup() {

        onUnlockVault.run();

    }

    @FXML
    private void backUp() {

        DirectoryChooser chooser = new DirectoryChooser();

        chooser.setTitle("Choose where to put the backup, for example a USB drive");

        File folder = chooser.showDialog(backupPanel.getScene().getWindow());

        if (folder != null) {
            backUpTo(folder.toPath());
        }

    }

    /** Also used by the tests, in place of the folder dialog. */
    void backUpTo(Path destination) {

        runBackupJob(listener -> fileStore.backUp(destination, listener),
                "Stopped. The backup folder it started is incomplete: delete it, or back up again.", result -> Ui.message(backupMessage,
                "Backed up " + result.files() + (result.files() == 1 ? " file" : " files") + " to " + result.folder() + ".", "good"));

    }

    @FXML
    private void restoreBackup() {

        DirectoryChooser chooser = new DirectoryChooser();

        chooser.setTitle("Choose a CyberShield backup folder");

        File folder = chooser.showDialog(backupPanel.getScene().getWindow());

        if (folder != null) {
            restoreBackupFrom(folder.toPath());
        }

    }

    /** Also used by the tests, in place of the folder dialog. */
    void restoreBackupFrom(Path folder) {

        runBackupJob(listener -> fileStore.restoreBackup(folder, listener),
                "Stopped. What was brought back so far stays listed; restore again for the rest.", result -> Ui.message(backupMessage, result.itemsAdded() == 0
                ? "Nothing to bring back: every item in the backup is already listed."
                : "Brought back " + result.itemsAdded() + (result.itemsAdded() == 1 ? " item" : " items")
                + (result.alreadyThere() > 0 ? "; " + result.alreadyThere() + " were already listed." : ".")
                + " See them in File Security.", "good"));

    }

    @FXML
    private void stopBackupJob() {

        stopBackup.set(true);

    }

    private <T> void runBackupJob(BackupJob<T> job, String stoppedText, Consumer<T> done) {

        if (backupRunning) {
            return;
        }

        if (!VaultSession.isUnlocked()) {
            refreshBackupStatus();
            Ui.message(backupMessage, "The vault locked itself. Unlock it, then try again.", "bad");
            return;
        }

        stopBackup.set(false);

        Task<T> task = new Task<>() {

            @Override
            protected T call() throws Exception {

                return job.run(new FileVaultService.JobListener() {

                    @Override
                    public void progress(long done, long total) {
                        updateProgress(done, total);
                    }

                    @Override
                    public void message(String text) {
                        updateMessage(text);
                    }

                    @Override
                    public boolean isCancelled() {
                        return stopBackup.get();
                    }

                });

            }

        };

        backupProgress.progressProperty().bind(task.progressProperty());
        backupProgressLabel.textProperty().bind(task.messageProperty());

        setBackupBusy(true);

        Ui.message(backupMessage, "", null);

        task.setOnSucceeded(event -> {

            setBackupBusy(false);

            done.accept(task.getValue());

            refreshBackupStatus();

        });

        task.setOnFailed(event -> {

            setBackupBusy(false);

            Throwable error = task.getException();

            Ui.message(backupMessage, error instanceof CancellationException
                    ? stoppedText
                    : "It stopped: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()), "bad");

            refreshBackupStatus();

        });

        Thread thread = new Thread(task, "cybershield-settings-backup");

        thread.setDaemon(true);

        thread.start();

    }

    private void setBackupBusy(boolean busy) {

        backupRunning = busy;

        backupProgressBox.setVisible(busy);
        backupProgressBox.setManaged(busy);

        backUpButton.setDisable(busy);
        restoreButton.setDisable(busy);

        if (!busy) {
            backupProgress.progressProperty().unbind();
            backupProgressLabel.textProperty().unbind();
        }

    }

    // =====================================================
    // AUTO-LOCK AND CLIPBOARD
    // =====================================================

    @FXML
    private void saveSecurity() {

        Integer minutes = typedValue(autoLockSpinner);

        Integer seconds = typedValue(clipboardSpinner);

        if (minutes == null || seconds == null) {
            Ui.message(securityMessage, "Type whole numbers only.", "bad");
            return;
        }

        try {

            SettingsDAO.Settings saved = settingsDAO.save(userId, new SettingsDAO.Settings(minutes, seconds));

            autoLockSpinner.getValueFactory().setValue(saved.autoLockMinutes());

            clipboardSpinner.getValueFactory().setValue(saved.clipboardClearSeconds());

            AutoLock.getInstance().setTimeoutMillis(saved.autoLockMinutes() * 60_000L);

            ActivityLog.recordPlain(ActivityLog.Action.SETTINGS_SAVED,
                    "Auto-lock " + saved.autoLockMinutes() + " min, clipboard " + saved.clipboardClearSeconds() + " s");

            Ui.message(securityMessage, "Saved. The vault now locks after " + saved.autoLockMinutes()
                    + (saved.autoLockMinutes() == 1 ? " minute" : " minutes") + " without use, and copied passwords clear after "
                    + saved.clipboardClearSeconds() + " seconds.", "good");

        } catch (SQLException e) {

            AppLogger.error("Couldn't save the settings: " + e.getMessage());

            Ui.message(securityMessage, "The settings couldn't be saved. See the console for details.", "bad");

        }

    }

    /** The spinner's typed text as a number (an editable spinner only takes it on Enter), or null. */
    private static Integer typedValue(Spinner<Integer> spinner) {

        try {

            int value = Integer.parseInt(spinner.getEditor().getText().trim());

            spinner.getValueFactory().setValue(value);

            return spinner.getValue();

        } catch (NumberFormatException e) {

            return null;

        }

    }

    // =====================================================
    // ACCOUNT PASSWORD
    // =====================================================

    @FXML
    private void changeAccountPassword() {

        String current = currentPasswordField.getText();

        String next = newPasswordField.getText();

        if (current.isEmpty() || next.isEmpty() || confirmPasswordField.getText().isEmpty()) {
            Ui.message(accountMessage, "Fill in all three fields.", "bad");
            return;
        }

        if (!next.equals(confirmPasswordField.getText())) {
            Ui.message(accountMessage, "The new passwords don't match.", "bad");
            confirmPasswordField.clear();
            return;
        }

        changePasswordButton.setDisable(true);

        Ui.message(accountMessage, "Changing your password…", null);

        Ui.background("change-password", () -> auth.changePassword(userId, current, next), result -> {

            changePasswordButton.setDisable(false);

            Ui.message(accountMessage, result.message(), result.ok() ? "good" : "bad");

            if (result.ok()) {
                currentPasswordField.clear();
                newPasswordField.clear();
                confirmPasswordField.clear();
            } else {
                currentPasswordField.clear();
                currentPasswordField.requestFocus();
            }

        }, error -> {

            changePasswordButton.setDisable(false);

            Ui.message(accountMessage, "The password couldn't be changed. See the console for details.", "bad");

        });

    }

    // =====================================================
    // MASTER PASSWORD
    // =====================================================

    private void fillMasterSummary() {

        Vault vault = null;

        try {
            vault = vaultService.find(userId);
        } catch (SQLException e) {
            AppLogger.error("Couldn't read the vault: " + e.getMessage());
        }

        masterForm.setVisible(vault != null);
        masterForm.setManaged(vault != null);

        if (vault == null) {
            masterSummary.setText("This account has no vault yet. Open Password Vault or File Security to set one up.");
            return;
        }

        String changed = vault.passwordChangedAt() == null
                ? "It hasn't been changed since the vault was set up on " + DisplayFormat.timestamp(vault.createdAt()) + "."
                : "Last changed " + DisplayFormat.timestamp(vault.passwordChangedAt()) + ".";

        masterSummary.setText("The vault key is locked with PBKDF2-HMAC-SHA512 ("
                + NumberFormat.getIntegerInstance().format(vault.kdfIterations()) + " rounds) and AES-256-GCM. " + changed);

    }

    @FXML
    private void changeMasterPassword() {

        String current = currentMasterField.getText();

        String next = newMasterField.getText();

        if (current.isEmpty() || next.isEmpty() || confirmMasterField.getText().isEmpty()) {
            Ui.message(masterMessage, "Fill in all three fields.", "bad");
            return;
        }

        if (!next.equals(confirmMasterField.getText())) {
            Ui.message(masterMessage, "The new master passwords don't match.", "bad");
            confirmMasterField.clear();
            return;
        }

        if (next.equals(current)) {
            Ui.message(masterMessage, "Choose a master password that's different from the current one.", "bad");
            return;
        }

        changeMasterButton.setDisable(true);

        Ui.message(masterMessage, "Changing the master password…", null);

        Ui.background("change-master-password", () -> vaultService.changeMasterPassword(userId, current, next), result -> {

            changeMasterButton.setDisable(false);

            currentMasterField.clear();

            switch (result) {

                case OK -> {
                    newMasterField.clear();
                    confirmMasterField.clear();
                    fillMasterSummary();
                    Ui.message(masterMessage, "Master password changed. Use the new one from now on.", "good");
                }

                case WRONG_PASSWORD -> {
                    Ui.message(masterMessage, "The current master password is wrong.", "bad");
                    currentMasterField.requestFocus();
                }

                case WEAK_PASSWORD -> Ui.message(masterMessage,
                        "The new master password is too weak: use at least 12 characters with upper and lower case letters, a number and a symbol.", "bad");

                default -> Ui.message(masterMessage, "The master password couldn't be changed. See the console for details.", "bad");

            }

        }, error -> {

            changeMasterButton.setDisable(false);

            Ui.message(masterMessage, "The master password couldn't be changed. See the console for details.", "bad");

        });

    }

    // =====================================================
    // SIGN-IN HISTORY
    // =====================================================

    private void initializeHistoryTable() {

        timeColumn.setCellValueFactory(cell -> new SimpleStringProperty(DisplayFormat.timestamp(cell.getValue().signedInAt())));

        resultColumn.setCellValueFactory(cell -> new SimpleStringProperty(describe(cell.getValue().status())));

        deviceColumn.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().device() == null ? "" : cell.getValue().device()));

        signedOutColumn.setCellValueFactory(cell -> new SimpleStringProperty(signedOut(cell.getValue())));

        activityTimeColumn.setCellValueFactory(cell -> new SimpleStringProperty(timeOnly(cell.getValue().occurredAt())));

        activityWhatColumn.setCellValueFactory(cell -> new SimpleStringProperty(ActivityLog.Action.wordsFor(cell.getValue().action())));

        activityDetailColumn.setCellValueFactory(cell -> new SimpleStringProperty(ActivityLog.readable(cell.getValue())));

        // The sign-ins take the top third of the panel, what the chosen one did the rest.
        historyTable.prefHeightProperty().bind(historyPanel.heightProperty().multiply(0.3));

        historyTable.getSelectionModel().selectedItemProperty().addListener((observable, before, entry) -> showActivity(entry));

        resultColumn.setCellFactory(column -> new TableCell<>() {

            @Override
            protected void updateItem(String text, boolean empty) {

                super.updateItem(text, empty);

                getStyleClass().removeAll("result-good", "result-bad", "result-warn");

                setText(empty ? null : text);

                if (!empty && text != null) {
                    getStyleClass().add(text.startsWith("Signed in") ? "result-good"
                            : text.startsWith("Wrong") ? "result-bad" : "result-warn");
                }

            }

        });

    }

    @FXML
    private void refreshHistory() {

        try {

            List<LoginHistoryDAO.Entry> entries = history.recent(userId, HISTORY_ROWS);

            historyTable.setItems(FXCollections.observableArrayList(entries));

            if (!entries.isEmpty()) {
                historyTable.getSelectionModel().select(0);
            } else {
                showActivity(null);
            }

            long failures = entries.stream().filter(entry -> !LoginHistoryDAO.SUCCESS.equals(entry.status())).count();

            historySummary.setText(entries.isEmpty()
                    ? "Sign-ins are recorded from now on."
                    : "The latest " + entries.size() + " attempts, newest first. "
                    + (failures == 0 ? "None of them failed." : failures + " didn't succeed: if you don't recognise them, change your password."));

        } catch (SQLException e) {

            AppLogger.error("Couldn't read the sign-in history: " + e.getMessage());

            historySummary.setText("The sign-in history couldn't be read.");

        }

    }

    private void showActivity(LoginHistoryDAO.Entry entry) {

        if (entry == null) {
            activityHeading.setText("What this sign-in did");
            activityTable.getItems().clear();
            return;
        }

        activityHeading.setText("What the sign-in of " + DisplayFormat.timestamp(entry.signedInAt()) + " did");

        try {

            activityTable.setItems(FXCollections.observableArrayList(activity.forSession(userId, entry.id())));

        } catch (SQLException e) {

            AppLogger.error("Couldn't read the activity: " + e.getMessage());

            activityTable.getItems().clear();

        }

    }

    /** "14:05" from a stored UTC time, in local time. */
    private static String timeOnly(String utc) {

        String shown = DisplayFormat.timestamp(utc);

        int comma = shown.lastIndexOf(", ");

        return comma >= 0 ? shown.substring(comma + 2) : shown;

    }

    @FXML
    private void askToClearHistory() {

        clearHistoryButton.setVisible(false);
        clearHistoryButton.setManaged(false);

        clearBox.setVisible(true);
        clearBox.setManaged(true);

        Ui.message(clearMessage, "Enter your master password to clear every earlier sign-in and what it did.", null);

        clearMasterField.requestFocus();

    }

    @FXML
    private void cancelClear() {

        clearMasterField.clear();

        clearBox.setVisible(false);
        clearBox.setManaged(false);

        clearHistoryButton.setVisible(true);
        clearHistoryButton.setManaged(true);

        Ui.message(clearMessage, "", null);

    }

    /** Clears the history after checking the master password; the current sign-in stays. */
    @FXML
    private void clearHistory() {

        String password = clearMasterField.getText();

        if (password.isEmpty()) {
            Ui.message(clearMessage, "Enter your master password.", "bad");
            return;
        }

        confirmClearButton.setDisable(true);

        Ui.message(clearMessage, "Checking…", null);

        long current = SessionManager.historyId();

        Ui.background("clear-history", () -> {

            try {

                if (vaultService.find(userId) == null) {
                    return "NO_VAULT";
                }

                if (!vaultService.verifyMasterPassword(userId, password)) {
                    return "WRONG";
                }

                activity.clear(userId, current);

                history.clear(userId, current);

                ActivityLog.record(ActivityLog.Action.HISTORY_CLEARED);

                return "OK";

            } catch (SQLException e) {

                AppLogger.error("Couldn't clear the history: " + e.getMessage());

                return "FAILED";

            }

        }, outcome -> {

            confirmClearButton.setDisable(false);

            clearMasterField.clear();

            switch (outcome) {

                case "OK" -> {
                    cancelClear();
                    refreshHistory();
                    Ui.message(clearMessage, "History cleared. Only this sign-in is left.", "good");
                }

                case "WRONG" -> Ui.message(clearMessage, "Wrong master password. Nothing was cleared.", "bad");

                case "NO_VAULT" -> Ui.message(clearMessage, "Clearing the history needs the master password: set up your vault first.", "bad");

                default -> Ui.message(clearMessage, "The history couldn't be cleared. See the console for details.", "bad");

            }

        }, error -> {

            confirmClearButton.setDisable(false);

            Ui.message(clearMessage, "The history couldn't be cleared.", "bad");

        });

    }

    private static String describe(String status) {

        if (status == null) {
            return "";
        }

        return switch (status) {
            case LoginHistoryDAO.SUCCESS -> "Signed in";
            case LoginHistoryDAO.FAILED -> "Wrong password";
            case LoginHistoryDAO.LOCKED -> "Refused: waiting";
            default -> status;
        };

    }

    private static String signedOut(LoginHistoryDAO.Entry entry) {

        if (!LoginHistoryDAO.SUCCESS.equals(entry.status())) {
            return "";
        }

        if (entry.id() == SessionManager.historyId()) {
            return "Signed in now";
        }

        return entry.signedOutAt() == null ? "App closed without signing out" : DisplayFormat.timestamp(entry.signedOutAt());

    }

}
