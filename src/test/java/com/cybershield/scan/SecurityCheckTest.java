package com.cybershield.scan;

import com.cybershield.scan.SecurityCheck.Facts;
import com.cybershield.scan.SecurityCheck.Finding;
import com.cybershield.scan.SecurityCheck.Status;
import com.cybershield.scan.SecurityCheck.Summary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The quick posture read. Driven entirely by a hand-built {@link FakeFacts}, so it never
 * reads the real machine — it only checks that CyberShield judges each setting sensibly.
 */
class SecurityCheckTest {

    private final SecurityCheck check = new SecurityCheck();

    private Finding find(List<Finding> findings, String title) {
        return findings.stream().filter(f -> f.title().equals(title)).findFirst().orElseThrow();
    }

    @Test
    void aWellSetUpLaptopComesBackAllGood() {
        List<Finding> findings = check.check(new FakeFacts().secure());

        assertEquals(Status.GOOD, find(findings, "Antivirus").status());
        assertEquals(Status.GOOD, find(findings, "Firewall").status());
        assertEquals(Status.GOOD, find(findings, "Drive encryption").status());
        assertEquals(Status.GOOD, find(findings, "Sign-in").status());
        assertEquals(Status.GOOD, find(findings, "Lock on wake").status());

        Summary summary = SecurityCheck.summarise(findings);
        assertEquals(0, summary.needsAttention());
    }

    @Test
    void theRealProblemsAreMarkedNeedsAttention() {
        FakeFacts facts = new FakeFacts().secure();
        facts.antivirus = Optional.of(false);
        facts.firewall = Optional.of(false);
        facts.autoLogin = Optional.of(true);

        List<Finding> findings = check.check(facts);

        assertEquals(Status.RISK, find(findings, "Antivirus").status());
        assertEquals(Status.RISK, find(findings, "Firewall").status());
        assertEquals(Status.RISK, find(findings, "Sign-in").status());
        // each risk should tell the person what to do
        assertTrue(find(findings, "Antivirus").advice().toLowerCase().contains("windows security"));
        assertTrue(find(findings, "Firewall").advice().toLowerCase().contains("firewall"));
    }

    @Test
    void anUnreadableSettingIsUnknownNotAScare() {
        FakeFacts facts = new FakeFacts().secure();
        facts.driveEncryption = Optional.empty();
        facts.antivirus = Optional.empty();

        List<Finding> findings = check.check(facts);

        assertEquals(Status.UNKNOWN, find(findings, "Drive encryption").status());
        assertEquals(Status.UNKNOWN, find(findings, "Antivirus").status());
        // unknown never counts as something wrong
        assertEquals(0, SecurityCheck.summarise(findings).needsAttention());
    }

    @Test
    void anUnencryptedDriveIsWorthALookButNotAnEmergency() {
        FakeFacts facts = new FakeFacts().secure();
        facts.driveEncryption = Optional.of(false);

        assertEquals(Status.WARN, find(check.check(facts), "Drive encryption").status());
    }

    @Test
    void allowingTheCameraIsJustInformation() {
        FakeFacts facts = new FakeFacts().secure();
        facts.camera = Optional.of("Allow");
        facts.microphone = Optional.of("Deny");

        List<Finding> findings = check.check(facts);
        assertEquals(Status.INFO, find(findings, "Camera access").status());
        assertEquals(Status.GOOD, find(findings, "Microphone access").status());
    }

    @Test
    void startupProgramsAreListedForTheReaderToJudge() {
        FakeFacts facts = new FakeFacts().secure();
        facts.startup = List.of("Spotify", "OneDrive", "SomeUpdater");

        Finding startup = find(check.check(facts), "Start-up programs");
        assertEquals(Status.INFO, startup.status());
        assertTrue(startup.detail().contains("Spotify"));
        assertTrue(startup.detail().contains("3 programs"));
    }

    /** A plain, settable stand-in for the real Windows reader. */
    private static final class FakeFacts implements Facts {

        Optional<Boolean> antivirus = Optional.empty();
        Optional<Boolean> antivirusUpToDate = Optional.empty();
        Optional<Boolean> firewall = Optional.empty();
        Optional<Boolean> driveEncryption = Optional.empty();
        Optional<Boolean> autoLogin = Optional.empty();
        Optional<Boolean> locksOnWake = Optional.empty();
        Optional<String> camera = Optional.empty();
        Optional<String> microphone = Optional.empty();
        Optional<String> location = Optional.empty();
        List<String> startup = List.of();

        FakeFacts secure() {
            antivirus = Optional.of(true);
            antivirusUpToDate = Optional.of(true);
            firewall = Optional.of(true);
            driveEncryption = Optional.of(true);
            autoLogin = Optional.of(false);
            locksOnWake = Optional.of(true);
            camera = Optional.of("Deny");
            microphone = Optional.of("Deny");
            location = Optional.of("Deny");
            startup = List.of();
            return this;
        }

        @Override public Optional<Boolean> antivirusEnabled() { return antivirus; }
        @Override public Optional<Boolean> antivirusUpToDate() { return antivirusUpToDate; }
        @Override public Optional<Boolean> firewallOn() { return firewall; }
        @Override public Optional<Boolean> driveEncryptionOn() { return driveEncryption; }
        @Override public Optional<Boolean> autoLoginOn() { return autoLogin; }
        @Override public Optional<Boolean> locksOnWake() { return locksOnWake; }
        @Override public Optional<String> cameraAccess() { return camera; }
        @Override public Optional<String> microphoneAccess() { return microphone; }
        @Override public Optional<String> locationAccess() { return location; }
        @Override public List<String> startupItems() { return startup; }

    }

}
