package com.cybershield.ui.controllers;

import com.cybershield.dao.SettingsDAO;
import com.cybershield.encryption.SharedCipher;
import com.cybershield.services.ActivityLog;
import com.cybershield.stego.Steganography;
import com.cybershield.ui.Ui;
import com.cybershield.utils.DisplayFormat;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Steganography: hide a message or a small file in a picture, locked with a password,
 * and reveal it again. Works without the vault, like the Encryption Center.
 */
public class SteganographyController implements LockableScreen {

    private static final int MIN_PASSWORD = 8;

    @FXML private Button hideTab;
    @FXML private Button revealTab;
    @FXML private VBox hidePanel;
    @FXML private VBox revealPanel;

    @FXML private Label coverLabel;
    @FXML private RadioButton hideTextChoice;
    @FXML private RadioButton hideFileChoice;
    @FXML private TextArea messageArea;
    @FXML private HBox fileRow;
    @FXML private Label hiddenFileLabel;
    @FXML private PasswordField hidePassword;
    @FXML private PasswordField hidePasswordAgain;
    @FXML private ProgressBar roomBar;
    @FXML private Label roomLabel;
    @FXML private Button hideButton;
    @FXML private Label hideMessage;
    @FXML private GridPane previewGrid;
    @FXML private StackPane beforeBox;
    @FXML private StackPane afterBox;
    @FXML private ImageView beforeView;
    @FXML private ImageView afterView;
    @FXML private ToggleButton changesToggle;
    @FXML private Label previewNote;

    @FXML private Label revealPictureLabel;
    @FXML private PasswordField revealPassword;
    @FXML private Button revealButton;
    @FXML private Label revealMessage;
    @FXML private TextArea revealedText;
    @FXML private HBox revealedTextActions;
    @FXML private HBox revealedFileRow;
    @FXML private Label revealedFileLabel;

    private Path coverPath;

    private BufferedImage cover;

    private Path hiddenFilePath;

    private BufferedImage result;

    private Image afterImage;

    private Image changesImage;

    private Path revealPath;

    private Steganography.Hidden revealed;

    @FXML
    public void initialize() {

        for (ImageView view : List.of(beforeView, afterView)) {
            StackPane box = (StackPane) view.getParent();
            view.fitWidthProperty().bind(box.widthProperty().subtract(12));
            view.fitHeightProperty().bind(box.heightProperty().subtract(12));
        }

        hideTextChoice.selectedProperty().addListener((observable, before, text) -> {
            messageArea.setVisible(text);
            messageArea.setManaged(text);
            fileRow.setVisible(!text);
            fileRow.setManaged(!text);
            showRoom();
        });

        messageArea.textProperty().addListener((observable, before, text) -> showRoom());

        showHide();

    }

    // =====================================================
    // SWITCHING
    // =====================================================

    @FXML
    private void showHide() {
        show(hideTab, hidePanel);
    }

    @FXML
    private void showReveal() {
        show(revealTab, revealPanel);
    }

    private void show(Button tab, VBox panel) {

        for (Button each : List.of(hideTab, revealTab)) {
            each.getStyleClass().remove("active");
        }

        tab.getStyleClass().add("active");

        hidePanel.setVisible(panel == hidePanel);
        revealPanel.setVisible(panel == revealPanel);

    }

    // =====================================================
    // HIDE
    // =====================================================

    @FXML
    private void chooseCover() {

        FileChooser chooser = new FileChooser();

        chooser.setTitle("Choose the picture to hide something in");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Pictures", "*.png", "*.bmp", "*.jpg", "*.jpeg", "*.gif"));

        File file = chooser.showOpenDialog(window());

        if (file != null) {
            useCover(file.toPath());
        }

    }

    /** Also used by the tests, in place of the file dialog. */
    void useCover(Path file) {

        Ui.message(hideMessage, "Reading the picture…", null);

        Ui.background("stego-cover", () -> {

            try {
                return Steganography.read(file);
            } catch (IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }

        }, picture -> {

            coverPath = file;
            cover = picture;

            coverLabel.setText(file.getFileName() + "  ·  " + picture.getWidth() + " × " + picture.getHeight()
                    + "  ·  room for " + DisplayFormat.size(Steganography.room(picture)));

            Ui.message(hideMessage, "", null);

            hidePreview();

            showRoom();

        }, error -> Ui.message(hideMessage, reason(error), "bad"));

    }

    @FXML
    private void chooseHiddenFile() {

        FileChooser chooser = new FileChooser();

        chooser.setTitle("Choose the file to hide");

        File file = chooser.showOpenDialog(window());

        if (file != null) {
            useHiddenFile(file.toPath());
        }

    }

    /** Also used by the tests. */
    void useHiddenFile(Path file) {

        hiddenFileLabel.setText(file.getFileName() + "  ·  " + DisplayFormat.size(sizeOf(file)));

        hiddenFileLabel.getStyleClass().remove("hint");

        hiddenFileLabel.getStyleClass().add("hint");

        hiddenFilePath = file;

        hideFileChoice.setSelected(true);

        showRoom();

    }

    /** How much of the picture's room this takes, as a bar and in words. */
    private void showRoom() {

        roomBar.getStyleClass().remove("full");

        if (cover == null) {
            roomBar.setProgress(0);
            roomLabel.setText("Choose a picture to see the room");
            return;
        }

        long room = Steganography.room(cover);

        long needed = needed();

        if (needed == 0) {
            roomBar.setProgress(0);
            roomLabel.setText("Room for " + DisplayFormat.size(room));
            return;
        }

        roomBar.setProgress(Math.min(1, (double) needed / Math.max(1, room)));

        if (needed > room) {
            roomBar.getStyleClass().add("full");
            roomLabel.setText("Too big: " + DisplayFormat.size(needed) + " of " + DisplayFormat.size(room));
        } else {
            roomLabel.setText("Uses " + Math.max(1, Math.round(100.0 * needed / room)) + "% of " + DisplayFormat.size(room));
        }

    }

    private long needed() {

        if (hideTextChoice.isSelected()) {
            return messageArea.getText().isEmpty() ? 0 : Steganography.neededForText(messageArea.getText());
        }

        return hiddenFilePath == null ? 0 : Steganography.neededForFile(hiddenFilePath.getFileName().toString(), sizeOf(hiddenFilePath));

    }

    @FXML
    private void hide() {

        String problem = problemBeforeHiding();

        if (problem != null) {
            Ui.message(hideMessage, problem, "bad");
            return;
        }

        FileChooser chooser = new FileChooser();

        chooser.setTitle("Save the picture with the hidden " + (hideTextChoice.isSelected() ? "message" : "file"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG pictures", "*.png"));
        chooser.setInitialFileName(baseName(coverPath) + " (hidden).png");

        if (coverPath.getParent() != null && Files.isDirectory(coverPath.getParent())) {
            chooser.setInitialDirectory(coverPath.getParent().toFile());
        }

        File file = chooser.showSaveDialog(window());

        if (file != null) {
            hideTo(file.toPath());
        }

    }

    /** What stops hiding now, or null. */
    private String problemBeforeHiding() {

        if (cover == null) {
            return "Choose a picture first.";
        }

        if (hideTextChoice.isSelected() && messageArea.getText().isEmpty()) {
            return "Type the message to hide.";
        }

        if (hideFileChoice.isSelected() && hiddenFilePath == null) {
            return "Choose the file to hide.";
        }

        if (hidePassword.getText().length() < MIN_PASSWORD) {
            return "The password needs " + MIN_PASSWORD + " or more characters.";
        }

        if (!hidePassword.getText().equals(hidePasswordAgain.getText())) {
            return "The two passwords don't match.";
        }

        if (needed() > Steganography.room(cover)) {
            return "That doesn't fit in this picture. Choose a bigger picture, or hide less.";
        }

        return null;

    }

    /** Also used by the tests, in place of the save dialog. Always saves as PNG. */
    void hideTo(Path chosen) {

        String problem = problemBeforeHiding();

        if (problem != null) {
            Ui.message(hideMessage, problem, "bad");
            return;
        }

        Path target = chosen.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")
                ? chosen
                : chosen.resolveSibling(baseName(chosen) + ".png");

        boolean text = hideTextChoice.isSelected();

        String message = messageArea.getText();

        Path file = hiddenFilePath;

        String password = hidePassword.getText();

        BufferedImage picture = cover;

        hideButton.setDisable(true);

        Ui.message(hideMessage, "Locking and hiding…", null);

        Ui.background("stego-hide", () -> {

            try {

                BufferedImage made;

                if (text) {
                    made = Steganography.hideText(picture, message, password);
                } else {
                    byte[] data = Files.readAllBytes(file);
                    try {
                        made = Steganography.hideFile(picture, file.getFileName().toString(), data, password);
                    } finally {
                        Arrays.fill(data, (byte) 0);
                    }
                }

                Steganography.writePng(made, target);

                Image before = fx(picture);

                Image after = fx(made);

                return new Object[] {made, before, after, Steganography.changedPixels(picture, made)};

            } catch (IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }

        }, done -> {

            hideButton.setDisable(false);

            result = (BufferedImage) done[0];

            afterImage = (Image) done[2];

            changesImage = null;

            changesToggle.setSelected(false);

            beforeView.setImage((Image) done[1]);

            afterView.setImage(afterImage);

            previewGrid.setVisible(true);
            previewGrid.setManaged(true);

            long changed = (Long) done[3];

            previewNote.setText(changed + (changed == 1 ? " pixel" : " pixels") + " changed, each colour value by at most 1 in 255, "
                    + "so the two look the same. Press Show what changed to see where.");

            hidePassword.clear();
            hidePasswordAgain.clear();

            Ui.message(hideMessage, "Saved " + target.getFileName() + ".", "good");

            ActivityLog.record(ActivityLog.Action.STEGO_HIDDEN, target.getFileName().toString());

            playWolfHide();

        }, error -> {

            hideButton.setDisable(false);

            Ui.message(hideMessage, "Couldn't hide it: " + reason(error), "bad");

        });

    }

    @FXML
    private void toggleChanges() {

        if (result == null) {
            return;
        }

        if (!changesToggle.isSelected()) {
            afterView.setImage(afterImage);
            return;
        }

        if (changesImage == null) {

            try {
                changesImage = fx(Steganography.changes(cover, result));
            } catch (IOException e) {
                Ui.message(hideMessage, "Couldn't show the changes: " + e.getMessage(), "bad");
                return;
            }

        }

        afterView.setImage(changesImage);

    }

    private void hidePreview() {

        previewGrid.setVisible(false);
        previewGrid.setManaged(false);
        previewNote.setText("");
        beforeView.setImage(null);
        afterView.setImage(null);
        result = null;
        afterImage = null;
        changesImage = null;

    }

    // =====================================================
    // REVEAL
    // =====================================================

    @FXML
    private void chooseHiddenPicture() {

        FileChooser chooser = new FileChooser();

        chooser.setTitle("Choose the picture something was hidden in");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Pictures", "*.png", "*.bmp"));

        File file = chooser.showOpenDialog(window());

        if (file != null) {
            useHiddenPicture(file.toPath());
        }

    }

    /** Also used by the tests. */
    void useHiddenPicture(Path file) {

        revealPath = file;

        revealPictureLabel.setText(file.getFileName() + "  ·  " + DisplayFormat.size(sizeOf(file)));

        clearRevealed();

    }

    @FXML
    void reveal() {

        if (revealPath == null) {
            Ui.message(revealMessage, "Choose a picture first.", "bad");
            return;
        }

        if (revealPassword.getText().isEmpty()) {
            Ui.message(revealMessage, "Type the password it was hidden with.", "bad");
            return;
        }

        Path file = revealPath;

        String password = revealPassword.getText();

        revealButton.setDisable(true);

        clearRevealed();

        Ui.message(revealMessage, "Looking…", null);

        Ui.background("stego-reveal", () -> {

            try {
                return Steganography.reveal(Steganography.read(file), password);
            } catch (IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }

        }, found -> {

            revealButton.setDisable(false);

            revealPassword.clear();

            revealed = found;

            if (found.isText()) {

                revealedText.setText(found.text());
                setShown(revealedText, true);
                setShown(revealedTextActions, true);

                Ui.message(revealMessage, "This message was hidden in the picture.", "good");

            } else {

                revealedFileLabel.setText("A hidden file: " + found.fileName() + "  ·  " + DisplayFormat.size(found.fileData().length));
                setShown(revealedFileRow, true);

                Ui.message(revealMessage, "A file was hidden in the picture.", "good");

            }

            ActivityLog.record(ActivityLog.Action.STEGO_REVEALED, file.getFileName().toString());

            playWolfReveal();

        }, error -> {

            revealButton.setDisable(false);

            Throwable cause = error.getCause() != null ? error.getCause() : error;

            Ui.message(revealMessage, cause instanceof SharedCipher.WrongPasswordException
                    ? "That password doesn't open it, or the picture was changed after hiding."
                    : reason(error), "bad");

        });

    }

    /** The wolf-in-sheep's-clothing flourish over the whole screen when a reveal succeeds. */
    private void playWolfReveal() {
        javafx.scene.layout.StackPane host = contentHost();
        if (host != null) {
            com.cybershield.ui.WolfRevealAnimation.playOver(host);
        }
    }

    /** The reverse flourish — the sheep laid back over the wolf — when a message is hidden. */
    private void playWolfHide() {
        javafx.scene.layout.StackPane host = contentHost();
        if (host != null) {
            com.cybershield.ui.WolfRevealAnimation.playHideOver(host);
        }
    }

    /** The StackPane the screen sits in (the dashboard content area), or null if the intro is off. */
    private javafx.scene.layout.StackPane contentHost() {
        if ("true".equals(System.getProperty("cybershield.noIntro"))) {
            return null;
        }
        javafx.scene.Node node = hidePanel;
        while (node != null && !(node.getParent() instanceof javafx.scene.layout.StackPane)) {
            node = node.getParent();
        }
        return node == null ? null : (javafx.scene.layout.StackPane) node.getParent();
    }

    @FXML
    private void copyRevealed() {

        if (revealed != null && revealed.isText()) {

            Ui.copyAndClearLater(revealed.text(), new SettingsDAO().clipboardClearSeconds());

            Ui.message(revealMessage, "Copied. The clipboard clears itself in a moment.", "good");

        }

    }

    @FXML
    private void saveRevealedFile() {

        if (revealed == null || revealed.isText()) {
            return;
        }

        FileChooser chooser = new FileChooser();

        chooser.setTitle("Save the hidden file");
        chooser.setInitialFileName(revealed.fileName());

        if (revealPath != null && revealPath.getParent() != null && Files.isDirectory(revealPath.getParent())) {
            chooser.setInitialDirectory(revealPath.getParent().toFile());
        }

        File file = chooser.showSaveDialog(window());

        if (file == null) {
            return;
        }

        try {
            Files.write(file.toPath(), revealed.fileData());
            Ui.message(revealMessage, "Saved " + file.getName() + ".", "good");
        } catch (IOException e) {
            Ui.message(revealMessage, "Couldn't save it: " + e.getMessage(), "bad");
        }

    }

    private void clearRevealed() {

        if (revealed != null && revealed.fileData() != null) {
            Arrays.fill(revealed.fileData(), (byte) 0);
        }

        revealed = null;

        revealedText.clear();

        setShown(revealedText, false);
        setShown(revealedTextActions, false);
        setShown(revealedFileRow, false);

        Ui.message(revealMessage, "", null);

    }

    // =====================================================
    // HELPERS
    // =====================================================

    private static void setShown(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private static Image fx(BufferedImage picture) throws IOException {
        return new Image(new ByteArrayInputStream(Steganography.pngBytes(picture)));
    }

    private static long sizeOf(Path file) {

        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }

    }

    private static String baseName(Path file) {

        String name = file.getFileName().toString();

        int dot = name.lastIndexOf('.');

        return dot > 0 ? name.substring(0, dot) : name;

    }

    private static String reason(Throwable error) {

        Throwable cause = error;

        while (cause.getCause() != null && cause.getMessage() != null && cause.getMessage().equals(cause.getCause().getMessage())) {
            cause = cause.getCause();
        }

        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();

    }

    private Window window() {
        return hidePanel.getScene() == null ? null : hidePanel.getScene().getWindow();
    }

    @Override
    public void setOnLocked(Runnable onLocked) {
        // Works without the vault: nothing to do when it locks.
    }

    /** Drops what was revealed and the pictures when the screen closes. */
    @Override
    public void dispose() {

        clearRevealed();

        hidePreview();

        messageArea.clear();
        hidePassword.clear();
        hidePasswordAgain.clear();
        revealPassword.clear();

        cover = null;

    }

}
