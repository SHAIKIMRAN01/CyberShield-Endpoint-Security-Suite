package com.cybershield.ui.viewer;

import javafx.beans.binding.Bindings;
import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

/**
 * Shows a photo, fitted to the window by default, with zoom and turning. Phone photos
 * are turned upright from their EXIF orientation. Copying isn't offered.
 *
 * Fitting has no scroll bars: with them, a picture sized to the visible area could make
 * a bar appear, which shrank the area, which shrank the picture, which hid the bar, and
 * so on, so the picture shook. Zoomed in, the bars come back for panning.
 */
public final class ImagePreview implements FilePreview {

    private final Image image;

    private final ImageView imageView = new ImageView();

    // The group's bounds follow the turned picture, so layout and fitting use the turned size.
    private final Group turned = new Group(imageView);

    private final ScrollPane scroll = new ScrollPane();

    private final Label zoomLabel = new Label();

    private final List<Node> controls;

    // 0 means "fit to the window".
    private double zoom;

    // Clockwise: 0, 90, 180 or 270.
    private int degrees;

    private boolean mirrored;

    public ImagePreview(Image image) {
        this(image, ExifOrientation.Turn.NONE);
    }

    public ImagePreview(Image image, ExifOrientation.Turn turn) {

        this.image = image;

        imageView.setImage(image);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.setId("previewImage");

        degrees = Math.floorMod(turn.degrees(), 360);
        mirrored = turn.mirrored();

        // Keeps a small picture centred instead of in the top-left corner.
        StackPane holder = new StackPane(turned);
        holder.minWidthProperty().bind(Bindings.createDoubleBinding(
                () -> scroll.getViewportBounds().getWidth(), scroll.viewportBoundsProperty()));
        holder.minHeightProperty().bind(Bindings.createDoubleBinding(
                () -> scroll.getViewportBounds().getHeight(), scroll.viewportBoundsProperty()));

        scroll.setContent(holder);
        scroll.setPannable(true);
        scroll.getStyleClass().add("preview-scroll");
        scroll.viewportBoundsProperty().addListener((observable, before, after) -> {
            if (zoom == 0) {
                applyZoom();
            }
        });

        controls = List.of(
                tip(Controls.button("↺", () -> turn(-90)), "Turn left"),
                tip(Controls.button("↻", () -> turn(90)), "Turn right"),
                tip(Controls.button("−", () -> setZoom(currentScale() / 1.25)), "Zoom out"),
                zoomLabel,
                tip(Controls.button("+", () -> setZoom(currentScale() * 1.25)), "Zoom in"),
                Controls.button("Fit", () -> setZoom(0)),
                Controls.button("100%", () -> setZoom(1)));

        applyTurn();

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
        return new ImagePreview(image, new ExifOrientation.Turn(degrees, mirrored));
    }

    @Override
    public void dispose() {
        imageView.setImage(null);
    }

    /** The current turn, for the tests. */
    int degrees() {
        return degrees;
    }

    /** The current scale (1 = 100 %), for the tests. */
    double scale() {
        return currentScale();
    }

    private void turn(int by) {

        degrees = Math.floorMod(degrees + by, 360);

        applyTurn();

        applyZoom();

    }

    private void applyTurn() {

        imageView.setRotate(degrees);

        imageView.setScaleX(mirrored ? -1 : 1);

    }

    private void setZoom(double value) {

        zoom = value == 0 ? 0 : Math.max(0.05, Math.min(8, value));

        applyZoom();

    }

    private boolean sideways() {
        return degrees == 90 || degrees == 270;
    }

    private double fitScale() {

        Bounds viewport = scroll.getViewportBounds();

        if (viewport.getWidth() <= 0 || viewport.getHeight() <= 0) {
            return 1;
        }

        double width = sideways() ? image.getHeight() : image.getWidth();

        double height = sideways() ? image.getWidth() : image.getHeight();

        // Shrink big pictures to fit, but don't blow small ones up.
        return Math.min(1, Math.min(viewport.getWidth() / width, viewport.getHeight() / height));

    }

    private double currentScale() {
        return zoom > 0 ? zoom : fitScale();
    }

    private void applyZoom() {

        boolean fitting = zoom == 0;

        // No scroll bars while fitting (see the class comment); bars as needed when zoomed.
        ScrollPane.ScrollBarPolicy policy = fitting ? ScrollPane.ScrollBarPolicy.NEVER : ScrollPane.ScrollBarPolicy.AS_NEEDED;
        scroll.setHbarPolicy(policy);
        scroll.setVbarPolicy(policy);

        double scale = currentScale();

        // Whole pixels, rounded down, so a fitted picture never overflows by a fraction.
        imageView.setFitWidth(Math.max(1, Math.floor(image.getWidth() * scale)));
        imageView.setFitHeight(Math.max(1, Math.floor(image.getHeight() * scale)));

        zoomLabel.setText(Math.round(scale * 100) + "%");

    }

    private static <T extends javafx.scene.control.Control> T tip(T control, String text) {

        control.setTooltip(new Tooltip(text));

        return control;

    }

}
