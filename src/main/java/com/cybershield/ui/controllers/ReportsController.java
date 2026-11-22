package com.cybershield.ui.controllers;

import com.cybershield.logger.AppLogger;
import com.cybershield.reports.ReportDAO;
import com.cybershield.reports.ReportService;
import com.cybershield.services.SecurityScore;
import com.cybershield.ui.Ui;
import com.cybershield.utils.DisplayFormat;
import com.cybershield.vault.VaultService;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
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

    private final ReportService reports = new ReportService();

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

        refresh();

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

        signInTable.getItems().clear();

        reportTable.getItems().clear();

        hintsBox.getChildren().clear();

    }

}
