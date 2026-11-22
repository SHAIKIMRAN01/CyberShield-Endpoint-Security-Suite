package com.cybershield.reports;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Lays a report out on A4 pages in the app's brown and off-white, using the standard PDF
 * fonts so nothing needs embedding. Characters those fonts can't show (Chinese letters
 * in a device name, say) are printed as "?". Long tables carry on to the next page with
 * their header repeated, and every page gets a footer with its number.
 */
final class ReportPdf implements AutoCloseable {

    record Rgb(float r, float g, float b) {

        static Rgb of(int hex) {
            return new Rgb(((hex >> 16) & 0xFF) / 255f, ((hex >> 8) & 0xFF) / 255f, (hex & 0xFF) / 255f);
        }

    }

    // The colours of theme.css.
    static final Rgb BROWN_900 = Rgb.of(0x2E1F16);
    static final Rgb BROWN_800 = Rgb.of(0x3D2A1E);
    static final Rgb CARAMEL = Rgb.of(0xB06A2F);
    static final Rgb SAND = Rgb.of(0xF2E9DD);
    static final Rgb PAPER = Rgb.of(0xFAF6F0);
    static final Rgb LINE = Rgb.of(0xDDD0BE);
    static final Rgb INK = Rgb.of(0x2B1D15);
    static final Rgb MUTED = Rgb.of(0x6B5849);
    static final Rgb ON_BROWN = Rgb.of(0xF6EEE4);
    static final Rgb ON_BROWN_MUTED = Rgb.of(0xCDB8A3);
    static final Rgb GOOD = Rgb.of(0x2E6A3A);
    static final Rgb WARN = Rgb.of(0x865800);
    static final Rgb BAD = Rgb.of(0xA13A28);

    private static final PDRectangle PAGE = PDRectangle.A4;

    private static final float LEFT = 48;

    private static final float WIDTH = PAGE.getWidth() - 2 * LEFT;

    private static final float TOP = PAGE.getHeight() - 48;

    // Content stops here; the footer sits below.
    private static final float BOTTOM = 72;

    private static final float LINE_GAP = 15;

    private static final float ROW_HEIGHT = 18;

    final PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    final PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private final PDDocument document = new PDDocument();

    private final String runningTitle;

    private PDPageContentStream out;

    private float y;

    /** @param runningTitle the small line at the top of every page after the first */
    ReportPdf(String title, String runningTitle) {

        this.runningTitle = runningTitle;

        PDDocumentInformation info = document.getDocumentInformation();

        info.setTitle(title);
        info.setCreator("CyberShield");
        info.setCreationDate(Calendar.getInstance());

    }

    // =====================================================
    // PAGES
    // =====================================================

    void addPage() throws IOException {

        if (out != null) {
            out.close();
        }

        PDPage page = new PDPage(PAGE);

        document.addPage(page);

        out = new PDPageContentStream(document, page);

        y = TOP;

        if (document.getNumberOfPages() > 1) {
            text(runningTitle, regular, 8.5f, MUTED, LEFT, y - 8);
            rule(y - 15, LINE, 0.5f);
            y -= 34;
        }

    }

    /** Moves to a new page unless height still fits above the footer. */
    void ensure(float height) throws IOException {

        if (y - height < BOTTOM) {
            addPage();
        }

    }

    /** Adds the footer to every page and writes the file. */
    void save(Path target, String footer) throws IOException {

        finish(footer);

        document.save(target.toFile());

    }

    /** Adds the footer to every page and returns the PDF, kept only in memory. */
    byte[] toBytes(String footer) throws IOException {

        finish(footer);

        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();

        document.save(bytes);

        return bytes.toByteArray();

    }

    private void finish(String footer) throws IOException {

        out.close();

        int pages = document.getNumberOfPages();

        for (int i = 0; i < pages; i++) {

            out = new PDPageContentStream(document, document.getPage(i), PDPageContentStream.AppendMode.APPEND, true, true);

            rule(52, LINE, 0.5f);

            text(fit(footer, regular, 8, WIDTH - 70), regular, 8, MUTED, LEFT, 38);

            textRight("Page " + (i + 1) + " of " + pages, regular, 8, MUTED, LEFT + WIDTH, 38);

            out.close();

        }

        out = null;

    }

    @Override
    public void close() throws IOException {

        document.close();

    }

    // =====================================================
    // BLOCKS
    // =====================================================

    /** The brown band across the top of the first page, with the title on the left and details on the right. */
    void banner(String kicker, String title, List<String> details) throws IOException {

        float height = 108;

        float top = PAGE.getHeight();

        fill(0, top - height, PAGE.getWidth(), height, BROWN_900);

        text(kicker, bold, 9, ON_BROWN_MUTED, LEFT, top - 42);

        text(title, bold, 26, ON_BROWN, LEFT, top - 74);

        float baseline = top - 42;

        for (int i = 0; i < details.size(); i++) {
            boolean first = i == 0;
            textRight(details.get(i), first ? bold : regular, first ? 10.5f : 9, first ? ON_BROWN : ON_BROWN_MUTED, LEFT + WIDTH, baseline);
            baseline -= 16;
        }

        y = top - height - 26;

    }

    /**
     * The score in a card across the left third, and three figures in the other two
     * thirds: the same rule of thirds as the app's screens.
     *
     * @param figures three rows of label, number, note
     */
    void scoreAndFigures(int percent, String grade, Rgb gradeColour, String[][] figures) throws IOException {

        float height = 116;

        float gap = 12;

        ensure(height + 10);

        float bottom = y - height;

        // Left third: the score.
        float cardWidth = WIDTH / 3 - gap / 2;

        fill(LEFT, bottom, cardWidth, height, PAPER);
        stroke(LEFT, bottom, cardWidth, height, LINE, 0.75f);

        float x = LEFT + 14;

        text("SECURITY SCORE", bold, 8, MUTED, x, y - 22);

        String number = String.valueOf(percent);

        text(number, bold, 36, BROWN_800, x, y - 62);

        text("out of 100", regular, 9, MUTED, x + width(number, bold, 36) + 6, y - 62);

        text(grade, bold, 11, gradeColour, x, y - 82);

        float barWidth = cardWidth - 28;

        fill(x, bottom + 16, barWidth, 6, SAND);
        fill(x, bottom + 16, barWidth * Math.max(0, Math.min(100, percent)) / 100f, 6, CARAMEL);

        // Other two thirds: three figures.
        float areaLeft = LEFT + WIDTH / 3 + gap / 2;

        float areaWidth = WIDTH * 2 / 3 - gap / 2;

        float boxWidth = (areaWidth - 2 * gap) / 3;

        for (int i = 0; i < figures.length && i < 3; i++) {
            figureBox(areaLeft + i * (boxWidth + gap), boxWidth, bottom, height, figures[i]);
        }

        y = bottom - 24;

    }

    /** Three figures side by side across the page, a third of the width each. */
    void figureRow(String[][] figures) throws IOException {

        float height = 100;

        float gap = 12;

        ensure(height + 10);

        float bottom = y - height;

        float boxWidth = (WIDTH - 2 * gap) / 3;

        for (int i = 0; i < figures.length && i < 3; i++) {
            figureBox(LEFT + i * (boxWidth + gap), boxWidth, bottom, height, figures[i]);
        }

        y = bottom - 24;

    }

    /** A box with a small label, a big number and a note under it. */
    private void figureBox(float boxLeft, float boxWidth, float bottom, float height, String[] figure) throws IOException {

        float top = bottom + height;

        fill(boxLeft, bottom, boxWidth, height, PAPER);
        stroke(boxLeft, bottom, boxWidth, height, LINE, 0.75f);

        float inner = boxLeft + 12;

        text(fit(figure[0].toUpperCase(java.util.Locale.ROOT), bold, 8, boxWidth - 24), bold, 8, MUTED, inner, top - 22);

        text(fit(figure[1], bold, 26, boxWidth - 24), bold, 26, BROWN_800, inner, top - 58);

        float baseline = top - 80;

        for (String line : wrap(figure[2], regular, 9, boxWidth - 24).stream().limit(2).toList()) {
            text(line, regular, 9, INK, inner, baseline);
            baseline -= 12;
        }

    }

    void heading(String title) throws IOException {

        // Room above, unless the heading starts a page.
        if (y < TOP - 40) {
            y -= 8;
        }

        // Keep a heading with at least two lines of what follows it.
        ensure(34 + 2 * LINE_GAP);

        text(title, bold, 13, BROWN_800, LEFT, y - 13);

        rule(y - 21, LINE, 0.75f);

        y -= 36;

    }

    /** A label in the left third and its value in the other two. */
    void row(String label, String value, Rgb valueColour) throws IOException {

        float valueLeft = LEFT + WIDTH / 3;

        List<String> lines = wrap(value, bold, 10, WIDTH * 2 / 3);

        float height = LINE_GAP * lines.size();

        ensure(height + 2);

        text(fit(label, regular, 10, WIDTH / 3 - 12), regular, 10, MUTED, LEFT, y - 10);

        float baseline = y - 10;

        for (String line : lines) {
            text(line, bold, 10, valueColour, valueLeft, baseline);
            baseline -= LINE_GAP;
        }

        y -= height + 3;

    }

    void paragraph(String text, Rgb colour) throws IOException {

        for (String line : wrap(text, regular, 10, WIDTH)) {
            ensure(LINE_GAP);
            text(line, regular, 10, colour, LEFT, y - 10);
            y -= LINE_GAP;
        }

        y -= 6;

    }

    void bullets(List<String> items) throws IOException {

        for (String item : items) {

            List<String> lines = wrap(item, regular, 10, WIDTH - 16);

            ensure(LINE_GAP * lines.size());

            text("•", bold, 10, CARAMEL, LEFT + 2, y - 10);

            float baseline = y - 10;

            for (String line : lines) {
                text(line, regular, 10, INK, LEFT + 16, baseline);
                baseline -= LINE_GAP;
            }

            y -= LINE_GAP * lines.size() + 3;

        }

        y -= 6;

    }

    /** A table across the full width; fractions share the width between the columns. */
    void table(String[] headers, float[] fractions, List<String[]> rows) throws IOException {

        ensure(ROW_HEIGHT * Math.min(rows.size() + 1, 4));

        tableHeader(headers, fractions);

        for (String[] row : rows) {

            if (y - ROW_HEIGHT < BOTTOM) {
                addPage();
                tableHeader(headers, fractions);
            }

            float x = LEFT;

            for (int column = 0; column < headers.length; column++) {

                float columnWidth = WIDTH * fractions[column];

                String cell = column < row.length && row[column] != null ? row[column] : "";

                text(fit(cell, regular, 9, columnWidth - 12), regular, 9, INK, x + 6, y - 12.5f);

                x += columnWidth;

            }

            rule(y - ROW_HEIGHT, LINE, 0.5f);

            y -= ROW_HEIGHT;

        }

        y -= 14;

    }

    private void tableHeader(String[] headers, float[] fractions) throws IOException {

        fill(LEFT, y - ROW_HEIGHT, WIDTH, ROW_HEIGHT, SAND);

        float x = LEFT;

        for (int column = 0; column < headers.length; column++) {

            float columnWidth = WIDTH * fractions[column];

            text(fit(headers[column], bold, 9, columnWidth - 12), bold, 9, BROWN_800, x + 6, y - 12.5f);

            x += columnWidth;

        }

        y -= ROW_HEIGHT;

    }

    // =====================================================
    // DRAWING
    // =====================================================

    private void fill(float x, float bottom, float w, float h, Rgb colour) throws IOException {

        out.setNonStrokingColor(colour.r(), colour.g(), colour.b());
        out.addRect(x, bottom, w, h);
        out.fill();

    }

    private void stroke(float x, float bottom, float w, float h, Rgb colour, float lineWidth) throws IOException {

        out.setStrokingColor(colour.r(), colour.g(), colour.b());
        out.setLineWidth(lineWidth);
        out.addRect(x, bottom, w, h);
        out.stroke();

    }

    private void rule(float atY, Rgb colour, float lineWidth) throws IOException {

        out.setStrokingColor(colour.r(), colour.g(), colour.b());
        out.setLineWidth(lineWidth);
        out.moveTo(LEFT, atY);
        out.lineTo(LEFT + WIDTH, atY);
        out.stroke();

    }

    private void text(String text, PDFont font, float size, Rgb colour, float x, float baseline) throws IOException {

        String shown = safe(font, text);

        if (shown.isBlank()) {
            return;
        }

        out.beginText();
        out.setFont(font, size);
        out.setNonStrokingColor(colour.r(), colour.g(), colour.b());
        out.newLineAtOffset(x, baseline);
        out.showText(shown);
        out.endText();

    }

    private void textRight(String text, PDFont font, float size, Rgb colour, float right, float baseline) throws IOException {

        String shown = fit(text, font, size, WIDTH);

        text(shown, font, size, colour, right - width(shown, font, size), baseline);

    }

    // =====================================================
    // TEXT
    // =====================================================

    float width(String text, PDFont font, float size) throws IOException {

        return font.getStringWidth(safe(font, text)) / 1000f * size;

    }

    /** The text, cut short with "..." if it is wider than maxWidth. */
    String fit(String text, PDFont font, float size, float maxWidth) throws IOException {

        String shown = safe(font, text);

        if (width(shown, font, size) <= maxWidth) {
            return shown;
        }

        int end = shown.length();

        while (end > 0 && width(shown.substring(0, end) + "...", font, size) > maxWidth) {
            end--;
        }

        return shown.substring(0, end).stripTrailing() + "...";

    }

    /** Splits text into lines no wider than maxWidth; a single word too long for a line is cut short. */
    List<String> wrap(String text, PDFont font, float size, float maxWidth) throws IOException {

        List<String> lines = new ArrayList<>();

        StringBuilder line = new StringBuilder();

        for (String word : safe(font, text).split(" +")) {

            if (word.isEmpty()) {
                continue;
            }

            String longer = line.isEmpty() ? word : line + " " + word;

            if (line.isEmpty() || width(longer, font, size) <= maxWidth) {
                line.setLength(0);
                line.append(longer);
            } else {
                lines.add(fit(line.toString(), font, size, maxWidth));
                line.setLength(0);
                line.append(word);
            }

        }

        if (!line.isEmpty()) {
            lines.add(fit(line.toString(), font, size, maxWidth));
        }

        if (lines.isEmpty()) {
            lines.add("");
        }

        return lines;

    }

    /** The text with line breaks and tabs as spaces, and "?" for anything the font can't draw. */
    static String safe(PDFont font, String text) {

        if (text == null) {
            return "";
        }

        StringBuilder shown = new StringBuilder(text.length());

        text.codePoints().forEach(codePoint -> {

            if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) {
                shown.append(' ');
                return;
            }

            String character = new String(Character.toChars(codePoint));

            try {
                font.encode(character);
                shown.append(character);
            } catch (IOException | IllegalArgumentException cantDraw) {
                shown.append('?');
            }

        });

        return shown.toString();

    }

}
