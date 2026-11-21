package com.cybershield.ui.viewer;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.ContextMenuEvent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Shows a text file read-only, with search. Copying is blocked, because
 * Windows clipboard history can keep anything copied and sync it to other devices.
 */
public final class TextPreview implements FilePreview {

    private final String text;

    private final TextArea area = new TextArea() {

        @Override
        public void copy() {
            // Blocked on purpose.
        }

        @Override
        public void cut() {
            // Blocked on purpose.
        }

    };

    private final TextField findField = new TextField();

    private final Label findStatus = new Label();

    private final List<Node> controls;

    public TextPreview(String text) {

        this.text = text;

        area.setText(text);
        area.setEditable(false);
        area.setWrapText(false);
        area.setId("previewText");
        area.getStyleClass().add("preview-text");

        // No right-click menu, so there is no Copy item either.
        area.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, Event::consume);

        findField.setPromptText("Find in text");
        findField.setPrefColumnCount(14);
        findField.setOnAction(event -> findNext());

        ToggleButton wrap = new ToggleButton("Wrap lines");
        wrap.selectedProperty().addListener((observable, before, on) -> area.setWrapText(on));

        controls = List.of(findField, Controls.button("Find next", this::findNext), findStatus, wrap);

    }

    /**
     * Turns bytes into text: honours a byte-order mark, otherwise tries UTF-8
     * and falls back to Windows-1252. Refuses data that looks binary.
     */
    public static String decode(byte[] bytes) throws IOException {

        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }

        if (startsWith(bytes, 0xFF, 0xFE)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }

        if (startsWith(bytes, 0xFE, 0xFF)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }

        for (int i = 0; i < Math.min(bytes.length, 8192); i++) {

            if (bytes[i] == 0) {
                throw new IOException("This doesn't look like a text file.");
            }

        }

        try {

            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();

        } catch (CharacterCodingException e) {

            return new String(bytes, Charset.forName("windows-1252"));

        }

    }

    private static boolean startsWith(byte[] bytes, int... prefix) {

        if (bytes.length < prefix.length) {
            return false;
        }

        for (int i = 0; i < prefix.length; i++) {

            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }

        }

        return true;

    }

    @Override
    public Node view() {
        return area;
    }

    @Override
    public List<Node> controls() {
        return controls;
    }

    @Override
    public FilePreview fullScreenCopy() {
        return new TextPreview(text);
    }

    @Override
    public void dispose() {
        area.clear();
    }

    /** Finds the next match after the current selection, ignoring case, wrapping at the end. */
    private void findNext() {

        String needle = findField.getText();

        if (needle.isEmpty()) {
            return;
        }

        String haystack = area.getText();

        int at = indexOfIgnoreCase(haystack, needle, area.getSelection().getEnd());

        if (at < 0) {
            at = indexOfIgnoreCase(haystack, needle, 0);
        }

        if (at < 0) {
            findStatus.setText("Not found");
            return;
        }

        findStatus.setText("");

        area.selectRange(at, at + needle.length());

    }

    private static int indexOfIgnoreCase(String haystack, String needle, int from) {

        for (int i = Math.max(0, from); i <= haystack.length() - needle.length(); i++) {

            if (haystack.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }

        }

        return -1;

    }

}
