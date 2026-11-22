package com.cybershield.notes;

/**
 * A note as the screen sees it, decrypted. In the database the title and text are
 * encrypted with the vault key; this object exists only in memory while the vault is open.
 *
 * @param id        0 for a note not saved yet
 * @param updatedAt UTC time of the last save, as SQLite writes it
 */
public record Note(int id, String title, String text, String createdAt, String updatedAt) {

    public Note withText(String newTitle, String newText) {
        return new Note(id, newTitle, newText, createdAt, updatedAt);
    }

    /** The title to show in lists: the title, or the first line of the text, or "Untitled". */
    public String displayTitle() {

        if (title != null && !title.isBlank()) {
            return title.strip();
        }

        String firstLine = text == null ? "" : text.strip().lines().findFirst().orElse("");

        return firstLine.isBlank() ? "Untitled" : firstLine.length() > 60 ? firstLine.substring(0, 60) + "…" : firstLine;

    }

    /** True if the search words all appear in the title or text, ignoring case. */
    public boolean matches(String query) {

        if (query == null || query.isBlank()) {
            return true;
        }

        String haystack = ((title == null ? "" : title) + "\n" + (text == null ? "" : text)).toLowerCase();

        for (String word : query.toLowerCase().strip().split("\\s+")) {
            if (!haystack.contains(word)) {
                return false;
            }
        }

        return true;

    }

}
