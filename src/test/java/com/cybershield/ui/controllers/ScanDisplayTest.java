package com.cybershield.ui.controllers;

import com.cybershield.scan.FileScan;
import com.cybershield.scan.SecurityCheck;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the Security Scan turns engine findings into rows and the one-line summary. Pure
 * text work — no JavaFX, no real machine.
 */
class ScanDisplayTest {

    @Test
    void aSettingRowCarriesItsAdviceAndIsMarkedAsASetting() {
        ScanController.Row row = ScanController.postureRow(new SecurityCheck.Finding(
                "Firewall", SecurityCheck.Status.RISK, "The firewall is off.", "Turn it back on."));

        assertEquals("Needs attention", row.level());
        assertEquals("Firewall", row.finding());
        assertTrue(row.detail().contains("The firewall is off."));
        assertTrue(row.detail().contains("Turn it back on."), "advice should be shown with the finding");
        assertEquals("Windows setting", row.where());
    }

    @Test
    void aSettingWithNoAdviceHasNoArrow() {
        ScanController.Row row = ScanController.postureRow(new SecurityCheck.Finding(
                "Firewall", SecurityCheck.Status.GOOD, "The firewall is on.", ""));

        assertFalse(row.detail().contains("\u2192"), "no advice means no arrow");
    }

    @Test
    void aFileRowShowsThePathAndTheFingerprintWhenThereIsOne() {
        FileScan.Finding finding = new FileScan.Finding(
                Path.of("C:\\Users\\sam\\Downloads\\invoice.pdf.exe"),
                FileScan.Area.DOWNLOADS, FileScan.Reason.DOUBLE_EXTENSION, 12,
                Instant.now(), "abc123");

        ScanController.Row row = ScanController.fileRow(finding);

        assertEquals("Worth a look", row.level());
        assertEquals("Hidden file type", row.finding());
        assertTrue(row.detail().contains("SHA-256 abc123"));
        assertTrue(row.where().endsWith("invoice.pdf.exe"));
    }

    @Test
    void aFileRowWithoutAFingerprintOmitsIt() {
        FileScan.Finding finding = new FileScan.Finding(
                Path.of("C:\\Users\\sam\\Downloads\\setup.exe"),
                FileScan.Area.DOWNLOADS, FileScan.Reason.PROGRAM_IN_DOWNLOADS, 12,
                Instant.now(), null);

        assertFalse(ScanController.fileRow(finding).detail().contains("SHA-256"));
    }

    @Test
    void aCleanScanReadsAsGoodNews() {
        List<SecurityCheck.Finding> posture = List.of(
                new SecurityCheck.Finding("Firewall", SecurityCheck.Status.GOOD, "on", ""),
                new SecurityCheck.Finding("Antivirus", SecurityCheck.Status.GOOD, "on", ""));

        String summary = ScanController.summarise(posture, List.of(), false);
        assertTrue(summary.toLowerCase().contains("nothing stood out"));
    }

    @Test
    void aScanWithProblemsCountsEachKind() {
        List<SecurityCheck.Finding> posture = List.of(
                new SecurityCheck.Finding("Firewall", SecurityCheck.Status.RISK, "off", "fix"),
                new SecurityCheck.Finding("Drive encryption", SecurityCheck.Status.WARN, "off", "fix"));

        List<FileScan.Finding> files = List.of(
                file(FileScan.Reason.DOUBLE_EXTENSION),
                file(FileScan.Reason.PROGRAM_IN_DOWNLOADS));

        String summary = ScanController.summarise(posture, files, false);

        assertTrue(summary.contains("2 settings to look at"), summary);
        assertTrue(summary.contains("1 file worth a look"), summary);
        assertTrue(summary.contains("1 file to be aware of"), summary);
    }

    @Test
    void aStoppedScanSaysSo() {
        String summary = ScanController.summarise(List.of(), List.of(), true);
        assertTrue(summary.startsWith("Scan stopped."));
    }

    @Test
    void everyLevelLabelMapsToAColourClass() {
        assertEquals("level-risk", ScanController.classForLabel("Needs attention"));
        assertEquals("level-warn", ScanController.classForLabel("Worth a look"));
        assertEquals("level-note", ScanController.classForLabel("Good to know"));
        assertEquals("level-good", ScanController.classForLabel("Good"));
        assertEquals("level-unknown", ScanController.classForLabel("Couldn't check"));
        assertEquals("level-info", ScanController.classForLabel("For information"));
    }

    private static FileScan.Finding file(FileScan.Reason reason) {
        return new FileScan.Finding(Path.of("C:\\x\\a"), FileScan.Area.DOWNLOADS, reason, 1, Instant.now(), null);
    }

}
