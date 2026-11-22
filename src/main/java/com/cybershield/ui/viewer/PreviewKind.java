package com.cybershield.ui.viewer;

import java.util.Map;
import java.util.Set;

/** Which viewer a file opens in, decided by its extension, and how big it may be. */
public enum PreviewKind {

    IMAGE(100L * 1024 * 1024),

    TEXT(10L * 1024 * 1024),

    PDF(200L * 1024 * 1024),

    // Media normally streams from the store a piece at a time, at any size. Only the
    // fallback player (when FFmpeg can't run) holds it in memory, outside the Java heap,
    // which by default may grow as large as the heap itself; leave room for everything else.
    MEDIA(Math.min(1024L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 2)),

    NONE(0);

    private static final Set<String> IMAGES = Set.of("jpg", "jpeg", "png", "gif", "bmp");

    private static final Set<String> TEXTS = Set.of(
            "txt", "text", "md", "markdown", "csv", "tsv", "log", "json", "xml", "html", "htm",
            "css", "js", "ts", "java", "py", "c", "cpp", "h", "hpp", "cs", "go", "rs", "kt", "kts",
            "swift", "rb", "php", "sql", "yml", "yaml", "ini", "cfg", "conf", "properties", "toml",
            "bat", "cmd", "ps1", "sh", "gradle", "srt", "vtt", "tex", "r", "lua", "pl", "scala", "dart");

    // Video FFmpeg plays: phone videos (MP4, MOV, HEVC included), MKV, AVI, WebM and more.
    private static final Set<String> VIDEOS = Set.of(
            "mp4", "m4v", "mov", "mkv", "webm", "avi", "wmv", "flv", "3gp", "3g2",
            "mpg", "mpeg", "ts", "m2ts", "mts", "ogv", "vob");

    private static final Set<String> SOUNDS = Set.of(
            "mp3", "m4a", "wav", "aif", "aiff", "flac", "ogg", "oga", "opus", "aac", "wma", "mka", "amr");

    // The types JavaFX's player accepts: only used when FFmpeg can't run.
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

    /** True if this viewer reads the file a piece at a time, so its size doesn't matter. */
    public boolean streams() {
        return this == MEDIA;
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

        if (VIDEOS.contains(extension) || SOUNDS.contains(extension)) {
            return MEDIA;
        }

        return NONE;

    }

    /** The media type JavaFX expects for this extension, or null if it can't play it. */
    public static String mediaType(String extension) {
        return MEDIA_TYPES.get(extension);
    }

    public static boolean isVideo(String extension) {
        return VIDEOS.contains(extension);
    }

}
