package com.cybershield.stego;

import com.cybershield.encryption.SharedCipher;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * Hides a message or a small file in a picture: in the lowest bit of each red, green and
 * blue value, which changes a colour by at most 1 in 255, too little to see. What is
 * hidden is sealed with a password first (AES-256-GCM, key from PBKDF2), so without the
 * password it can't be read, and a changed picture is noticed when revealing.
 *
 * <p>Layout of the hidden bits, from the top-left pixel on: "CSG1", the sealed length
 * (4 bytes), then the sealed bytes. Inside the seal: a type byte, then the text, or the
 * file's name length (2 bytes), name and contents. The result must be kept as PNG: JPEG
 * compression would wipe the hidden bits.
 */
public final class Steganography {

    static final byte[] MAGIC = {'C', 'S', 'G', '1'};

    static final int HEADER_BYTES = MAGIC.length + 4;

    private static final byte TEXT = 1;

    private static final byte FILE = 2;

    private static final int MAX_NAME_BYTES = 255;

    /** The picture has no CyberShield message in it. */
    public static final class NoMessageException extends IOException {

        NoMessageException() {
            super("No hidden message was found in this picture.");
        }

    }

    /** What doesn't fit, with the room there is. */
    public static final class TooBigException extends IOException {

        private final long needed;

        private final long room;

        TooBigException(long needed, long room) {
            super("That doesn't fit: it needs " + needed + " bytes and the picture has room for " + room + ".");
            this.needed = needed;
            this.room = room;
        }

        public long needed() {
            return needed;
        }

        public long room() {
            return room;
        }

    }

    /** What was revealed: a text, or a file's name and contents. */
    public record Hidden(String text, String fileName, byte[] fileData) {

        public boolean isText() {
            return text != null;
        }

    }

    private Steganography() {
    }

    // =====================================================
    // ROOM
    // =====================================================

    /** How many bytes of sealed data the picture can hold. */
    public static long room(BufferedImage picture) {

        return Math.max(0, (long) picture.getWidth() * picture.getHeight() * 3 / 8 - HEADER_BYTES);

    }

    /** What hiding this text takes, in the same bytes as room(). */
    public static long neededForText(String text) {

        return SharedCipher.SEAL_OVERHEAD + 1 + text.getBytes(StandardCharsets.UTF_8).length;

    }

    /** What hiding this file takes, in the same bytes as room(). */
    public static long neededForFile(String name, long size) {

        return SharedCipher.SEAL_OVERHEAD + 1 + 2 + nameBytes(name).length + size;

    }

    // =====================================================
    // HIDING
    // =====================================================

    /** A copy of cover with the text hidden in it. */
    public static BufferedImage hideText(BufferedImage cover, String text, String password) throws IOException {

        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);

        byte[] plain = ByteBuffer.allocate(1 + utf8.length).put(TEXT).put(utf8).array();

        try {
            return embed(cover, SharedCipher.sealBytes(plain, password));
        } finally {
            Arrays.fill(plain, (byte) 0);
        }

    }

    /** A copy of cover with the file hidden in it. */
    public static BufferedImage hideFile(BufferedImage cover, String name, byte[] data, String password) throws IOException {

        byte[] nameBytes = nameBytes(name);

        byte[] plain = ByteBuffer.allocate(1 + 2 + nameBytes.length + data.length)
                .put(FILE).putShort((short) nameBytes.length).put(nameBytes).put(data).array();

        try {
            return embed(cover, SharedCipher.sealBytes(plain, password));
        } finally {
            Arrays.fill(plain, (byte) 0);
        }

    }

    private static BufferedImage embed(BufferedImage cover, byte[] sealed) throws IOException {

        long room = room(cover);

        if (sealed.length > room) {
            throw new TooBigException(sealed.length, room);
        }

        byte[] bits = ByteBuffer.allocate(HEADER_BYTES + sealed.length).put(MAGIC).putInt(sealed.length).put(sealed).array();

        BufferedImage result = copyArgb(cover);

        int width = result.getWidth();

        int[] pixels = result.getRGB(0, 0, width, result.getHeight(), null, 0, width);

        long bit = 0;

        for (byte value : bits) {

            for (int i = 7; i >= 0; i--) {

                int pixel = (int) (bit / 3);

                int shift = 16 - 8 * (int) (bit % 3);

                int one = (value >> i) & 1;

                pixels[pixel] = (pixels[pixel] & ~(1 << shift)) | (one << shift);

                bit++;

            }

        }

        result.setRGB(0, 0, width, result.getHeight(), pixels, 0, width);

        return result;

    }

    // =====================================================
    // REVEALING
    // =====================================================

    /** True if the picture starts with a CyberShield message (the password isn't needed for this). */
    public static boolean holdsMessage(BufferedImage picture) {

        try {
            return sealedBytes(picture) != null;
        } catch (IOException e) {
            return false;
        }

    }

    /** Reveals what is hidden. Without a message: NoMessageException; a wrong password: WrongPasswordException. */
    public static Hidden reveal(BufferedImage picture, String password) throws IOException {

        byte[] sealed = sealedBytes(picture);

        if (sealed == null) {
            throw new NoMessageException();
        }

        byte[] plain = SharedCipher.openBytes(sealed, password);

        try {

            ByteBuffer in = ByteBuffer.wrap(plain);

            byte type = in.get();

            if (type == TEXT) {

                byte[] text = new byte[in.remaining()];

                in.get(text);

                return new Hidden(new String(text, StandardCharsets.UTF_8), null, null);

            }

            if (type == FILE && in.remaining() >= 2) {

                int nameLength = Short.toUnsignedInt(in.getShort());

                if (nameLength > in.remaining()) {
                    throw new IOException("The hidden file is damaged.");
                }

                byte[] name = new byte[nameLength];

                in.get(name);

                byte[] data = new byte[in.remaining()];

                in.get(data);

                return new Hidden(null, safeName(new String(name, StandardCharsets.UTF_8)), data);

            }

            throw new IOException("The hidden data is of a kind this version doesn't know.");

        } finally {

            Arrays.fill(plain, (byte) 0);

        }

    }

    /** The sealed bytes after the header, or null if the picture holds no message. */
    private static byte[] sealedBytes(BufferedImage picture) throws IOException {

        long room = room(picture);

        if (room <= SharedCipher.SEAL_OVERHEAD) {
            return null;
        }

        int width = picture.getWidth();

        int height = picture.getHeight();

        Reader reader = new Reader(picture, width, height);

        byte[] header = reader.read(HEADER_BYTES);

        if (!Arrays.equals(header, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            return null;
        }

        int length = ByteBuffer.wrap(header, MAGIC.length, 4).getInt();

        if (length < SharedCipher.SEAL_OVERHEAD || length > room) {
            return null;
        }

        return reader.read(length);

    }

    /** Reads hidden bytes in order, a row of pixels at a time. */
    private static final class Reader {

        private final BufferedImage picture;

        private final int width;

        private final int height;

        private int[] row = new int[0];

        private int rowIndex = -1;

        private long bit;

        Reader(BufferedImage picture, int width, int height) {
            this.picture = picture;
            this.width = width;
            this.height = height;
        }

        byte[] read(int count) {

            byte[] out = new byte[count];

            for (int b = 0; b < count; b++) {

                int value = 0;

                for (int i = 0; i < 8; i++) {

                    long pixel = bit / 3;

                    int y = (int) (pixel / width);

                    if (y != rowIndex) {
                        row = picture.getRGB(0, y, width, 1, row.length == width ? row : null, 0, width);
                        rowIndex = y;
                    }

                    int shift = 16 - 8 * (int) (bit % 3);

                    value = (value << 1) | ((row[(int) (pixel % width)] >> shift) & 1);

                    bit++;

                }

                out[b] = (byte) value;

            }

            return out;

        }

    }

    // =====================================================
    // PICTURES
    // =====================================================

    /** Reads a PNG, BMP, GIF or JPEG picture. */
    public static BufferedImage read(Path file) throws IOException {

        BufferedImage picture = ImageIO.read(file.toFile());

        if (picture == null) {
            throw new IOException("This isn't a picture CyberShield can read. Use a PNG, BMP or JPEG.");
        }

        return picture;

    }

    /** Saves as PNG, written beside the target first so a failure leaves nothing half-written. */
    public static void writePng(BufferedImage picture, Path target) throws IOException {

        Path part = target.resolveSibling(target.getFileName() + ".part");

        try {

            if (!ImageIO.write(picture, "png", part.toFile())) {
                throw new IOException("PNG pictures can't be written here.");
            }

            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        } finally {

            Files.deleteIfExists(part);

        }

    }

    /** The picture as PNG bytes, for showing it without saving it. */
    public static byte[] pngBytes(BufferedImage picture) throws IOException {

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ImageIO.write(picture, "png", out);

        return out.toByteArray();

    }

    /**
     * For the preview: the after picture dimmed, with every pixel that changed marked in
     * bright orange, so the otherwise invisible change can be seen.
     */
    public static BufferedImage changes(BufferedImage before, BufferedImage after) {

        int width = after.getWidth();

        int height = after.getHeight();

        BufferedImage shown = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        int[] a = copyArgb(before).getRGB(0, 0, width, height, null, 0, width);

        int[] b = after.getRGB(0, 0, width, height, null, 0, width);

        for (int i = 0; i < b.length; i++) {

            if (a[i] != b[i]) {
                b[i] = 0xFFFF7A1A;
            } else {
                int gray = (((b[i] >> 16) & 0xFF) + ((b[i] >> 8) & 0xFF) + (b[i] & 0xFF)) / 3 / 3 + 40;
                b[i] = 0xFF000000 | gray << 16 | gray << 8 | gray;
            }

        }

        shown.setRGB(0, 0, width, height, b, 0, width);

        return shown;

    }

    /** How many pixels differ. */
    public static long changedPixels(BufferedImage before, BufferedImage after) {

        int width = after.getWidth();

        int[] a = copyArgb(before).getRGB(0, 0, width, after.getHeight(), null, 0, width);

        int[] b = after.getRGB(0, 0, width, after.getHeight(), null, 0, width);

        long changed = 0;

        for (int i = 0; i < b.length; i++) {
            if (a[i] != b[i]) {
                changed++;
            }
        }

        return changed;

    }

    private static BufferedImage copyArgb(BufferedImage picture) {

        BufferedImage copy = new BufferedImage(picture.getWidth(), picture.getHeight(), BufferedImage.TYPE_INT_ARGB);

        java.awt.Graphics2D g = copy.createGraphics();

        g.setComposite(java.awt.AlphaComposite.Src);

        g.drawImage(picture, 0, 0, null);

        g.dispose();

        return copy;

    }

    private static byte[] nameBytes(String name) {

        byte[] bytes = (name == null || name.isBlank() ? "hidden-file" : name).getBytes(StandardCharsets.UTF_8);

        return bytes.length <= MAX_NAME_BYTES ? bytes : Arrays.copyOf(bytes, MAX_NAME_BYTES);

    }

    /** A hidden file's name can't point anywhere but a plain file name. */
    static String safeName(String name) {

        String plain = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();

        while (plain.startsWith(".")) {
            plain = plain.substring(1);
        }

        return plain.isEmpty() ? "hidden-file" : plain;

    }

}
