package com.cybershield.privacy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Reads and removes the details photos carry: where they were taken (GPS), the camera
 * or phone, the time, and other blocks such as XMP, IPTC and comments. Removing them
 * doesn't re-compress the picture: JPEG and PNG blocks are dropped, the image data is
 * copied as it is. Extra pictures phones append after a JPEG's end (thumbnails, depth
 * maps, each with its own details) are dropped too. Only the orientation is kept, in a
 * tiny block of its own, so phone photos still show the right way up.
 */
public final class PhotoDetails {

    /** What a photo gives away. */
    public record Found(String location, String camera, String taken, int orientation, List<String> other) {

        public boolean any() {
            return location != null || camera != null || taken != null || !other.isEmpty();
        }

        /** "Location 48.8584, 2.2945 · Google Pixel 8 · Taken 1 Nov 2026, 10:22 · XMP", or "No details". */
        public String describe() {

            List<String> parts = new ArrayList<>();

            if (location != null) {
                parts.add("Location " + location);
            }

            if (camera != null) {
                parts.add(camera);
            }

            if (taken != null) {
                parts.add("Taken " + taken);
            }

            parts.addAll(other);

            return parts.isEmpty() ? "No details" : String.join("  ·  ", parts);

        }

    }

    private static final byte[] EXIF_HEADER = {'E', 'x', 'i', 'f', 0, 0};

    private static final byte[] XMP_HEADER = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1);

    private static final DateTimeFormatter EXIF_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT);

    private static final DateTimeFormatter SHOWN_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private PhotoDetails() {
    }

    public static boolean supports(String extension) {

        String lower = extension.toLowerCase(Locale.ROOT);

        return lower.equals("jpg") || lower.equals("jpeg") || lower.equals("png");

    }

    // =====================================================
    // READING
    // =====================================================

    public static Found read(byte[] photo) throws IOException {

        if (isPng(photo)) {
            return readPng(photo);
        }

        if (isJpeg(photo)) {
            return readJpeg(photo);
        }

        throw new IOException("Only JPEG and PNG photos are supported.");

    }

    private static Found readJpeg(byte[] jpeg) {

        Details details = new Details();

        int pos = 2;

        while (pos + 4 <= jpeg.length) {

            if ((jpeg[pos] & 0xFF) != 0xFF) {
                break;
            }

            int marker = jpeg[pos + 1] & 0xFF;

            if (marker == 0xFF) {
                pos++;
                continue;
            }

            if (marker == 0xDA || marker == 0xD9) {
                break;
            }

            int length = u16(jpeg, pos + 2);

            int start = pos + 4;

            int end = Math.min(jpeg.length, pos + 2 + length);

            if (marker == 0xE1 && startsWith(jpeg, start, EXIF_HEADER)) {
                if (readTiff(jpeg, start + EXIF_HEADER.length, end, details)) {
                    details.other.add("EXIF");
                }
            } else if (marker == 0xE1 && startsWith(jpeg, start, XMP_HEADER)) {
                details.other.add("XMP");
            } else if (marker == 0xED) {
                details.other.add("IPTC");
            } else if (marker == 0xFE) {
                details.other.add("comment");
            } else if (marker >= 0xE3 && marker <= 0xEF && marker != 0xEE) {
                details.other.add("APP" + (marker - 0xE0));
            }

            pos += 2 + length;

        }

        int afterEnd = jpegEnd(jpeg);

        if (afterEnd > 0 && afterEnd < jpeg.length) {
            details.other.add("extra pictures after the end");
        }

        return details.found();

    }

    private static Found readPng(byte[] png) {

        Details details = new Details();

        int pos = PNG_SIGNATURE.length;

        while (pos + 12 <= png.length) {

            int length = ByteBuffer.wrap(png, pos, 4).getInt();

            String type = new String(png, pos + 4, 4, StandardCharsets.ISO_8859_1);

            int start = pos + 8;

            if (length < 0 || start + length + 4 > png.length) {
                break;
            }

            switch (type) {
                case "eXIf" -> {
                    if (readTiff(png, start, start + length, details)) {
                        details.other.add("EXIF");
                    }
                }
                case "tEXt", "zTXt", "iTXt" -> details.other.add("text: " + textKey(png, start, length));
                case "tIME" -> details.other.add("time saved");
                default -> {
                }
            }

            pos = start + length + 4;

            if (type.equals("IEND")) {
                break;
            }

        }

        return details.found();

    }

    private static String textKey(byte[] png, int start, int length) {

        int end = start;

        while (end < start + length && png[end] != 0) {
            end++;
        }

        return new String(png, start, end - start, StandardCharsets.ISO_8859_1);

    }

    /**
     * Reads the camera, time, orientation and location out of an EXIF (TIFF) block.
     * Returns false if the block holds nothing but the orientation.
     */
    private static boolean readTiff(byte[] data, int start, int end, Details details) {

        boolean more = false;

        try {

            ByteBuffer tiff = ByteBuffer.wrap(data, start, end - start).slice();

            tiff.order(tiff.get(0) == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);

            int ifd0 = tiff.getInt(4);

            String make = null;

            String model = null;

            int exifIfd = -1;

            int gpsIfd = -1;

            int count = Short.toUnsignedInt(tiff.getShort(ifd0));

            for (int i = 0; i < count; i++) {

                int entry = ifd0 + 2 + i * 12;

                int tag = Short.toUnsignedInt(tiff.getShort(entry));

                more |= tag != 0x0112;

                switch (tag) {
                    case 0x010F -> make = ascii(tiff, entry);
                    case 0x0110 -> model = ascii(tiff, entry);
                    case 0x0132 -> details.taken = details.taken == null ? friendlyTime(ascii(tiff, entry)) : details.taken;
                    case 0x0112 -> details.orientation = Short.toUnsignedInt(tiff.getShort(entry + 8));
                    case 0x8769 -> exifIfd = tiff.getInt(entry + 8);
                    case 0x8825 -> gpsIfd = tiff.getInt(entry + 8);
                    default -> {
                    }
                }

            }

            if (make != null || model != null) {
                String both = model == null ? make : make == null || model.startsWith(make) ? model : make + " " + model;
                details.camera = both.strip();
            }

            if (exifIfd > 0 && exifIfd < tiff.limit()) {

                int exifCount = Short.toUnsignedInt(tiff.getShort(exifIfd));

                for (int i = 0; i < exifCount; i++) {

                    int entry = exifIfd + 2 + i * 12;

                    // When the photo was taken wins over when the file was last changed.
                    if (Short.toUnsignedInt(tiff.getShort(entry)) == 0x9003 && ascii(tiff, entry) != null) {
                        details.taken = friendlyTime(ascii(tiff, entry));
                    }

                }

            }

            if (gpsIfd > 0 && gpsIfd < tiff.limit()) {
                details.location = gps(tiff, gpsIfd);
            }

            // A second IFD holds a thumbnail: a small copy of the picture, as it was.
            more |= tiff.getInt(ifd0 + 2 + count * 12) != 0;

        } catch (RuntimeException damaged) {

            // A damaged block: what was read so far stands, and it counts as more.
            more = true;

        }

        return more;

    }

    private static String gps(ByteBuffer tiff, int ifd) {

        String latRef = "N";
        String lonRef = "E";
        double[] lat = null;
        double[] lon = null;

        int count = Short.toUnsignedInt(tiff.getShort(ifd));

        for (int i = 0; i < count; i++) {

            int entry = ifd + 2 + i * 12;

            int tag = Short.toUnsignedInt(tiff.getShort(entry));

            switch (tag) {
                case 1 -> latRef = ascii(tiff, entry);
                case 2 -> lat = rationals(tiff, entry);
                case 3 -> lonRef = ascii(tiff, entry);
                case 4 -> lon = rationals(tiff, entry);
                default -> {
                }
            }

        }

        if (lat == null || lon == null || lat.length < 3 || lon.length < 3) {
            return null;
        }

        double latitude = (lat[0] + lat[1] / 60 + lat[2] / 3600) * ("S".equalsIgnoreCase(latRef) ? -1 : 1);

        double longitude = (lon[0] + lon[1] / 60 + lon[2] / 3600) * ("W".equalsIgnoreCase(lonRef) ? -1 : 1);

        return String.format(Locale.ROOT, "%.4f, %.4f", latitude, longitude);

    }

    /** "2026:11:01 10:22:05" (as EXIF writes it) as "1 Nov 2026, 10:22". */
    private static String friendlyTime(String exif) {

        if (exif == null) {
            return null;
        }

        try {
            return LocalDateTime.parse(exif, EXIF_TIME).format(SHOWN_TIME);
        } catch (DateTimeParseException e) {
            return exif;
        }

    }

    private static String ascii(ByteBuffer tiff, int entry) {

        int count = tiff.getInt(entry + 4);

        int offset = count <= 4 ? entry + 8 : tiff.getInt(entry + 8);

        StringBuilder text = new StringBuilder();

        for (int i = 0; i < count && offset + i < tiff.limit(); i++) {
            byte b = tiff.get(offset + i);
            if (b == 0) {
                break;
            }
            text.append((char) (b & 0xFF));
        }

        String value = text.toString().strip();

        return value.isEmpty() ? null : value;

    }

    private static double[] rationals(ByteBuffer tiff, int entry) {

        int count = tiff.getInt(entry + 4);

        int offset = tiff.getInt(entry + 8);

        double[] values = new double[count];

        for (int i = 0; i < count; i++) {
            long top = Integer.toUnsignedLong(tiff.getInt(offset + i * 8));
            long bottom = Integer.toUnsignedLong(tiff.getInt(offset + i * 8 + 4));
            values[i] = bottom == 0 ? 0 : (double) top / bottom;
        }

        return values;

    }

    private static final class Details {

        String location;
        String camera;
        String taken;
        int orientation = 1;
        final List<String> other = new ArrayList<>();

        Found found() {
            return new Found(location, camera, taken, orientation, List.copyOf(other));
        }

    }

    // =====================================================
    // REMOVING
    // =====================================================

    /** The photo without its details: the same picture, nothing re-compressed. */
    public static byte[] strip(byte[] photo) throws IOException {

        if (isPng(photo)) {
            return stripPng(photo);
        }

        if (isJpeg(photo)) {
            return stripJpeg(photo);
        }

        throw new IOException("Only JPEG and PNG photos are supported.");

    }

    private static byte[] stripJpeg(byte[] jpeg) throws IOException {

        int orientation = readJpeg(jpeg).orientation();

        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length);

        out.write(0xFF);
        out.write(0xD8);

        boolean orientationWritten = orientation <= 1;

        int pos = 2;

        while (pos + 4 <= jpeg.length) {

            if ((jpeg[pos] & 0xFF) != 0xFF) {
                throw new IOException("This JPEG is damaged.");
            }

            int marker = jpeg[pos + 1] & 0xFF;

            if (marker == 0xFF) {
                pos++;
                continue;
            }

            if (marker == 0xDA) {

                if (!orientationWritten) {
                    out.write(orientationBlock(orientation));
                }

                // The picture itself, up to its own end: anything appended after it goes.
                int end = jpegEnd(jpeg);

                out.write(jpeg, pos, (end > 0 ? end : jpeg.length) - pos);

                return out.toByteArray();

            }

            int length = u16(jpeg, pos + 2);

            int start = pos + 4;

            boolean keep = switch (marker) {
                case 0xE0 -> true;                                          // JFIF
                case 0xE2 -> startsWith(jpeg, start, "ICC_PROFILE".getBytes(StandardCharsets.ISO_8859_1)); // colours, not MPF
                case 0xEE -> true;                                          // Adobe colour transform
                case 0xE1, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xEB, 0xEC, 0xED, 0xEF, 0xFE -> false;
                default -> true;                                            // tables, frame header, and so on
            };

            if (keep) {

                // The orientation block goes right after the JFIF header, before anything else.
                if (!orientationWritten && marker != 0xE0) {
                    out.write(orientationBlock(orientation));
                    orientationWritten = true;
                }

                out.write(jpeg, pos, 2 + length);

            }

            pos += 2 + length;

        }

        throw new IOException("This JPEG is cut short.");

    }

    /** Where a JPEG's image data ends (after its EOI marker), or -1 if it can't be told. */
    private static int jpegEnd(byte[] jpeg) {

        int pos = 2;

        while (pos + 4 <= jpeg.length) {

            if ((jpeg[pos] & 0xFF) != 0xFF) {
                return -1;
            }

            int marker = jpeg[pos + 1] & 0xFF;

            if (marker == 0xFF) {
                pos++;
                continue;
            }

            if (marker == 0xD9) {
                return pos + 2;
            }

            int length = u16(jpeg, pos + 2);

            if (marker != 0xDA) {
                pos += 2 + length;
                continue;
            }

            // Entropy-coded data: 0xFF is followed by 0x00 (a stuffed byte) or a restart marker,
            // so the first other marker after it starts the next segment or ends the picture.
            int scan = pos + 2 + length;

            while (scan + 1 < jpeg.length) {

                if ((jpeg[scan] & 0xFF) == 0xFF) {

                    int next = jpeg[scan + 1] & 0xFF;

                    if (next == 0x00 || (next >= 0xD0 && next <= 0xD7) || next == 0xFF) {
                        scan += next == 0xFF ? 1 : 2;
                        continue;
                    }

                    if (next == 0xD9) {
                        return scan + 2;
                    }

                    // Another segment (progressive JPEGs have several scans).
                    pos = scan;
                    break;

                }

                scan++;

            }

            if (scan + 1 >= jpeg.length) {
                return -1;
            }

        }

        return -1;

    }

    /** A minimal EXIF block holding only the orientation. */
    private static byte[] orientationBlock(int orientation) {

        ByteBuffer block = ByteBuffer.allocate(2 + 2 + 6 + 26).order(ByteOrder.BIG_ENDIAN);

        block.put((byte) 0xFF).put((byte) 0xE1).putShort((short) (2 + 6 + 26));
        block.put(EXIF_HEADER);
        block.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);
        block.putShort((short) 1);
        block.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        block.putInt(0);

        return block.array();

    }

    private static byte[] stripPng(byte[] png) throws IOException {

        ByteArrayOutputStream out = new ByteArrayOutputStream(png.length);

        out.write(PNG_SIGNATURE);

        int orientation = readPng(png).orientation();

        int pos = PNG_SIGNATURE.length;

        while (pos + 12 <= png.length) {

            int length = ByteBuffer.wrap(png, pos, 4).getInt();

            String type = new String(png, pos + 4, 4, StandardCharsets.ISO_8859_1);

            if (length < 0 || pos + 12 + length > png.length) {
                throw new IOException("This PNG is damaged.");
            }

            boolean drop = type.equals("tEXt") || type.equals("zTXt") || type.equals("iTXt")
                    || type.equals("eXIf") || type.equals("tIME");

            if (type.equals("IDAT") && orientation > 1) {
                // An orientation to keep goes in a small eXIf chunk before the image data.
                byte[] tiff = java.util.Arrays.copyOfRange(orientationBlock(orientation), 10, 10 + 26);
                out.write(chunk("eXIf", tiff));
                orientation = 1;
            }

            if (!drop) {
                out.write(png, pos, 12 + length);
            }

            pos += 12 + length;

            if (type.equals("IEND")) {
                return out.toByteArray();
            }

        }

        throw new IOException("This PNG is cut short.");

    }

    private static byte[] chunk(String type, byte[] data) {

        byte[] typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);

        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);

        return ByteBuffer.allocate(12 + data.length).putInt(data.length).put(typeBytes).put(data).putInt((int) crc.getValue()).array();

    }

    // =====================================================
    // FILES
    // =====================================================

    /** Saves a copy without details in folder, as "name (no details).jpg", and returns where. */
    public static Path cleanCopy(Path photo, Path folder) throws IOException {

        byte[] clean = strip(Files.readAllBytes(photo));

        String name = photo.getFileName().toString();

        int dot = name.lastIndexOf('.');

        String base = dot > 0 ? name.substring(0, dot) : name;

        String extension = dot > 0 ? name.substring(dot) : "";

        Path target = folder.resolve(base + " (no details)" + extension);

        for (int n = 2; Files.exists(target); n++) {
            target = folder.resolve(base + " (no details " + n + ")" + extension);
        }

        Files.write(target, clean, StandardOpenOption.CREATE_NEW);

        return target;

    }

    // =====================================================
    // BYTES
    // =====================================================

    private static boolean isJpeg(byte[] bytes) {
        return bytes.length > 4 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8;
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length > PNG_SIGNATURE.length && java.util.Arrays.equals(bytes, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length);
    }

    private static int u16(byte[] bytes, int at) {
        return ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
    }

    private static boolean startsWith(byte[] bytes, int at, byte[] prefix) {
        return at + prefix.length <= bytes.length && java.util.Arrays.equals(bytes, at, at + prefix.length, prefix, 0, prefix.length);
    }

}
