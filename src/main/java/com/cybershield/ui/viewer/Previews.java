package com.cybershield.ui.viewer;

import com.cybershield.files.FileVaultService;

import javafx.scene.image.Image;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

/**
 * Loads previews in two halves: the slow part (decrypting and decoding) on a
 * background thread, then the views on the JavaFX thread.
 */
public final class Previews {

    /** A decrypted, decoded file waiting to be shown. */
    public interface Prepared {

        /** Builds the view. Runs on the JavaFX thread. */
        FilePreview create() throws IOException;

        /** Lets go of the data when it is no longer wanted (e.g. the user moved on). */
        void discard();

    }

    private Previews() {
    }

    /** Runs on a background thread. Everything stays in memory. */
    public static Prepared prepare(PreviewKind kind, FileVaultService service, FileVaultService.VaultFile file)
            throws IOException {

        if (kind != PreviewKind.NONE) {
            com.cybershield.services.ActivityLog.record(com.cybershield.services.ActivityLog.Action.FILE_OPENED, file.originalPath());
        }

        switch (kind) {

            case IMAGE: {

                byte[] bytes = service.readFile(file, kind.sizeLimit());

                try {

                    Image image = ImagePreview.decode(bytes);

                    // Phone photos are stored sideways with a note on how to turn them.
                    ExifOrientation.Turn turn = ExifOrientation.of(ExifOrientation.read(bytes));

                    return prepared(() -> new ImagePreview(image, turn), () -> { });

                } finally {

                    Arrays.fill(bytes, (byte) 0);

                }

            }

            case TEXT: {

                byte[] bytes = service.readFile(file, kind.sizeLimit());

                try {

                    String text = TextPreview.decode(bytes);

                    return prepared(() -> new TextPreview(text), () -> { });

                } finally {

                    Arrays.fill(bytes, (byte) 0);

                }

            }

            case PDF: {

                byte[] bytes = service.readFile(file, kind.sizeLimit());

                PDDocument document;

                try {
                    document = PdfPreview.load(bytes);
                } catch (IOException e) {
                    Arrays.fill(bytes, (byte) 0);
                    throw e;
                }

                return prepared(() -> new PdfPreview(document, bytes), () -> {
                    try {
                        document.close();
                    } catch (IOException ignored) {
                        // Nothing left to release.
                    }
                    Arrays.fill(bytes, (byte) 0);
                });

            }

            case MEDIA: {

                ByteBuffer buffer = service.readFileForPlayback(file, kind.sizeLimit());

                try {

                    Prepared split = splitAacIfNeeded(buffer, file.extension());

                    if (split != null) {
                        return split;
                    }

                } catch (IOException | RuntimeException e) {

                    MediaPreview.wipe(buffer);

                    throw e;

                }

                return prepared(() -> MediaPreview.open(buffer, file.extension()), () -> MediaPreview.wipe(buffer));

            }

            default:

                throw new IllegalArgumentException("There is no viewer for " + kind);

        }

    }

    /**
     * Where JavaFX can't decode AAC sound, decodes it in Java: the video then plays with its
     * sound tracks switched off, beside the decoded WAV. A song (M4A) becomes just the WAV.
     * Returns null when the file can play as it is.
     */
    private static Prepared splitAacIfNeeded(ByteBuffer buffer, String extension) throws IOException {

        if (!MediaPreview.usesAac(extension) || MediaPreview.aacPlays()) {
            return null;
        }

        List<Mp4Sound.Track> tracks = Mp4Sound.tracks(buffer);

        Mp4Sound.Track aac = Mp4Sound.firstAac(tracks);

        if (aac == null) {
            return null;
        }

        ByteBuffer sound;

        String note = null;

        try {

            sound = Mp4Sound.decodeToWav(buffer, aac);

        } catch (Mp4Sound.TooLongException tooLong) {

            if (!Mp4Sound.hasVideo(tracks)) {
                throw new IOException("This song is too long to play here. Use Export a copy to play it in another app.");
            }

            sound = null;

            note = "No sound: it is too long to prepare here.";

        }

        if (!Mp4Sound.hasVideo(tracks)) {

            MediaPreview.wipe(buffer);

            ByteBuffer song = sound;

            return prepared(() -> MediaPreview.open(song, "wav"), () -> MediaPreview.wipe(song));

        }

        Mp4Sound.muteInPlace(buffer, tracks);

        ByteBuffer decoded = sound;

        String why = note;

        return prepared(() -> MediaPreview.openSplit(buffer, decoded, extension, why), () -> {
            MediaPreview.wipe(buffer);
            MediaPreview.wipe(decoded);
        });

    }

    private interface Creator {
        FilePreview create() throws IOException;
    }

    private static Prepared prepared(Creator creator, Runnable discard) {

        return new Prepared() {

            @Override
            public FilePreview create() throws IOException {
                return creator.create();
            }

            @Override
            public void discard() {
                discard.run();
            }

        };

    }

}
