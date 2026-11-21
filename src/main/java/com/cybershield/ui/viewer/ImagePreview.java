package com.cybershield.ui.viewer;

import javafx.beans.binding.Bindings;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

/** Shows a photo, fitted to the window by default, with zoom. Copying isn't offered. */
public final class ImagePreview implements FilePreview {

    private final Image image;

    private final ImageView imageView = new ImageView();

    private final ScrollPane scroll = new ScrollPane();

    private final Label zoomLabel = new Label();

    private final List<Node> controls;

    // 0 means "fit to the window".
    private double zoom;

    public ImagePreview(Image image) {

        this.image = image;

        imageView.setImage(image);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.setId("previewImage");

        // Keeps a small picture centred instead of in the top-left corner.
        StackPane holder = new StackPane(imageView);
        holder.minWidthProperty().bind(Bindings.createDoubleBinding(
                () -> scroll.getViewportBounds().getWidth(), scroll.viewportBoundsProperty()));
        holder.minHeightProperty().bind(Bindings.createDoubleBinding(
                () -> scroll.getViewportBounds().getHeight(), scroll.viewportBoundsProperty()));

        scroll.setContent(holder);
        scroll.setPannable(true);
        scroll.getStyleClass().add("preview-scroll");
        scroll.viewportBoundsProperty().addListener((observable, before, after) -> applyZoom());

        controls = List.of(
                Controls.button("−", () -> setZoom(currentScale() / 1.25)),
                zoomLabel,
                Controls.button("+", () -> setZoom(currentScale() * 1.25)),
                Controls.button("Fit", () -> setZoom(0)),
                Controls.button("100%", () -> setZoom(1)));

        applyZoom();

    }

    /** Decodes JPG, PNG, GIF or BMP bytes held in memory. */
    public static Image decode(byte[] bytes) throws IOException {

        Image image = new Image(new ByteArrayInputStream(bytes));

        if (image.isError() || image.getWidth() <= 0) {
            throw new IOException("This picture can't be read.");
        }

        return image;

    }

    @Override
    public Node view() {
        return scroll;
    }

    @Override
    public List<Node> controls() {
        return controls;
    }

    @Override
    public FilePreview fullScreenCopy() {
        return new ImagePreview(image);
    }

    @Override
    public void dispose() {
        imageView.setImage(null);
    }

    private void setZoom(double value) {

        zoom = value == 0 ? 0 : Math.max(0.05, Math.min(8, value));

        applyZoom();

    }

    private double fitScale() {

        Bounds viewport = scroll.getViewportBounds();

        if (viewport.getWidth() <= 0 || viewport.getHeight() <= 0) {
            return 1;
        }

        // Shrink big pictures to fit, but don't blow small ones up.
        return Math.min(1, Math.min(viewport.getWidth() / image.getWidth(), viewport.getHeight() / image.getHeight()));

    }

    private double currentScale() {
        return zoom > 0 ? zoom : fitScale();
    }

    private void applyZoom() {

        double scale = currentScale();

        imageView.setFitWidth(image.getWidth() * scale);
        imageView.setFitHeight(image.getHeight() * scale);

        zoomLabel.setText(Math.round(scale * 100) + "%");

    }

}
