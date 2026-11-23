package com.cybershield.ui.controllers;

import com.cybershield.dao.SettingsDAO;
import com.cybershield.encryption.Checksums;
import com.cybershield.encryption.SharedCipher;
import com.cybershield.recovery.Candidates;
import com.cybershield.recovery.ContactsImport;
import com.cybershield.recovery.PasswordRecovery;
import com.cybershield.services.ActivityLog;
import com.cybershield.ui.Ui;
import com.cybershield.utils.PasswordGenerator;
import com.cybershield.utils.PasswordStrengthUtil;
import com.cybershield.utils.ValidationUtil;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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

    // Password generator
    @FXML private Button generatorTab;
    @FXML private VBox generatorPanel;
    @FXML private ToggleGroup modeGroup;
    @FXML private RadioButton randomMode;
    @FXML private RadioButton wordsMode;
    @FXML private RadioButton pinMode;
    @FXML private Region randomControls;
    @FXML private Region wordControls;
    @FXML private Region pinControls;
    @FXML private Spinner<Integer> randomLength;
    @FXML private CheckBox useUpper;
    @FXML private CheckBox useLower;
    @FXML private CheckBox useDigits;
    @FXML private CheckBox useSymbols;
    @FXML private CheckBox avoidLookalikes;
    @FXML private Spinner<Integer> wordCount;
    @FXML private ComboBox<String> wordSeparator;
    @FXML private CheckBox capitalizeWords;
    @FXML private CheckBox addNumber;
    @FXML private Spinner<Integer> pinLength;
    @FXML private TextField generatedPassword;
    @FXML private Label generatorStrength;

    // Recover a file password
    @FXML private Button recoveryTab;
    @FXML private VBox recoveryPanel;
    @FXML private Label recoveryFileLabel;
    @FXML private TextArea recoveryHints;
    @FXML private CheckBox includeCommon;
    @FXML private Region recoveryProgressBox;
    @FXML private ProgressBar recoveryProgress;
    @FXML private Label recoveryProgressLabel;
    @FXML private Button startRecoveryButton;
    @FXML private TextField recoveredPassword;
    @FXML private Button copyRecoveredButton;
    @FXML private Label recoveryMessage;

    private Path recoveryFile;

    private final AtomicBoolean stopRecovery = new AtomicBoolean();

    private boolean recovering;

    private Path chosenFile;

    @FXML
    public void initialize() {

        algorithmBox.setItems(FXCollections.observableArrayList(Checksums.Algorithm.values()));
        algorithmBox.getSelectionModel().select(Checksums.Algorithm.SHA_256);

        expectedField.textProperty().addListener((observable, before, text) -> compareChecksum());

        checkField.textProperty().addListener((observable, before, text) -> showStrength(text));

        showStrength("");

        wordSeparator.setItems(FXCollections.observableArrayList("-", ".", "_", "space"));
        wordSeparator.getSelectionModel().select("-");

        modeGroup.selectedToggleProperty().addListener((observable, before, mode) -> updateGeneratorControls());
        updateGeneratorControls();

        showText();

    }

    private void updateGeneratorControls() {

        show(randomControls, randomMode.isSelected());
        show(wordControls, wordsMode.isSelected());
        show(pinControls, pinMode.isSelected());

    }

    private static void show(Region node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
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

    @FXML
    private void showGenerator() {
        show(generatorTab, generatorPanel);
    }

    @FXML
    private void showRecovery() {
        show(recoveryTab, recoveryPanel);
    }

    private void show(Button tab, VBox panel) {

        for (Button each : List.of(textTab, fileTab, hashTab, strengthTab, generatorTab, recoveryTab)) {
            each.getStyleClass().remove("active");
        }

        tab.getStyleClass().add("active");

        for (VBox each : List.of(textPanel, filePanel, hashPanel, strengthPanel, generatorPanel, recoveryPanel)) {
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

    // =====================================================
    // PASSWORD GENERATOR (3 modes)
    // =====================================================

    @FXML
    private void generatePassword() {

        String password;

        BigInteger combinations;

        try {

            if (wordsMode.isSelected()) {

                int count = wordCount.getValue();
                char separator = "space".equals(wordSeparator.getValue()) ? ' ' : wordSeparator.getValue().charAt(0);

                password = PasswordGenerator.passphrase(count, separator, capitalizeWords.isSelected(), addNumber.isSelected());
                combinations = PasswordGenerator.passphraseCombinations(count, addNumber.isSelected());

            } else if (pinMode.isSelected()) {

                int length = pinLength.getValue();

                password = PasswordGenerator.pin(length);
                combinations = PasswordGenerator.pinCombinations(length);

            } else {

                int length = randomLength.getValue();

                password = PasswordGenerator.random(length, useUpper.isSelected(), useLower.isSelected(),
                        useDigits.isSelected(), useSymbols.isSelected(), avoidLookalikes.isSelected());
                combinations = PasswordGenerator.randomCombinations(length, useUpper.isSelected(), useLower.isSelected(),
                        useDigits.isSelected(), useSymbols.isSelected(), avoidLookalikes.isSelected());

            }

        } catch (IllegalArgumentException | NullPointerException e) {

            generatedPassword.clear();
            Ui.message(generatorStrength, "Choose at least one kind of character.", "bad");
            return;

        }

        generatedPassword.setText(password);

        double bits = PasswordGenerator.entropyBits(combinations);

        Ui.message(generatorStrength, "About " + approximate(combinations) + " possible passwords  ·  "
                + Math.round(bits) + " bits  ·  " + strengthWord(bits) + ".", "good");

    }

    @FXML
    private void copyGeneratedPassword() {

        if (generatedPassword.getText().isEmpty()) {
            Ui.message(generatorStrength, "Generate a password first.", null);
            return;
        }

        int seconds = new SettingsDAO().clipboardClearSeconds();

        Ui.copyAndClearLater(generatedPassword.getText(), seconds);

        Ui.message(generatorStrength, "Copied. The clipboard clears in " + seconds + " seconds.", "good");

    }

    private static String approximate(BigInteger number) {

        if (number.bitLength() <= 30) {
            return String.format("%,d", number.longValue());
        }

        return String.format("%.1e", number.doubleValue()).replace("e+0", " × 10^").replace("e+", " × 10^");

    }

    private static String strengthWord(double bits) {
        return bits < 40 ? "weak" : bits < 60 ? "fair" : bits < 80 ? "strong" : "very strong";
    }

    // =====================================================
    // RECOVER A FILE PASSWORD (a file you own)
    // =====================================================

    private ContactsImport.Hints importedContacts;

    @FXML
    private void chooseRecoveryFile() {

        FileChooser chooser = new FileChooser();

        chooser.setTitle("Choose a file you own to recover the password for");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("CyberShield files and PDFs", "*.cshield", "*.pdf"),
                new FileChooser.ExtensionFilter("All files", "*.*"));

        File file = chooser.showOpenDialog(recoveryPanel.getScene().getWindow());

        if (file != null) {
            useRecoveryFile(file.toPath());
        }

    }

    /** Also used by the tests. */
    void useRecoveryFile(Path file) {

        recoveryFile = file;

        recoveryFileLabel.setText(file.getFileName().toString());

        showRecovered(false);

        Ui.message(recoveryMessage, "", null);

    }

    @FXML
    private void importContacts() {

        FileChooser chooser = new FileChooser();

        chooser.setTitle("Choose your own contacts file (vCard or CSV)");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Contacts (vCard, CSV)", "*.vcf", "*.csv", "*.txt"),
                new FileChooser.ExtensionFilter("All files", "*.*"));

        File file = chooser.showOpenDialog(recoveryPanel.getScene().getWindow());

        if (file != null) {
            importContactsFrom(file.toPath());
        }

    }

    /** Also used by the tests. */
    void importContactsFrom(Path file) {

        Ui.message(recoveryMessage, "Reading your contacts…", null);

        Ui.background("import-contacts", () -> {
            try {
                return ContactsImport.read(file);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e.getMessage(), e);
            }
        }, hints -> {

            importedContacts = hints;

            Ui.message(recoveryMessage, hints.isEmpty()
                    ? "Nothing to use was found in that file."
                    : "Added " + hints.words().size() + " names and " + hints.numbers().size() + " numbers from your contacts; they'll be tried too.",
                    hints.isEmpty() ? "warn" : "good");

        }, error -> Ui.message(recoveryMessage, "Couldn't read that file: " + error.getMessage(), "bad"));

    }

    @FXML
    private void startRecovery() {

        if (recovering) {
            return;
        }

        if (recoveryFile == null) {
            Ui.message(recoveryMessage, "Choose a file first.", "bad");
            return;
        }

        Candidates.Builder builder = Candidates.builder().includeCommon(includeCommon.isSelected());

        builder.hints(recoveryHints.getText());

        if (importedContacts != null) {
            importedContacts.words().forEach(builder::word);
            importedContacts.numbers().forEach(builder::number);
        }

        if (builder.isEmpty()) {
            Ui.message(recoveryMessage, "Add some hints, or leave the common-password list on.", "bad");
            return;
        }

        Candidates candidates = builder.build();

        Path file = recoveryFile;

        stopRecovery.set(false);

        showRecovered(false);

        Task<PasswordRecovery.Result> task = new Task<>() {

            @Override
            protected PasswordRecovery.Result call() throws Exception {

                PasswordRecovery.Target target = PasswordRecovery.targetFor(file);

                if (!target.isProtected()) {
                    throw new IllegalStateException("That file isn't password-protected, so there's nothing to recover.");
                }

                return PasswordRecovery.recover(target, candidates, new PasswordRecovery.Progress() {

                    @Override
                    public void update(long tried, long total) {
                        updateProgress(tried, Math.max(1, total));
                        updateMessage(String.format("%,d of about %,d tried", tried, total));
                    }

                    @Override
                    public boolean isCancelled() {
                        return stopRecovery.get();
                    }

                });

            }

        };

        recoveryProgress.progressProperty().bind(task.progressProperty());
        recoveryProgressLabel.textProperty().bind(task.messageProperty());

        setRecovering(true);

        Ui.message(recoveryMessage, "Trying the passwords people usually pick… this can take a while.", null);

        task.setOnSucceeded(event -> {

            setRecovering(false);

            PasswordRecovery.Result result = task.getValue();

            switch (result.outcome()) {

                case FOUND -> {
                    recoveredPassword.setText(result.password());
                    showRecovered(true);
                    Ui.message(recoveryMessage, "Found it, after " + String.format("%,d", result.tried()) + " tries.", "good");
                }

                case CANCELLED -> Ui.message(recoveryMessage, "Stopped after " + String.format("%,d", result.tried()) + " tries.", "warn");

                case EXHAUSTED -> Ui.message(recoveryMessage, "No luck after " + String.format("%,d", result.tried())
                        + " tries. If it's a password you chose, add more hints — a pet, a place, a date — or import your contacts."
                        + " A long random password can't be recovered.", "warn");

            }

        });

        task.setOnFailed(event -> {

            setRecovering(false);

            Throwable error = task.getException();

            Ui.message(recoveryMessage, error instanceof IllegalStateException ? error.getMessage()
                    : "Couldn't do that: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()), "bad");

        });

        Thread thread = new Thread(task, "cybershield-password-recovery");

        thread.setDaemon(true);

        thread.start();

    }

    @FXML
    private void cancelRecovery() {
        stopRecovery.set(true);
    }

    @FXML
    private void copyRecovered() {

        if (recoveredPassword.getText().isEmpty()) {
            return;
        }

        int seconds = new SettingsDAO().clipboardClearSeconds();

        Ui.copyAndClearLater(recoveredPassword.getText(), seconds);

        Ui.message(recoveryMessage, "Copied. The clipboard clears in " + seconds + " seconds.", "good");

    }

    private void setRecovering(boolean busy) {

        recovering = busy;

        show(recoveryProgressBox, busy);

        startRecoveryButton.setDisable(busy);

        if (!busy) {
            recoveryProgress.progressProperty().unbind();
            recoveryProgressLabel.textProperty().unbind();
        }

    }

    private void showRecovered(boolean visible) {

        show(recoveredPassword, visible);

        show(copyRecoveredButton, visible);

    }

}
