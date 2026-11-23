package com.cybershield.recovery;

import com.cybershield.encryption.SharedCipher;
import com.cybershield.utils.PasswordGenerator;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Recovering a forgotten password to a file you own — and not finding a strong random one. */
class PasswordRecoveryTest {

    @TempDir
    Path dir;

    // =====================================================
    // THE CANDIDATES
    // =====================================================

    @Test
    void candidatesCoverYourHintsAndCommonPasswordsButNotRandomOnes() {

        Candidates candidates = Candidates.builder().hints("Rex 2015").includeCommon(true).build();

        Set<String> all = new HashSet<>();

        for (String candidate : candidates) {
            all.add(candidate);
        }

        assertTrue(all.contains("rex"), "the hint itself");
        assertTrue(all.contains("Rex2015"), "capitalised hint with your number");
        assertTrue(all.contains("rex123"), "the hint with common digits");
        assertTrue(all.contains("letmein"), "a common password");
        assertTrue(all.contains("Password1"), "a common password, capitalised, with a 1");

        String strong = PasswordGenerator.random(20, true, true, true, true, false);
        assertFalse(all.contains(strong), "a long random password is not guessed: " + strong);

        assertTrue(all.size() <= Candidates.MAX);
        assertTrue(candidates.estimate() > 0);

    }

    @Test
    void anEmptyBuilderIsEmptyOnlyWithoutTheCommonList() {

        assertTrue(Candidates.builder().includeCommon(false).build().iterator().hasNext() == false
                || !Candidates.builder().includeCommon(false).isEmpty());
        assertTrue(Candidates.builder().includeCommon(false).isEmpty());
        assertFalse(Candidates.builder().word("rex").includeCommon(false).isEmpty());

    }

    // =====================================================
    // CYBERSHIELD .cshield FILES
    // =====================================================

    @Test
    void recoversACyberShieldFilePasswordFromAHint() throws Exception {

        Path secret = Files.writeString(dir.resolve("diary.txt"), "my private notes");

        Path locked = dir.resolve("diary.txt.cshield");

        SharedCipher.encryptFile(secret, locked, "rex123", SharedCipher.Progress.NONE);

        PasswordRecovery.Target target = PasswordRecovery.targetFor(locked);

        assertTrue(target.isProtected());
        assertEquals("CyberShield file", target.kind());

        // Only a few guesses are needed, which matters: each try is slow by design.
        Candidates candidates = Candidates.builder().word("rex").includeCommon(false).build();

        PasswordRecovery.Result result = PasswordRecovery.recover(target, candidates, PasswordRecovery.Progress.NONE);

        assertTrue(result.found(), "found after " + result.tried() + " tries");
        assertEquals("rex123", result.password());

    }

    // =====================================================
    // PDF FILES
    // =====================================================

    @Test
    void recoversAPdfPasswordFromAHint() throws Exception {

        Path pdf = protectedPdf("mango", "owner-" + PasswordGenerator.pin(8));

        PasswordRecovery.Target target = PasswordRecovery.targetFor(pdf);

        assertTrue(target.isProtected(), "the PDF needs a password");
        assertEquals("PDF", target.kind());

        Candidates candidates = Candidates.builder().word("mango").includeCommon(false).build();

        PasswordRecovery.Result result = PasswordRecovery.recover(target, candidates, PasswordRecovery.Progress.NONE);

        assertTrue(result.found());
        assertEquals("mango", result.password());

    }

    @Test
    void aStrongRandomPasswordIsNotFound() throws Exception {

        String strong = PasswordGenerator.random(20, true, true, true, true, false);

        Path pdf = protectedPdf(strong, "owner-" + PasswordGenerator.pin(8));

        PasswordRecovery.Target target = PasswordRecovery.targetFor(pdf);

        Candidates candidates = Candidates.builder().word("mango").includeCommon(false).build();

        PasswordRecovery.Result result = PasswordRecovery.recover(target, candidates, PasswordRecovery.Progress.NONE);

        assertFalse(result.found(), "a long random password can't be guessed");
        assertEquals(PasswordRecovery.Outcome.EXHAUSTED, result.outcome());
        assertTrue(result.tried() > 100, "it did try many guesses: " + result.tried());

    }

    @Test
    void stoppingEndsTheRunAtOnce() throws Exception {

        Path pdf = protectedPdf("never-tried-" + PasswordGenerator.pin(6), "owner");

        PasswordRecovery.Target target = PasswordRecovery.targetFor(pdf);

        AtomicInteger seen = new AtomicInteger();

        PasswordRecovery.Progress stopper = new PasswordRecovery.Progress() {
            @Override
            public void update(long tried, long total) {
                seen.incrementAndGet();
            }

            @Override
            public boolean isCancelled() {
                return seen.get() > 0;
            }
        };

        PasswordRecovery.Result result = PasswordRecovery.recover(target, Candidates.builder().includeCommon(true).build(), stopper);

        assertEquals(PasswordRecovery.Outcome.CANCELLED, result.outcome());
        assertFalse(result.found());

    }

    @Test
    void anUnprotectedPdfIsSeenAsOpen() throws Exception {

        Path pdf = dir.resolve("open.pdf");

        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }

        assertFalse(PasswordRecovery.targetFor(pdf).isProtected(), "no password on it");

    }

    @Test
    void anUnsupportedFileIsRefused() {

        assertThrows(IOException.class, () -> PasswordRecovery.targetFor(dir.resolve("song.mp3")));

    }

    // =====================================================
    // CONTACTS IMPORT
    // =====================================================

    @Test
    void readsNamesAndNumbersFromAVCard() {

        String vcard = """
                BEGIN:VCARD
                VERSION:3.0
                FN:Rex Walker
                TEL;TYPE=CELL:+1 (555) 867-5309
                BDAY:1990-04-23
                END:VCARD
                """;

        ContactsImport.Hints hints = ContactsImport.fromText(vcard);

        assertTrue(hints.words().contains("rex"), hints.words().toString());
        assertTrue(hints.words().contains("walker"));
        assertFalse(hints.words().contains("vcard"), "field names aren't hints");
        assertFalse(hints.words().contains("tel"));
        assertTrue(hints.numbers().contains("5309"), "the last four digits of the phone");
        assertTrue(hints.numbers().contains("1990"), hints.numbers().toString());

    }

    @Test
    void readsACsvExportAndUsesItToRecover() throws Exception {

        String csv = "Name,Phone,Birthday\nMango Jones,555-0100,2008\n";

        ContactsImport.Hints hints = ContactsImport.fromText(csv);

        assertTrue(hints.words().contains("mango"));

        Path pdf = protectedPdf("Mango2008", "owner");

        Candidates.Builder builder = Candidates.builder().includeCommon(false);
        hints.words().forEach(builder::word);
        hints.numbers().forEach(builder::number);

        PasswordRecovery.Result result = PasswordRecovery.recover(
                PasswordRecovery.targetFor(pdf), builder.build(), PasswordRecovery.Progress.NONE);

        assertTrue(result.found(), "recovered from the imported contact");
        assertEquals("Mango2008", result.password());

    }

    // =====================================================
    // HELPERS
    // =====================================================

    private Path protectedPdf(String userPassword, String ownerPassword) throws IOException {

        Path pdf = dir.resolve("locked-" + Math.abs(userPassword.hashCode()) + ".pdf");

        try (PDDocument document = new PDDocument()) {

            document.addPage(new PDPage());

            StandardProtectionPolicy policy = new StandardProtectionPolicy(ownerPassword, userPassword, new AccessPermission());

            policy.setEncryptionKeyLength(128);

            document.protect(policy);

            document.save(pdf.toFile());

        }

        return pdf;

    }

}
