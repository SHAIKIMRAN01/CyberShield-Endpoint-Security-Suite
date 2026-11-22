package com.cybershield.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.transform.Scale;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CyberShield's logo: a brown cartoon wolf guarding a treasure chest, on a round
 * off-white badge. It is drawn from shapes on a 256 × 256 canvas, so it is sharp at any
 * size: the sidebar, the sign-in screen, the window icons and the opening animation all
 * use it. The animation also uses the chest's parts on their own, to open the lid.
 */
public final class Logo {

    public static final double CANVAS = 256;

    // The palette: the app's browns and off-white, with gold for the treasure.
    static final Color OUTLINE = Color.web("#2B1A10");
    static final Color BADGE = Color.web("#F4E7D1");
    static final Color FUR = Color.web("#8A5A36");
    static final Color FUR_DARK = Color.web("#5E3A22");
    static final Color FUR_LIGHT = Color.web("#EAD6B8");
    static final Color EYE = Color.web("#F2B33D");
    static final Color WHITE = Color.web("#FFF9EE");
    static final Color WOOD = Color.web("#6F4428");
    static final Color WOOD_LIGHT = Color.web("#8E5833");
    static final Color GOLD = Color.web("#E2A93B");
    static final Color GOLD_LIGHT = Color.web("#F6D27A");

    /** One shape: an SVG path with its fill and outline. A null fill draws only the line. */
    public record Part(String id, String path, Color fill, Color stroke, double width) {
    }

    public static final List<Part> BADGE_PARTS = List.of(
            new Part("badge", "M128 6 A122 122 0 1 1 127.99 6 Z", BADGE, OUTLINE, 6));

    public static final List<Part> WOLF = List.of(
            // Ears, behind the head.
            new Part("ear-left", "M70 70 Q66 40 78 16 Q96 30 112 50 Z", FUR_DARK, OUTLINE, 6),
            new Part("ear-right", "M186 70 Q190 40 178 16 Q160 30 144 50 Z", FUR_DARK, OUTLINE, 6),
            new Part("ear-left-in", "M80 61 Q77 42 83 28 Q95 39 103 52 Z", Color.web("#E7C29E"), null, 0),
            new Part("ear-right-in", "M176 61 Q179 42 173 28 Q161 39 153 52 Z", Color.web("#E7C29E"), null, 0),
            // Head with tufts of fur at the cheeks.
            new Part("head", "M128 34 C158 34 182 50 188 74 L206 84 L192 92 L202 104 L180 106 "
                    + "C170 120 150 126 128 126 C106 126 86 120 76 106 L54 104 L64 92 L50 84 L68 74 "
                    + "C74 50 98 34 128 34 Z", FUR, OUTLINE, 6),
            // The lighter mask over the muzzle and cheeks.
            new Part("mask", "M128 64 C140 64 148 74 152 86 C164 88 174 96 172 106 C160 118 146 124 128 124 "
                    + "C110 124 96 118 84 106 C82 96 92 88 104 86 C108 74 116 64 128 64 Z", FUR_LIGHT, null, 0),
            // A darker stripe down the forehead, as wolves have.
            new Part("stripe", "M128 38 C134 50 136 62 132 74 Q128 78 124 74 C120 62 122 50 128 38 Z", FUR_DARK, null, 0),
            // Amber eyes under stern brows.
            new Part("eye-left", "M94 80 Q106 68 120 80 Q106 88 94 80 Z", EYE, OUTLINE, 3.5),
            new Part("eye-right", "M162 80 Q150 68 136 80 Q150 88 162 80 Z", EYE, OUTLINE, 3.5),
            new Part("pupil-left", "M107.5 76.5 A3.6 3.6 0 1 1 107.49 76.5 Z", OUTLINE, null, 0),
            new Part("pupil-right", "M148.5 76.5 A3.6 3.6 0 1 1 148.49 76.5 Z", OUTLINE, null, 0),
            new Part("shine-left", "M110 76 A1.5 1.5 0 1 1 109.99 76 Z", WHITE, null, 0),
            new Part("shine-right", "M151 76 A1.5 1.5 0 1 1 150.99 76 Z", WHITE, null, 0),
            new Part("brow-left", "M90 70 L117 75", null, OUTLINE, 5),
            new Part("brow-right", "M166 70 L139 75", null, OUTLINE, 5),
            // Nose and mouth.
            new Part("nose", "M114 98 Q128 91 142 98 Q138 108 128 111 Q118 108 114 98 Z", OUTLINE, null, 0),
            new Part("nose-shine", "M121 97.5 A3 1.8 0 1 1 120.99 97.5 Z", Color.web("#7A5A44"), null, 0),
            new Part("mouth", "M128 111 L128 116 M128 116 Q121 121 114 117 M128 116 Q135 121 142 117", null, OUTLINE, 3));

    public static final List<Part> CHEST_BODY = List.of(
            new Part("body", "M54 150 H202 V200 Q202 212 190 212 H66 Q54 212 54 200 Z", WOOD, OUTLINE, 6),
            new Part("plank", "M58 181 H198", null, Color.web("#5A361F"), 3),
            new Part("band-body-left", "M76 152 H94 V211 H76 Z", GOLD, OUTLINE, 4),
            new Part("band-body-right", "M162 152 H180 V211 H162 Z", GOLD, OUTLINE, 4));

    public static final List<Part> CHEST_LID = List.of(
            new Part("lid", "M46 152 V134 Q46 112 70 112 H186 Q210 112 210 134 V152 Z", WOOD_LIGHT, OUTLINE, 6),
            new Part("band-lid-left", "M76 113 H94 V151 H76 Z", GOLD, OUTLINE, 4),
            new Part("band-lid-right", "M162 113 H180 V151 H162 Z", GOLD, OUTLINE, 4),
            new Part("rim", "M44 146 H212 V158 H44 Z", GOLD, OUTLINE, 4),
            new Part("rim-shine", "M50 149.5 H206", null, GOLD_LIGHT, 2));

    public static final List<Part> PAWS = List.of(
            new Part("paw-left", "M68 118 Q68 104 86 104 Q104 104 104 118 Q104 126 86 126 Q68 126 68 118 Z", FUR, OUTLINE, 5),
            new Part("paw-right", "M152 118 Q152 104 170 104 Q188 104 188 118 Q188 126 170 126 Q152 126 152 118 Z", FUR, OUTLINE, 5),
            new Part("toes", "M80 117 V125 M92 117 V125 M164 117 V125 M176 117 V125", null, OUTLINE, 2.5));

    public static final List<Part> LOCK = List.of(
            new Part("plate", "M114 150 H142 Q146 150 146 154 V184 Q146 190 140 190 H116 Q110 190 110 184 V154 Q110 150 114 150 Z",
                    GOLD, OUTLINE, 4),
            new Part("plate-shine", "M116 155 H132", null, GOLD_LIGHT, 2),
            new Part("keyhole", "M128 160 A5.5 5.5 0 0 1 131 170 L133 180 H123 L125 170 A5.5 5.5 0 0 1 128 160 Z", OUTLINE, null, 0));

    private Logo() {
    }

    /** The whole logo, size pixels wide and high. */
    public static Node create(double size) {
        return create(size, true);
    }

    /** The logo with or without its round badge. */
    public static Node create(double size, boolean badge) {

        List<Part> parts = new ArrayList<>();

        if (badge) {
            parts.addAll(BADGE_PARTS);
        }

        parts.addAll(WOLF);
        parts.addAll(CHEST_BODY);
        parts.addAll(CHEST_LID);
        parts.addAll(PAWS);
        parts.addAll(LOCK);

        return scaled(group(parts), size);

    }

    /** A group of parts on the 256 canvas, unscaled. */
    public static Group group(List<Part> parts) {

        Group group = new Group();

        for (Part part : parts) {
            group.getChildren().add(shape(part));
        }

        return group;

    }

    /** Shrinks or grows a canvas-sized group to size pixels. */
    public static Group scaled(Node canvas, double size) {

        double factor = size / CANVAS;

        canvas.getTransforms().setAll(new Scale(factor, factor, 0, 0));

        return new Group(canvas);

    }

    private static SVGPath shape(Part part) {

        SVGPath shape = new SVGPath();

        shape.setId(part.id());
        shape.setContent(part.path());
        shape.setFill(part.fill());

        if (part.stroke() != null && part.width() > 0) {
            shape.setStroke(part.stroke());
            shape.setStrokeWidth(part.width());
            shape.setStrokeLineJoin(StrokeLineJoin.ROUND);
            shape.setStrokeLineCap(StrokeLineCap.ROUND);
        }

        return shape;

    }

    /** The logo as a picture with a clear background, for window icons. Call on the JavaFX thread. */
    public static Image image(int size) {

        SnapshotParameters parameters = new SnapshotParameters();

        parameters.setFill(Color.TRANSPARENT);

        // The whole square, not just the drawing's edges: icons must be exactly size × size.
        parameters.setViewport(new javafx.geometry.Rectangle2D(0, 0, size, size));

        return create(size).snapshot(parameters, null);

    }

    private static List<Image> windowIcons;

    /** The sizes Windows picks from for the title bar, taskbar and Alt+Tab. Call on the JavaFX thread. */
    public static List<Image> windowIcons() {

        if (windowIcons == null) {

            List<Image> icons = new ArrayList<>();

            for (int size : new int[] {16, 24, 32, 48, 64, 128, 256}) {
                icons.add(image(size));
            }

            windowIcons = List.copyOf(icons);

        }

        return windowIcons;

    }

    /** The logo as an SVG document, for the README and other places outside the app. */
    public static String svg() {

        StringBuilder svg = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 256 256\" width=\"256\" height=\"256\">\n");

        List<Part> parts = new ArrayList<>(BADGE_PARTS);
        parts.addAll(WOLF);
        parts.addAll(CHEST_BODY);
        parts.addAll(CHEST_LID);
        parts.addAll(PAWS);
        parts.addAll(LOCK);

        for (Part part : parts) {
            svg.append("  <path d=\"").append(part.path()).append("\" fill=\"").append(part.fill() == null ? "none" : hex(part.fill())).append('"');
            if (part.stroke() != null && part.width() > 0) {
                svg.append(" stroke=\"").append(hex(part.stroke())).append("\" stroke-width=\"").append(part.width())
                        .append("\" stroke-linejoin=\"round\" stroke-linecap=\"round\"");
            }
            svg.append("/>\n");
        }

        return svg.append("</svg>\n").toString();

    }

    private static String hex(Color color) {

        return String.format(Locale.ROOT, "#%02X%02X%02X",
                (int) Math.round(color.getRed() * 255), (int) Math.round(color.getGreen() * 255), (int) Math.round(color.getBlue() * 255));

    }

}
