package com.cybershield.scan;

import com.cybershield.logger.AppLogger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Reads the real Windows security settings for {@link SecurityCheck}. It runs one short,
 * read-only PowerShell script and reads a few registry values — it never changes a
 * setting. Anything it can't read (often because it needs an administrator) is reported
 * as "couldn't check", never guessed.
 *
 * <p>The reading of the machine ({@link #read}) and the turning of its output into facts
 * ({@link #parse}) are kept apart, so the parsing can be tested on sample output without a
 * Windows box.
 */
public final class WindowsFacts implements SecurityCheck.Facts {

    /**
     * One read-only PowerShell pass. Every section swallows its own errors and prints
     * {@code key=value} lines (or {@code key=unknown}) so a missing section never breaks
     * the rest.
     */
    private static final String SCRIPT = """
            $ErrorActionPreference = 'SilentlyContinue'
            function Emit($k,$v){ Write-Output ("{0}={1}" -f $k,$v) }

            try {
              $av = Get-CimInstance -Namespace root/SecurityCenter2 -ClassName AntiVirusProduct
              $enabled = $false; $upToDate = $false; $any = $false
              foreach ($p in $av) {
                $any = $true
                $hex = ([Convert]::ToString([int]$p.productState,16)).PadLeft(6,'0')
                $scanner = $hex.Substring(2,2)
                $sig = $hex.Substring(4,2)
                if ($scanner -eq '10' -or $scanner -eq '11') { $enabled = $true }
                if ($sig -eq '00') { $upToDate = $true }
              }
              if ($any) {
                Emit 'antivirus' ($enabled.ToString().ToLower())
                Emit 'antivirusUpToDate' ($upToDate.ToString().ToLower())
              } else { Emit 'antivirus' 'unknown'; Emit 'antivirusUpToDate' 'unknown' }
            } catch { Emit 'antivirus' 'unknown'; Emit 'antivirusUpToDate' 'unknown' }

            try {
              $fw = Get-NetFirewallProfile -ErrorAction Stop
              $allOn = $true
              foreach ($f in $fw) { if (-not $f.Enabled) { $allOn = $false } }
              Emit 'firewall' ($allOn.ToString().ToLower())
            } catch { Emit 'firewall' 'unknown' }

            try {
              $bl = Get-BitLockerVolume -MountPoint $env:SystemDrive -ErrorAction Stop
              if ($bl.ProtectionStatus -eq 'On') { Emit 'driveEncryption' 'true' }
              elseif ($bl.ProtectionStatus -eq 'Off') { Emit 'driveEncryption' 'false' }
              else { Emit 'driveEncryption' 'unknown' }
            } catch { Emit 'driveEncryption' 'unknown' }

            try {
              $w = Get-ItemProperty 'HKLM:\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Winlogon' -ErrorAction Stop
              if ($w.AutoAdminLogon -eq '1') { Emit 'autoLogin' 'true' } else { Emit 'autoLogin' 'false' }
            } catch { Emit 'autoLogin' 'unknown' }

            try {
              $d = Get-ItemProperty 'HKCU:\\Control Panel\\Desktop' -ErrorAction Stop
              if ($d.ScreenSaveActive -eq '1' -and $d.ScreenSaverIsSecure -eq '1') { Emit 'locksOnWake' 'true' }
              elseif ($d.ScreenSaveActive -eq '1' -and $d.ScreenSaverIsSecure -eq '0') { Emit 'locksOnWake' 'false' }
              else { Emit 'locksOnWake' 'unknown' }
            } catch { Emit 'locksOnWake' 'unknown' }

            function Access($name,$key){
              try {
                $path = 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\CapabilityAccessManager\\ConsentStore\\' + $key
                $v = (Get-ItemProperty $path -ErrorAction Stop).Value
                if ($v) { Emit $name $v } else { Emit $name 'unknown' }
              } catch { Emit $name 'unknown' }
            }
            Access 'camera' 'webcam'
            Access 'microphone' 'microphone'
            Access 'location' 'location'

            $items = New-Object System.Collections.Generic.List[string]
            try {
              (Get-ItemProperty 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run' -ErrorAction Stop).PSObject.Properties |
                Where-Object { $_.Name -notmatch '^PS' } | ForEach-Object { $items.Add($_.Name) }
            } catch {}
            try {
              (Get-ItemProperty 'HKLM:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run' -ErrorAction Stop).PSObject.Properties |
                Where-Object { $_.Name -notmatch '^PS' } | ForEach-Object { $items.Add($_.Name) }
            } catch {}
            try {
              $sf = Join-Path $env:APPDATA 'Microsoft\\Windows\\Start Menu\\Programs\\Startup'
              Get-ChildItem -Path $sf -File -ErrorAction Stop |
                ForEach-Object { $items.Add([System.IO.Path]::GetFileNameWithoutExtension($_.Name)) }
            } catch {}
            Emit 'startup' ($items -join '|')
            """;

    private final Map<String, String> values;

    private WindowsFacts(Map<String, String> values) {
        this.values = values;
    }

    /** Reads this PC's settings. On any failure every fact comes back as "couldn't check". */
    public static WindowsFacts read() {
        return parse(run());
    }

    /** Turns the script's {@code key=value} output into facts. Package-private so tests can drive it. */
    static WindowsFacts parse(String output) {

        Map<String, String> values = new HashMap<>();

        if (output != null) {
            for (String line : output.split("\\R")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        }

        return new WindowsFacts(values);
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
                AppLogger.warn("Security scan: reading Windows settings timed out.");
                return "";
            }

            return output.get(5, TimeUnit.SECONDS);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            AppLogger.warn("Security scan: couldn't read Windows settings (" + e.getClass().getSimpleName() + ")");
            return "";
        } finally {
            if (reader != null) {
                reader.shutdownNow();
            }
        }
    }

    private Optional<Boolean> bool(String key) {
        String v = values.get(key);
        if ("true".equalsIgnoreCase(v)) {
            return Optional.of(true);
        }
        if ("false".equalsIgnoreCase(v)) {
            return Optional.of(false);
        }
        return Optional.empty();
    }

    private Optional<String> text(String key) {
        String v = values.get(key);
        if (v == null || v.isBlank() || "unknown".equalsIgnoreCase(v)) {
            return Optional.empty();
        }
        return Optional.of(v);
    }

    @Override
    public Optional<Boolean> antivirusEnabled() {
        return bool("antivirus");
    }

    @Override
    public Optional<Boolean> antivirusUpToDate() {
        return bool("antivirusUpToDate");
    }

    @Override
    public Optional<Boolean> firewallOn() {
        return bool("firewall");
    }

    @Override
    public Optional<Boolean> driveEncryptionOn() {
        return bool("driveEncryption");
    }

    @Override
    public Optional<Boolean> autoLoginOn() {
        return bool("autoLogin");
    }

    @Override
    public Optional<Boolean> locksOnWake() {
        return bool("locksOnWake");
    }

    @Override
    public Optional<String> cameraAccess() {
        return text("camera");
    }

    @Override
    public Optional<String> microphoneAccess() {
        return text("microphone");
    }

    @Override
    public Optional<String> locationAccess() {
        return text("location");
    }

    @Override
    public List<String> startupItems() {
        String v = values.get("startup");
        if (v == null || v.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : v.split("\\|")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }

}
