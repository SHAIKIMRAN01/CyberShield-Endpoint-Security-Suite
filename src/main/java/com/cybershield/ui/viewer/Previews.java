package com.cybershield.ui.viewer;

import com.cybershield.files.FileVaultService;

import javafx.scene.image.Image;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

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

        switch (kind) {

            case IMAGE: {

                byte[] bytes = service.readFile(file, kind.sizeLimit());

                try {

                    Image image = ImagePreview.decode(bytes);

                    return prepared(() -> new ImagePreview(image), () -> { });

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

                return prepared(() -> MediaPreview.open(buffer, file.extension()), () -> MediaPreview.wipe(buffer));

            }

            default:

                throw new IllegalArgumentException("There is no viewer for " + kind);

        }

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
