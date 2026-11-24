package com.cybershield.ui.controllers;

import com.cybershield.network.NetworkAnalysis.Connection;
import com.cybershield.network.NetworkAnalysis.Protocol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the Network Analyzer turns connections into table rows and the one-line summary.
 * Pure text work — no JavaFX, no real machine.
 */
class NetworkDisplayTest {

    @Test
    void anOutgoingConnectionShowsTheRemoteEndAndIsExplained() {
        Connection c = new Connection(Protocol.TCP, "Established",
                "2409:40f0::1", 59868, "2607:6bc0::10", 443, 2304, "claude");

        NetworkController.ConnRow row = NetworkController.connRow(c);

        assertEquals("claude", row.app());
        assertEquals("The Internet", row.scope());
        assertEquals("secure web (HTTPS)", row.service());
        assertEquals("2607:6bc0::10 : 443", row.address());
        assertTrue(row.explanation().contains("out on the Internet"), row.explanation());
    }

    @Test
    void aListeningServerShowsItsLocalPort() {
        Connection c = new Connection(Protocol.TCP, "Listen",
                "::", 5432, "::", 0, 7232, "postgres");

        NetworkController.ConnRow row = NetworkController.connRow(c);

        assertEquals("PostgreSQL", row.service());
        assertEquals(":: : 5432", row.address());
        assertTrue(row.explanation().contains("listening on port 5432"), row.explanation());
    }

    @Test
    void theSummaryCountsApps_InternetAndListeners() {
        List<Connection> connections = List.of(
                new Connection(Protocol.TCP, "Established", "a", 1, "2607:6bc0::10", 443, 1, "claude"),
                new Connection(Protocol.TCP, "Established", "a", 2, "142.250.80.46", 443, 2, "chrome"),
                new Connection(Protocol.TCP, "Established", "a", 3, "192.168.1.9", 445, 3, "explorer"),
                new Connection(Protocol.TCP, "Listen", "::", 5432, "::", 0, 4, "postgres"));

        String summary = NetworkController.summarise(connections, 7);

        assertTrue(summary.contains("4 connections"), summary);
        assertTrue(summary.contains("across 4 apps"), summary);
        assertTrue(summary.contains("2 to the Internet"), summary);   // the two HTTPS ones, not the private 445
        assertTrue(summary.contains("1 listening"), summary);
        assertTrue(summary.contains("7 names looked up recently"), summary);
    }

    @Test
    void noConnectionsReadsPlainly() {
        assertEquals("No active connections were found.", NetworkController.summarise(List.of(), 0));
    }

    @Test
    void everyScopeMapsToAColourClass() {
        assertEquals("scope-internet", NetworkController.classForScope("The Internet"));
        assertEquals("scope-local", NetworkController.classForScope("Local network"));
        assertEquals("scope-local", NetworkController.classForScope("Local network group"));
        assertEquals("scope-computer", NetworkController.classForScope("This computer"));
        assertEquals("scope-computer", NetworkController.classForScope("All interfaces"));
        assertEquals("scope-unknown", NetworkController.classForScope("Unknown"));
    }

}
