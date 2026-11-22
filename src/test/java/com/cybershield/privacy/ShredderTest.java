package com.cybershield.privacy;

import com.cybershield.files.FileVaultService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The shredder, on made-up files in a temp folder only. */
class ShredderTest {

    @TempDir
    Path dir;

    private Shredder shredder() {
        return new Shredder(new FileVaultService(dir.resolve("store")));
    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    @Test
    void shredsFilesAndFoldersAndLeavesNoNameBehind() throws Exception {

        Path chosen = Files.createDirectories(dir.resolve("old taxes"));

        Path nested = Files.createDirectories(chosen.resolve("2025").resolve("receipts"));

        Files.writeString(chosen.resolve("return.txt"), "return ".repeat(1000));
        Files.write(nested.resolve("scan.bin"), new byte[3 * 1024 * 1024 + 17]);
        Files.writeString(nested.resolve("empty.txt"), "");

        Path readOnly = chosen.resolve("signed.txt");
        Files.writeString(readOnly, "read only");

        if (windows()) {
            Files.setAttribute(readOnly, "dos:readonly", true);
        }

        Path single = Files.writeString(dir.resolve("note.txt"), "a note");

        Path keep = Files.writeString(dir.resolve("keep.txt"), "not chosen");

        Shredder.Plan plan = shredder().plan(List.of(chosen, single, single));

        assertEquals(5, plan.files().size(), "each file once: " + plan.files());
        assertEquals(3, plan.folders().size());
        assertEquals(7000 + 3 * 1024 * 1024 + 17 + 9 + 6, plan.bytes());
        assertTrue(plan.refused().isEmpty(), String.valueOf(plan.refused()));

        AtomicInteger last = new AtomicInteger(-1);

        Shredder.Result result = shredder().shred(plan, new Shredder.Progress() {
            @Override
            public void update(int done, int total) {
                assertTrue(done >= last.get() && done <= total);
                last.set(done);
            }
        });

        assertEquals(5, result.shredded());
        assertEquals(0, result.failed());
        assertEquals(plan.bytes(), result.bytes());
        assertEquals(5, last.get(), "progress ends at the total");

        assertFalse(Files.exists(chosen), "the folder goes once empty");
        assertFalse(Files.exists(single));
        assertEquals("not chosen", Files.readString(keep));

        try (Stream<Path> left = Files.list(dir)) {
            assertEquals(List.of(keep), left.toList(), "no renamed leftovers");
        }

    }

    @Test
    void refusesDrivesTheUserFolderAndCyberShieldsOwnFiles() throws Exception {

        Path store = Files.createDirectories(dir.resolve("store"));

        Files.writeString(store.resolve("blob"), "encrypted");

        Path inside = Files.createDirectories(dir.resolve("holds the store"));

        // Only planned, never shredded: plan() reads and refuses, nothing is touched.
        Shredder.Plan plan = new Shredder(new FileVaultService(inside.resolve("store")))
                .plan(List.of(dir.getRoot(), Path.of(System.getProperty("user.home")), inside));

        assertTrue(plan.isEmpty(), "nothing to shred: " + plan.files());
        assertEquals(3, plan.refused().size(), String.valueOf(plan.refused()));
        assertTrue(plan.refused().get(0).contains("Whole drives"), plan.refused().get(0));
        assertTrue(plan.refused().get(1).contains("user folder can't be shredded"), plan.refused().get(1));
        assertTrue(plan.refused().get(2).contains("CyberShield's own files"), plan.refused().get(2));

        Shredder.Plan own = shredder().plan(List.of(store));

        assertTrue(own.isEmpty());
        assertTrue(own.refused().get(0).contains("CyberShield's own files"));

        Shredder.Plan gone = shredder().plan(List.of(dir.resolve("not there.txt")));

        assertTrue(gone.isEmpty());
        assertTrue(gone.refused().get(0).contains("isn't there any more"));

    }

    @Test
    void neverFollowsAJunctionInsideOrChosen() throws Exception {

        assumeTrue(windows(), "junctions are a Windows thing");

        Path outside = Files.createDirectories(dir.resolve("outside"));

        Files.writeString(outside.resolve("secret.txt"), "outside the selection");

        Path chosen = Files.createDirectories(dir.resolve("chosen"));

        Files.writeString(chosen.resolve("old.txt"), "old");

        Path junction = junction(chosen.resolve("link-to-outside"), outside);

        Shredder.Plan plan = shredder().plan(List.of(chosen));

        assertEquals(List.of(chosen.resolve("old.txt")), plan.files());
        assertEquals(1, plan.refused().size(), String.valueOf(plan.refused()));
        assertTrue(plan.refused().get(0).contains("link-to-outside"));

        Shredder.Result result = shredder().shred(plan, Shredder.Progress.NONE);

        assertEquals(1, result.shredded());
        assertEquals("outside the selection", Files.readString(outside.resolve("secret.txt")), "the junction's target is untouched");
        assertTrue(Files.exists(junction, LinkOption.NOFOLLOW_LINKS), "the junction stays, so its folder does too");

        Shredder.Plan direct = shredder().plan(List.of(junction));

        assertTrue(direct.isEmpty(), "a junction chosen directly is refused: " + direct.files());
        assertTrue(direct.refused().get(0).contains("Links aren't followed"), direct.refused().get(0));
        assertEquals("outside the selection", Files.readString(outside.resolve("secret.txt")));

    }

    @Test
    void aFileOpenInAnotherProgramStaysWhole() throws Exception {

        assumeTrue(windows(), "only Windows refuses to rename an open file");

        Path open = Files.writeString(dir.resolve("open.txt"), "still being edited");

        Path other = Files.writeString(dir.resolve("other.txt"), "goes");

        Shredder.Plan plan = shredder().plan(List.of(open, other));

        Shredder.Result result;

        try (FileInputStream holdOpen = new FileInputStream(open.toFile())) {
            result = shredder().shred(plan, Shredder.Progress.NONE);
        }

        assertEquals(1, result.shredded());
        assertEquals(1, result.failed());
        assertTrue(result.problems().get(0).startsWith("open.txt"), String.valueOf(result.problems()));
        assertEquals("still being edited", Files.readString(open), "untouched, under its own name");
        assertFalse(Files.exists(other));

    }

    @Test
    void stoppingLeavesTheRestUntouched() throws Exception {

        Path first = Files.writeString(dir.resolve("a.txt"), "a");
        Path second = Files.writeString(dir.resolve("b.txt"), "b");
        Path third = Files.writeString(dir.resolve("c.txt"), "c");

        Shredder.Plan plan = shredder().plan(List.of(first, second, third));

        AtomicInteger asked = new AtomicInteger();

        assertThrows(CancellationException.class, () -> shredder().shred(plan, new Shredder.Progress() {
            @Override
            public boolean isCancelled() {
                return asked.incrementAndGet() > 1;
            }
        }));

        assertFalse(Files.exists(first));
        assertEquals("b", Files.readString(second));
        assertEquals("c", Files.readString(third));

    }

    static Path junction(Path link, Path target) throws IOException, InterruptedException {

        Process mklink = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();

        mklink.getInputStream().readAllBytes();

        assertEquals(0, mklink.waitFor(), "test junction");

        return link;

    }

}
