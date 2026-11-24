package com.cybershield.network;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Makes sense of a single network connection in plain words: which well-known service a
 * port belongs to, whether the other end is this computer, your local network or out on
 * the Internet, and a one-line explanation of what the connection is doing.
 *
 * <p>Everything here is pure — it works on the facts of a {@link Connection} and touches
 * nothing outside — so it is tested on made-up connections. The reading of the real
 * machine lives in {@link NetworkReader}. This describes connections; it does not judge
 * them as safe or dangerous, and it never reads the contents of any traffic.
 */
public final class NetworkAnalysis {

    public enum Protocol { TCP, UDP }

    /** Where the other end of a connection sits. */
    public enum Scope {

        LOOPBACK("This computer"),
        PRIVATE("Local network"),
        LINK_LOCAL("Local network"),
        MULTICAST("Local network group"),
        INTERNET("The Internet"),
        ANY("All interfaces"),
        UNKNOWN("Unknown");

        private final String label;

        Scope(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

    }

    /** One connection or listening endpoint, as read from Windows. */
    public record Connection(Protocol protocol, String state, String localAddress, int localPort,
                             String remoteAddress, int remotePort, int pid, String app) {

        public boolean isEstablished() {
            return "Established".equalsIgnoreCase(state);
        }

    }

    private static final Map<Integer, String> SERVICES = new HashMap<>();

    static {
        SERVICES.put(20, "FTP data");
        SERVICES.put(21, "FTP");
        SERVICES.put(22, "SSH");
        SERVICES.put(23, "Telnet");
        SERVICES.put(25, "email (SMTP)");
        SERVICES.put(53, "DNS");
        SERVICES.put(67, "DHCP");
        SERVICES.put(68, "DHCP");
        SERVICES.put(80, "web (HTTP)");
        SERVICES.put(88, "Kerberos");
        SERVICES.put(110, "email (POP3)");
        SERVICES.put(123, "time (NTP)");
        SERVICES.put(135, "Windows RPC");
        SERVICES.put(137, "Windows networking");
        SERVICES.put(138, "Windows networking");
        SERVICES.put(139, "Windows networking");
        SERVICES.put(143, "email (IMAP)");
        SERVICES.put(161, "SNMP");
        SERVICES.put(389, "LDAP");
        SERVICES.put(443, "secure web (HTTPS)");
        SERVICES.put(445, "Windows file sharing (SMB)");
        SERVICES.put(465, "email (SMTP over TLS)");
        SERVICES.put(587, "email (SMTP)");
        SERVICES.put(636, "secure LDAP");
        SERVICES.put(993, "email (IMAP over TLS)");
        SERVICES.put(995, "email (POP3 over TLS)");
        SERVICES.put(1433, "SQL Server");
        SERVICES.put(1900, "device discovery (SSDP/UPnP)");
        SERVICES.put(3306, "MySQL");
        SERVICES.put(3389, "Remote Desktop");
        SERVICES.put(4369, "Erlang port mapper");
        SERVICES.put(5040, "Windows service");
        SERVICES.put(5353, "device discovery (mDNS)");
        SERVICES.put(5355, "name lookup (LLMNR)");
        SERVICES.put(5432, "PostgreSQL");
        SERVICES.put(5672, "message queue (AMQP)");
        SERVICES.put(6379, "Redis");
        SERVICES.put(8080, "web (HTTP, alternate)");
        SERVICES.put(8443, "secure web (HTTPS, alternate)");
        SERVICES.put(27017, "MongoDB");
    }

    private NetworkAnalysis() {
    }

    /** A friendly service name for a well-known port, or {@code null} if it isn't one. */
    public static String service(int port) {
        return SERVICES.get(port);
    }

    /** A service name for a port, falling back to "port N" when it isn't well known. */
    public static String serviceLabel(int port) {
        String service = SERVICES.get(port);
        return service != null ? service : "port " + port;
    }

    /** Works out where an address sits, for both IPv4 and IPv6 (a %zone suffix is ignored). */
    public static Scope scopeOf(String address) {

        if (address == null || address.isBlank()) {
            return Scope.UNKNOWN;
        }

        String ip = address.trim().toLowerCase(Locale.ROOT);

        int zone = ip.indexOf('%');
        if (zone >= 0) {
            ip = ip.substring(0, zone);
        }

        if (ip.equals("0.0.0.0") || ip.equals("::")) {
            return Scope.ANY;
        }

        return ip.indexOf(':') >= 0 ? ipv6Scope(ip) : ipv4Scope(ip);
    }

    private static Scope ipv4Scope(String ip) {

        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return Scope.UNKNOWN;
        }

        int a = number(parts[0]);
        int b = number(parts[1]);
        if (a < 0 || b < 0) {
            return Scope.UNKNOWN;
        }

        if (a == 127) {
            return Scope.LOOPBACK;
        }
        if (a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)) {
            return Scope.PRIVATE;
        }
        if (a == 169 && b == 254) {
            return Scope.LINK_LOCAL;
        }
        if (a >= 224 && a <= 239) {
            return Scope.MULTICAST;
        }
        if (a == 0) {
            return Scope.ANY;
        }
        return Scope.INTERNET;
    }

    private static Scope ipv6Scope(String ip) {

        if (ip.equals("::1")) {
            return Scope.LOOPBACK;
        }
        if (ip.startsWith("::ffff:") && ip.indexOf('.') >= 0) {
            return ipv4Scope(ip.substring(ip.lastIndexOf(':') + 1));
        }
        if (ip.startsWith("fe8") || ip.startsWith("fe9") || ip.startsWith("fea") || ip.startsWith("feb")) {
            return Scope.LINK_LOCAL;
        }
        if (ip.startsWith("fc") || ip.startsWith("fd")) {
            return Scope.PRIVATE;
        }
        if (ip.startsWith("ff")) {
            return Scope.MULTICAST;
        }
        if (ip.startsWith("2") || ip.startsWith("3")) {
            return Scope.INTERNET;
        }
        return Scope.UNKNOWN;
    }

    /** One plain sentence describing what a connection is doing. */
    public static String explain(Connection c) {

        if (c.protocol() == Protocol.TCP && c.isEstablished()) {
            Scope scope = scopeOf(c.remoteAddress());
            return c.app() + " is connected to " + c.remoteAddress() + " on port " + c.remotePort()
                    + " (" + serviceLabel(c.remotePort()) + ") — " + reachingPhrase(scope) + ".";
        }

        Scope scope = scopeOf(c.localAddress());
        String verb = c.protocol() == Protocol.UDP ? "is receiving on UDP port " : "is listening on port ";

        String reach = switch (scope) {
            case LOOPBACK -> "only this computer can reach it";
            case ANY -> "reachable from your network";
            default -> "on your local network";
        };

        return c.app() + " " + verb + c.localPort() + " (" + serviceLabel(c.localPort()) + ") — " + reach + ".";
    }

    private static String reachingPhrase(Scope scope) {
        return switch (scope) {
            case LOOPBACK -> "a link within this computer";
            case PRIVATE, LINK_LOCAL -> "a device on your local network";
            case MULTICAST -> "a group on your local network";
            case INTERNET -> "out on the Internet";
            case ANY -> "all network interfaces";
            case UNKNOWN -> "an address CyberShield couldn't place";
        };
    }

    private static int number(String s) {
        try {
            int n = Integer.parseInt(s);
            return n >= 0 && n <= 255 ? n : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

}
