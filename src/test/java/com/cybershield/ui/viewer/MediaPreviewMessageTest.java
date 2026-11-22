package com.cybershield.ui.viewer;

import javafx.scene.media.MediaException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The viewer explains the AAC problem instead of showing a bare error code. */
class MediaPreviewMessageTest {

    @Test
    void aacFilesGetAnExplanationAndOthersTheError() throws Exception {

        assertTrue(MediaPreview.usesAac("MP4"));
        assertTrue(MediaPreview.usesAac("m4a"));
        assertFalse(MediaPreview.usesAac("mp3"));
        assertFalse(MediaPreview.usesAac("wav"));

        var constructor = MediaException.class.getDeclaredConstructor(MediaException.Type.class, String.class);
        constructor.setAccessible(true);
        MediaException invalid = constructor.newInstance(MediaException.Type.UNKNOWN,
                "[player] ERROR_MEDIA_INVALID: ERROR_MEDIA_INVALID");

        assertTrue(MediaPreview.problem(invalid, true).contains("AAC"));
        assertTrue(MediaPreview.problem(invalid, false).startsWith("Can't play: "));

    }

}
