package com.cybershield.ui.controllers;

import com.cybershield.password.PasswordEntry;
import com.cybershield.password.PasswordService;
import com.cybershield.utils.DisplayFormat;
import com.cybershield.vault.VaultService;

import javafx.animation.PauseTransition;
import javafx.beans.property.SimpleStringProperty;
import com.cybershield.security.SessionManager;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import javafx.fxml.FXML;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;

import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.Clipboard;
import javafx.util.Duration;

import java.util.List;
import java.util.stream.Collectors;

public class PasswordVaultController implements LockableScreen {

    // =====================================================
    // Form
    // =====================================================

    @FXML
    private TextField websiteField;

    @FXML
    private TextField usernameField;

    @FXML
    private PasswordField passwordField;

    // Plain-text twin of passwordField, shown while the password is revealed.
    @FXML
    private TextField visiblePasswordField;

    @FXML
    private Button showPasswordButton;

    @FXML
    private TextArea notesArea;

    @FXML
    private TextField searchField;

    // =====================================================
    // Labels
    // =====================================================

    @FXML
    private Label statusLabel;

    @FXML
    private Label strengthLabel;

    // =====================================================
    // Table
    // =====================================================

    @FXML
    private TableView<PasswordEntry> passwordTable;

    @FXML
    private TableColumn<PasswordEntry,String> websiteColumn;

    @FXML
    private TableColumn<PasswordEntry,String> usernameColumn;

    @FXML
    private TableColumn<PasswordEntry,String> updatedColumn;

    // =====================================================
    // Data
    // =====================================================

    private final PasswordService service =
            new PasswordService();

    private ObservableList<PasswordEntry> passwordList =
            FXCollections.observableArrayList();

    private PasswordEntry selectedEntry;

    private boolean passwordVisible = false;

    private final VaultService vaultService =
            new VaultService();

    // Set by the dashboard so it can show the unlock screen after locking.
    private Runnable onLocked = () -> { };

    private static final Duration CLIPBOARD_CLEAR_DELAY =
            Duration.seconds(30);

        @FXML
    public void initialize() {

        websiteColumn.setCellValueFactory(
                new PropertyValueFactory<>("website"));

        usernameColumn.setCellValueFactory(
                new PropertyValueFactory<>("username"));

        updatedColumn.setCellValueFactory(cell ->

                new SimpleStringProperty(
                        DisplayFormat.timestamp(cell.getValue().getUpdatedAt())
                )

        );

        visiblePasswordField.textProperty()
                .bindBidirectional(passwordField.textProperty());

        loadPasswords();

        initializeSelection();

        initializeSearch();

        initializeStrengthMeter();

    }
        private void loadPasswords() {

        passwordList.clear();

        List<PasswordEntry> list =
                service.getAllPasswords();

        passwordList.addAll(list);

        passwordTable.setItems(passwordList);

    }
        private void initializeSelection() {

        passwordTable.getSelectionModel()

                .selectedItemProperty()

                .addListener(

                        (obs, oldValue, newValue) -> {

                            if (newValue == null) {

                                return;

                            }

                            selectedEntry = newValue;

                            websiteField.setText(
                                    newValue.getWebsite());

                            usernameField.setText(
                                    newValue.getUsername());

                            passwordField.setText(
                                    newValue.getPassword());

                            notesArea.setText(
                                    newValue.getNotes());

                            updateStrength();

                        }

                );

    }
        private void initializeSearch() {

        searchField.textProperty()

                .addListener(

                        (observable,
                         oldValue,
                         newValue) -> {

                            if (newValue.isBlank()) {

                                passwordTable.setItems(
                                        passwordList);

                                return;

                            }

                            List<PasswordEntry> filtered =

                                    passwordList.stream()

                                            .filter(

                                                    p ->

                                                            p.getWebsite()

                                                                    .toLowerCase()

                                                                    .contains(

                                                                            newValue.toLowerCase()

                                                                    )

                                                            ||

                                                            p.getUsername()

                                                                    .toLowerCase()

                                                                    .contains(

                                                                            newValue.toLowerCase()

                                                                    )

                                            )

                                            .collect(

                                                    Collectors.toList()

                                            );

                            passwordTable.setItems(

                                    FXCollections.observableArrayList(

                                            filtered

                                    )

                            );

                        }

                );

    }
        private void initializeStrengthMeter() {

        passwordField.textProperty()

                .addListener(

                        (obs,
                         oldValue,
                         newValue) ->

                                updateStrength()

                );

    }
        private void updateStrength() {

        String password = passwordField.getText();

        if(password==null){

            strengthLabel.setText(
                    "Strength :");

            return;

        }

        int score=0;

        if(password.length()>=8)
            score++;

        if(password.matches(".*[A-Z].*"))
            score++;

        if(password.matches(".*[a-z].*"))
            score++;

        if(password.matches(".*\\d.*"))
            score++;

        if(password.matches(".*[^A-Za-z0-9].*"))
            score++;

        switch(score){

            case 0,1 ->

                    strengthLabel.setText(
                            "Strength : Weak");

            case 2,3 ->

                    strengthLabel.setText(
                            "Strength : Medium");

            case 4 ->

                    strengthLabel.setText(
                            "Strength : Strong");

            case 5 ->

                    strengthLabel.setText(
                            "Strength : Very Strong");

        }

    }
    // =====================================================
    // SAVE PASSWORD
    // =====================================================

    @FXML
    private void savePassword() {

        String website = websiteField.getText().trim();
        String username = usernameField.getText().trim();
        String password = passwordField.getText();
        String notes = notesArea.getText();

        if (website.isBlank()
                || username.isBlank()
                || password.isBlank()) {

            statusLabel.setText(
                    "Please fill all required fields."
            );

            return;

        }

        PasswordEntry entry = new PasswordEntry();

        entry.setUserId(
        SessionManager
                .getCurrentUser()
                .getId()
);

        entry.setWebsite(website);

        entry.setUsername(username);

        entry.setPassword(password);

        entry.setNotes(notes);

        boolean saved =
                service.savePassword(entry);

        if (saved) {

            statusLabel.setText(
                    "Password saved successfully."
            );

            clearForm();

            loadPasswords();

        }

        else {

            statusLabel.setText(
                    "Unable to save password."
            );

        }

    }

    // =====================================================
    // UPDATE PASSWORD
    // =====================================================

    @FXML
    private void updatePassword() {

        if (selectedEntry == null) {

            statusLabel.setText(
                    "Please select an entry."
            );

            return;

        }

        selectedEntry.setWebsite(
                websiteField.getText().trim()
        );

        selectedEntry.setUsername(
                usernameField.getText().trim()
        );

        selectedEntry.setPassword(
                passwordField.getText()
        );

        selectedEntry.setNotes(
                notesArea.getText()
        );

        boolean updated =
                service.updatePassword(
                        selectedEntry
                );

        if (updated) {

            statusLabel.setText(
                    "Password updated successfully."
            );

            clearForm();

            loadPasswords();

        }

        else {

            statusLabel.setText(
                    "Update failed."
            );

        }

    }

    // =====================================================
    // DELETE PASSWORD
    // =====================================================

    @FXML
    private void deletePassword() {

        if (selectedEntry == null) {

            statusLabel.setText(
                    "Select a password first."
            );

            return;

        }

        boolean deleted =
                service.deletePassword(
                        selectedEntry.getId()
                );

        if (deleted) {

            statusLabel.setText(
                    "Password deleted."
            );

            clearForm();

            loadPasswords();

        }

        else {

            statusLabel.setText(
                    "Delete failed."
            );

        }

    }

    // =====================================================
    // CLEAR FORM
    // =====================================================

    @FXML
    public void clearForm() {

        // Otherwise clicking the same row again would not fill the form.
        passwordTable.getSelectionModel().clearSelection();

        websiteField.clear();

        usernameField.clear();

        passwordField.clear();

        notesArea.clear();

        strengthLabel.setText(
                "Strength :"
        );

        selectedEntry = null;

        setPasswordVisible(false);

    }
        // =====================================================
    // REVEAL / HIDE PASSWORD
    // =====================================================

    @FXML
    private void togglePasswordVisibility() {

        if (passwordField.getText().isBlank()) {

            statusLabel.setText(
                    "No password available."
            );

            return;

        }

        setPasswordVisible(!passwordVisible);

        if (passwordVisible) {

            statusLabel.setText(
                    "Password Revealed"
            );

        }

        else {

            statusLabel.setText(
                    "Password Hidden"
            );

        }

    }

    private void setPasswordVisible(boolean visible) {

        passwordVisible = visible;

        visiblePasswordField.setVisible(visible);

        passwordField.setVisible(!visible);

        showPasswordButton.setText(visible ? "Hide" : "Show");

    }

    // =====================================================
    // COPY PASSWORD
    // =====================================================

    @FXML
    private void copyPassword() {

        if (passwordField.getText().isBlank()) {

            statusLabel.setText(
                    "Nothing to copy."
            );

            return;

        }

        javafx.scene.input.Clipboard clipboard =

                javafx.scene.input.Clipboard
                        .getSystemClipboard();

        javafx.scene.input.ClipboardContent content =

                new javafx.scene.input.ClipboardContent();

        content.putString(
                passwordField.getText()
        );

        clipboard.setContent(content);

        scheduleClipboardClear(passwordField.getText());

        statusLabel.setText(
                "Password copied. The clipboard clears in 30 seconds."
        );

    }

    // =====================================================
    // COPY USERNAME
    // =====================================================

    @FXML
    private void copyUsername() {

        if (usernameField.getText().isBlank()) {

            statusLabel.setText(
                    "Nothing to copy."
            );

            return;

        }

        javafx.scene.input.Clipboard clipboard =

                javafx.scene.input.Clipboard
                        .getSystemClipboard();

        javafx.scene.input.ClipboardContent content =

                new javafx.scene.input.ClipboardContent();

        content.putString(
                usernameField.getText()
        );

        clipboard.setContent(content);

        statusLabel.setText(
                "Username copied."
        );

    }

    // =====================================================
    // PASSWORD GENERATOR
    // =====================================================

    @FXML
    private void generatePassword() {

        String upper =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

        String lower =
                "abcdefghijklmnopqrstuvwxyz";

        String numbers =
                "0123456789";

        String symbols =
                "!@#$%^&*()_-+=<>?/";

        String chars =
                upper + lower + numbers + symbols;

        java.security.SecureRandom random =
                new java.security.SecureRandom();

        StringBuilder builder =
                new StringBuilder();

        for (int i = 0; i < 16; i++) {

            builder.append(

                    chars.charAt(

                            random.nextInt(

                                    chars.length()

                            )

                    )

            );

        }

        passwordField.setText(
                builder.toString()
        );

        updateStrength();

        statusLabel.setText(
                "Password Generated."
        );

    }
        // =====================================================
    // REFRESH TABLE
    // =====================================================

    @FXML
    private void refreshTable() {

        loadPasswords();

        statusLabel.setText(
                "Password list refreshed."
        );

    }

    // =====================================================
    // CLEAR SEARCH
    // =====================================================

    @FXML
    private void clearSearch() {

        searchField.clear();

        passwordTable.setItems(passwordList);

    }

    // =====================================================
    // AUTO CLEAR CLIPBOARD
    // =====================================================

    private void scheduleClipboardClear(String copiedText) {

        // Runs on the JavaFX thread, so no extra thread or runLater is needed.
        // It keeps running if the user locks the vault or leaves this screen.
        PauseTransition delay =
                new PauseTransition(CLIPBOARD_CLEAR_DELAY);

        delay.setOnFinished(event -> {

            Clipboard clipboard =
                    Clipboard.getSystemClipboard();

            // Leave it alone if the user has copied something else since.
            if (copiedText.equals(clipboard.getString())) {

                clipboard.clear();

            }

        });

        delay.play();

    }

    // =====================================================
    // LOCK VAULT
    // =====================================================

    @Override
    public void setOnLocked(Runnable onLocked) {

        this.onLocked = onLocked;

    }

    /** Drops the decrypted passwords from the screen when it closes or the vault locks. */
    @Override
    public void dispose() {

        clearForm();

        passwordList.clear();

    }

    @FXML
    private void lockVault() {

        clearForm();

        passwordList.clear();

        vaultService.lockVault();

        onLocked.run();

    }

}