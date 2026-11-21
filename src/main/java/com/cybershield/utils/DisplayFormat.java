package com.cybershield.utils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Formats sizes and times for the screens. */
public final class DisplayFormat {

    // SQLite's CURRENT_TIMESTAMP is UTC in this format.
    private static final DateTimeFormatter DB_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    private static final String[] UNITS = {"KB", "MB", "GB", "TB"};

    private DisplayFormat() {
    }

    /** Converts SQLite's UTC timestamp to local time, e.g. "21 Nov 2026, 10:39". */
    public static String timestamp(String utcTimestamp) {

        if (utcTimestamp == null || utcTimestamp.isBlank()) {
            return "";
        }

        try {

            return LocalDateTime.parse(utcTimestamp, DB_TIME)
                    .atOffset(ZoneOffset.UTC)
                    .atZoneSameInstant(ZoneId.systemDefault())
                    .format(DISPLAY_TIME);

        } catch (DateTimeParseException e) {

            return utcTimestamp;

        }

    }

    public static String dateTime(long epochMillis) {

        return Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault())
                .format(DISPLAY_TIME);

    }

    /** 532 B, 4.2 KB, 18 MB, 1.3 GB... */
    public static String size(long bytes) {

        if (bytes < 1024) {
            return bytes + " B";
        }

        double value = bytes;

        int unit = -1;

        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < UNITS.length - 1);

        return String.format(Locale.ROOT, value < 10 ? "%.1f %s" : "%.0f %s", value, UNITS[unit]);

    }

}
