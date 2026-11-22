package com.cybershield.stego;

import com.cybershield.encryption.SharedCipher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static com.cybershield.TestSupport.random;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hiding messages and small files in pictures, and getting them back only with the password. */
class SteganographyTest {

    private static final String PASSWORD = "Picture-Pass-42!";

    @TempDir
    Path dir;

    @Test
    void aMessageComesBackFromTheSavedPngAndOnlyWithThePassword() throws Exception {

        BufferedImage cover = cover(320, 200);

        String message = "Meet at the old bridge at 6 – bring the map ☕";

        BufferedImage hidden = Steganography.hideText(cover, message, PASSWORD);

        Path saved = dir.resolve("holiday.png");

        Steganography.writePng(hidden, saved);

        BufferedImage read = Steganography.read(saved);

        assertTrue(Steganography.holdsMessage(read));

        Steganography.Hidden found = Steganography.reveal(read, PASSWORD);

        assertTrue(found.isText());
        assertEquals(message, found.text());

        assertThrows(SharedCipher.WrongPasswordException.class, () -> Steganography.reveal(read, "Not-The-Pass-1!"));

        assertFalse(Steganography.holdsMessage(cover));
        assertThrows(Steganography.NoMessageException.class, () -> Steganography.reveal(cover, PASSWORD));

    }

    @Test
    void theChangeCantBeSeen() throws Exception {

        BufferedImage cover = cover(320, 200);

        BufferedImage hidden = Steganography.hideText(cover, "x".repeat(2000), PASSWORD);

        int worst = 0;

        for (int y = 0; y < cover.getHeight(); y++) {
            for (int x = 0; x < cover.getWidth(); x++) {
                int a = cover.getRGB(x, y);
                int b = hidden.getRGB(x, y);
                for (int shift = 0; shift <= 16; shift += 8) {
                    worst = Math.max(worst, Math.abs(((a >> shift) & 0xFF) - ((b >> shift) & 0xFF)));
                }
                assertEquals(a >>> 24, b >>> 24, "transparency is untouched");
            }
        }

        assertEquals(1, worst, "no colour value moves by more than 1 in 255");

        long changed = Steganography.changedPixels(cover, hidden);

        assertTrue(changed > 0 && changed < cover.getWidth() * cover.getHeight() / 2L, "only the start of the picture holds data: " + changed);

    }

    @Test
    void aSmallFileComesBackWithItsName() throws Exception {

        BufferedImage cover = cover(400, 300);

        byte[] data = random(20_000);

        BufferedImage hidden = Steganography.hideFile(cover, "../plans/secret map.pdf", data, PASSWORD);

        Path saved = dir.resolve("hidden.png");

        Steganography.writePng(hidden, saved);

        Steganography.Hidden found = Steganography.reveal(Steganography.read(saved), PASSWORD);

        assertFalse(found.isText());
        assertEquals(".._plans_secret map.pdf".replaceFirst("^\\.+", ""), found.fileName(), "a name can't point to another folder");
        assertArrayEquals(data, found.fileData());

        assertEquals(Steganography.neededForFile("../plans/secret map.pdf", data.length),
                SharedCipher.SEAL_OVERHEAD + 1 + 2 + "../plans/secret map.pdf".length() + data.length);

    }

    @Test
    void whatDoesntFitIsRefusedAndRoomIsReportedFirst() throws Exception {

        BufferedImage small = cover(40, 30);

        long room = Steganography.room(small);

        assertEquals(40L * 30 * 3 / 8 - 8, room);

        assertTrue(Steganography.neededForText("hello") < room);

        Steganography.TooBigException tooBig = assertThrows(Steganography.TooBigException.class,
                () -> Steganography.hideFile(small, "big.bin", random((int) room), PASSWORD));

        assertEquals(room, tooBig.room());
        assertTrue(tooBig.needed() > room);

    }

    @Test
    void jpegCompressionWipesTheHiddenBits() throws Exception {

        BufferedImage hidden = Steganography.hideText(cover(320, 200), "gone after JPEG", PASSWORD);

        BufferedImage rgb = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        rgb.createGraphics().drawImage(hidden, 0, 0, null);

        Path jpeg = dir.resolve("hidden.jpg");

        ImageIO.write(rgb, "jpg", jpeg.toFile());

        assertFalse(Steganography.holdsMessage(Steganography.read(jpeg)), "which is why the result is always saved as PNG");

    }

    /** A gradient with a few shapes, like a real picture rather than a flat colour. */
    private static BufferedImage cover(int width, int height) {

        BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        Graphics2D g = picture.createGraphics();

        g.setPaint(new GradientPaint(0, 0, new Color(214, 190, 160), width, height, new Color(90, 59, 39)));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(250, 246, 240));
        g.fillOval(width / 2, height / 6, width / 4, width / 4);
        g.dispose();

        return picture;

    }

}
