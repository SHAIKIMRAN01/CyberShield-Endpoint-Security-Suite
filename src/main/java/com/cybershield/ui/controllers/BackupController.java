package com.cybershield.ui.controllers;

import com.cybershield.backup.BackupScan;
import com.cybershield.backup.BackupScan.Options;
import com.cybershield.backup.BackupScan.Plan;
import com.cybershield.backup.BackupScan.Source;
import com.cybershield.backup.BackupWriter;
import com.cybershield.backup.BackupWriter.Extra;
import com.cybershield.backup.BackupWriter.Result;
import com.cybershield.backup.InstalledApps;
import com.cybershield.logger.AppLogger;
import com.cybershield.ui.Ui;
import com.cybershield.utils.DisplayFormat;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.HBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Backup: makes one compressed copy of the folders the person chose, saved where they chose.
 * It previews first — showing how many files and how big, and what the filters left out — and
 * only copies after they press Back up now. Only the chosen folders are read; nothing is
 * changed, and it needs no vault. It never reaches into other apps' private data.
 */
public class BackupController implements LockableScreen {

    @FXML private ListView<Source> sourceList;
    @FXML private Button addFolderButton;
    @FXML private Button removeFolderButton;
    @FXML private CheckBox recentCheck;
    @FXML private Spinner<Integer> recentDaysSpinner;
    @FXML private CheckBox sizeCheck;
    @FXML private Spinner<Integer> sizeMbSpinner;
    @FXML private CheckBox appsCheck;
    @FXML private Label destLabel;
    @FXML private Button changeDestButton;
    @FXML private Button previewButton;
    @FXML private Button backupButton;
    @FXML private Button stopButton;
    @FXML private Button openFolderButton;
    @FXML private Label summaryLabel;
    @FXML private HBox progressBox;
    @FXML private ProgressBar progressBar;
    @FXML private Label statusLabel;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private volatile boolean busy;

    private Path destination;

    private Plan lastPlan;

    private int lastSourceCount;

    private Path lastArchive;

    @FXML
    public void initialize() {

        sourceList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Source source, boolean empty) {
                super.updateItem(source, empty);
                if (empty || source == null) {
                    setText(null);
                } else {
                    setText(source.label() + "   —   " + source.dir());
                }
            }
        });
        sourceList.setItems(FXCollections.observableArrayList(defaultSources()));

        recentDaysSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 3650, 7));
        sizeMbSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 100_000, 500));

        recentCheck.setSelected(true);
        sizeCheck.setSelected(true);
        appsCheck.setSelected(true);
        recentDaysSpinner.disableProperty().bind(recentCheck.selectedProperty().not());
        sizeMbSpinner.disableProperty().bind(sizeCheck.selectedProperty().not());

        destination = defaultDestination();
        destLabel.setText(destination.toString());

        // any change means the last preview no longer matches, so ask for a fresh one
        sourceList.getItems().addListener((javafx.collections.ListChangeListener<Source>) change -> markStale());
        recentCheck.selectedProperty().addListener((o, was, is) -> markStale());
        sizeCheck.selectedProperty().addListener((o, was, is) -> markStale());
        appsCheck.selectedProperty().addListener((o, was, is) -> markStale());
        recentDaysSpinner.valueProperty().addListener((o, was, is) -> markStale());
        sizeMbSpinner.valueProperty().addListener((o, was, is) -> markStale());

        show(progressBox, false);
        show(summaryLabel, false);
        show(stopButton, false);
        show(openFolderButton, false);
        backupButton.setDisable(true);
    }

    // ---------------------------------------------------------------- choosing folders and destination

    @FXML
    private void addFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Add a folder to back up");
        File chosen = chooser.showDialog(window());
        if (chosen != null) {
            Path dir = chosen.toPath();
            boolean already = sourceList.getItems().stream().anyMatch(s -> s.dir().equals(dir));
            if (!already) {
                sourceList.getItems().add(new Source(dir, folderLabel(dir)));
            }
        }
    }

    @FXML
    private void removeFolder() {
        Source selected = sourceList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            sourceList.getItems().remove(selected);
        }
    }

    @FXML
    private void changeDestination() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Where to save the backup");
        if (destination != null && Files.isDirectory(destination)) {
            chooser.setInitialDirectory(destination.toFile());
        }
        File chosen = chooser.showDialog(window());
        if (chosen != null) {
            destination = chosen.toPath();
            destLabel.setText(destination.toString());
            markStale();
        }
    }

    // ---------------------------------------------------------------- preview, then back up

    @FXML
    private void preview() {

        if (busy) {
            return;
        }
        List<Source> sources = new ArrayList<>(sourceList.getItems());
        if (sources.isEmpty()) {
            summaryLabel.setText("Add at least one folder to back up first.");
            show(summaryLabel, true);
            return;
        }

        Options options = currentOptions();
        lastSourceCount = sources.size();

        busy = true;
        cancelled.set(false);
        show(openFolderButton, false);
        show(progressBox, true);
        progressBar.setProgress(-1);
        statusLabel.setText("Looking through the folders…");
        setInputsDisabled(true);

        Ui.background("backup-preview",
                () -> new BackupScan().scan(sources, options, scanProgress()),
                this::showPlan, this::showFailure);
    }

    private void showPlan(Plan plan) {
        busy = false;
        lastPlan = plan;
        show(progressBox, false);
        setInputsDisabled(false);

        if (plan.count() == 0) {
            summaryLabel.setText("Nothing matched — the folders held no files within your filters. "
                    + "Adjust the folders or filters and preview again.");
            backupButton.setDisable(true);
        } else {
            StringBuilder text = new StringBuilder("Ready to back up ")
                    .append(plan.count()).append(plan.count() == 1 ? " file (" : " files (")
                    .append(DisplayFormat.size(plan.totalBytes())).append(") from ")
                    .append(lastSourceCount).append(lastSourceCount == 1 ? " folder." : " folders.");
            if (plan.skippedTooBig() > 0 || plan.skippedTooOld() > 0) {
                text.append("  Left out: ").append(plan.skippedTooBig()).append(" too big, ")
                        .append(plan.skippedTooOld()).append(" older than the limit.");
            }
            text.append("  Press Back up now to make the copy.");
            summaryLabel.setText(text.toString());
            backupButton.setDisable(false);
        }
        show(summaryLabel, true);
    }

    @FXML
    private void startBackup() {

        if (busy || lastPlan == null || lastPlan.count() == 0) {
            return;
        }

        Plan plan = lastPlan;
        boolean includeApps = appsCheck.isSelected();
        Path archive = destination.resolve("CyberShield Backup "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm")) + ".zip");

        busy = true;
        cancelled.set(false);
        show(openFolderButton, false);
        show(progressBox, true);
        show(stopButton, true);
        stopButton.setDisable(false);
        progressBar.setProgress(0);
        statusLabel.setText("Starting…");
        setInputsDisabled(true);
        backupButton.setDisable(true);

        Ui.background("backup-write", () -> runBackup(plan, archive, includeApps), this::showDone, this::backupError);
    }

    private Result runBackup(Plan plan, Path archive, boolean includeApps) {

        List<Extra> extras = new ArrayList<>();
        if (includeApps) {
            List<String> apps = InstalledApps.read();
            extras.add(new Extra("installed-apps.txt", InstalledApps.asText(apps).getBytes(StandardCharsets.UTF_8)));
        }

        try {
            // a CancellationException from the writer is unchecked and passes straight through to backupError
            return new BackupWriter().write(plan, archive, extras, writeProgress());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e.getMessage(), e);
        }
    }

    private void showDone(Result result) {
        busy = false;
        lastArchive = result.archive();
        show(progressBox, false);
        show(stopButton, false);
        setInputsDisabled(false);
        backupButton.setDisable(true);
        summaryLabel.setText("Backed up " + result.files() + (result.files() == 1 ? " file (" : " files (")
                + DisplayFormat.size(result.bytes()) + ") to " + result.archive().getFileName() + ".");
        show(summaryLabel, true);
        show(openFolderButton, true);
        AppLogger.info("Backup finished: " + result.files() + " files.");
    }

    private void showFailure(Throwable error) {
        busy = false;
        show(progressBox, false);
        setInputsDisabled(false);
        summaryLabel.setText("Couldn't look through the folders. Details are in CyberShield's log file.");
        show(summaryLabel, true);
        AppLogger.error("Backup preview failed", error);
    }

    private void backupError(Throwable error) {
        busy = false;
        show(progressBox, false);
        show(stopButton, false);
        setInputsDisabled(false);
        if (error instanceof CancellationException || error.getCause() instanceof CancellationException) {
            summaryLabel.setText("Backup stopped. The part-written file was removed, so nothing was left behind.");
            backupButton.setDisable(lastPlan == null || lastPlan.count() == 0);
        } else {
            summaryLabel.setText("The backup couldn't finish. Details are in CyberShield's log file.");
            AppLogger.error("Backup failed", error);
        }
        show(summaryLabel, true);
    }

    @FXML
    private void stop() {
        cancelled.set(true);
        stopButton.setDisable(true);
        statusLabel.setText("Stopping…");
    }

    @FXML
    private void openFolder() {
        if (lastArchive == null) {
            return;
        }
        try {
            new ProcessBuilder("explorer.exe", "/select,", lastArchive.toString()).start();
        } catch (IOException e) {
            AppLogger.warn("Backup: couldn't open the folder (" + e.getMessage() + ")");
        }
    }

    // ---------------------------------------------------------------- helpers

    private Options currentOptions() {
        int days = recentCheck.isSelected() ? recentDaysSpinner.getValue() : 0;
        long maxBytes = sizeCheck.isSelected() ? sizeMbSpinner.getValue() * 1024L * 1024L : 0;
        return new Options(days, maxBytes, 200_000);
    }

    private void markStale() {
        lastPlan = null;
        backupButton.setDisable(true);
        show(openFolderButton, false);
    }

    private void setInputsDisabled(boolean disabled) {
        previewButton.setDisable(disabled);
        addFolderButton.setDisable(disabled);
        removeFolderButton.setDisable(disabled);
        changeDestButton.setDisable(disabled);
        sourceList.setDisable(disabled);
        recentCheck.setDisable(disabled);
        sizeCheck.setDisable(disabled);
        appsCheck.setDisable(disabled);
    }

    private BackupScan.Progress scanProgress() {
        return new BackupScan.Progress() {
            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }

            @Override
            public void update(int filesSeen, Path current) {
                Platform.runLater(() -> {
                    if (busy) {
                        statusLabel.setText("Looked through " + filesSeen + " files…");
                    }
                });
            }
        };
    }

    private BackupWriter.Progress writeProgress() {
        return new BackupWriter.Progress() {
            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }

            @Override
            public void update(int done, int total, Path current) {
                Platform.runLater(() -> {
                    if (busy) {
                        progressBar.setProgress(total == 0 ? 0 : (double) done / total);
                        statusLabel.setText("Copied " + done + " of " + total + " items…");
                    }
                });
            }
        };
    }

    private static List<Source> defaultSources() {
        List<Source> sources = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (home != null) {
            Path base = Path.of(home);
            for (String name : List.of("Pictures", "Documents", "Downloads", "Desktop", "Videos", "Contacts")) {
                Path dir = base.resolve(name);
                if (Files.isDirectory(dir)) {
                    sources.add(new Source(dir, name));
                }
            }
        }
        return sources;
    }

    private static Path defaultDestination() {
        String home = System.getProperty("user.home");
        Path base = home != null ? Path.of(home) : Path.of(".");
        Path documents = base.resolve("Documents");
        return (Files.isDirectory(documents) ? documents : base).resolve("CyberShield Backups");
    }

    private static String folderLabel(Path dir) {
        Path name = dir.getFileName();
        return name != null ? name.toString() : dir.toString();
    }

    private Window window() {
        return sourceList.getScene() == null ? null : sourceList.getScene().getWindow();
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    // ---------------------------------------------------------------- test hook

    /** Test hook: show a canned preview without touching the disk (for the screenshot walk). */
    void showForDemo(List<Source> sources, String summary) {
        sourceList.setItems(FXCollections.observableArrayList(sources));
        destLabel.setText("C:\\Users\\sam\\Documents\\CyberShield Backups");
        summaryLabel.setText(summary);
        show(summaryLabel, true);
        backupButton.setDisable(false);
    }

    @Override
    public void setOnLocked(Runnable onLocked) {
        // the Backup tool works without the vault and has no Lock button; nothing to wire
    }

    @Override
    public void dispose() {
        // leaving the screen stops a preview or backup still running
        cancelled.set(true);
        busy = false;
    }

}
