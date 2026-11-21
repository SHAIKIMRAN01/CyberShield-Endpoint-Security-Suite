package com.cybershield.files;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * The files and folders picked for protection, possibly from many different
 * places. Keeps the list tidy: newest first, no duplicates, and nothing that is
 * already inside another selected folder.
 */
public final class FileSelection {

    public enum Change {
        /** Added as a new entry. */
        ADDED,
        /** Not added: it is already selected, or sits inside a selected folder. */
        ALREADY_INCLUDED,
        /** Added, and selected items inside it were merged into it. */
        ADDED_AND_MERGED
    }

    /**
     * @param coveredBy the selected folder that already includes it (for ALREADY_INCLUDED)
     * @param merged    how many selected items it replaced (for ADDED_AND_MERGED)
     */
    public record AddResult(Change change, Path coveredBy, int merged) {
    }

    private final LinkedList<Path> entries = new LinkedList<>();

    public AddResult add(Path path) {

        Path candidate = normalize(path);

        Path covering = includedBy(candidate);

        if (covering != null) {
            return new AddResult(Change.ALREADY_INCLUDED, covering, 0);
        }

        int merged = 0;

        Iterator<Path> it = entries.iterator();

        while (it.hasNext()) {

            if (it.next().startsWith(candidate)) {
                it.remove();
                merged++;
            }

        }

        entries.addFirst(candidate);

        return new AddResult(merged > 0 ? Change.ADDED_AND_MERGED : Change.ADDED, null, merged);

    }

    public boolean remove(Path path) {
        return entries.remove(normalize(path));
    }

    public void clear() {
        entries.clear();
    }

    /** Newest first. */
    public List<Path> entries() {
        return List.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** True if this exact path was picked. */
    public boolean contains(Path path) {
        return entries.contains(normalize(path));
    }

    /** The selected entry that is this path or a folder containing it, or null. */
    public Path includedBy(Path path) {

        Path candidate = normalize(path);

        for (Path entry : entries) {

            if (candidate.startsWith(entry)) {
                return entry;
            }

        }

        return null;

    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

}
