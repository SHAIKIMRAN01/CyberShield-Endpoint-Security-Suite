package com.cybershield.ui.controllers;

import com.cybershield.logger.AppLogger;
import com.cybershield.reports.ReportDAO;
import com.cybershield.reports.ReportService;
import com.cybershield.reports.SessionReports;
import com.cybershield.ui.viewer.PdfPreview;
import com.cybershield.services.SecurityScore;
import com.cybershield.ui.Ui;
import com.cybershield.utils.DisplayFormat;
import com.cybershield.vault.VaultService;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Reports: the account's security summary on screen, saved as a PDF on request, and the
 * list of reports made so far. Opens behind the vault, because password health needs the
 * passwords decrypted; the report itself never contains them.
 */
public class ReportsController implements LockableScreen {

    @FXML private Label scoreValue;
    @FXML private Label scoreGrade;
    @FXML private ProgressBar scoreBar;
    @FXML private VBox hintsBox;
    @FXML private Button createButton;
    @FXML private Label createMessage;

    @FXML private TabPane tabs;
    @FXML private Tab summaryTab;
    @FXML private Tab savedTab;

    @FXML private Label loginsValue;
    @FXML private Label loginsNote;
    @FXML private Label filesValue;
    @FXML private Label filesNote;
    @FXML private Label signInsValue;
    @FXML private Label signInsNote;

    @FXML private TableView<ReportService.SignIn> signInTable;
    @FXML private TableColumn<ReportService.SignIn, String> whenColumn;
    @FXML private TableColumn<ReportService.SignIn, String> resultColumn;
    @FXML private TableColumn<ReportService.SignIn, String> deviceColumn;
    @FXML private TableColumn<ReportService.SignIn, String> signedOutColumn;
    @FXML private Label vaultLine;
    @FXML private Label filesLine;

    @FXML private TableView<ReportDAO.Row> reportTable;
    @FXML private TableColumn<ReportDAO.Row, String> nameColumn;
    @FXML private TableColumn<ReportDAO.Row, String> createdColumn;
    @FXML private TableColumn<ReportDAO.Row, String> placeColumn;
    @FXML private HBox confirmBox;
    @FXML private Label confirmLabel;
    @FXML private Label savedMessage;
    @FXML private Button openButton;
    @FXML private Button showButton;
    @FXML private Button deleteButton;

    @FXML private Tab sessionsTab;
    @FXML private TableView<SessionReports.Session> sessionTable;
    @FXML private TableColumn<SessionReports.Session, String> sessionInColumn;
    @FXML private TableColumn<SessionReports.Session, String> sessionOutColumn;
    @FXML private TableColumn<SessionReports.Session, String> sessionDeviceColumn;
    @FXML private TableColumn<SessionReports.Session, String> sessionActionsColumn;
    @FXML private VBox sessionAskBox;
    @FXML private Label sessionAskTitle;
    @FXML private PasswordField sessionMasterField;
    @FXML private HBox sessionCopyBox;
    @FXML private PasswordField sessionCopyField;
    @FXML private PasswordField sessionCopyAgainField;
    @FXML private Label sessionAskMessage;
    @FXML private Button sessionAskConfirm;
    @FXML private Label sessionMessage;
    @FXML private Button sessionOpenButton;
    @FXML private Button sessionShareButton;
    @FXML private Button sessionDeleteButton;
    @FXML private Button sessionDeleteAllButton;

    private final ReportService reports = new ReportService();

    private final SessionReports sessionReports = new SessionReports();

    private enum Ask { SHARE, DELETE, DELETE_ALL }

    private Ask asking;

    // The sign-in report open in its own window, closed with the screen.
    private Stage reportWindow;

    private PdfPreview reportPreview;

    private final VaultService vaultService = new VaultService();

    private Runnable onLocked = () -> { };

    @FXML
    public void initialize() {

        column(whenColumn, ReportService.SignIn::when);
        column(resultColumn, ReportService.SignIn::result);
        column(deviceColumn, ReportService.SignIn::device);
        column(signedOutColumn, ReportService.SignIn::signedOut);

        column(nameColumn, ReportDAO.Row::name);
        column(createdColumn, row -> DisplayFormat.timestamp(row.createdAt()));
        column(placeColumn, row -> row.fileExists() ? String.valueOf(Path.of(row.path()).getParent()) : "Moved or deleted");

        reportTable.getSelectionModel().selectedItemProperty().addListener((observable, before, row) -> {
            hideConfirm();
            showSelection(row);
        });

        showSelection(null);

        column(sessionInColumn, session -> DisplayFormat.timestamp(session.signedInAt()) + (session.current() ? "  (now)" : ""));
        column(sessionOutColumn, session -> session.current() ? "Still signed in"
                : session.signedOutAt() == null ? "Closed without signing out" : DisplayFormat.timestamp(session.signedOutAt()));
        column(sessionDeviceColumn, session -> session.device() == null ? "" : session.device());
        column(sessionActionsColumn, session -> String.valueOf(session.actions()));

        sessionTable.getSelectionModel().selectedItemProperty().addListener((observable, before, session) -> {
            hideSessionAsk();
            showSessionSelection(session);
        });

        showSessionSelection(null);

        refresh();

        loadSessions(null);

    }

    private static <S> void column(TableColumn<S, String> column, Function<S, String> value) {

        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));

    }

    // =====================================================
    // THE SUMMARY
    // =====================================================

    private void refresh() {

        try {

            show(reports.summarize());

            showHistory(null);

        } catch (SQLException | RuntimeException e) {

            AppLogger.error("Couldn't make the report summary: " + e.getMessage());

            Ui.message(createMessage, "The summary couldn't be read. See the console for details.", "bad");

        }

    }

    private void show(ReportService.Summary summary) {

        SecurityScore.Score score = summary.score();

        scoreValue.setText(String.valueOf(score.percent()));

        scoreGrade.setText(score.grade());

        scoreGrade.getStyleClass().removeAll("plain", "good", "warn", "bad");
        scoreGrade.getStyleClass().add(score.percent() >= 60 ? "good" : score.percent() >= 35 ? "warn" : "bad");

        scoreBar.setProgress(score.percent() / 100.0);

        hintsBox.getChildren().clear();

        List<String> hints = score.hints().isEmpty() ? List.of("Nothing to fix: everything checked is in good shape.") : score.hints();

        hints.stream().limit(4).forEach(hint -> {
            Label line = new Label((score.hints().isEmpty() ? "" : "• ") + hint);
            line.getStyleClass().add("hint");
            line.setWrapText(true);
            hintsBox.getChildren().add(line);
        });

        loginsValue.setText(String.valueOf(summary.passwords().total()));
        loginsNote.setText(summary.passwords().total() == 0 ? "None saved yet"
                : summary.passwordsNeedingAttention() == 0 ? "All in good shape"
                : summary.passwordsNeedingAttention() + " need attention: weak, reused or old");

        filesValue.setText(String.valueOf(summary.protectedItems()));
        filesNote.setText(summary.protectedFiles() + (summary.protectedFiles() == 1 ? " file" : " files") + " hidden in the store");

        signInsValue.setText(String.valueOf(summary.signIns()));
        signInsNote.setText(summary.failedSignIns() == 0 ? "No failed attempts"
                : summary.failedSignIns() + (summary.failedSignIns() == 1 ? " failed attempt" : " failed attempts"));

        signInTable.setItems(FXCollections.observableArrayList(summary.recentSignIns()));

        vaultLine.setText("In the last " + ReportService.DAYS + " days the vault was unlocked " + times(summary.unlocks())
                + ", locked by you " + times(summary.locks()) + " and locked itself " + times(summary.autoLocks())
                + ". Auto-lock is set to " + summary.autoLockMinutes() + (summary.autoLockMinutes() == 1 ? " minute." : " minutes."));

        filesLine.setText("Last check of the protected files: " + (summary.lastCheck() == null ? "none in the last " + ReportService.DAYS + " days" : summary.lastCheck())
                + ". Last backup: " + (summary.lastBackup() == null ? "none in the last " + ReportService.DAYS + " days" : summary.lastBackup()) + ".");

    }

    private static String times(int count) {
        return count == 1 ? "once" : count + " times";
    }

    // =====================================================
    // CREATE
    // =====================================================

    @FXML
    private void createReport() {

        FileChooser chooser = new FileChooser();

        chooser.setTitle("Save the security report");

        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF files", "*.pdf"));

        chooser.setInitialFileName(ReportService.defaultFileName());

        Path folder = reports.suggestedFolder();

        if (folder != null && Files.isDirectory(folder)) {
            chooser.setInitialDirectory(folder.toFile());
        }

        File file = chooser.showSaveDialog(createButton.getScene().getWindow());

        if (file != null) {
            createReportAt(file.toPath());
        }

    }

    /** Also used by the tests, in place of the save dialog. */
    void createReportAt(Path chosen) {

        Path target = chosen.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")
                ? chosen
                : chosen.resolveSibling(chosen.getFileName() + ".pdf");

        createButton.setDisable(true);

        Ui.message(createMessage, "Making the report…", null);

        Ui.background("report", () -> {

            try {
                return reports.create(target);
            } catch (IOException | SQLException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }

        }, saved -> {

            createButton.setDisable(false);

            show(saved.summary());

            showHistory(saved.row().id());

            tabs.getSelectionModel().select(savedTab);

            Ui.message(createMessage, "Saved " + saved.row().name() + ".", "good");

        }, error -> {

            createButton.setDisable(false);

            Ui.message(createMessage, "The report couldn't be saved: " + reason(error), "bad");

        });

    }

    // =====================================================
    // SAVED REPORTS
    // =====================================================

    private void showHistory(Long select) {

        try {

            List<ReportDAO.Row> rows = reports.history();

            reportTable.setItems(FXCollections.observableArrayList(rows));

            savedTab.setText(rows.isEmpty() ? "Saved reports" : "Saved reports (" + rows.size() + ")");

            if (select != null) {
                rows.stream().filter(row -> row.id() == select).findFirst().ifPresent(row -> reportTable.getSelectionModel().select(row));
            }

        } catch (SQLException | RuntimeException e) {

            AppLogger.error("Couldn't list the reports: " + e.getMessage());

            Ui.message(savedMessage, "The list of reports couldn't be read.", "bad");

        }

    }

    private void showSelection(ReportDAO.Row row) {

        boolean exists = row != null && row.fileExists();

        openButton.setDisable(!exists);

        showButton.setDisable(!exists);

        deleteButton.setDisable(row == null);

        Ui.message(savedMessage, row != null && !exists ? "This report's PDF was moved or deleted outside CyberShield." : "", row != null && !exists ? "warn" : null);

    }

    @FXML
    private void openReport() {

        ReportDAO.Row row = reportTable.getSelectionModel().getSelectedItem();

        if (row == null || !row.fileExists()) {
            return;
        }

        File file = new File(row.path());

        Ui.background("open-report", () -> {

            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                throw new IllegalStateException("this PC has no program set up to open PDF files");
            }

            try {
                Desktop.getDesktop().open(file);
            } catch (IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }

            return Boolean.TRUE;

        }, opened -> Ui.message(savedMessage, "Opened " + row.name() + " in your PDF reader.", "good"),
                error -> Ui.message(savedMessage, "Couldn't open it: " + reason(error), "bad"));

    }

    @FXML
    private void showInFolder() {

        ReportDAO.Row row = reportTable.getSelectionModel().getSelectedItem();

        if (row == null || !row.fileExists()) {
            return;
        }

        Ui.background("show-report", () -> {

            try {

                if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
                    new ProcessBuilder("explorer.exe", "/select,", row.path()).start();
                } else {
                    Desktop.getDesktop().open(Path.of(row.path()).getParent().toFile());
                }

            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }

            return Boolean.TRUE;

        }, shown -> { }, error -> Ui.message(savedMessage, "Couldn't show the folder: " + reason(error), "bad"));

    }

    @FXML
    private void askDelete() {

        ReportDAO.Row row = reportTable.getSelectionModel().getSelectedItem();

        if (row == null) {
            return;
        }

        confirmLabel.setText(row.fileExists()
                ? "Delete " + row.name() + "? The PDF goes to the Recycle Bin."
                : "Take " + row.name() + " off the list? Its PDF is already gone.");

        confirmBox.setVisible(true);
        confirmBox.setManaged(true);

    }

    @FXML
    private void cancelDelete() {

        hideConfirm();

    }

    private void hideConfirm() {

        confirmBox.setVisible(false);
        confirmBox.setManaged(false);

    }

    @FXML
    private void confirmDelete() {

        ReportDAO.Row row = reportTable.getSelectionModel().getSelectedItem();

        hideConfirm();

        if (row == null) {
            return;
        }

        deleteButton.setDisable(true);

        Ui.background("delete-report", () -> {

            try {
                reports.delete(row);
            } catch (IOException | SQLException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }

            return Boolean.TRUE;

        }, deleted -> {

            showHistory(null);

            Ui.message(savedMessage, "Deleted " + row.name() + ".", "good");

        }, error -> {

            deleteButton.setDisable(false);

            Ui.message(savedMessage, "Couldn't delete it: " + reason(error), "bad");

        });

    }

    private static String reason(Throwable error) {

        Throwable cause = error;

        while (cause.getCause() != null && cause.getMessage() != null && cause.getMessage().equals(cause.getCause().getMessage())) {
            cause = cause.getCause();
        }

        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();

    }

    // =====================================================
    // SIGN-IN REPORTS
    // =====================================================

    /** Makes any reports still missing, then lists them; selects keep if given. */
    private void loadSessions(Long keep) {

        Ui.background("sign-in-reports", () -> {

            try {
                sessionReports.catchUp();
                return sessionReports.list();
            } catch (SQLException | IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }

        }, list -> {

            sessionTable.setItems(FXCollections.observableArrayList(list));

            long stored = list.stream().filter(session -> !session.current()).count();

            sessionsTab.setText(stored == 0 ? "Sign-in reports" : "Sign-in reports (" + stored + ")");

            sessionDeleteAllButton.setDisable(stored == 0);

            if (keep != null) {
                list.stream().filter(session -> session.sessionId() == keep).findFirst()
                        .ifPresent(session -> sessionTable.getSelectionModel().select(session));
            }

        }, error -> Ui.message(sessionMessage, "The sign-in reports couldn't be read: " + reason(error), "bad"));

    }

    private void showSessionSelection(SessionReports.Session session) {

        sessionOpenButton.setDisable(session == null);

        sessionShareButton.setDisable(session == null);

        sessionDeleteButton.setDisable(session == null || session.current());

    }

    @FXML
    private void openSession() {

        SessionReports.Session session = sessionTable.getSelectionModel().getSelectedItem();

        if (session == null) {
            return;
        }

        Ui.message(sessionMessage, "Opening…", null);

        Ui.background("open-sign-in-report", () -> {

            try {
                return sessionReports.pdf(session);
            } catch (SQLException | IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }

        }, bytes -> {

            Ui.message(sessionMessage, "", null);

            showReport(session, bytes);

        }, error -> Ui.message(sessionMessage, "Couldn't open it: " + reason(error), "bad"));

    }

    /** Shows a sign-in report in its own window, from memory: nothing is written to disk. */
    private void showReport(SessionReports.Session session, byte[] bytes) {

        closeReportWindow();

        PdfPreview preview;

        try {
            preview = new PdfPreview(PdfPreview.load(bytes), bytes);
        } catch (IOException e) {
            Arrays.fill(bytes, (byte) 0);
            Ui.message(sessionMessage, "Couldn't open it: " + e.getMessage(), "bad");
            return;
        }

        HBox bar = new HBox(8);
        bar.getChildren().addAll(preview.controls());
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 14, 10, 14));

        BorderPane root = new BorderPane(preview.view());
        root.setBottom(bar);
        root.setStyle("-fx-background-color: #FAF6F0;");

        Scene scene = new Scene(root, 860, 760);
        scene.getStylesheets().add(Ui.class.getResource("/css/theme.css").toExternalForm());

        Stage window = new Stage();
        window.initOwner(sessionTable.getScene().getWindow());
        window.setTitle("Sign-in report · " + DisplayFormat.timestamp(session.signedInAt()));
        window.getIcons().setAll(com.cybershield.ui.Logo.windowIcons());
        window.setScene(scene);
        window.setOnHidden(event -> {
            preview.dispose();
            if (reportWindow == window) {
                reportWindow = null;
                reportPreview = null;
            }
        });

        reportWindow = window;
        reportPreview = preview;

        window.show();

    }

    private void closeReportWindow() {

        if (reportWindow != null) {
            reportWindow.close();
        }

        if (reportPreview != null) {
            reportPreview.dispose();
        }

        reportWindow = null;
        reportPreview = null;

    }

    @FXML
    private void askShareSession() {

        SessionReports.Session session = sessionTable.getSelectionModel().getSelectedItem();

        if (session != null) {
            ask(Ask.SHARE, "Share the report of " + DisplayFormat.timestamp(session.signedInAt())
                    + ". The copy opens in any PDF reader, only with the password you choose here: give it to whoever should read it.",
                    "Save copy…");
        }

    }

    @FXML
    private void askDeleteSession() {

        SessionReports.Session session = sessionTable.getSelectionModel().getSelectedItem();

        if (session != null && !session.current()) {
            ask(Ask.DELETE, "Delete the report of " + DisplayFormat.timestamp(session.signedInAt())
                    + "? It can't be brought back.", "Delete");
        }

    }

    @FXML
    private void askDeleteAllSessions() {

        long stored = sessionTable.getItems().stream().filter(session -> !session.current()).count();

        if (stored > 0) {
            ask(Ask.DELETE_ALL, "Delete all " + stored + (stored == 1 ? " sign-in report" : " sign-in reports")
                    + "? They can't be brought back.", "Delete all");
        }

    }

    private void ask(Ask what, String title, String confirmText) {

        asking = what;

        sessionAskTitle.setText(title);

        sessionCopyBox.setVisible(what == Ask.SHARE);
        sessionCopyBox.setManaged(what == Ask.SHARE);

        sessionAskConfirm.setText(confirmText);
        sessionAskConfirm.getStyleClass().removeAll("primary-button", "danger-button");
        sessionAskConfirm.getStyleClass().add(what == Ask.SHARE ? "primary-button" : "danger-button");

        clearSessionPasswords();

        Ui.message(sessionAskMessage, "", null);

        sessionAskBox.setVisible(true);
        sessionAskBox.setManaged(true);

        sessionMasterField.requestFocus();

    }

    @FXML
    private void cancelSessionAction() {

        hideSessionAsk();

    }

    private void hideSessionAsk() {

        clearSessionPasswords();

        sessionAskBox.setVisible(false);
        sessionAskBox.setManaged(false);

    }

    private void clearSessionPasswords() {

        sessionMasterField.clear();
        sessionCopyField.clear();
        sessionCopyAgainField.clear();

    }

    @FXML
    private void confirmSessionAction() {

        String master = sessionMasterField.getText();

        if (master.isEmpty()) {
            Ui.message(sessionAskMessage, "Type your master password.", "bad");
            return;
        }

        SessionReports.Session session = sessionTable.getSelectionModel().getSelectedItem();

        switch (asking == null ? Ask.DELETE_ALL : asking) {

            case SHARE -> shareSession(session, master);

            case DELETE -> deleteSessions(session, master);

            case DELETE_ALL -> deleteSessions(null, master);

        }

    }

    private void shareSession(SessionReports.Session session, String master) {

        if (session == null) {
            return;
        }

        String copy = sessionCopyField.getText();

        if (copy.length() < SessionReports.MIN_COPY_PASSWORD) {
            Ui.message(sessionAskMessage, "The copy's password needs " + SessionReports.MIN_COPY_PASSWORD + " or more characters.", "bad");
            return;
        }

        if (!copy.equals(sessionCopyAgainField.getText())) {
            Ui.message(sessionAskMessage, "The two passwords for the copy don't match.", "bad");
            return;
        }

        if (copy.equals(master)) {
            Ui.message(sessionAskMessage, "Use a different password from your master password: you'll give this one to someone.", "bad");
            return;
        }

        FileChooser chooser = new FileChooser();

        chooser.setTitle("Save a protected copy of the sign-in report");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF files", "*.pdf"));
        chooser.setInitialFileName(SessionReports.fileName(session));

        Path documents = Path.of(System.getProperty("user.home"), "Documents");

        if (Files.isDirectory(documents)) {
            chooser.setInitialDirectory(documents.toFile());
        }

        File file = chooser.showSaveDialog(sessionTable.getScene().getWindow());

        if (file != null) {
            shareSessionTo(session, master, copy, file.toPath());
        }

    }

    /** Also used by the tests, in place of the save dialog. */
    void shareSessionTo(SessionReports.Session session, String master, String copy, Path target) {

        sessionAskConfirm.setDisable(true);

        Ui.message(sessionAskMessage, "Checking your master password…", null);

        Ui.background("share-sign-in-report", () -> {

            try {
                return sessionReports.share(session, master, copy, target);
            } catch (SQLException | IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }

        }, outcome -> {

            sessionAskConfirm.setDisable(false);

            if (outcome == SessionReports.Outcome.WRONG_PASSWORD) {
                sessionMasterField.clear();
                Ui.message(sessionAskMessage, "That isn't your master password.", "bad");
                return;
            }

            hideSessionAsk();

            Ui.message(sessionMessage, "Saved " + target.getFileName() + ". It opens with the password you chose.", "good");

        }, error -> {

            sessionAskConfirm.setDisable(false);

            Ui.message(sessionAskMessage, "Couldn't save the copy: " + reason(error), "bad");

        });

    }

    private void deleteSessions(SessionReports.Session session, String master) {

        sessionAskConfirm.setDisable(true);

        Ui.message(sessionAskMessage, "Checking your master password…", null);

        Ui.background("delete-sign-in-reports", () -> {

            try {
                return session == null ? sessionReports.deleteAll(master) : sessionReports.delete(session, master);
            } catch (SQLException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }

        }, outcome -> {

            sessionAskConfirm.setDisable(false);

            if (outcome == SessionReports.Outcome.WRONG_PASSWORD) {
                sessionMasterField.clear();
                Ui.message(sessionAskMessage, "That isn't your master password.", "bad");
                return;
            }

            hideSessionAsk();

            loadSessions(null);

            Ui.message(sessionMessage, session == null ? "All sign-in reports were deleted." : "Deleted.", "good");

        }, error -> {

            sessionAskConfirm.setDisable(false);

            Ui.message(sessionAskMessage, "Couldn't delete: " + reason(error), "bad");

        });

    }

    // =====================================================
    // LOCK
    // =====================================================

    @FXML
    private void lockVault() {

        dispose();

        vaultService.lockVault();

        onLocked.run();

    }

    @Override
    public void setOnLocked(Runnable onLocked) {

        this.onLocked = onLocked;

    }

    @Override
    public void dispose() {

        closeReportWindow();

        clearSessionPasswords();

        sessionTable.getItems().clear();

        signInTable.getItems().clear();

        reportTable.getItems().clear();

        hintsBox.getChildren().clear();

    }

}
