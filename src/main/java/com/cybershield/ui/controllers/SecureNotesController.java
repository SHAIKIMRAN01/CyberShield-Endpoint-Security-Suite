package com.cybershield.ui.controllers;

import com.cybershield.dao.SettingsDAO;
import com.cybershield.logger.AppLogger;
import com.cybershield.notes.Note;
import com.cybershield.notes.NoteService;
import com.cybershield.services.ActivityLog;
import com.cybershield.ui.Ui;
import com.cybershield.utils.DisplayFormat;
import com.cybershield.vault.VaultService;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.sql.SQLException;

/**
 * Secure Notes: a searchable list and an editor that saves as you type. Notes are
 * encrypted with the vault key; this screen only holds them decrypted in memory, and
 * drops them when the vault locks or the screen closes. Copied text clears itself
 * from the clipboard after the time set in Settings, like copied passwords.
 */
public class SecureNotesController implements LockableScreen {

    private static final Duration AUTOSAVE_DELAY = Duration.seconds(1);

    @FXML
    private TextField searchField;

    @FXML
    private ListView<Note> noteList;

    @FXML
    private Label countLabel;

    @FXML
    private TextField titleField;

    @FXML
    private TextArea textArea;

    @FXML
    private HBox confirmBox;

    @FXML
    private Label confirmLabel;

    @FXML
    private Label savedLabel;

    @FXML
    private Button copyButton;

    @FXML
    private Button deleteButton;

    private final NoteService notes = new NoteService();

    private final VaultService vaultService = new VaultService();

    private final ObservableList<Note> all = FXCollections.observableArrayList();

    private final FilteredList<Note> shown = new FilteredList<>(all, note -> true);

    private final PauseTransition autosave = new PauseTransition(AUTOSAVE_DELAY);

    // The note in the editor; id 0 while it hasn't been saved yet.
    private Note current;

    private boolean dirty;

    // True while the editor is being filled in code, so that isn't taken as typing.
    private boolean loading;

    private Runnable onLocked = () -> { };

    @FXML
    public void initialize() {

        noteList.setItems(shown);

        noteList.setCellFactory(list -> new ListCell<>() {

            @Override
            protected void updateItem(Note note, boolean empty) {

                super.updateItem(note, empty);

                if (empty || note == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }

                Label title = new Label(note.displayTitle());
                title.getStyleClass().add("note-cell-title");

                Label when = new Label(note.updatedAt() == null ? "Not saved yet" : DisplayFormat.timestamp(note.updatedAt()));
                when.getStyleClass().add("hint");

                setText(null);
                setGraphic(new VBox(2, title, when));

            }

        });

        searchField.textProperty().addListener((observable, before, query) -> {
            shown.setPredicate(note -> note.matches(query));
            showCount();
        });

        noteList.getSelectionModel().selectedItemProperty().addListener((observable, before, note) -> {
            if (!loading && note != null && (current == null || note.id() != current.id())) {
                saveNow();
                open(note);
            }
        });

        titleField.textProperty().addListener((observable, before, text) -> edited());

        textArea.textProperty().addListener((observable, before, text) -> edited());

        autosave.setOnFinished(event -> saveNow());

        load();

    }

    private void load() {

        try {

            all.setAll(notes.all());

        } catch (SQLException | RuntimeException e) {

            AppLogger.error("Couldn't read the notes: " + e.getMessage());

            savedLabel.setText("The notes couldn't be read. Details are in CyberShield's log file.");

        }

        showCount();

        if (all.isEmpty()) {
            startNew();
        } else {
            noteList.getSelectionModel().select(0);
        }

    }

    private void showCount() {

        int total = all.size();

        countLabel.setText(shown.size() == total
                ? total + (total == 1 ? " note" : " notes") + ", encrypted"
                : shown.size() + " of " + total + " notes match");

    }

    // =====================================================
    // EDITING
    // =====================================================

    @FXML
    private void newNote() {

        saveNow();

        noteList.getSelectionModel().clearSelection();

        startNew();

        titleField.requestFocus();

    }

    private void startNew() {

        show(new Note(0, "", "", null, null));

        savedLabel.setText("A new note. It is saved as you type.");

    }

    private void open(Note note) {

        show(note);

        ActivityLog.record(ActivityLog.Action.NOTE_OPENED, note.displayTitle());

        savedLabel.setText(note.updatedAt() == null ? "" : "Saved " + DisplayFormat.timestamp(note.updatedAt()));

    }

    private void show(Note note) {

        hideConfirm();

        loading = true;

        current = note;

        titleField.setText(note.title());

        textArea.setText(note.text());

        loading = false;

        dirty = false;

        deleteButton.setDisable(note.id() == 0);

    }

    private void edited() {

        if (loading || current == null) {
            return;
        }

        dirty = true;

        savedLabel.setText("Saving…");

        autosave.playFromStart();

    }

    /** Saves the note in the editor if it changed and isn't empty. */
    private void saveNow() {

        autosave.stop();

        if (!dirty || current == null) {
            return;
        }

        String title = titleField.getText();

        String text = textArea.getText();

        if (current.id() == 0 && title.isBlank() && text.isBlank()) {
            dirty = false;
            return;
        }

        try {

            boolean isNew = current.id() == 0;

            Note saved = notes.save(current.withText(title, text));

            dirty = false;

            replaceInList(saved, isNew);

            current = saved;

            deleteButton.setDisable(false);

            savedLabel.setText("Saved " + DisplayFormat.timestamp(saved.updatedAt()));

        } catch (SQLException | RuntimeException e) {

            AppLogger.error("Couldn't save the note: " + e.getMessage());

            savedLabel.setText("Not saved: " + e.getMessage());

        }

    }

    private void replaceInList(Note saved, boolean isNew) {

        loading = true;

        if (!isNew) {
            all.removeIf(note -> note.id() == saved.id());
        }

        // The latest change goes to the top, as the list is sorted.
        all.add(0, saved);

        noteList.getSelectionModel().select(saved);

        loading = false;

        showCount();

    }

    @FXML
    private void copyText() {

        if (current == null) {
            return;
        }

        String selected = textArea.getSelectedText();

        String text = selected == null || selected.isEmpty() ? textArea.getText() : selected;

        if (text.isEmpty()) {
            savedLabel.setText("Nothing to copy.");
            return;
        }

        int seconds = new SettingsDAO().clipboardClearSeconds();

        Ui.copyAndClearLater(text, seconds);

        ActivityLog.record(ActivityLog.Action.NOTE_COPIED, current.displayTitle());

        savedLabel.setText((selected == null || selected.isEmpty() ? "The note's text was" : "The selection was")
                + " copied. The clipboard clears in " + seconds + " seconds.");

    }

    // =====================================================
    // DELETE, WITH CONFIRMATION
    // =====================================================

    @FXML
    private void askDelete() {

        if (current == null || current.id() == 0) {
            return;
        }

        confirmLabel.setText("Delete “" + current.displayTitle() + "”? This can't be undone.");

        confirmBox.setVisible(true);
        confirmBox.setManaged(true);

    }

    @FXML
    private void cancelDelete() {

        hideConfirm();

    }

    @FXML
    private void confirmDelete() {

        hideConfirm();

        try {

            Note doomed = current;

            if (notes.delete(doomed)) {

                loading = true;
                all.removeIf(note -> note.id() == doomed.id());
                loading = false;

                showCount();

                if (all.isEmpty()) {
                    startNew();
                } else {
                    noteList.getSelectionModel().select(0);
                }

                savedLabel.setText("Note deleted.");

            }

        } catch (SQLException | RuntimeException e) {

            AppLogger.error("Couldn't delete the note: " + e.getMessage());

            savedLabel.setText("The note couldn't be deleted.");

        }

    }

    private void hideConfirm() {

        confirmBox.setVisible(false);
        confirmBox.setManaged(false);

    }

    // =====================================================
    // LOCKING
    // =====================================================

    @FXML
    private void lockVault() {

        saveNow();

        dispose();

        vaultService.lockVault();

        onLocked.run();

    }

    @Override
    public void setOnLocked(Runnable onLocked) {

        this.onLocked = onLocked;

    }

    /** Saves pending typing while the vault is still open, then drops every decrypted note. */
    @Override
    public void dispose() {

        if (vaultService.isUnlocked()) {
            saveNow();
        }

        autosave.stop();

        loading = true;

        current = null;

        all.clear();

        titleField.clear();

        textArea.clear();

        loading = false;

        Platform.runLater(() -> searchField.clear());

    }

}
