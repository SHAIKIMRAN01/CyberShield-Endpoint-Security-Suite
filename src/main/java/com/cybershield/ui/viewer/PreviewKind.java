package com.cybershield.ui.viewer;

import java.util.Map;
import java.util.Set;

/** Which viewer a file opens in, decided by its extension, and how big it may be. */
public enum PreviewKind {

    IMAGE(100L * 1024 * 1024),

    TEXT(10L * 1024 * 1024),

    PDF(200L * 1024 * 1024),

    // Media is held in memory outside the Java heap, which by default may grow
    // as large as the heap itself; leave room for everything else.
    MEDIA(Math.min(1024L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 2)),

    NONE(0);

    private static final Set<String> IMAGES = Set.of("jpg", "jpeg", "png", "gif", "bmp");

    private static final Set<String> TEXTS = Set.of(
            "txt", "text", "md", "markdown", "csv", "tsv", "log", "json", "xml", "html", "htm",
            "css", "js", "ts", "java", "py", "c", "cpp", "h", "hpp", "cs", "go", "rs", "kt", "kts",
            "swift", "rb", "php", "sql", "yml", "yaml", "ini", "cfg", "conf", "properties", "toml",
            "bat", "cmd", "ps1", "sh", "gradle", "srt", "vtt", "tex", "r", "lua", "pl", "scala", "dart");

    // The types JavaFX's player accepts.
    private static final Map<String, String> MEDIA_TYPES = Map.of(
            "mp4", "video/mp4",
            "m4v", "video/x-m4v",
            "m4a", "audio/x-m4a",
            "mp3", "audio/mpeg",
            "wav", "audio/x-wav",
            "aif", "audio/x-aiff",
            "aiff", "audio/x-aiff");

    private final long sizeLimit;

    PreviewKind(long sizeLimit) {
        this.sizeLimit = sizeLimit;
    }

    /** The largest file this viewer will decrypt into memory. */
    public long sizeLimit() {
        return sizeLimit;
    }

    public static PreviewKind of(String extension) {

        if (IMAGES.contains(extension)) {
            return IMAGE;
        }

        if (TEXTS.contains(extension)) {
            return TEXT;
        }

        if (extension.equals("pdf")) {
            return PDF;
        }

        if (MEDIA_TYPES.containsKey(extension)) {
            return MEDIA;
        }

        return NONE;

    }

    /** The media type JavaFX expects for this extension, or null if it can't play it. */
    public static String mediaType(String extension) {
        return MEDIA_TYPES.get(extension);
    }

    public static boolean isVideo(String extension) {
        return extension.equals("mp4") || extension.equals("m4v");
    }

}
