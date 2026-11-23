package com.cybershield.recovery;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * The passwords a recovery run will try, in a sensible order and without repeats. They are
 * built from what you remember — your own words (names, a pet, a place) and numbers (years,
 * dates, a phone number) — in their common forms: capitalised, UPPER CASE, a few "leet"
 * swaps, and with the digits and symbols people usually add. A short built-in list of the
 * most common passwords can be added too.
 *
 * <p>This is for recovering a password you chose and forgot, so it only tries the kind of
 * passwords a person picks. It will not find a long random password — which is the point of
 * using one.
 */
public final class Candidates implements Iterable<String> {

    /** The most candidates a run will make, so memory and time stay bounded. */
    public static final int MAX = 1_000_000;

    // The digits and symbols people add to a word.
    private static final List<String> FIXED_SUFFIXES = List.of(
            "", "1", "2", "3", "4", "5", "0", "7", "9", "11", "12", "21", "00", "01", "99",
            "123", "1234", "12345", "007", "321", "69", "101", "111", "222", "777", "786",
            "!", "@", "#", "$", ".", "*", "!!", "123!", "1!", "@123");

    private final List<String> hintWords;

    private final List<String> hintNumbers;

    private final boolean includeCommon;

    private Candidates(List<String> hintWords, List<String> hintNumbers, boolean includeCommon) {
        this.hintWords = List.copyOf(hintWords);
        this.hintNumbers = List.copyOf(hintNumbers);
        this.includeCommon = includeCommon;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A rough total, for the progress bar. The run may try a few less (repeats are skipped). */
    public long estimate() {

        List<String> suffixes = suffixes();

        List<String> baseTokens = baseTokens();

        long perToken = 4L * suffixes.size(); // up to four case forms per word

        long total = (long) extras().size() + baseTokens.size() * perToken;

        return Math.min(total, MAX);

    }

    @Override
    public Iterator<String> iterator() {
        return new CandidateIterator();
    }

    // =====================================================
    // THE PIECES
    // =====================================================

    private List<String> extras() {

        // Tried first: the numbers themselves and pairs of your own words — a password is
        // often two things you remember stuck together.
        List<String> extras = new ArrayList<>(hintNumbers);

        for (String a : hintWords) {
            for (String b : hintWords) {
                if (!a.equals(b)) {
                    extras.add(a + b);
                    extras.add(cap(a) + cap(b));
                }
            }
        }

        for (String word : hintWords) {
            for (String number : hintNumbers) {
                extras.add(word + number);
                extras.add(cap(word) + number);
            }
        }

        return extras;

    }

    private List<String> baseTokens() {

        List<String> tokens = new ArrayList<>(hintWords);

        if (includeCommon) {
            tokens.addAll(commonPasswords());
        }

        return tokens;

    }

    private List<String> suffixes() {

        List<String> suffixes = new ArrayList<>(FIXED_SUFFIXES);

        int thisYear = Year.now().getValue();

        for (int year = 1960; year <= thisYear + 1; year++) {
            suffixes.add(String.valueOf(year));
        }

        suffixes.addAll(hintNumbers);

        return suffixes;

    }

    /** The case and leet forms of a word, in order, without repeats. */
    private static List<String> forms(String word) {

        Set<String> forms = new LinkedHashSet<>();

        forms.add(word);
        forms.add(cap(word));
        forms.add(word.toUpperCase(java.util.Locale.ROOT));
        forms.add(leet(word));

        return new ArrayList<>(forms);

    }

    private static String cap(String word) {
        return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    private static String leet(String word) {

        StringBuilder out = new StringBuilder(word.length());

        for (int i = 0; i < word.length(); i++) {

            char c = Character.toLowerCase(word.charAt(i));

            out.append(switch (c) {
                case 'a' -> '@';
                case 'e' -> '3';
                case 'i' -> '1';
                case 'o' -> '0';
                case 's' -> '$';
                default -> word.charAt(i);
            });

        }

        return out.toString();

    }

    // =====================================================
    // THE STREAM
    // =====================================================

    private final class CandidateIterator implements Iterator<String> {

        private final Set<String> seen = new java.util.HashSet<>();

        private final Iterator<String> extras = extras().iterator();

        private final List<String> baseTokens = baseTokens();

        private final List<String> suffixes = suffixes();

        private int tokenIndex;

        private List<String> forms = List.of();

        private int formIndex;

        private int suffixIndex;

        private String next = advance();

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public String next() {

            if (next == null) {
                throw new NoSuchElementException();
            }

            String value = next;

            next = advance();

            return value;

        }

        private String advance() {

            while (seen.size() < MAX) {

                String candidate = raw();

                if (candidate == null) {
                    return null;
                }

                if (seen.add(candidate)) {
                    return candidate;
                }

            }

            return null;

        }

        /** The next candidate by position, before the de-duplication check. */
        private String raw() {

            if (extras.hasNext()) {
                return extras.next();
            }

            while (tokenIndex < baseTokens.size()) {

                if (formIndex >= forms.size()) {
                    forms = forms(baseTokens.get(tokenIndex));
                    formIndex = 0;
                    suffixIndex = 0;
                }

                if (formIndex < forms.size() && suffixIndex < suffixes.size()) {
                    return forms.get(formIndex) + suffixes.get(suffixIndex++);
                }

                if (suffixIndex >= suffixes.size()) {
                    formIndex++;
                    suffixIndex = 0;
                }

                if (formIndex >= forms.size()) {
                    tokenIndex++;
                    forms = List.of();
                    formIndex = 0;
                }

            }

            return null;

        }

    }

    // =====================================================
    // THE BUILT-IN COMMON-PASSWORD LIST
    // =====================================================

    private static volatile List<String> common;

    static List<String> commonPasswords() {

        List<String> loaded = common;

        if (loaded == null) {
            synchronized (Candidates.class) {
                if (common == null) {
                    common = load("/words/common-passwords.txt");
                }
                loaded = common;
            }
        }

        return loaded;

    }

    private static List<String> load(String resource) {

        List<String> list = new ArrayList<>();

        try (InputStream in = Candidates.class.getResourceAsStream(resource)) {

            if (in == null) {
                return List.of();
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

            String line;

            while ((line = reader.readLine()) != null) {
                String word = line.trim();
                if (!word.isEmpty()) {
                    list.add(word);
                }
            }

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return List.copyOf(list);

    }

    // =====================================================
    // BUILDER
    // =====================================================

    public static final class Builder {

        private final Set<String> words = new LinkedHashSet<>();

        private final Set<String> numbers = new LinkedHashSet<>();

        private boolean includeCommon = true;

        /** Splits free text into word hints (letters) and number hints (digits). */
        public Builder hints(String text) {

            if (text == null) {
                return this;
            }

            for (String token : text.split("[^\\p{Alnum}]+")) {

                if (token.isBlank()) {
                    continue;
                }

                if (token.chars().allMatch(Character::isDigit)) {
                    numbers.add(token);
                } else if (token.chars().allMatch(Character::isLetter)) {
                    words.add(token.toLowerCase(java.util.Locale.ROOT));
                } else {
                    // A mix such as "rex2010": keep the letters and the digits apart, and the whole.
                    words.add(token.toLowerCase(java.util.Locale.ROOT));
                }

            }

            return this;

        }

        public Builder word(String word) {
            if (word != null && !word.isBlank()) {
                words.add(word.trim().toLowerCase(java.util.Locale.ROOT));
            }
            return this;
        }

        public Builder number(String number) {
            if (number != null) {
                String digits = number.replaceAll("\\D", "");
                if (!digits.isEmpty()) {
                    numbers.add(digits);
                }
            }
            return this;
        }

        public Builder includeCommon(boolean include) {
            this.includeCommon = include;
            return this;
        }

        public boolean isEmpty() {
            return words.isEmpty() && numbers.isEmpty() && !includeCommon;
        }

        public Candidates build() {
            return new Candidates(new ArrayList<>(words), new ArrayList<>(numbers), includeCommon);
        }

    }

}
