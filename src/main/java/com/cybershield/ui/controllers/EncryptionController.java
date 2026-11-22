package com.cybershield.ui.controllers;

import com.cybershield.dao.SettingsDAO;
import com.cybershield.encryption.Checksums;
import com.cybershield.encryption.SharedCipher;
import com.cybershield.services.ActivityLog;
import com.cybershield.ui.Ui;
import com.cybershield.utils.PasswordGenerator;
import com.cybershield.utils.PasswordStrengthUtil;
import com.cybershield.utils.ValidationUtil;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The Encryption Center: password-based tools for sharing, separate from the vault.
 * Text and files are encrypted with a key derived from a password the user shares
 * (see SharedCipher), plus checksums and a password strength checker.
 */
public class EncryptionController {

    @FXML private Button textTab;
    @FXML private Button fileTab;
    @FXML private Button hashTab;
    @FXML private Button strengthTab;

    @FXML private VBox textPanel;
    @FXML private VBox filePanel;
    @FXML private VBox hashPanel;
    @FXML private VBox strengthPanel;

    @FXML private PasswordField textPassword;
    @FXML private TextArea textInput;
    @FXML private TextArea textOutput;
    @FXML private Label textMessage;

    @FXML private Label fileLabel;
    @FXML private PasswordField filePassword;
    @FXML private PasswordField fileConfirm;
    @FXML private Button encryptFileButton;
    @FXML private Button decryptFileButton;
    @FXML private ProgressBar fileProgress;
    @FXML private Label fileMessage;

    @FXML private ComboBox<Checksums.Algorithm> algorithmBox;
    @FXML private TextArea hashInput;
    @FXML private TextField hashOutput;
    @FXML private TextField expectedField;
    @FXML private ProgressBar hashProgress;
    @FXML private Label hashMessage;

    @FXML private PasswordField checkField;
    @FXML private ProgressBar checkBar;
    @FXML private Label checkLabel;
    @FXML private VBox checkList;
    @FXML private Spinner<Integer> generateLength;
    @FXML private CheckBox generateDigits;
    @FXML private CheckBox generateSymbols;
    @FXML private TextField generated;
    @FXML private Label strengthMessage;

    private Path chosenFile;

    @FXML
    public void initialize() {

        algorithmBox.setItems(FXCollections.observableArrayList(Checksums.Algorithm.values()));
        algorithmBox.getSelectionModel().select(Checksums.Algorithm.SHA_256);

        expectedField.textProperty().addListener((observable, before, text) -> compareChecksum());

        checkField.textProperty().addListener((observable, before, text) -> showStrength(text));

        showStrength("");

        showText();

    }

    // =====================================================
    // TOOLS
    // =====================================================

    @FXML
    private void showText() {
        show(textTab, textPanel);
    }

    @FXML
    private void showFile() {
        show(fileTab, filePanel);
    }

    @FXML
    private void showHash() {
        show(hashTab, hashPanel);
    }

    @FXML
    private void showStrength() {
        show(strengthTab, strengthPanel);
    }

    private void show(Button tab, VBox panel) {

        for (Button each : List.of(textTab, fileTab, hashTab, strengthTab)) {
            each.getStyleClass().remove("active");
        }

        tab.getStyleClass().add("active");

        for (VBox each : List.of(textPanel, filePanel, hashPanel, strengthPanel)) {
            each.setVisible(each == panel);
        }

    }

    // =====================================================
    // TEXT
    // =====================================================

    @FXML
    private void encryptText() {

        String password = textPassword.getText();

        if (password.length() < 8) {
            Ui.message(textMessage, "Use a password of at least 8 characters.", "bad");
            return;
        }

        if (textInput.getText().isEmpty()) {
            Ui.message(textMessage, "Type the text to encrypt.", "bad");
            return;
        }

        String text = textInput.getText();

        Ui.message(textMessage, "Encrypting…", null);

        Ui.background("encrypt-text", () -> SharedCipher.encryptText(text, password), result -> {

            textOutput.setText(result);

            ActivityLog.record(ActivityLog.Action.TEXT_ENCRYPTED);

            Ui.message(textMessage, "Encrypted. Copy the result and send the password another way.", "good");

        }, error -> Ui.message(textMessage, "Couldn't encrypt: " + error.getMessage(), "bad"));

    }

    @FXML
    private void decryptText() {

        String password = textPassword.getText();

        String armored = textInput.getText();

        if (password.isEmpty() || armored.isBlank()) {
            Ui.message(textMessage, "Paste the encrypted text and type its password.", "bad");
            return;
        }

        Ui.message(textMessage, "Decrypting…", null);

        Ui.background("decrypt-text", () -> {
            try {
                return SharedCipher.decryptText(armored, password);
            } catch (IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }, result -> {

            textOutput.setText(result);

            ActivityLog.record(ActivityLog.Action.TEXT_DECRYPTED);

            Ui.message(textMessage, "Decrypted.", "good");

        }, error -> {

            textOutput.clear();

            Ui.message(textMessage, error.getMessage(), "bad");

        });

    }

    @FXML
    private void copyTextResult() {

        if (textOutput.getText().isEmpty()) {
            Ui.message(textMessage, "There's no result to copy yet.", null);
            return;
        }

        int seconds = new SettingsDAO().clipboardClearSeconds();

        Ui.copyAndClearLater(textOutput.getText(), seconds);

        Ui.message(textMessage, "Copied. The clipboard clears in " + seconds + " seconds.", "good");

    }

    // =====================================================
    // FILES
    // =====================================================

    @FXML
    private void chooseFile() {

        FileChooser chooser = new FileChooser();

        chooser.setTitle("Choose a file to encrypt or decrypt");

        File file = chooser.showOpenDialog(fileLabel.getScene().getWindow());

        if (file != null) {
            useFile(file.toPath());
        }

    }

    /** Also used by the tests, in place of the file dialog. */
    void useFile(Path file) {

        chosenFile = file;

        fileLabel.setText(file.toString());

        boolean encrypted = file.getFileName().toString().endsWith(SharedCipher.FILE_EXTENSION);

        Ui.message(fileMessage, encrypted ? "This looks like an encrypted file: type its password and press Decrypt." : "", null);

    }

    @FXML
    private void encryptFile() {

        if (chosenFile == null) {
            Ui.message(fileMessage, "Choose a file first.", "bad");
            return;
        }

        String password = filePassword.getText();

        if (!ValidationUtil.isValidMasterPassword(password)) {
            Ui.message(fileMessage, "Use at least 12 characters with upper and lower case letters, a digit and a symbol.", "bad");
            return;
        }

        if (!password.equals(fileConfirm.getText())) {
            Ui.message(fileMessage, "The two passwords don't match.", "bad");
            return;
        }

        Path source = chosenFile;

        Path target = SharedCipher.freeName(source.resolveSibling(source.getFileName() + SharedCipher.FILE_EXTENSION), "2");

        runFileJob("Encrypting…", () -> {
            SharedCipher.encryptFile(source, target, password, progress());
            return target;
        }, written -> {
            ActivityLog.record(ActivityLog.Action.FILE_ENCRYPTED, source.toString());
            Ui.message(fileMessage, "Encrypted to " + written.getFileName() + ". Send it, and send the password another way.", "good");
        });

    }

    @FXML
    private void decryptFile() {

        if (chosenFile == null) {
            Ui.message(fileMessage, "Choose the .cshield file first.", "bad");
            return;
        }

        String password = filePassword.getText();

        if (password.isEmpty()) {
            Ui.message(fileMessage, "Type the file's password.", "bad");
            return;
        }

        Path source = chosenFile;

        runFileJob("Decrypting…", () -> SharedCipher.decryptFile(source, source.toAbsolutePath().getParent(), password, progress()), written -> {
            ActivityLog.record(ActivityLog.Action.FILE_DECRYPTED, written.toString());
            Ui.message(fileMessage, "Decrypted to " + written.getFileName() + ", next to the encrypted file.", "good");
        });

    }

    private interface FileWork {
        Path run() throws IOException;
    }

    private void runFileJob(String working, FileWork work, java.util.function.Consumer<Path> done) {

        encryptFileButton.setDisable(true);
        decryptFileButton.setDisable(true);

        fileProgress.setProgress(0);
        fileProgress.setVisible(true);
        fileProgress.setManaged(true);

        Ui.message(fileMessage, working, null);

        Ui.background("file-cipher", () -> {
            try {
                return work.run();
            } catch (IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }, written -> {
            finishFileJob();
            done.accept(written);
        }, error -> {
            finishFileJob();
            Ui.message(fileMessage, error.getMessage(), "bad");
        });

    }

    private void finishFileJob() {

        encryptFileButton.setDisable(false);
        decryptFileButton.setDisable(false);

        fileProgress.setVisible(false);
        fileProgress.setManaged(false);

        filePassword.clear();
        fileConfirm.clear();

    }

    private SharedCipher.Progress progress() {

        return (done, total) -> Platform.runLater(() -> fileProgress.setProgress(total <= 0 ? 1 : done / (double) total));

    }

    // =====================================================
    // CHECKSUMS
    // =====================================================

    @FXML
    private void hashText() {

        String checksum = Checksums.of(hashInput.getText(), algorithmBox.getValue());

        hashOutput.setText(checksum);

        ActivityLog.recordPlain(ActivityLog.Action.HASH_MADE, algorithmBox.getValue() + " of text");

        compareChecksum();

    }

    @FXML
    private void hashFile() {

        FileChooser chooser = new FileChooser();

        chooser.setTitle("Choose a file to make a checksum of");

        File file = chooser.showOpenDialog(hashOutput.getScene().getWindow());

        if (file != null) {
            hashPath(file.toPath());
        }

    }

    /** Also used by the tests, in place of the file dialog. */
    void hashPath(Path file) {

        Checksums.Algorithm algorithm = algorithmBox.getValue();

        long size;

        try {
            size = Files.size(file);
        } catch (IOException e) {
            Ui.message(hashMessage, "That file can't be read.", "bad");
            return;
        }

        hashProgress.setProgress(0);
        hashProgress.setVisible(true);
        hashProgress.setManaged(true);

        Ui.message(hashMessage, "Reading " + file.getFileName() + "…", null);

        Ui.background("checksum", () -> {
            try {
                return Checksums.of(file, algorithm, done -> Platform.runLater(() -> hashProgress.setProgress(size == 0 ? 1 : done / (double) size)));
            } catch (IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }, checksum -> {

            hashProgress.setVisible(false);
            hashProgress.setManaged(false);

            hashOutput.setText(checksum);

            ActivityLog.record(ActivityLog.Action.HASH_MADE, algorithm + " of " + file);

            Ui.message(hashMessage, algorithm + " of " + file.getFileName() + ".", null);

            compareChecksum();

        }, error -> {

            hashProgress.setVisible(false);
            hashProgress.setManaged(false);

            Ui.message(hashMessage, "That file can't be read: " + error.getMessage(), "bad");

        });

    }

    private void compareChecksum() {

        String expected = expectedField.getText();

        if (hashOutput.getText().isEmpty() || expected == null || expected.isBlank()) {
            return;
        }

        boolean same = Checksums.matches(hashOutput.getText(), expected);

        Ui.message(hashMessage, same ? "Match: it is the exact file or text you expected." : "Doesn't match: it isn't the same.", same ? "good" : "bad");

    }

    // =====================================================
    // PASSWORD STRENGTH
    // =====================================================

    private void showStrength(String password) {

        Ui.strength(checkBar, checkLabel, password);

        checkList.getChildren().setAll(
                rule(password.length() >= 12, "At least 12 characters (16 or more is better)"),
                rule(PasswordStrengthUtil.hasUpperCase(password) && PasswordStrengthUtil.hasLowerCase(password), "Upper and lower case letters"),
                rule(PasswordStrengthUtil.hasDigit(password), "A digit"),
                rule(PasswordStrengthUtil.hasSpecialCharacter(password), "A symbol"));

    }

    private static Label rule(boolean met, String text) {

        Label line = new Label((met ? "✓  " : "○  ") + text);

        line.getStyleClass().add(met ? "check-yes" : "check-no");

        return line;

    }

    @FXML
    private void generate() {

        int length = generateLength.getValue() == null ? 20 : generateLength.getValue();

        try {
            length = Integer.parseInt(generateLength.getEditor().getText().strip());
        } catch (NumberFormatException ignored) {
            // Keep the spinner's value.
        }

        generated.setText(PasswordGenerator.generate(length, generateDigits.isSelected(), generateSymbols.isSelected()));

        Ui.message(strengthMessage, "", null);

    }

    @FXML
    private void copyGenerated() {

        if (generated.getText().isEmpty()) {
            Ui.message(strengthMessage, "Generate a password first.", null);
            return;
        }

        int seconds = new SettingsDAO().clipboardClearSeconds();

        Ui.copyAndClearLater(generated.getText(), seconds);

        Ui.message(strengthMessage, "Copied. The clipboard clears in " + seconds + " seconds.", "good");

    }

}
