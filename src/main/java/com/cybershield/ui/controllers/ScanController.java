package com.cybershield.ui.controllers;

import com.cybershield.logger.AppLogger;
import com.cybershield.scan.FileScan;
import com.cybershield.scan.SecurityCheck;
import com.cybershield.scan.WindowsFacts;
import com.cybershield.ui.Ui;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.util.Callback;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Security Scan: runs the read-only {@link SecurityCheck} posture read and the
 * {@link FileScan} risky-file finder, and shows what they turn up. A quick scan is
 * shallow and fast; a deep scan walks the whole user folder and fingerprints the files
 * it flags. The work runs off the UI thread and can be stopped; leaving the screen
 * (which calls {@link #dispose()}) stops it too. Nothing on disk is changed.
 */
public class ScanController implements LockableScreen {

    @FXML private Button quickScanButton;
    @FXML private Button deepScanButton;
    @FXML private Button stopButton;
    @FXML private HBox progressBox;
    @FXML private ProgressBar progressBar;
    @FXML private Label statusLabel;
    @FXML private Label summaryLabel;
    @FXML private TableView<Row> resultsTable;
    @FXML private TableColumn<Row, String> levelColumn;
    @FXML private TableColumn<Row, String> findingColumn;
    @FXML private TableColumn<Row, String> detailColumn;
    @FXML private TableColumn<Row, String> whereColumn;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private volatile boolean scanning;

    /** One line in the results table. */
    public record Row(String level, String finding, String detail, String where) {
    }

    /** What a finished scan hands back to the UI thread. */
    private record Report(List<Row> rows, String summary) {
    }

    @FXML
    public void initialize() {

        resultsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        resultsTable.setPlaceholder(new Label(
                "Run a scan to see results. CyberShield only looks — nothing is opened or changed."));

        levelColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().level()));
        findingColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().finding()));
        detailColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().detail()));
        whereColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().where()));

        levelColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                getStyleClass().removeAll("level-tag", "level-risk", "level-warn", "level-review",
                        "level-good", "level-note", "level-info", "level-unknown");
                if (empty || value == null) {
                    setText(null);
                } else {
                    setText(value);
                    getStyleClass().addAll("level-tag", classForLabel(value));
                }
            }
        });

        detailColumn.setCellFactory(wrapping());
        whereColumn.setCellFactory(wrapping());

        show(progressBox, false);
        show(summaryLabel, false);
    }

    @FXML
    private void runQuickScan() {
        startScan(false);
    }

    @FXML
    private void runDeepScan() {
        startScan(true);
    }

    @FXML
    private void stopScan() {
        cancelled.set(true);
        stopButton.setDisable(true);
        statusLabel.setText("Stopping…");
    }

    private void startScan(boolean deep) {

        if (scanning) {
            return;
        }
        scanning = true;
        cancelled.set(false);

        resultsTable.getItems().clear();
        show(summaryLabel, false);
        show(progressBox, true);
        progressBar.setProgress(-1);
        statusLabel.setText("Reading security settings…");
        quickScanButton.setDisable(true);
        deepScanButton.setDisable(true);
        stopButton.setDisable(false);

        FileScan.Progress progress = new FileScan.Progress() {
            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }

            @Override
            public void update(int filesSeen, Path current) {
                Platform.runLater(() -> {
                    if (scanning) {
                        statusLabel.setText("Looked through " + filesSeen + " files…");
                    }
                });
            }
        };

        Ui.background("security-scan", () -> runScan(deep, progress), this::showReport, this::showFailure);
    }

    /** Runs off the UI thread. Reads settings and walks files; changes nothing. */
    private Report runScan(boolean deep, FileScan.Progress progress) {

        List<SecurityCheck.Finding> posture = new SecurityCheck().check(WindowsFacts.read());

        if (cancelled.get()) {
            return new Report(List.of(), "Scan stopped before it finished.");
        }

        Platform.runLater(() -> {
            if (scanning) {
                statusLabel.setText("Looking through your files…");
            }
        });

        List<FileScan.Finding> files = new FileScan().scan(
                deep ? FileScan.deepRoots() : FileScan.quickRoots(),
                deep ? FileScan.Options.deep() : FileScan.Options.quick(),
                progress);

        List<Row> rows = new ArrayList<>();

        posture.stream()
                .sorted(Comparator.comparingInt(f -> postureRank(f.status())))
                .forEach(f -> rows.add(postureRow(f)));

        files.stream()
                .sorted(Comparator.comparingInt((FileScan.Finding f) -> fileRank(f.level()))
                        .thenComparing(f -> f.path().toString()))
                .forEach(f -> rows.add(fileRow(f)));

        AppLogger.info("Security scan finished (" + (deep ? "deep" : "quick") + "): "
                + SecurityCheck.summarise(posture).needsAttention() + " settings to look at, "
                + files.size() + " file findings.");

        return new Report(rows, summarise(posture, files, cancelled.get()));
    }

    private void showReport(Report report) {
        scanning = false;
        show(progressBox, false);
        resultsTable.getItems().setAll(report.rows());
        summaryLabel.setText(report.summary());
        show(summaryLabel, true);
        quickScanButton.setDisable(false);
        deepScanButton.setDisable(false);
    }

    private void showFailure(Throwable error) {
        scanning = false;
        show(progressBox, false);
        summaryLabel.setText("The scan couldn't finish. Details are in CyberShield's log file.");
        show(summaryLabel, true);
        quickScanButton.setDisable(false);
        deepScanButton.setDisable(false);
        AppLogger.error("Security scan failed", error);
    }

    /** Test hook: show canned results without reading the machine (for the screenshot walk). */
    void showForDemo(List<Row> rows, String summary) {
        resultsTable.getItems().setAll(rows);
        summaryLabel.setText(summary);
        show(summaryLabel, true);
        show(progressBox, false);
    }

    static Row postureRow(SecurityCheck.Finding f) {
        String detail = f.detail();
        if (f.advice() != null && !f.advice().isBlank()) {
            detail = detail + "    →  " + f.advice();
        }
        return new Row(f.status().label(), f.title(), detail, "Windows setting");
    }

    static Row fileRow(FileScan.Finding f) {
        String detail = f.reason().detail();
        if (f.sha256() != null) {
            detail = detail + "    ·  SHA-256 " + f.sha256();
        }
        return new Row(f.level().label(), f.reason().title(), detail, f.path().toString());
    }

    static String summarise(List<SecurityCheck.Finding> posture, List<FileScan.Finding> files, boolean stopped) {

        SecurityCheck.Summary s = SecurityCheck.summarise(posture);

        long review = files.stream().filter(f -> f.level() == FileScan.Level.REVIEW).count();
        long note = files.stream().filter(f -> f.level() == FileScan.Level.NOTE).count();

        List<String> parts = new ArrayList<>();
        if (s.needsAttention() > 0) {
            parts.add(s.needsAttention() + " setting" + plural(s.needsAttention()) + " to look at");
        }
        if (review > 0) {
            parts.add(review + " file" + plural(review) + " worth a look");
        }
        if (note > 0) {
            parts.add(note + " file" + plural(note) + " to be aware of");
        }

        String head = stopped ? "Scan stopped. " : "";

        if (parts.isEmpty()) {
            return head + "Good news — nothing stood out. Your security settings are listed below.";
        }
        return head + "Found " + String.join(" · ", parts) + ". See below.";
    }

    private static int postureRank(SecurityCheck.Status status) {
        return switch (status) {
            case RISK -> 0;
            case WARN -> 1;
            case UNKNOWN -> 2;
            case INFO -> 3;
            case GOOD -> 4;
        };
    }

    private static int fileRank(FileScan.Level level) {
        return switch (level) {
            case REVIEW -> 0;
            case NOTE -> 1;
            case INFO -> 2;
        };
    }

    static String classForLabel(String label) {
        return switch (label) {
            case "Needs attention" -> "level-risk";
            case "Worth a look" -> "level-warn";
            case "Good to know" -> "level-note";
            case "Good" -> "level-good";
            case "Couldn't check" -> "level-unknown";
            default -> "level-info";
        };
    }

    private static Callback<TableColumn<Row, String>, TableCell<Row, String>> wrapping() {
        return col -> new TableCell<>() {
            private final Text text = new Text();

            {
                text.getStyleClass().add("cell-text");
                text.wrappingWidthProperty().bind(col.widthProperty().subtract(14));
                setGraphic(text);
                setPrefHeight(Region.USE_COMPUTED_SIZE);
            }

            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                text.setText(empty || value == null ? "" : value);
            }
        };
    }

    private static String plural(long n) {
        return n == 1 ? "" : "s";
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    @Override
    public void setOnLocked(Runnable onLocked) {
        // the Security Scan works without the vault and has no Lock button; nothing to wire
    }

    @Override
    public void dispose() {
        // leaving the screen stops any scan still running
        cancelled.set(true);
        scanning = false;
    }

}
