package com.cybershield.network;

import com.cybershield.network.NetworkAnalysis.Connection;
import com.cybershield.network.NetworkAnalysis.Protocol;
import com.cybershield.network.NetworkReader.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parsing half of the network reader, driven by sample lines shaped exactly like the
 * script's real output. The reading half ({@code read()}) runs live PowerShell and is left
 * to a hands-on check rather than the test suite.
 */
class NetworkReaderTest {

    @Test
    void readsConnectionsAndDnsLines() {
        Snapshot snap = NetworkReader.parse(String.join("\n",
                "TCP|Established|2409:40f0::1|59868|2607:6bc0::10|443|2304|claude",
                "TCP|Listen|::|5432|::|0|7232|postgres",
                "UDP|Listen|::|5353|||14660|copilotapp",
                "DNS|www.example.com|93.184.216.34"));

        assertEquals(3, snap.connections().size());
        assertEquals(1, snap.dnsCache().size());

        Connection first = snap.connections().get(0);
        assertEquals(Protocol.TCP, first.protocol());
        assertEquals("Established", first.state());
        assertEquals("2607:6bc0::10", first.remoteAddress());
        assertEquals(443, first.remotePort());
        assertEquals("claude", first.app());
        assertTrue(first.isEstablished());

        Connection udp = snap.connections().get(2);
        assertEquals(Protocol.UDP, udp.protocol());
        assertEquals(5353, udp.localPort());

        assertEquals("www.example.com", snap.dnsCache().get(0).name());
        assertEquals("93.184.216.34", snap.dnsCache().get(0).data());
    }

    @Test
    void emptyOutputGivesAnEmptySnapshot() {
        Snapshot snap = NetworkReader.parse("");
        assertTrue(snap.isEmpty());
        assertTrue(snap.connections().isEmpty());
        assertTrue(snap.dnsCache().isEmpty());
    }

    @Test
    void shortOrOddLinesAreSkipped() {
        Snapshot snap = NetworkReader.parse(String.join("\n",
                "TCP|Established|only|three|fields",
                "garbage line",
                "",
                "DNS|",
                "TCP|Listen|0.0.0.0|135|0.0.0.0|0|984|svchost"));

        // only the one well-formed TCP line survives; the empty DNS name is dropped
        assertEquals(1, snap.connections().size());
        assertEquals(135, snap.connections().get(0).localPort());
        assertTrue(snap.dnsCache().isEmpty());
    }

    @Test
    void aMissingProcessNameStillParses() {
        Snapshot snap = NetworkReader.parse("TCP|Established|10.0.0.2|5000|10.0.0.3|443|9999|pid 9999");
        assertEquals("pid 9999", snap.connections().get(0).app());
        assertEquals(9999, snap.connections().get(0).pid());
    }

    @Test
    void theDnsCacheBecomesAnAddressToNameLookup() {
        Snapshot snap = NetworkReader.parse(String.join("\n",
                "DNS|www.google.com|142.250.80.46",
                "DNS|cdn.example.com|star.example.net",     // a name-to-name (CNAME) row: ignored
                "DNS|api.spotify.com|2607:6bc0::10"));

        Map<String, String> hosts = snap.hostByAddress();
        assertEquals("www.google.com", hosts.get("142.250.80.46"));
        assertEquals("api.spotify.com", hosts.get("2607:6bc0::10"), "IPv6 is looked up too");
        assertFalse(hosts.containsKey("star.example.net"), "a name is not an address");
    }

    @Test
    void anAddressIsToldApartFromAName() {
        assertTrue(NetworkReader.looksLikeAddress("142.250.80.46"));
        assertTrue(NetworkReader.looksLikeAddress("2607:6bc0::10"));
        assertFalse(NetworkReader.looksLikeAddress("star.example.net"));
        assertFalse(NetworkReader.looksLikeAddress("999.1.1.1"), "octets over 255 aren't an address");
        assertFalse(NetworkReader.looksLikeAddress(""));
    }

}
