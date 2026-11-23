package com.cybershield.scan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parsing half of the Windows reader, driven by sample script output so it needs no
 * real machine. The reading half ({@code read()}) runs live PowerShell and is left to a
 * hands-on check rather than the test suite.
 */
class WindowsFactsTest {

    @Test
    void readsEachKindOfValue() {
        WindowsFacts f = WindowsFacts.parse(String.join("\n",
                "antivirus=true",
                "antivirusUpToDate=false",
                "firewall=true",
                "driveEncryption=unknown",
                "autoLogin=false",
                "locksOnWake=true",
                "camera=Allow",
                "microphone=Deny",
                "location=unknown",
                "startup=Spotify|OneDrive|Updater"));

        assertEquals(Optional.of(true), f.antivirusEnabled());
        assertEquals(Optional.of(false), f.antivirusUpToDate());
        assertEquals(Optional.of(true), f.firewallOn());
        // "unknown" becomes empty, not a guess
        assertEquals(Optional.empty(), f.driveEncryptionOn());
        assertEquals(Optional.of(false), f.autoLoginOn());
        assertEquals(Optional.of("Allow"), f.cameraAccess());
        assertEquals(Optional.of("Deny"), f.microphoneAccess());
        assertEquals(Optional.empty(), f.locationAccess());
        assertEquals(List.of("Spotify", "OneDrive", "Updater"), f.startupItems());
    }

    @Test
    void emptyOutputMeansEverythingIsUnknown() {
        WindowsFacts f = WindowsFacts.parse("");

        assertTrue(f.antivirusEnabled().isEmpty());
        assertTrue(f.firewallOn().isEmpty());
        assertTrue(f.cameraAccess().isEmpty());
        assertTrue(f.startupItems().isEmpty());
    }

    @Test
    void aBlankStartupLineMeansNoStartupItems() {
        WindowsFacts f = WindowsFacts.parse("startup=");
        assertTrue(f.startupItems().isEmpty());
    }

    @Test
    void oddLinesAreIgnored() {
        WindowsFacts f = WindowsFacts.parse(String.join("\n",
                "this line has no equals sign",
                "",
                "firewall=true"));

        assertEquals(Optional.of(true), f.firewallOn());
        assertFalse(f.antivirusEnabled().isPresent());
    }

}
