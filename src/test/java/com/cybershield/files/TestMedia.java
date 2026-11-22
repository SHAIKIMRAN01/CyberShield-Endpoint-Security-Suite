package com.cybershield.files;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/** Small real files of every kind the viewer opens, made on the fly. */
final class TestMedia {

    private TestMedia() {
    }

    /** Writes a photo, text, a PDF, a WAV clip, binary data and a file in a sub-folder. Returns name -> bytes. */
    static Map<String, byte[]> writeAlbum(Path album) throws Exception {

        Map<String, byte[]> files = new TreeMap<>();

        files.put("photo.png", png(640, 480));
        files.put("notes.txt", "Trip notes\nDay 1: naïve café visit\nSECRET-VIEWER-MARKER\n".getBytes(StandardCharsets.UTF_8));
        files.put("report.pdf", pdf("Page one", "Page two"));
        files.put("clip.wav", wav(3));

        byte[] binary = new byte[3000];
        new Random(7).nextBytes(binary);
        files.put("data.bin", binary);

        files.put("sub\\inner.txt", "inside a sub-folder".getBytes(StandardCharsets.UTF_8));

        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            Files.write(album.resolve(entry.getKey()), entry.getValue());
        }

        return files;

    }

    static byte[] png(int width, int height) throws Exception {

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        Graphics2D g = image.createGraphics();

        g.setPaint(new GradientPaint(0, 0, new Color(90, 59, 39), width, height, new Color(250, 246, 240)));
        g.fillRect(0, 0, width, height);
        g.setColor(Color.WHITE);
        g.fillOval(width / 2 - 80, height / 2 - 80, 160, 160);
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ImageIO.write(image, "png", out);

        return out.toByteArray();

    }

    static byte[] pdf(String... pages) throws Exception {

        try (PDDocument document = new PDDocument()) {

            for (String text : pages) {

                PDPage page = new PDPage();

                document.addPage(page);

                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 36);
                    content.newLineAtOffset(72, 650);
                    content.showText(text);
                    content.endText();
                }

            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();

            document.save(out);

            return out.toByteArray();

        }

    }

    static byte[] wav(int seconds) throws Exception {

        AudioFormat format = new AudioFormat(44100, 16, 2, true, false);

        int frames = 44100 * seconds;

        byte[] pcm = new byte[frames * 4];

        for (int i = 0; i < frames; i++) {

            short sample = (short) (Math.sin(2 * Math.PI * 440 * i / 44100.0) * 3000);

            pcm[i * 4] = (byte) sample;
            pcm[i * 4 + 1] = (byte) (sample >> 8);
            pcm[i * 4 + 2] = (byte) sample;
            pcm[i * 4 + 3] = (byte) (sample >> 8);

        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(pcm), format, frames), AudioFileFormat.Type.WAVE, out);

        return out.toByteArray();

    }

}
