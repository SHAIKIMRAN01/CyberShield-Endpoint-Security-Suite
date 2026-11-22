package com.cybershield.ui.controllers;

import com.cybershield.privacy.PhotoDetails;
import com.cybershield.privacy.Shredder;
import com.cybershield.privacy.TraceCleaner;
import com.cybershield.services.ActivityLog;
import com.cybershield.ui.OnScreenKeyboard;
import com.cybershield.ui.Ui;
import com.cybershield.utils.DisplayFormat;

import javafx.beans.property.SimpleStringProperty;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Privacy Tools: shred files, clear the clipboard, remove photo details, clean the
 * traces Windows keeps, and the on-screen keyboard. Works without the vault.
 */
public class PrivacyController implements LockableScreen {

    @FXML private Button shredTab;
    @FXML private Button clipboardTab;
    @FXML private Button photoTab;
    @FXML private Button tracesTab;
    @FXML private Button keyboardTab;
    @FXML private VBox shredPanel;
    @FXML private VBox clipboardPanel;
    @FXML private VBox photoPanel;
    @FXML private VBox tracesPanel;
    @FXML private VBox keyboardPanel;

    @FXML private ListView<Path> shredList;
    @FXML private Label shredSummary;
    @FXML private HBox shredConfirmBox;
    @FXML private Label shredConfirmLabel;
    @FXML private HBox shredProgressBox;
    @FXML private ProgressBar shredProgress;
    @FXML private Label shredProgressLabel;
    @FXML private Button shredButton;
    @FXML private Label shredMessage;

    @FXML private Label clipboardMessage;

    @FXML private TableView<PhotoRow> photoTable;
    @FXML private TableColumn<PhotoRow, String> photoNameColumn;
    @FXML private TableColumn<PhotoRow, String> photoFoundColumn;
    @FXML private Button cleanPhotosButton;
    @FXML private Label photoMessage;

    @FXML private Button scanTracesButton;
    @FXML private Label scanTracesLabel;
    @FXML private VBox traceChoices;
    @FXML private HBox tracesConfirmBox;
    @FXML private Label tracesConfirmLabel;
    @FXML private Button cleanTracesButton;
    @FXML private Label tracesMessage;

    @FXML private PasswordField tryKeyboardField;
    @FXML private Label tryKeyboardEcho;

    /** A photo in the list, with what it gives away, or why it couldn't be read. */
    record PhotoRow(Path path, PhotoDetails.Found found, String problem) {
    }

    /** Photos are read whole; anything bigger than this is no ordinary photo. */
    private static final long MAX_PHOTO_BYTES = 256L * 1024 * 1024;

    private final Shredder shredder = new Shredder();

    private TraceCleaner cleaner = new TraceCleaner();

    private Shredder.Plan shredPlan;

    private final AtomicBoolean stopShred = new AtomicBoolean();

    private final Map<CheckBox, TraceCleaner.Found> traceBoxes = new LinkedHashMap<>();

    @FXML
    public void initialize() {

        shredList.setCellFactory(list -> new ListCell<>() {

            @Override
            protected void updateItem(Path path, boolean empty) {

                super.updateItem(path, empty);

                if (empty || path == null) {
                    setText(null);
                    return;
                }

                boolean folder = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);

                setText((folder ? "Folder: " : "") + path.getFileName() + "   (" + path.getParent() + ")");

            }

        });

        // Files and folders can be dropped from Explorer onto the list.
        shredList.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });

        shredList.setOnDragDropped(event -> {
            if (event.getDragboard().hasFiles()) {
                addToShred(event.getDragboard().getFiles().stream().map(File::toPath).toList());
                event.setDropCompleted(true);
            }
            event.consume();
        });

        photoTable.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });

        photoTable.setOnDragDropped(event -> {
            if (event.getDragboard().hasFiles()) {
                addPhotos(event.getDragboard().getFiles().stream().map(File::toPath).filter(PrivacyController::isPhoto).toList());
                event.setDropCompleted(true);
            }
            event.consume();
        });

        photoNameColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().path().getFileName().toString()));
        photoFoundColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().problem() != null
                ? cell.getValue().problem() : cell.getValue().found().describe()));

        // What a photo gives away can be long: it wraps onto more lines instead of being cut off.
        photoFoundColumn.setCellFactory(column -> {

            TableCell<PhotoRow, String> cell = new TableCell<>();

            Text text = new Text();

            text.wrappingWidthProperty().bind(column.widthProperty().subtract(16));
            text.textProperty().bind(cell.itemProperty());
            text.fillProperty().bind(cell.textFillProperty());
            text.fontProperty().bind(cell.fontProperty());

            cell.setGraphic(text);
            cell.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            cell.setPrefHeight(Control.USE_COMPUTED_SIZE);

            return cell;

        });

        OnScreenKeyboard.attach(tryKeyboardField);

        tryKeyboardField.textProperty().addListener((observable, before, text) ->
                tryKeyboardEcho.setText(text.isEmpty() ? "" : text.length() + (text.length() == 1 ? " character typed" : " characters typed")));

        showShred();

    }

    // =====================================================
    // SWITCHING
    // =====================================================

    @FXML
    private void showShred() {
        show(shredTab, shredPanel);
    }

    @FXML
    private void showClipboard() {
        show(clipboardTab, clipboardPanel);
    }

    @FXML
    private void showPhotos() {
        show(photoTab, photoPanel);
    }

    @FXML
    private void showTraces() {
        show(tracesTab, tracesPanel);
    }

    @FXML
    private void showKeyboard() {
        show(keyboardTab, keyboardPanel);
    }

    private void show(Button tab, VBox panel) {

        for (Button each : List.of(shredTab, clipboardTab, photoTab, tracesTab, keyboardTab)) {
            each.getStyleClass().remove("active");
        }

        tab.getStyleClass().add("active");

        for (VBox each : List.of(shredPanel, clipboardPanel, photoPanel, tracesPanel, keyboardPanel)) {
            each.setVisible(each == panel);
        }

    }

    // =====================================================
    // SHRED
    // =====================================================

    @FXML
    private void addShredFiles() {

        FileChooser chooser = new FileChooser();

        chooser.setTitle("Choose the files to shred");

        List<File> files = chooser.showOpenMultipleDialog(window());

        if (files != null) {
            addToShred(files.stream().map(File::toPath).toList());
        }

    }

    @FXML
    private void addShredFolder() {

        DirectoryChooser chooser = new DirectoryChooser();

        chooser.setTitle("Choose the folder to shred");

        File folder = chooser.showDialog(window());

        if (folder != null) {
            addToShred(List.of(folder.toPath()));
        }

    }

    /** Also used by the tests, in place of the dialogs. */
    void addToShred(List<Path> paths) {

        for (Path path : paths) {

            Path absolute = path.toAbsolutePath().normalize();

            if (!shredList.getItems().contains(absolute)) {
                shredList.getItems().add(absolute);
            }

        }

        hideShredConfirm();

        planShred();

    }

    @FXML
    private void removeShredItem() {

        Path selected = shredList.getSelectionModel().getSelectedItem();

        if (selected != null) {
            shredList.getItems().remove(selected);
            planShred();
        }

    }

    @FXML
    private void clearShredList() {

        shredList.getItems().clear();

        hideShredConfirm();

        planShred();

    }

    /** Works out what would be shredded, to show it before anything is touched. */
    private void planShred() {

        List<Path> chosen = List.copyOf(shredList.getItems());

        shredPlan = null;

        if (chosen.isEmpty()) {
            shredSummary.setText("");
            return;
        }

        shredSummary.setText("Counting…");

        Ui.background("shred-plan", () -> shredder.plan(chosen), plan -> {

            if (!chosen.equals(shredList.getItems())) {
                return;
            }

            shredPlan = plan;

            StringBuilder text = new StringBuilder(plan.files().size() + (plan.files().size() == 1 ? " file" : " files")
                    + (plan.folders().isEmpty() ? "" : " in " + plan.folders().size() + (plan.folders().size() == 1 ? " folder" : " folders"))
                    + ", " + DisplayFormat.size(plan.bytes()) + ".");

            if (!plan.refused().isEmpty()) {
                text.append(" Left alone: ").append(String.join("; ", plan.refused().stream().limit(3).toList()))
                        .append(plan.refused().size() > 3 ? "; and " + (plan.refused().size() - 3) + " more." : ".");
            }

            shredSummary.setText(text.toString());

        }, error -> shredSummary.setText("Couldn't count the files: " + error.getMessage()));

    }

    @FXML
    private void askShred() {

        if (shredPlan == null || shredPlan.files().isEmpty() && shredPlan.folders().isEmpty()) {
            Ui.message(shredMessage, shredList.getItems().isEmpty() ? "Add files or folders first." : "There is nothing here that can be shredded.", "bad");
            return;
        }

        shredConfirmLabel.setText("Shred " + shredPlan.files().size() + (shredPlan.files().size() == 1 ? " file" : " files")
                + " (" + DisplayFormat.size(shredPlan.bytes()) + ") for good? They can't be brought back.");

        shredConfirmBox.setVisible(true);
        shredConfirmBox.setManaged(true);

    }

    @FXML
    private void cancelShred() {
        hideShredConfirm();
    }

    private void hideShredConfirm() {
        shredConfirmBox.setVisible(false);
        shredConfirmBox.setManaged(false);
    }

    @FXML
    void confirmShred() {

        hideShredConfirm();

        Shredder.Plan plan = shredPlan;

        if (plan == null) {
            return;
        }

        stopShred.set(false);

        Task<Shredder.Result> task = new Task<>() {

            @Override
            protected Shredder.Result call() {

                return shredder.shred(plan, new Shredder.Progress() {

                    @Override
                    public void update(int done, int total) {
                        updateProgress(done, Math.max(1, total));
                        updateMessage(done + " of " + total);
                    }

                    @Override
                    public boolean isCancelled() {
                        return stopShred.get();
                    }

                });

            }

        };

        shredProgress.progressProperty().bind(task.progressProperty());
        shredProgressLabel.textProperty().bind(task.messageProperty());

        setBusy(shredProgressBox, shredButton, true);

        task.setOnSucceeded(event -> {

            setBusy(shredProgressBox, shredButton, false);

            Shredder.Result result = task.getValue();

            String text = "Shredded " + result.shredded() + (result.shredded() == 1 ? " file" : " files") + " ("
                    + DisplayFormat.size(result.bytes()) + ")."
                    + (result.failed() > 0 ? " " + result.failed() + " couldn't be: " + String.join("; ", result.problems().stream().limit(2).toList()) : "");

            Ui.message(shredMessage, text, result.failed() > 0 ? "warn" : "good");

            shredList.getItems().removeIf(path -> !Files.exists(path, LinkOption.NOFOLLOW_LINKS));

            planShred();

        });

        task.setOnFailed(event -> {

            setBusy(shredProgressBox, shredButton, false);

            Ui.message(shredMessage, task.getException() instanceof CancellationException
                    ? "Stopped. What was shredded is gone; the rest is untouched."
                    : "Shredding stopped: " + task.getException().getMessage(), "warn");

            shredList.getItems().removeIf(path -> !Files.exists(path, LinkOption.NOFOLLOW_LINKS));

            planShred();

        });

        start(task, "cybershield-shredder");

    }

    @FXML
    private void stopShred() {
        stopShred.set(true);
    }

    // =====================================================
    // CLIPBOARD
    // =====================================================

    @FXML
    private void clearClipboard() {

        Clipboard.getSystemClipboard().clear();

        ActivityLog.record(ActivityLog.Action.CLIPBOARD_CLEARED);

        Ui.message(clipboardMessage, "Cleared. Nothing is copied now.", "good");

    }

    // =====================================================
    // PHOTO DETAILS
    // =====================================================

    @FXML
    private void addPhotos() {

        FileChooser chooser = new FileChooser();

        chooser.setTitle("Choose photos");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JPEG and PNG photos", "*.jpg", "*.jpeg", "*.png"));

        List<File> files = chooser.showOpenMultipleDialog(window());

        if (files != null) {
            addPhotos(files.stream().map(File::toPath).toList());
        }

    }

    private static boolean isPhoto(Path path) {

        String name = path.getFileName().toString();

        int dot = name.lastIndexOf('.');

        return dot > 0 && PhotoDetails.supports(name.substring(dot + 1)) && Files.isRegularFile(path);

    }

    /** Also used by the tests. Reads what each photo gives away. */
    void addPhotos(List<Path> photos) {

        if (photos.isEmpty()) {
            Ui.message(photoMessage, "Only JPEG and PNG photos can be checked.", "bad");
            return;
        }

        Ui.message(photoMessage, "Reading…", null);

        Ui.background("photo-details", () -> {

            List<PhotoRow> rows = new ArrayList<>();

            for (Path photo : photos) {

                try {

                    if (Files.size(photo) > MAX_PHOTO_BYTES) {
                        rows.add(new PhotoRow(photo, null, "Too big to check here (over " + DisplayFormat.size(MAX_PHOTO_BYTES) + ")."));
                        continue;
                    }

                    rows.add(new PhotoRow(photo, PhotoDetails.read(Files.readAllBytes(photo)), null));

                } catch (IOException e) {
                    rows.add(new PhotoRow(photo, null, e.getMessage()));
                }

            }

            return rows;

        }, rows -> {

            for (PhotoRow row : rows) {
                photoTable.getItems().removeIf(existing -> existing.path().equals(row.path()));
                photoTable.getItems().add(row);
            }

            long withDetails = photoTable.getItems().stream().filter(row -> row.found() != null && row.found().any()).count();

            Ui.message(photoMessage, withDetails == 0 ? "None of these photos gives anything away."
                    : withDetails + (withDetails == 1 ? " photo has" : " photos have") + " details to remove.", null);

        }, error -> Ui.message(photoMessage, "Couldn't read them: " + error.getMessage(), "bad"));

    }

    @FXML
    private void clearPhotos() {

        photoTable.getItems().clear();

        Ui.message(photoMessage, "", null);

    }

    @FXML
    void cleanPhotos() {

        List<Path> photos = photoTable.getItems().stream()
                .filter(row -> row.found() != null && row.found().any())
                .map(PhotoRow::path)
                .toList();

        if (photos.isEmpty()) {
            Ui.message(photoMessage, photoTable.getItems().isEmpty() ? "Add photos first." : "There is nothing to remove from these photos.", "bad");
            return;
        }

        cleanPhotosButton.setDisable(true);

        Ui.background("photo-clean", () -> {

            List<String> problems = new ArrayList<>();

            int saved = 0;

            for (Path photo : photos) {

                try {
                    PhotoDetails.cleanCopy(photo, photo.getParent());
                    saved++;
                } catch (IOException e) {
                    problems.add(photo.getFileName() + ": " + e.getMessage());
                }

            }

            if (saved > 0) {
                ActivityLog.recordPlain(ActivityLog.Action.PHOTO_DETAILS_REMOVED, saved + (saved == 1 ? " photo" : " photos"));
            }

            return new Object[] {saved, problems};

        }, done -> {

            cleanPhotosButton.setDisable(false);

            int saved = (Integer) done[0];

            @SuppressWarnings("unchecked")
            List<String> problems = (List<String>) done[1];

            Ui.message(photoMessage, "Saved " + saved + (saved == 1 ? " clean copy" : " clean copies") + " beside the originals."
                    + (problems.isEmpty() ? "" : " " + String.join("; ", problems)), problems.isEmpty() ? "good" : "warn");

        }, error -> {

            cleanPhotosButton.setDisable(false);

            Ui.message(photoMessage, "Couldn't save the copies: " + error.getMessage(), "bad");

        });

    }

    // =====================================================
    // WINDOWS TRACES
    // =====================================================

    /** For the tests: look in made-up folders, never the PC's own. */
    void useTraceCleaner(TraceCleaner testCleaner) {
        this.cleaner = testCleaner;
    }

    @FXML
    void scanTraces() {

        scanTracesButton.setDisable(true);

        scanTracesLabel.setText("Looking…");

        hideTracesConfirm();

        TraceCleaner looking = cleaner;

        Ui.background("trace-scan", looking::scan, found -> {

            scanTracesButton.setDisable(false);

            scanTracesLabel.setText("Tick what to remove.");

            traceChoices.getChildren().clear();

            traceBoxes.clear();

            for (TraceCleaner.Found kind : found) {

                CheckBox box = new CheckBox(kind.kind().title() + ":  " + kind.files().size()
                        + (kind.files().size() == 1 ? " item" : " items") + ", " + DisplayFormat.size(kind.bytes()));

                // Jump lists also hold what is pinned to them, so they wait to be ticked.
                box.setSelected(!kind.files().isEmpty() && kind.kind() != TraceCleaner.Kind.JUMP_LISTS);
                box.setDisable(kind.files().isEmpty());
                box.selectedProperty().addListener((observable, before, now) -> updateCleanButton());

                Label detail = new Label(kind.kind().detail());
                detail.getStyleClass().add("hint");
                detail.setWrapText(true);

                VBox choice = new VBox(2, box, detail);
                choice.getStyleClass().add("trace-choice");

                traceChoices.getChildren().add(choice);

                traceBoxes.put(box, kind);

            }

            updateCleanButton();

        }, error -> {

            scanTracesButton.setDisable(false);

            scanTracesLabel.setText("Couldn't look: " + error.getMessage());

        });

    }

    private void updateCleanButton() {

        cleanTracesButton.setDisable(traceBoxes.keySet().stream().noneMatch(CheckBox::isSelected));

    }

    private List<TraceCleaner.Found> tickedTraces() {

        List<TraceCleaner.Found> ticked = new ArrayList<>();

        traceBoxes.forEach((box, kind) -> {
            if (box.isSelected()) {
                ticked.add(kind);
            }
        });

        return ticked;

    }

    @FXML
    private void askCleanTraces() {

        List<TraceCleaner.Found> ticked = tickedTraces();

        int items = ticked.stream().mapToInt(kind -> kind.files().size()).sum();

        long bytes = ticked.stream().mapToLong(TraceCleaner.Found::bytes).sum();

        tracesConfirmLabel.setText("Remove " + items + (items == 1 ? " item" : " items") + " (" + DisplayFormat.size(bytes)
                + ") for good? Windows makes new ones as you go.");

        tracesConfirmBox.setVisible(true);
        tracesConfirmBox.setManaged(true);

    }

    @FXML
    private void cancelCleanTraces() {
        hideTracesConfirm();
    }

    private void hideTracesConfirm() {
        tracesConfirmBox.setVisible(false);
        tracesConfirmBox.setManaged(false);
    }

    @FXML
    void confirmCleanTraces() {

        hideTracesConfirm();

        List<TraceCleaner.Found> ticked = tickedTraces();

        TraceCleaner cleaning = cleaner;

        cleanTracesButton.setDisable(true);

        Ui.message(tracesMessage, "Removing…", null);

        Ui.background("trace-clean", () -> cleaning.clean(ticked, TraceCleaner.Progress.NONE), result -> {

            Ui.message(tracesMessage, "Removed " + result.removed() + (result.removed() == 1 ? " item" : " items")
                    + " (" + DisplayFormat.size(result.bytes()) + ")."
                    + (result.skipped() > 0 ? " " + result.skipped() + " in use stayed." : ""), "good");

            scanTraces();

        }, error -> {

            updateCleanButton();

            Ui.message(tracesMessage, "Cleaning stopped: " + error.getMessage(), "bad");

        });

    }

    // =====================================================
    // HELPERS
    // =====================================================

    private static void setBusy(HBox progress, Button button, boolean busy) {

        progress.setVisible(busy);
        progress.setManaged(busy);

        button.setDisable(busy);

    }

    private static void start(Task<?> task, String name) {

        Thread thread = new Thread(task, name);

        thread.setDaemon(true);

        thread.start();

    }

    private Window window() {
        return shredPanel.getScene() == null ? null : shredPanel.getScene().getWindow();
    }

    @Override
    public void setOnLocked(Runnable onLocked) {
        // Works without the vault.
    }

    @Override
    public void dispose() {

        stopShred.set(true);

        tryKeyboardField.clear();

        photoTable.getItems().clear();

    }

}
