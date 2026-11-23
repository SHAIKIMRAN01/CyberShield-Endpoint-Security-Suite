package com.cybershield.recovery;

import com.cybershield.encryption.SharedCipher;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Recovers a forgotten password to a file <b>you own</b> by trying the passwords a person
 * is likely to have chosen (see {@link Candidates}). It supports files CyberShield itself
 * encrypted ({@code .cshield}) and password-protected PDFs. It is meant for your own files;
 * it will not open a file locked with a long random password.
 */
public final class PasswordRecovery {

    public interface Progress {

        Progress NONE = new Progress() { };

        default void update(long tried, long total) {
        }

        default boolean isCancelled() {
            return false;
        }

    }

    /** A locked file to try passwords against. */
    public interface Target {

        /**
         * Whether password opens the file.
         *
         * @throws IOException if the file itself can't be read or isn't the kind it should be
         *                     (this is not the same as a wrong password, which returns false)
         */
        boolean opensWith(String password) throws IOException;

        /** Whether the file is actually password-protected, so recovery makes sense. */
        boolean isProtected() throws IOException;

        /** A short name of the file kind, for messages. */
        String kind();

    }

    public enum Outcome { FOUND, EXHAUSTED, CANCELLED }

    public record Result(String password, long tried, Outcome outcome) {

        public boolean found() {
            return outcome == Outcome.FOUND;
        }

    }

    private PasswordRecovery() {
    }

    /** A target for file by its type, or an error if the type isn't one this tool handles. */
    public static Target targetFor(Path file) throws IOException {

        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);

        if (name.endsWith(".cshield")) {
            return new CshieldTarget(file);
        }

        if (name.endsWith(".pdf")) {
            return new PdfTarget(file);
        }

        throw new IOException("Password recovery works on .cshield files (made by this app) and PDF files.");

    }

    /** Tries each candidate against target until one opens it, they run out, or it is cancelled. */
    public static Result recover(Target target, Candidates candidates, Progress progress) throws IOException {

        long total = Math.max(1, candidates.estimate());

        long tried = 0;

        for (String password : candidates) {

            if (progress.isCancelled()) {
                return new Result(null, tried, Outcome.CANCELLED);
            }

            if ((tried & 0x1FF) == 0) {
                progress.update(tried, Math.max(total, tried));
            }

            tried++;

            if (target.opensWith(password)) {
                progress.update(tried, Math.max(total, tried));
                return new Result(password, tried, Outcome.FOUND);
            }

        }

        progress.update(tried, Math.max(total, tried));

        return new Result(null, tried, Outcome.EXHAUSTED);

    }

    // =====================================================
    // CYBERSHIELD .cshield FILES
    // =====================================================

    static final class CshieldTarget implements Target {

        private final Path file;

        CshieldTarget(Path file) {
            this.file = file;
        }

        @Override
        public boolean opensWith(String password) throws IOException {
            return SharedCipher.fileOpensWith(file, password);
        }

        @Override
        public boolean isProtected() {
            return true;
        }

        @Override
        public String kind() {
            return "CyberShield file";
        }

    }

    // =====================================================
    // PDF FILES
    // =====================================================

    static final class PdfTarget implements Target {

        private final Path file;

        PdfTarget(Path file) {
            this.file = file;
        }

        @Override
        public boolean opensWith(String password) throws IOException {

            try (PDDocument document = Loader.loadPDF(file.toFile(), password)) {
                return document != null;
            } catch (InvalidPasswordException wrong) {
                return false;
            }

        }

        @Override
        public boolean isProtected() throws IOException {

            try (PDDocument document = Loader.loadPDF(file.toFile())) {
                return document.isEncrypted();
            } catch (InvalidPasswordException needsPassword) {
                return true;
            }

        }

        @Override
        public String kind() {
            return "PDF";
        }

    }

}
