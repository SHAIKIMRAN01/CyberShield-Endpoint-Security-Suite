package com.cybershield.scan;

import com.cybershield.logger.AppLogger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Lists the drives attached to this PC and says which are external — a USB stick, an SD card,
 * an external disk, or a phone that shows up as a drive — so the Security Scan can look through
 * one for files worth a second look. It only reads the list of drives and changes nothing.
 *
 * <p>The reading ({@link #read}) and the parsing ({@link #parse}) are kept apart so the
 * parsing is tested on sample output.
 */
public final class Drives {

    /** What kind of drive it is. A phone in file-transfer (MTP) mode has no drive letter, so it isn't listed. */
    public enum Kind {

        REMOVABLE("Removable drive"),
        USB("USB drive"),
        FIXED("Internal drive"),
        NETWORK("Network drive"),
        OPTICAL("Disc drive"),
        UNKNOWN("Drive");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

    }

    /** One drive: its letter (like {@code E:}), its label, kind, and size. */
    public record Drive(String letter, String label, Kind kind, long bytes, long free) {

        /** True for the drives the device scan offers — a USB stick, SD card, external disk or drive-mounted phone. */
        public boolean isExternal() {
            return kind == Kind.REMOVABLE || kind == Kind.USB;
        }

        /** The drive's root folder, e.g. {@code E:\}. */
        public Path root() {
            return Path.of(letter + "\\");
        }

        /** A short name for a list: "E: — Kingston (USB drive)". */
        public String display() {
            String name = label == null || label.isBlank() ? kind.label() : label;
            return letter + "  —  " + name + " (" + kind.label() + ")";
        }

    }

    private static final String SCRIPT = """
            $ErrorActionPreference = 'SilentlyContinue'
            function Emit($s){ Write-Output $s }
            $usb = @{}
            try {
              Get-Disk | Where-Object { $_.BusType -eq 'USB' } | ForEach-Object {
                Get-Partition -DiskNumber $_.Number | Where-Object { $_.DriveLetter } |
                  ForEach-Object { $usb[[string]$_.DriveLetter] = $true }
              }
            } catch {}
            Get-CimInstance Win32_LogicalDisk | ForEach-Object {
              $letter = $_.DeviceID.TrimEnd(':')
              $dt = [int]$_.DriveType
              $kind = 'Unknown'
              if ($dt -eq 2) { $kind = 'Removable' }
              elseif ($dt -eq 3) { if ($usb[$letter]) { $kind = 'USB' } else { $kind = 'Fixed' } }
              elseif ($dt -eq 4) { $kind = 'Network' }
              elseif ($dt -eq 5) { $kind = 'Optical' }
              Emit ("DRIVE|{0}|{1}|{2}|{3}|{4}" -f $_.DeviceID, $_.VolumeName, $kind, $_.Size, $_.FreeSpace)
            }
            """;

    private Drives() {
    }

    /** All drives on this PC. Empty if the list couldn't be read. */
    public static List<Drive> read() {
        return parse(run());
    }

    /** Only the external drives — what the device scan offers. */
    public static List<Drive> external() {
        List<Drive> external = new ArrayList<>();
        for (Drive drive : read()) {
            if (drive.isExternal()) {
                external.add(drive);
            }
        }
        return external;
    }

    /** Turns the {@code DRIVE|letter|label|kind|bytes|free} output into drives. Package-private for tests. */
    static List<Drive> parse(String output) {

        List<Drive> drives = new ArrayList<>();

        if (output != null) {
            for (String line : output.split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                String[] p = line.split("\\|", -1);
                if (p.length >= 6 && "DRIVE".equals(p[0]) && !p[1].isBlank()) {
                    drives.add(new Drive(p[1].trim(), p[2].trim(), kind(p[3]), number(p[4]), number(p[5])));
                }
            }
        }

        return drives;
    }

    private static Kind kind(String text) {
        return switch (text == null ? "" : text.trim()) {
            case "Removable" -> Kind.REMOVABLE;
            case "USB" -> Kind.USB;
            case "Fixed" -> Kind.FIXED;
            case "Network" -> Kind.NETWORK;
            case "Optical" -> Kind.OPTICAL;
            default -> Kind.UNKNOWN;
        };
    }

    private static long number(String s) {
        try {
            return s == null || s.isBlank() ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
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
                AppLogger.warn("Security scan: listing drives timed out.");
                return "";
            }

            return output.get(5, TimeUnit.SECONDS);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            AppLogger.warn("Security scan: couldn't list drives (" + e.getClass().getSimpleName() + ")");
            return "";
        } finally {
            if (reader != null) {
                reader.shutdownNow();
            }
        }
    }

}
