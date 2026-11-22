package com.cybershield.privacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Photo details: what a JPEG or PNG gives away, and a clean copy without it. */
class PhotoDetailsTest {

    @TempDir
    Path dir;

    @Test
    void readsAndRemovesWhatAPhonePhotoGivesAway() throws Exception {

        byte[] plain = TestPhotos.jpeg(TestPhotos.picture(64, 48));

        byte[] photo = TestPhotos.phonePhoto(plain);

        PhotoDetails.Found found = PhotoDetails.read(photo);

        assertEquals("48.8584, 2.2945", found.location());
        assertEquals("Google Pixel 8", found.camera());
        assertEquals("1 Nov 2026, 10:22", found.taken(), "when it was taken, not when the file changed");
        assertEquals(6, found.orientation());
        assertTrue(found.other().containsAll(List.of("EXIF", "XMP", "IPTC", "comment", "extra pictures after the end")),
                String.valueOf(found.other()));
        assertTrue(found.any());
        assertTrue(found.describe().startsWith("Location 48.8584, 2.2945  ·  Google Pixel 8  ·  Taken 1 Nov 2026, 10:22"),
                found.describe());

        byte[] clean = PhotoDetails.strip(photo);

        PhotoDetails.Found after = PhotoDetails.read(clean);

        assertFalse(after.any(), after.describe());
        assertEquals("No details", after.describe());
        assertEquals(6, after.orientation(), "kept, so the photo still shows the right way up");

        for (String gone : List.of("Google", "Pixel", "2026:11", "xmpmeta", "Photoshop", "Holiday", "MPF")) {
            assertFalse(contains(clean, gone), gone + " is still in the clean copy");
        }

        assertEquals(0xFF, clean[clean.length - 2] & 0xFF);
        assertEquals(0xD9, clean[clean.length - 1] & 0xFF, "ends where the picture ends");

        assertArrayEquals(fromScan(plain), fromScan(clean), "the picture itself is copied as it is");
        assertSamePixels(decode(plain), decode(clean));

    }

    @Test
    void aPhotoWithNothingToHideIsLeftAsItIs() throws Exception {

        byte[] plainJpeg = TestPhotos.jpeg(TestPhotos.picture(20, 20));

        PhotoDetails.Found found = PhotoDetails.read(plainJpeg);

        assertFalse(found.any(), found.describe());
        assertEquals(1, found.orientation());
        assertArrayEquals(plainJpeg, PhotoDetails.strip(plainJpeg));

        byte[] plainPng = TestPhotos.png(TestPhotos.picture(20, 20));

        assertFalse(PhotoDetails.read(plainPng).any());
        assertArrayEquals(plainPng, PhotoDetails.strip(plainPng));

    }

    @Test
    void readsAndRemovesPngTextTimeAndExif() throws Exception {

        byte[] plain = TestPhotos.png(TestPhotos.picture(40, 30));

        byte[] photo = TestPhotos.afterHeader(plain,
                TestPhotos.chunk("tEXt", "Comment\0Holiday at the lake".getBytes(StandardCharsets.ISO_8859_1)),
                TestPhotos.chunk("iTXt", "Location\0\0\0\0\0Lake house".getBytes(StandardCharsets.UTF_8)),
                TestPhotos.chunk("tIME", new byte[] {0x07, (byte) 0xEA, 11, 1, 10, 22, 5}),
                TestPhotos.chunk("eXIf", TestPhotos.smallTiff("Canon", 8)));

        PhotoDetails.Found found = PhotoDetails.read(photo);

        assertEquals("Canon", found.camera());
        assertEquals(8, found.orientation());
        assertTrue(found.other().containsAll(List.of("text: Comment", "text: Location", "time saved", "EXIF")),
                String.valueOf(found.other()));

        byte[] clean = PhotoDetails.strip(photo);

        PhotoDetails.Found after = PhotoDetails.read(clean);

        assertFalse(after.any(), after.describe());
        assertEquals(8, after.orientation(), "the orientation stays");

        List<String> chunks = chunkTypes(clean);

        assertFalse(chunks.contains("tEXt") || chunks.contains("iTXt") || chunks.contains("tIME"), String.valueOf(chunks));
        assertTrue(chunks.indexOf("eXIf") >= 0 && chunks.indexOf("eXIf") < chunks.indexOf("IDAT"), "orientation before the image: " + chunks);

        for (String gone : List.of("Holiday", "Lake house", "Canon")) {
            assertFalse(contains(clean, gone), gone + " is still in the clean copy");
        }

        assertSamePixels(decode(plain), decode(clean));

    }

    @Test
    void aCleanCopyGoesBesideTheOriginalWhichStaysAsItIs() throws Exception {

        byte[] photo = TestPhotos.phonePhoto(TestPhotos.jpeg(TestPhotos.picture(32, 32)));

        Path original = Files.write(dir.resolve("beach.jpg"), photo);

        Path first = PhotoDetails.cleanCopy(original, dir);

        Path second = PhotoDetails.cleanCopy(original, dir);

        assertEquals("beach (no details).jpg", first.getFileName().toString());
        assertEquals("beach (no details 2).jpg", second.getFileName().toString(), "an earlier copy isn't overwritten");
        assertArrayEquals(photo, Files.readAllBytes(original), "the original is untouched");
        assertFalse(PhotoDetails.read(Files.readAllBytes(first)).any());
        assertNotNull(ImageIO.read(first.toFile()));

    }

    @Test
    void onlyJpegAndPngAreTaken() {

        assertTrue(PhotoDetails.supports("JPG"));
        assertTrue(PhotoDetails.supports("jpeg"));
        assertTrue(PhotoDetails.supports("png"));
        assertFalse(PhotoDetails.supports("gif"));

        byte[] gif = "GIF89a not a photo".getBytes(StandardCharsets.ISO_8859_1);

        assertThrows(IOException.class, () -> PhotoDetails.read(gif));
        assertThrows(IOException.class, () -> PhotoDetails.strip(gif));

    }

    // ------------------------------------------------------------- checks

    /** The chunk types in order, checking every chunk's CRC on the way. */
    private static List<String> chunkTypes(byte[] png) {

        List<String> types = new ArrayList<>();

        int pos = 8;

        while (pos + 12 <= png.length) {

            int length = ByteBuffer.wrap(png, pos, 4).getInt();

            String type = new String(png, pos + 4, 4, StandardCharsets.ISO_8859_1);

            CRC32 crc = new CRC32();
            crc.update(png, pos + 4, 4 + length);

            assertEquals((int) crc.getValue(), ByteBuffer.wrap(png, pos + 8 + length, 4).getInt(), "CRC of " + type);

            types.add(type);

            pos += 12 + length;

        }

        assertEquals(png.length, pos, "nothing after IEND");

        return types;

    }

    /** Everything from the JPEG's first scan on: the image data. */
    private static byte[] fromScan(byte[] jpeg) {

        int pos = 2;

        while ((jpeg[pos + 1] & 0xFF) != 0xDA) {
            pos += 2 + (((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF));
        }

        return Arrays.copyOfRange(jpeg, pos, jpeg.length);

    }

    private static boolean contains(byte[] bytes, String text) {

        byte[] wanted = text.getBytes(StandardCharsets.ISO_8859_1);

        outer:
        for (int i = 0; i + wanted.length <= bytes.length; i++) {

            for (int j = 0; j < wanted.length; j++) {
                if (bytes[i + j] != wanted[j]) {
                    continue outer;
                }
            }

            return true;

        }

        return false;

    }

    private static BufferedImage decode(byte[] bytes) throws IOException {

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));

        assertNotNull(image, "still opens as a picture");

        return image;

    }

    private static void assertSamePixels(BufferedImage expected, BufferedImage actual) {

        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());

        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                assertEquals(expected.getRGB(x, y), actual.getRGB(x, y), "pixel " + x + "," + y);
            }
        }

    }

}
