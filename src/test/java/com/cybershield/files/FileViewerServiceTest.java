package com.cybershield.files;

import com.cybershield.TestSupport;
import com.cybershield.encryption.FileDecryptor;
import com.cybershield.services.EncryptionService;
import com.cybershield.ui.viewer.PdfPreview;
import com.cybershield.ui.viewer.PreviewKind;
import com.cybershield.ui.viewer.TextPreview;
import com.cybershield.vault.VaultService;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.cybershield.TestSupport.MASTER_PASSWORD;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the in-app viewer relies on: listing, reading into memory, export, restoring single files, decoding. */
class FileViewerServiceTest {

    private static final FileVaultService.JobListener QUIET = new FileVaultService.JobListener() { };

    @TempDir
    Path tempDir;

    private Path store;

    private Path album;

    private Map<String, byte[]> originals;

    private FileVaultService service;

    private final VaultService vaults = new VaultService();

    @BeforeEach
    void setUp() throws Exception {

        store = TestSupport.freshEnvironment(tempDir);

        album = Files.createDirectories(tempDir.resolve("files").resolve("Album"));

        Files.createDirectories(album.resolve("sub"));
        Files.createDirectories(album.resolve("Empty"));

        originals = TestMedia.writeAlbum(album);

        Files.setLastModifiedTime(album.resolve("notes.txt"), FileTime.from(Instant.parse("2021-05-06T07:08:09Z")));

        vaults.createVault(TestSupport.newUser("alice"), MASTER_PASSWORD);

        service = new FileVaultService(store);

        assertEquals(6, service.protect(service.scan(List.of(album), QUIET), QUIET).protectedFiles());

    }

    @AfterEach
    void lock() {
        vaults.lockVault();
    }

    @Test
    void filesAreListedWithTheirOriginalDetails() throws Exception {

        List<FileVaultService.VaultFile> listed = service.listFiles(album());

        assertEquals(List.of("clip.wav", "data.bin", "notes.txt", "photo.png", "report.pdf", "sub\\inner.txt"),
                listed.stream().map(FileVaultService.VaultFile::relativePath).toList());

        assertTrue(listed.stream().allMatch(file -> file.size() == originals.get(file.relativePath()).length));

        assertEquals(Instant.parse("2021-05-06T07:08:09Z").toEpochMilli(), file(listed, "notes.txt").lastModifiedMillis());

        assertEquals("pdf", file(listed, "report.pdf").extension());

        assertEquals("inner.txt", file(listed, "sub\\inner.txt").name());

    }

    @Test
    void readingIntoMemoryAndForPlayback() throws Exception {

        List<FileVaultService.VaultFile> listed = service.listFiles(album());

        FileVaultService.VaultFile notes = file(listed, "notes.txt");

        assertArrayEquals(originals.get("notes.txt"), service.readFile(notes, 1 << 20));

        assertThrows(FileDecryptor.TooLargeException.class, () -> service.readFile(notes, 5));

        ByteBuffer clip = service.readFileForPlayback(file(listed, "clip.wav"), 1 << 26);

        byte[] clipBytes = new byte[clip.remaining()];

        clip.get(clipBytes);

        assertTrue(clip.isDirect(), "playback data lives off the Java heap");

        assertArrayEquals(originals.get("clip.wav"), clipBytes);

    }

    @Test
    void aChangedEncryptedFileIsRefusedNotShown() throws Exception {

        FileVaultService.VaultFile notes = file(service.listFiles(album()), "notes.txt");

        byte[] blob = Files.readAllBytes(store.resolve(notes.id()));

        blob[blob.length - 5] ^= 1;

        Path tampered = Files.write(tempDir.resolve("tampered.bin"), blob);

        assertThrows(FileDecryptor.DamagedFileException.class,
                () -> FileDecryptor.decryptToBytes(tampered, EncryptionService.getInstance(), 1 << 20));

    }

    @Test
    void exportWritesADecryptedCopyAndKeepsTheFileProtected() throws Exception {

        FileVaultService.VaultFile notes = file(service.listFiles(album()), "notes.txt");

        Path export = Files.createDirectories(tempDir.resolve("export")).resolve("notes copy.txt");

        service.exportCopy(notes, export);

        assertArrayEquals(originals.get("notes.txt"), Files.readAllBytes(export));

        assertEquals(notes.lastModifiedMillis(), Files.getLastModifiedTime(export).toMillis());

        service.exportCopy(notes, export);

        assertArrayEquals(originals.get("notes.txt"), Files.readAllBytes(export), "an earlier copy can be replaced");

        assertTrue(Files.exists(store.resolve(notes.id())));

        assertEquals(6, service.listFiles(album()).size());

        try (Stream<Path> next = Files.list(export.getParent())) {
            assertEquals(1, next.count(), "no temp file left next to the export");
        }

    }

    @Test
    void restoringSingleFilesThenTheRest() throws Exception {

        ProtectedItem item = album();

        FileVaultService.VaultFile notes = file(service.listFiles(item), "notes.txt");

        FileVaultService.RestoreResult one = service.restoreFiles(item, List.of(notes), QUIET);

        assertEquals(1, one.restoredFiles());
        assertEquals(0, one.restoredItems());
        assertArrayEquals(originals.get("notes.txt"), Files.readAllBytes(album.resolve("notes.txt")));

        ProtectedItem after = album();

        assertEquals(5, after.storedFiles());
        assertEquals(item.info().totalBytes() - originals.get("notes.txt").length, after.info().totalBytes());

        FileVaultService.RestoreResult rest = service.restoreFiles(after, service.listFiles(after), QUIET);

        assertEquals(5, rest.restoredFiles());
        assertEquals(1, rest.restoredItems(), "restoring the last files removes the item");
        assertTrue(Files.isDirectory(album.resolve("Empty")));

        for (Map.Entry<String, byte[]> original : originals.entrySet()) {
            assertArrayEquals(original.getValue(), Files.readAllBytes(album.resolve(original.getKey())), original.getKey());
        }

    }

    @Test
    void viewerTypesAndTextDecoding() throws Exception {

        assertEquals(PreviewKind.IMAGE, PreviewKind.of("jpg"));
        assertEquals(PreviewKind.TEXT, PreviewKind.of("csv"));
        assertEquals(PreviewKind.PDF, PreviewKind.of("pdf"));
        assertEquals(PreviewKind.MEDIA, PreviewKind.of("mp4"));
        assertEquals(PreviewKind.MEDIA, PreviewKind.of("mp3"));
        assertEquals(PreviewKind.NONE, PreviewKind.of("docx"));
        assertEquals("video/mp4", PreviewKind.mediaType("mp4"));
        assertNull(PreviewKind.mediaType("mkv"));

        String text = "naïve café – ünïcödé";

        assertEquals(text, TextPreview.decode(text.getBytes(StandardCharsets.UTF_8)));

        assertEquals(text, TextPreview.decode(concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                text.getBytes(StandardCharsets.UTF_8))));

        assertEquals(text, TextPreview.decode(concat(new byte[] {(byte) 0xFF, (byte) 0xFE},
                text.getBytes(StandardCharsets.UTF_16LE))));

        assertEquals("café", TextPreview.decode("café".getBytes(Charset.forName("windows-1252"))));

        assertThrows(IOException.class, () -> TextPreview.decode(new byte[] {1, 2, 0, 3}), "binary data isn't text");

    }

    @Test
    void pdfsOpenAndDrawFromMemory() throws Exception {

        try (PDDocument document = PdfPreview.load(originals.get("report.pdf").clone())) {

            assertEquals(2, document.getNumberOfPages());

            assertTrue(new PDFRenderer(document).renderImageWithDPI(0, 72).getWidth() > 100);

        }

    }

    private ProtectedItem album() throws Exception {

        return service.listItems().stream().filter(item -> item.name().equals("Album")).findFirst().orElseThrow();

    }

    private static FileVaultService.VaultFile file(List<FileVaultService.VaultFile> files, String relativePath) {

        return files.stream().filter(file -> file.relativePath().equals(relativePath)).findFirst().orElseThrow();

    }

    private static byte[] concat(byte[] first, byte[] second) {

        byte[] out = Arrays.copyOf(first, first.length + second.length);

        System.arraycopy(second, 0, out, first.length, second.length);

        return out;

    }

}
