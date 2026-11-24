package com.cybershield.backup;

import com.cybershield.logger.AppLogger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Reads the list of programs installed on this PC, from the places Windows records them in
 * its own registry (read-only). It is offered as an optional text file inside a backup, so
 * you have a record of what was installed. It only reads; it installs and changes nothing.
 *
 * <p>The reading ({@link #read}) and the parsing ({@link #parse}) are kept apart so the
 * parsing is tested on sample output.
 */
public final class InstalledApps {

    private static final String SCRIPT = """
            $ErrorActionPreference = 'SilentlyContinue'
            $paths = @(
              'HKLM:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*',
              'HKLM:\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*',
              'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*'
            )
            foreach ($p in $paths) {
              Get-ItemProperty $p | Where-Object { $_.DisplayName } | ForEach-Object {
                Write-Output ("{0}|{1}" -f $_.DisplayName, $_.DisplayVersion)
              }
            }
            """;

    private InstalledApps() {
    }

    /** Reads the installed programs, sorted and without repeats. Empty if it couldn't be read. */
    public static List<String> read() {
        return parse(run());
    }

    /** Turns the script's {@code name|version} output into sorted, de-duplicated display lines. */
    static List<String> parse(String output) {

        Set<String> lines = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        if (output != null) {
            for (String line : output.split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                String[] p = line.split("\\|", -1);
                String name = p[0].trim();
                if (name.isEmpty()) {
                    continue;
                }
                String version = p.length > 1 ? p[1].trim() : "";
                lines.add(version.isEmpty() ? name : name + "  —  " + version);
            }
        }

        // de-dup once more on the display text while keeping the sorted order
        return new ArrayList<>(new LinkedHashSet<>(lines));
    }

    /** The whole list as the text that goes into the backup. */
    public static String asText(List<String> apps) {
        StringBuilder text = new StringBuilder("Installed programs on this PC (" + apps.size() + ")\r\n");
        text.append("Read from the Windows registry by CyberShield. Read-only.\r\n\r\n");
        for (String app : apps) {
            text.append(app).append("\r\n");
        }
        return text.toString();
    }

    private static String run() {

        ExecutorService reader = null;
        try {
            String encoded = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));

            Process process = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-EncodedCommand", encoded)
                    .redirectErrorStream(true)
                    .start();

            reader = Executors.newSingleThreadExecutor();
            Future<String> output = reader.submit(() -> {
                try (InputStream in = process.getInputStream()) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            });

            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                output.cancel(true);
                AppLogger.warn("Backup: reading the installed-apps list timed out.");
                return "";
            }

            return output.get(5, TimeUnit.SECONDS);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            AppLogger.warn("Backup: couldn't read the installed-apps list (" + e.getClass().getSimpleName() + ")");
            return "";
        } finally {
            if (reader != null) {
                reader.shutdownNow();
            }
        }
    }

}
