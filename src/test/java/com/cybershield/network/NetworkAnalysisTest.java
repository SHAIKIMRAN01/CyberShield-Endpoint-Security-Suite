package com.cybershield.network;

import com.cybershield.network.NetworkAnalysis.Connection;
import com.cybershield.network.NetworkAnalysis.Protocol;
import com.cybershield.network.NetworkAnalysis.Scope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plain-words layer: which service a port is, where an address sits, and the one-line
 * explanation of a connection. All pure — no sockets, no real machine.
 */
class NetworkAnalysisTest {

    @Test
    void knowsTheEverydayPorts() {
        assertEquals("secure web (HTTPS)", NetworkAnalysis.service(443));
        assertEquals("DNS", NetworkAnalysis.service(53));
        assertEquals("PostgreSQL", NetworkAnalysis.service(5432));
        assertNull(NetworkAnalysis.service(64260), "a random high port isn't a known service");
        assertEquals("port 64260", NetworkAnalysis.serviceLabel(64260));
        assertEquals("secure web (HTTPS)", NetworkAnalysis.serviceLabel(443));
    }

    @Test
    void placesIpv4Addresses() {
        assertEquals(Scope.LOOPBACK, NetworkAnalysis.scopeOf("127.0.0.1"));
        assertEquals(Scope.PRIVATE, NetworkAnalysis.scopeOf("192.168.1.5"));
        assertEquals(Scope.PRIVATE, NetworkAnalysis.scopeOf("10.0.0.9"));
        assertEquals(Scope.PRIVATE, NetworkAnalysis.scopeOf("172.16.4.4"));
        assertEquals(Scope.INTERNET, NetworkAnalysis.scopeOf("142.250.80.46"));
        assertEquals(Scope.PRIVATE, NetworkAnalysis.scopeOf("172.20.0.1"));
        assertEquals(Scope.INTERNET, NetworkAnalysis.scopeOf("172.32.0.1"), "172.32 is outside the private range");
        assertEquals(Scope.LINK_LOCAL, NetworkAnalysis.scopeOf("169.254.3.3"));
        assertEquals(Scope.ANY, NetworkAnalysis.scopeOf("0.0.0.0"));
    }

    @Test
    void placesIpv6Addresses() {
        assertEquals(Scope.LOOPBACK, NetworkAnalysis.scopeOf("::1"));
        assertEquals(Scope.ANY, NetworkAnalysis.scopeOf("::"));
        // the real shapes this machine produced
        assertEquals(Scope.INTERNET, NetworkAnalysis.scopeOf("2607:6bc0::10"));
        assertEquals(Scope.INTERNET, NetworkAnalysis.scopeOf("2409:40f0:747c:a59d:f1aa:9981:f954:e918"));
        assertEquals(Scope.LINK_LOCAL, NetworkAnalysis.scopeOf("fe80::c247:23fb:f0f2:855a%24"), "a %zone is ignored");
        assertEquals(Scope.PRIVATE, NetworkAnalysis.scopeOf("fd00::1"));
        assertEquals(Scope.MULTICAST, NetworkAnalysis.scopeOf("ff02::fb"));
    }

    @Test
    void oddAddressesAreUnknownNotAGuess() {
        assertEquals(Scope.UNKNOWN, NetworkAnalysis.scopeOf(""));
        assertEquals(Scope.UNKNOWN, NetworkAnalysis.scopeOf(null));
        assertEquals(Scope.UNKNOWN, NetworkAnalysis.scopeOf("not-an-address"));
        assertEquals(Scope.UNKNOWN, NetworkAnalysis.scopeOf("300.1.1.1"), "octets over 255 aren't an IPv4 address");
    }

    @Test
    void explainsAnOutgoingSecureWebConnection() {
        Connection c = new Connection(Protocol.TCP, "Established",
                "2409:40f0::1", 59868, "2607:6bc0::10", 443, 2304, "claude");

        String text = NetworkAnalysis.explain(c);
        assertTrue(text.startsWith("claude is connected to 2607:6bc0::10 on port 443"), text);
        assertTrue(text.contains("secure web (HTTPS)"), text);
        assertTrue(text.contains("out on the Internet"), text);
    }

    @Test
    void explainsAListeningServer() {
        Connection c = new Connection(Protocol.TCP, "Listen",
                "::", 5432, "::", 0, 7232, "postgres");

        String text = NetworkAnalysis.explain(c);
        assertTrue(text.contains("postgres is listening on port 5432"), text);
        assertTrue(text.contains("PostgreSQL"), text);
    }

    @Test
    void explainsLoopbackOnlyAndUdpDiscovery() {
        Connection loopback = new Connection(Protocol.TCP, "Listen",
                "127.0.0.1", 49669, "0.0.0.0", 0, 4672, "jhi_service");
        assertTrue(NetworkAnalysis.explain(loopback).contains("only this computer can reach it"));

        Connection mdns = new Connection(Protocol.UDP, "Listen",
                "::", 5353, "", 0, 14660, "copilotapp");
        String text = NetworkAnalysis.explain(mdns);
        assertTrue(text.contains("receiving on UDP port 5353"), text);
        assertTrue(text.contains("device discovery (mDNS)"), text);
    }

}
