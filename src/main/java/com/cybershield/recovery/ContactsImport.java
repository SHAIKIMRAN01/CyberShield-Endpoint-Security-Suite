package com.cybershield.recovery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads hints out of a contacts file you choose — a vCard (.vcf) or a CSV exported from your
 * own phone or address book — to help recover a password you based on a name, a place or a
 * phone number. It only reads the file you pick; nothing is kept. Names become word hints,
 * phone numbers and years become number hints.
 */
public final class ContactsImport {

    /** Words (names, places) and numbers (phones, years, dates) to try. */
    public record Hints(List<String> words, List<String> numbers) {

        public boolean isEmpty() {
            return words.isEmpty() && numbers.isEmpty();
        }

    }

    /** A file bigger than this isn't an ordinary contacts export; we don't read it. */
    public static final long MAX_BYTES = 16L * 1024 * 1024;

    private static final int MAX_WORDS = 4000;

    private static final int MAX_NUMBERS = 4000;

    private static final Pattern LETTERS = Pattern.compile("\\p{L}{2,20}");

    private static final Pattern DIGITS = Pattern.compile("\\d{2,20}");

    private ContactsImport() {
    }

    public static Hints read(Path file) throws IOException {

        if (Files.size(file) > MAX_BYTES) {
            throw new IOException("That file is too big to be a contacts export (over " + (MAX_BYTES / 1024 / 1024) + " MB).");
        }

        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);

        return fromText(text);

    }

    /** Also used by the tests. */
    static Hints fromText(String text) {

        Set<String> words = new LinkedHashSet<>();

        Set<String> numbers = new LinkedHashSet<>();

        Matcher letters = LETTERS.matcher(text);

        while (letters.find() && words.size() < MAX_WORDS) {

            String word = letters.group().toLowerCase(Locale.ROOT);

            if (!VCARD_KEYWORDS.contains(word)) {
                words.add(word);
            }

        }

        Matcher digits = DIGITS.matcher(text);

        while (digits.find() && numbers.size() < MAX_NUMBERS) {

            String number = digits.group();

            numbers.add(number);

            // The last four digits of a phone number are a common PIN or suffix.
            if (number.length() > 4) {
                numbers.add(number.substring(number.length() - 4));
            }

        }

        return new Hints(List.copyOf(words), List.copyOf(numbers));

    }

    // vCard field names and common CSV headers, so "TEL", "EMAIL", "VCARD" and the like
    // don't become password guesses.
    private static final Set<String> VCARD_KEYWORDS = Set.of(
            "begin", "end", "vcard", "version", "fn", "tel", "adr", "email", "org", "title",
            "note", "bday", "url", "photo", "type", "cell", "home", "work", "voice", "pref",
            "charset", "encoding", "quoted", "printable", "item", "www", "http", "https", "com",
            "name", "first", "last", "middle", "phone", "mobile", "number", "address", "nickname",
            "given", "family", "display", "primary", "mail", "google", "contact", "contacts");

}
