package com.cybershield.backup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The installed-apps list, parsed from sample registry output — no real registry needed. */
class InstalledAppsTest {

    @Test
    void namesAndVersionsBecomeTidyLines() {
        List<String> apps = InstalledApps.parse(String.join("\n",
                "7-Zip 23.01|23.01",
                "Google Chrome|130.0",
                "A Tool|"));

        assertTrue(apps.contains("7-Zip 23.01  —  23.01"));
        assertTrue(apps.contains("Google Chrome  —  130.0"));
        assertTrue(apps.contains("A Tool"), "a missing version just shows the name");
    }

    @Test
    void theListIsSortedAndFreeOfRepeats() {
        List<String> apps = InstalledApps.parse(String.join("\n",
                "Zeta|1", "alpha|1", "Zeta|1"));

        assertEquals(2, apps.size(), "the repeat is dropped");
        assertEquals("alpha  —  1", apps.get(0), "sorted, ignoring case");
    }

    @Test
    void emptyOutputGivesNoApps() {
        assertTrue(InstalledApps.parse("").isEmpty());
    }

    @Test
    void theTextFileHasACountAndEveryApp() {
        String text = InstalledApps.asText(List.of("App A", "App B"));
        assertTrue(text.contains("(2)"));
        assertTrue(text.contains("App A"));
        assertTrue(text.contains("App B"));
    }

}
