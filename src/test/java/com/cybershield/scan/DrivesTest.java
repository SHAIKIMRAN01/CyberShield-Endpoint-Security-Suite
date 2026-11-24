package com.cybershield.scan;

import com.cybershield.scan.Drives.Drive;
import com.cybershield.scan.Drives.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading the list of drives, parsed from sample output — no real drives needed. */
class DrivesTest {

    @Test
    void parsesEachKindOfDrive() {
        List<Drive> drives = Drives.parse(String.join("\n",
                "DRIVE|C:|Windows|Fixed|500000000000|200000000000",
                "DRIVE|E:|KINGSTON|Removable|32000000000|30000000000",
                "DRIVE|F:|Backup HDD|USB|2000000000000|1500000000000",
                "DRIVE|Z:|Share|Network|0|0"));

        assertEquals(4, drives.size());
        assertEquals(Kind.FIXED, drives.get(0).kind());
        assertEquals(Kind.REMOVABLE, drives.get(1).kind());
        assertEquals(Kind.USB, drives.get(2).kind());
        assertEquals(Kind.NETWORK, drives.get(3).kind());
    }

    @Test
    void onlyRemovableAndUsbDrivesCountAsExternal() {
        List<Drive> drives = Drives.parse(String.join("\n",
                "DRIVE|C:|Windows|Fixed|1|1",
                "DRIVE|E:|Stick|Removable|1|1",
                "DRIVE|F:|Ext|USB|1|1",
                "DRIVE|Z:|Share|Network|1|1"));

        assertTrue(drives.get(1).isExternal(), "a removable drive is external");
        assertTrue(drives.get(2).isExternal(), "a USB disk is external");
        assertFalse(drives.get(0).isExternal(), "the internal disk is not");
        assertFalse(drives.get(3).isExternal(), "a network share is not");
    }

    @Test
    void theRootAndDisplayAreTidy() {
        Drive drive = Drives.parse("DRIVE|E:|KINGSTON|Removable|1|1").get(0);
        assertEquals("E:\\", drive.root().toString());
        assertTrue(drive.display().startsWith("E:"));
        assertTrue(drive.display().contains("KINGSTON"));
        assertTrue(drive.display().contains("Removable drive"));

        // an unlabelled drive falls back to its kind
        Drive blank = Drives.parse("DRIVE|G:||USB|1|1").get(0);
        assertTrue(blank.display().contains("USB drive"));
    }

    @Test
    void emptyOutputGivesNoDrives() {
        assertTrue(Drives.parse("").isEmpty());
    }

}
