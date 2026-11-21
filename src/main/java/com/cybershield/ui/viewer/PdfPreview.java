package com.cybershield.ui.viewer;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows a PDF one page at a time. Pages are drawn as pictures by Apache PDFBox
 * from the decrypted bytes in memory; nothing is written to disk and there is
 * no selectable text to copy.
 */
public final class PdfPreview implements FilePreview {

    private static final float BASE_DPI = 110;

    private final PDDocument document;

    private final byte[] bytes;

    private final PDFRenderer renderer;

    private final int pages;

    // PDFBox isn't thread-safe, so every use of the document goes through one thread.
    private final ExecutorService drawer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "cybershield-pdf");
        thread.setDaemon(true);
        return thread;
    });

    private final ImageView pageView = new ImageView();

    private final ScrollPane scroll = new ScrollPane();

    private final Label pageLabel = new Label();

    private final Button previous;

    private final Button next;

    private final List<Node> controls;

    private int page;

    private float scale = 1f;

    private boolean disposed;

    /**
     * Opens a PDF held in memory. PDFBox is told to keep its working data in
     * memory too, never in temporary files.
     */
    public static PDDocument load(byte[] bytes) throws IOException {

        try {

            return Loader.loadPDF(bytes, "", null, null, IOUtils.createMemoryOnlyStreamCache());

        } catch (InvalidPasswordException e) {

            throw new IOException("This PDF has its own password, so it can't be shown here.", e);

        }

    }

    /** Takes ownership of the document and its bytes; both are cleared on dispose. */
    public PdfPreview(PDDocument document, byte[] bytes) {

        this.document = document;
        this.bytes = bytes;
        this.renderer = new PDFRenderer(document);
        this.pages = document.getNumberOfPages();

        pageView.setPreserveRatio(true);
        pageView.setId("pdfPage");

        StackPane holder = new StackPane(pageView);
        holder.minWidthProperty().bind(Bindings.createDoubleBinding(
                () -> scroll.getViewportBounds().getWidth(), scroll.viewportBoundsProperty()));

        scroll.setContent(holder);
        scroll.setPannable(true);
        scroll.getStyleClass().add("preview-scroll");

        pageLabel.setId("pdfPageLabel");

        previous = Controls.button("◀ Page", () -> show(page - 1));
        next = Controls.button("Page ▶", () -> show(page + 1));

        controls = List.of(
                previous,
                pageLabel,
                next,
                Controls.button("−", () -> zoom(1 / 1.25f)),
                Controls.button("+", () -> zoom(1.25f)));

        draw();

    }

    @Override
    public Node view() {
        return scroll;
    }

    @Override
    public List<Node> controls() {
        return controls;
    }

    /** The full-screen window gets its own copy, so closing one never affects the other. */
    @Override
    public FilePreview fullScreenCopy() {

        try {

            byte[] copy = bytes.clone();

            PdfPreview preview = new PdfPreview(load(copy), copy);

            preview.show(page);

            return preview;

        } catch (IOException e) {

            return null;

        }

    }

    @Override
    public void dispose() {

        if (disposed) {
            return;
        }

        disposed = true;

        pageView.setImage(null);

        drawer.execute(() -> {

            try {
                document.close();
            } catch (IOException e) {
                // Closing an in-memory document has nothing left to release.
            }

            Arrays.fill(bytes, (byte) 0);

        });

        drawer.shutdown();

    }

    public int pageCount() {
        return pages;
    }

    private void show(int index) {

        if (index < 0 || index >= pages || disposed) {
            return;
        }

        page = index;

        draw();

    }

    private void zoom(float factor) {

        scale = Math.max(0.25f, Math.min(4f, scale * factor));

        draw();

    }

    private void draw() {

        int index = page;

        float dpi = BASE_DPI * scale;

        pageLabel.setText("Page " + (index + 1) + " of " + pages);

        previous.setDisable(index == 0);

        next.setDisable(index >= pages - 1);

        drawer.execute(() -> {

            try {

                BufferedImage image = renderer.renderImageWithDPI(index, dpi, ImageType.RGB);

                int width = image.getWidth();

                int height = image.getHeight();

                int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);

                Platform.runLater(() -> {

                    if (disposed || index != page) {
                        return;
                    }

                    WritableImage picture = new WritableImage(width, height);

                    picture.getPixelWriter().setPixels(0, 0, width, height,
                            PixelFormat.getIntArgbInstance(), pixels, 0, width);

                    pageView.setImage(picture);

                });

            } catch (IOException | RuntimeException e) {

                Platform.runLater(() -> pageLabel.setText("Page " + (index + 1) + " couldn't be drawn"));

            }

        });

    }

}
