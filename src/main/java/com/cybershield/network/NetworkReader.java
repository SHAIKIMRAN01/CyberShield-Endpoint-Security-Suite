package com.cybershield.network;

import com.cybershield.logger.AppLogger;
import com.cybershield.network.NetworkAnalysis.Connection;
import com.cybershield.network.NetworkAnalysis.Protocol;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Takes a read-only snapshot of how the network is being used right now: every active TCP
 * connection and listening port, the UDP endpoints apps are using, and the names Windows
 * has looked up recently — each tied to the program behind it. It runs one short PowerShell
 * pass and changes nothing, reads no traffic, and needs no administrator.
 *
 * <p>The reading of the machine ({@link #read}) and the parsing of its output
 * ({@link #parse}) are separate, so the parsing is tested on sample output.
 */
public final class NetworkReader {

    /** What a snapshot holds: the live connections and the recent DNS look-ups. */
    public record Snapshot(List<Connection> connections, List<Dns> dnsCache) {

        public boolean isEmpty() {
            return connections.isEmpty() && dnsCache.isEmpty();
        }

    }

    /** One recent name look-up from the DNS cache: the name and what it resolved to. */
    public record Dns(String name, String data) {
    }

    /**
     * One read-only PowerShell pass. It maps each connection to the program that owns it,
     * and prints pipe-separated lines: {@code TCP|state|local|lport|remote|rport|pid|app},
     * {@code UDP|Listen|local|lport|||pid|app} and {@code DNS|name|data}.
     */
    private static final String SCRIPT = """
            $ErrorActionPreference = 'SilentlyContinue'
            $names = @{}
            Get-Process | ForEach-Object { $names[[int]$_.Id] = $_.ProcessName }
            function Emit($s){ Write-Output $s }

            try {
              Get-NetTCPConnection |
                Where-Object { $_.State -eq 'Established' -or $_.State -eq 'Listen' } |
                Select-Object -First 500 | ForEach-Object {
                  $op = [int]$_.OwningProcess
                  $app = $names[$op]
                  if (-not $app) { $app = 'pid ' + $op }
                  Emit ("TCP|{0}|{1}|{2}|{3}|{4}|{5}|{6}" -f $_.State,$_.LocalAddress,$_.LocalPort,$_.RemoteAddress,$_.RemotePort,$op,$app)
                }
            } catch {}

            try {
              Get-NetUDPEndpoint | Select-Object -First 200 | ForEach-Object {
                $op = [int]$_.OwningProcess
                $app = $names[$op]
                if (-not $app) { $app = 'pid ' + $op }
                Emit ("UDP|Listen|{0}|{1}|||{2}|{3}" -f $_.LocalAddress,$_.LocalPort,$op,$app)
              }
            } catch {}

            try {
              Get-DnsClientCache | Select-Object -First 300 | ForEach-Object {
                Emit ("DNS|{0}|{1}" -f $_.Entry,$_.Data)
              }
            } catch {}
            """;

    private NetworkReader() {
    }

    /** Reads this PC's live network use. On any failure it returns an empty snapshot. */
    public static Snapshot read() {
        return parse(run());
    }

    /** Turns the script's pipe-separated output into a snapshot. Package-private for tests. */
    static Snapshot parse(String output) {

        List<Connection> connections = new ArrayList<>();
        List<Dns> dns = new ArrayList<>();

        if (output != null) {
            for (String line : output.split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                String[] p = line.split("\\|", -1);
                switch (p[0]) {
                    case "TCP", "UDP" -> {
                        if (p.length >= 8) {
                            connections.add(new Connection(
                                    "UDP".equals(p[0]) ? Protocol.UDP : Protocol.TCP,
                                    p[1], p[2], number(p[3]), p[4], number(p[5]), number(p[6]), p[7]));
                        }
                    }
                    case "DNS" -> {
                        if (p.length >= 3 && !p[1].isBlank()) {
                            dns.add(new Dns(p[1], p[2]));
                        }
                    }
                    default -> {
                        // a line we don't recognise is ignored
                    }
                }
            }
        }

        return new Snapshot(connections, dns);
    }

    private static int number(String s) {
        try {
            return s == null || s.isBlank() ? 0 : Integer.parseInt(s.trim());
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
                AppLogger.warn("Network Analyzer: reading connections timed out.");
                return "";
            }

            return output.get(5, TimeUnit.SECONDS);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            AppLogger.warn("Network Analyzer: couldn't read connections (" + e.getClass().getSimpleName() + ")");
            return "";
        } finally {
            if (reader != null) {
                reader.shutdownNow();
            }
        }
    }

}
