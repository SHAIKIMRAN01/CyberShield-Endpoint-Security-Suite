package com.cybershield.privacy;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

/** Made-up photos for the tests: plain ones, and one with everything a phone adds. */
public final class TestPhotos {

    private static final byte[] EXIF_HEADER = {'E', 'x', 'i', 'f', 0, 0};

    private TestPhotos() {
    }

    public static BufferedImage picture(int width, int height) {

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, ((x * 4) & 0xFF) << 16 | ((y * 5) & 0xFF) << 8 | ((x * y) & 0xFF));
            }
        }

        return image;

    }

    public static byte[] jpeg(BufferedImage image) throws IOException {

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ImageIO.write(image, "jpg", out);

        return out.toByteArray();

    }

    public static byte[] png(BufferedImage image) throws IOException {

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ImageIO.write(image, "png", out);

        return out.toByteArray();

    }

    /** A JPEG with what phones add: EXIF with GPS, XMP, IPTC, a comment, an MPF index, and a second picture after the end. */
    public static byte[] phonePhoto(byte[] plain) throws IOException {

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        out.write(plain, 0, 2);

        out.write(segment(0xE1, concat(EXIF_HEADER, cameraTiff())));
        out.write(segment(0xE1, concat("http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1),
                "<x:xmpmeta>GPS 48.85, 2.29</x:xmpmeta>".getBytes(StandardCharsets.UTF_8))));
        out.write(segment(0xED, "Photoshop 3.0\0 8BIM caption".getBytes(StandardCharsets.ISO_8859_1)));
        out.write(segment(0xFE, "Holiday".getBytes(StandardCharsets.ISO_8859_1)));
        out.write(segment(0xE2, "MPF\0 index of the pictures".getBytes(StandardCharsets.ISO_8859_1)));

        out.write(plain, 2, plain.length - 2);

        out.write(jpeg(picture(8, 8)));

        return out.toByteArray();

    }

    /** EXIF as a phone writes it: camera, times, orientation 6, and where (48.8584 N, 2.2945 E). */
    static byte[] cameraTiff() {

        ByteBuffer out = ByteBuffer.allocate(512).order(ByteOrder.BIG_ENDIAN);

        int ifd0 = 8;
        int exif = ifd0 + 2 + 6 * 12 + 4;
        int gps = exif + 2 + 12 + 4;
        int data = gps + 2 + 4 * 12 + 4;

        byte[] make = ascii("Google");
        byte[] model = ascii("Pixel 8");
        byte[] changed = ascii("2026:11:01 10:30:00");
        byte[] taken = ascii("2026:11:01 10:22:05");

        int makeAt = data;
        int modelAt = makeAt + make.length;
        int changedAt = modelAt + model.length;
        int takenAt = changedAt + changed.length;
        int latAt = takenAt + taken.length;
        int lonAt = latAt + 24;

        out.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(ifd0);

        out.position(ifd0);
        out.putShort((short) 6);
        entry(out, 0x010F, 2, make.length, makeAt);
        entry(out, 0x0110, 2, model.length, modelAt);
        out.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) 6).putShort((short) 0);
        entry(out, 0x0132, 2, changed.length, changedAt);
        entry(out, 0x8769, 4, 1, exif);
        entry(out, 0x8825, 4, 1, gps);
        out.putInt(0);

        out.position(exif);
        out.putShort((short) 1);
        entry(out, 0x9003, 2, taken.length, takenAt);
        out.putInt(0);

        out.position(gps);
        out.putShort((short) 4);
        out.putShort((short) 1).putShort((short) 2).putInt(2).put((byte) 'N').put((byte) 0).putShort((short) 0);
        entry(out, 2, 5, 3, latAt);
        out.putShort((short) 3).putShort((short) 2).putInt(2).put((byte) 'E').put((byte) 0).putShort((short) 0);
        entry(out, 4, 5, 3, lonAt);
        out.putInt(0);

        out.position(makeAt);
        out.put(make).put(model).put(changed).put(taken);
        out.putInt(48).putInt(1).putInt(51).putInt(1).putInt(3024).putInt(100);
        out.putInt(2).putInt(1).putInt(17).putInt(1).putInt(4020).putInt(100);

        return Arrays.copyOf(out.array(), out.position());

    }

    /** A little EXIF block with the camera make and an orientation. */
    public static byte[] smallTiff(String make, int orientation) {

        byte[] name = ascii(make);

        ByteBuffer out = ByteBuffer.allocate(8 + 2 + 2 * 12 + 4 + name.length).order(ByteOrder.LITTLE_ENDIAN);

        out.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        out.putShort((short) 2);
        entry(out, 0x010F, 2, name.length, 8 + 2 + 2 * 12 + 4);
        out.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        out.putInt(0);
        out.put(name);

        return out.array();

    }

    static void entry(ByteBuffer out, int tag, int type, int count, int valueOrOffset) {
        out.putShort((short) tag).putShort((short) type).putInt(count).putInt(valueOrOffset);
    }

    static byte[] ascii(String text) {
        return (text + "\0").getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] segment(int marker, byte[] payload) {

        ByteBuffer out = ByteBuffer.allocate(4 + payload.length);

        out.put((byte) 0xFF).put((byte) marker).putShort((short) (payload.length + 2)).put(payload);

        return out.array();

    }

    public static byte[] chunk(String type, byte[] data) {

        byte[] name = type.getBytes(StandardCharsets.ISO_8859_1);

        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);

        return ByteBuffer.allocate(12 + data.length).putInt(data.length).put(name).put(data).putInt((int) crc.getValue()).array();

    }

    /** The PNG with chunks put right after its header (signature and IHDR). */
    public static byte[] afterHeader(byte[] png, byte[]... chunks) throws IOException {

        int headerEnd = 8 + 4 + 4 + 13 + 4;

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        out.write(png, 0, headerEnd);

        for (byte[] chunk : chunks) {
            out.write(chunk);
        }

        out.write(png, headerEnd, png.length - headerEnd);

        return out.toByteArray();

    }

    static byte[] concat(byte[] a, byte[] b) {

        byte[] both = Arrays.copyOf(a, a.length + b.length);

        System.arraycopy(b, 0, both, a.length, b.length);

        return both;

    }

}
