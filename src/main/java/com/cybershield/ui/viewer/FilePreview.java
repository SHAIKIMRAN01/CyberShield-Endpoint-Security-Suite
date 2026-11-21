package com.cybershield.ui.viewer;

import javafx.scene.Node;

import java.util.List;

/** A view of one decrypted file. Its content lives only in memory, never on disk. */
public interface FilePreview {

    /** The content to show. */
    Node view();

    /** Buttons and labels for the bar under the content. */
    List<Node> controls();

    /** An independent view of the same file for the full-screen window, or null if there is none. */
    default FilePreview fullScreenCopy() {
        return null;
    }

    /** Stops playback and lets go of the decrypted data. Safe to call more than once. */
    default void dispose() {
    }

}
