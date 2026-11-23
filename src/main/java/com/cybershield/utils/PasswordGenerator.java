package com.cybershield.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes strong passwords three ways: random characters, a memorable passphrase of words,
 * or a numeric PIN. Each draws from {@link SecureRandom}. The random mode always meets
 * ValidationUtil's rules when upper, lower, digits and symbols are all on.
 */
public final class PasswordGenerator {

    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";

    private static final String NUMBERS = "0123456789";

    private static final String SYMBOLS = "!@#$%^&*()-_=+[]{}<>?/";

    // Characters easy to misread; left out when "avoid look-alikes" is on.
    private static final String LOOKALIKES = "Il1O0o";

    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile List<String> words;

    private PasswordGenerator() {
    }

    // =====================================================
    // THE ORIGINAL RANDOM GENERATOR (kept for its callers)
    // =====================================================

    /** @param length at least 8; shorter requests get 8 characters */
    public static String generate(int length) {
        return generate(length, true, true);
    }

    /**
     * Upper and lower case letters always; digits and symbols when asked for. With
     * both, the password meets ValidationUtil's rules. Lengths run from 8 to 128.
     */
    public static String generate(int length, boolean digits, boolean symbols) {
        return random(Math.max(8, length), true, true, digits, symbols, false);
    }

    // =====================================================
    // MODE 1: RANDOM CHARACTERS
    // =====================================================

    /**
     * A random password of length characters from the chosen sets. One character of each
     * chosen set is guaranteed, the rest are random, and the whole is shuffled. Length is
     * pulled into 4..128.
     *
     * @throws IllegalArgumentException if no character set is chosen
     */
    public static String random(int length, boolean upper, boolean lower, boolean digits, boolean symbols, boolean avoidLookalikes) {

        length = Math.max(4, Math.min(128, length));

        List<String> sets = new ArrayList<>();

        if (upper) {
            sets.add(filter(UPPER, avoidLookalikes));
        }
        if (lower) {
            sets.add(filter(LOWER, avoidLookalikes));
        }
        if (digits) {
            sets.add(filter(NUMBERS, avoidLookalikes));
        }
        if (symbols) {
            sets.add(filter(SYMBOLS, avoidLookalikes));
        }

        sets.removeIf(String::isEmpty);

        if (sets.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one kind of character.");
        }

        String pool = String.join("", sets);

        // If more sets are chosen than there is room for, not every set can appear.
        int guaranteed = Math.min(sets.size(), length);

        char[] password = new char[length];

        for (int i = 0; i < guaranteed; i++) {
            password[i] = pick(sets.get(i));
        }

        for (int i = guaranteed; i < length; i++) {
            password[i] = pick(pool);
        }

        shuffle(password);

        return new String(password);

    }

    /** How many passwords {@link #random} could make with these settings. */
    public static BigInteger randomCombinations(int length, boolean upper, boolean lower, boolean digits, boolean symbols, boolean avoidLookalikes) {

        length = Math.max(4, Math.min(128, length));

        int pool = 0;

        if (upper) {
            pool += filter(UPPER, avoidLookalikes).length();
        }
        if (lower) {
            pool += filter(LOWER, avoidLookalikes).length();
        }
        if (digits) {
            pool += filter(NUMBERS, avoidLookalikes).length();
        }
        if (symbols) {
            pool += filter(SYMBOLS, avoidLookalikes).length();
        }

        return pool == 0 ? BigInteger.ZERO : BigInteger.valueOf(pool).pow(length);

    }

    // =====================================================
    // MODE 2: A MEMORABLE PASSPHRASE
    // =====================================================

    /**
     * A passphrase of count words joined by separator, e.g. "river-amber-koala-stone".
     * Each word is chosen independently from the built-in list. capitalize upper-cases
     * each word's first letter; addNumber appends a two-digit number. count is pulled into
     * 2..12.
     */
    public static String passphrase(int count, char separator, boolean capitalize, boolean addNumber) {

        count = Math.max(2, Math.min(12, count));

        List<String> list = words();

        StringBuilder phrase = new StringBuilder();

        for (int i = 0; i < count; i++) {

            if (i > 0) {
                phrase.append(separator);
            }

            String word = list.get(RANDOM.nextInt(list.size()));

            phrase.append(capitalize ? Character.toUpperCase(word.charAt(0)) + word.substring(1) : word);

        }

        if (addNumber) {
            phrase.append(separator).append(10 + RANDOM.nextInt(90));
        }

        return phrase.toString();

    }

    /** How many passphrases {@link #passphrase} could make (words only; a two-digit number multiplies by 90). */
    public static BigInteger passphraseCombinations(int count, boolean addNumber) {

        count = Math.max(2, Math.min(12, count));

        BigInteger combos = BigInteger.valueOf(words().size()).pow(count);

        return addNumber ? combos.multiply(BigInteger.valueOf(90)) : combos;

    }

    // =====================================================
    // MODE 3: A NUMERIC PIN
    // =====================================================

    /** A PIN of length digits (pulled into 3..32). */
    public static String pin(int length) {

        length = Math.max(3, Math.min(32, length));

        char[] pin = new char[length];

        for (int i = 0; i < length; i++) {
            pin[i] = NUMBERS.charAt(RANDOM.nextInt(NUMBERS.length()));
        }

        return new String(pin);

    }

    public static BigInteger pinCombinations(int length) {

        length = Math.max(3, Math.min(32, length));

        return BigInteger.TEN.pow(length);

    }

    // =====================================================
    // HELPERS
    // =====================================================

    /** The strength in bits (log2) of a space of this many combinations. */
    public static double entropyBits(BigInteger combinations) {

        if (combinations.signum() <= 0) {
            return 0;
        }

        return combinations.bitLength() - 1 + Math.log(combinations.doubleValue() / BigInteger.ONE.shiftLeft(combinations.bitLength() - 1).doubleValue()) / Math.log(2);

    }

    /** The built-in passphrase words. */
    public static List<String> words() {

        List<String> loaded = words;

        if (loaded == null) {
            synchronized (PasswordGenerator.class) {
                if (words == null) {
                    words = loadWords();
                }
                loaded = words;
            }
        }

        return loaded;

    }

    private static List<String> loadWords() {

        List<String> list = new ArrayList<>();

        try (InputStream in = PasswordGenerator.class.getResourceAsStream("/words/passphrase-words.txt")) {

            if (in == null) {
                throw new IOException("passphrase-words.txt not found.");
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

    private static String filter(String characters, boolean avoidLookalikes) {

        if (!avoidLookalikes) {
            return characters;
        }

        StringBuilder kept = new StringBuilder();

        for (int i = 0; i < characters.length(); i++) {
            if (LOOKALIKES.indexOf(characters.charAt(i)) < 0) {
                kept.append(characters.charAt(i));
            }
        }

        return kept.toString();

    }

    private static char pick(String characters) {
        return characters.charAt(RANDOM.nextInt(characters.length()));
    }

    private static void shuffle(char[] array) {

        for (int i = array.length - 1; i > 0; i--) {

            int j = RANDOM.nextInt(i + 1);

            char swap = array[i];
            array[i] = array[j];
            array[j] = swap;

        }

    }

}
