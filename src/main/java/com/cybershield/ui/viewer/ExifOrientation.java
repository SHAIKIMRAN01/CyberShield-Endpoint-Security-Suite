package com.cybershield.ui.viewer;

/**
 * Reads the EXIF orientation of a JPEG photo. Phones store most photos sideways and
 * record how to turn them in this tag; JavaFX ignores it, so the viewer applies it.
 *
 * <pre>
 *   1 as stored        2 mirrored           3 turned 180°       4 mirrored, turned 180°
 *   5 mirrored, 270°   6 turned 90° right   7 mirrored, 90°     8 turned 90° left
 * </pre>
 */
public final class ExifOrientation {

    /** How to show a photo: turn clockwise by degrees, after mirroring left to right if mirrored. */
    public record Turn(int degrees, boolean mirrored) {

        public static final Turn NONE = new Turn(0, false);

    }

    private ExifOrientation() {
    }

    public static Turn of(int orientation) {

        return switch (orientation) {
            case 2 -> new Turn(0, true);
            case 3 -> new Turn(180, false);
            case 4 -> new Turn(180, true);
            case 5 -> new Turn(270, true);
            case 6 -> new Turn(90, false);
            case 7 -> new Turn(90, true);
            case 8 -> new Turn(270, false);
            default -> Turn.NONE;
        };

    }

    /** The orientation tag (1 to 8) of a JPEG, or 1 if there is none or it can't be read. */
    public static int read(byte[] jpeg) {

        try {

            if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
                return 1;
            }

            int position = 2;

            while (position + 4 <= jpeg.length) {

                if ((jpeg[position] & 0xFF) != 0xFF) {
                    return 1;
                }

                int marker = jpeg[position + 1] & 0xFF;

                // Start of the picture data: no orientation came before it.
                if (marker == 0xDA || marker == 0xD9) {
                    return 1;
                }

                int length = ((jpeg[position + 2] & 0xFF) << 8) | (jpeg[position + 3] & 0xFF);

                if (marker == 0xE1 && length >= 8 && startsWithExif(jpeg, position + 4)) {
                    return orientationFromTiff(jpeg, position + 10, position + 2 + length);
                }

                position += 2 + length;

            }

        } catch (RuntimeException damaged) {
            // A damaged header just means "show it as stored".
        }

        return 1;

    }

    private static boolean startsWithExif(byte[] data, int at) {

        return at + 6 <= data.length && data[at] == 'E' && data[at + 1] == 'x' && data[at + 2] == 'i'
                && data[at + 3] == 'f' && data[at + 4] == 0 && data[at + 5] == 0;

    }

    private static int orientationFromTiff(byte[] data, int tiff, int end) {

        boolean little = data[tiff] == 'I' && data[tiff + 1] == 'I';

        int firstDirectory = (int) number(data, tiff + 4, 4, little);

        int directory = tiff + firstDirectory;

        int entries = (int) number(data, directory, 2, little);

        for (int i = 0; i < entries; i++) {

            int entry = directory + 2 + i * 12;

            if (entry + 12 > end) {
                break;
            }

            if (number(data, entry, 2, little) == 0x0112) {

                int value = (int) number(data, entry + 8, 2, little);

                return value >= 1 && value <= 8 ? value : 1;

            }

        }

        return 1;

    }

    private static long number(byte[] data, int at, int bytes, boolean little) {

        long value = 0;

        for (int i = 0; i < bytes; i++) {

            int b = data[at + (little ? bytes - 1 - i : i)] & 0xFF;

            value = (value << 8) | b;

        }

        return value;

    }

}
