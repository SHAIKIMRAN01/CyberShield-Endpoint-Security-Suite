package com.cybershield.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A quick read of how the laptop is set up to protect itself: is antivirus on, is the
 * firewall up, does it ask you to sign in, can apps reach the camera, microphone and
 * location, is the drive encrypted, and what runs at start-up.
 *
 * <p>Every answer comes from {@link Facts}, which only reads settings Windows already
 * exposes — nothing here is changed. The judgement of each fact ({@link #check}) is pure
 * and has no contact with the system, so it is tested on made-up facts; the real reading
 * of the machine lives in a separate {@code Facts} implementation. When a fact can't be
 * read (often because it needs an administrator), the result is {@code UNKNOWN}, never a
 * guess and never a scare.
 */
public final class SecurityCheck {

    /** How a single setting looks. */
    public enum Status {

        GOOD("Good"),
        INFO("For information"),
        WARN("Worth a look"),
        RISK("Needs attention"),
        UNKNOWN("Couldn't check");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

    }

    /** One setting that was checked, in plain words, with advice when something can be done. */
    public record Finding(String title, Status status, String detail, String advice) {
    }

    /** Where the answers come from. Each {@link Optional} is empty when Windows wouldn't say. */
    public interface Facts {

        Optional<Boolean> antivirusEnabled();

        Optional<Boolean> antivirusUpToDate();

        Optional<Boolean> firewallOn();

        Optional<Boolean> driveEncryptionOn();

        Optional<Boolean> autoLoginOn();

        Optional<Boolean> locksOnWake();

        /** "Allow", "Deny", or empty if not readable. */
        Optional<String> cameraAccess();

        Optional<String> microphoneAccess();

        Optional<String> locationAccess();

        /** Names of the extra programs set to run at sign-in; empty list if none. */
        List<String> startupItems();

    }

    /** Totals, so a caller can say "2 things need attention" without re-counting. */
    public record Summary(int good, int info, int warn, int risk, int unknown) {

        public int needsAttention() {
            return warn + risk;
        }

    }

    /** Judges the facts and returns one finding per setting, in the order people expect to read them. */
    public List<Finding> check(Facts f) {

        List<Finding> out = new ArrayList<>();

        out.add(antivirus(f));
        out.add(firewall(f));
        out.add(driveEncryption(f));
        out.add(signIn(f));
        out.add(lockOnWake(f));
        out.add(access("Camera", f.cameraAccess()));
        out.add(access("Microphone", f.microphoneAccess()));
        out.add(access("Location", f.locationAccess()));
        out.add(startup(f));

        return out;
    }

    public static Summary summarise(List<Finding> findings) {

        int good = 0;
        int info = 0;
        int warn = 0;
        int risk = 0;
        int unknown = 0;

        for (Finding finding : findings) {
            switch (finding.status()) {
                case GOOD -> good++;
                case INFO -> info++;
                case WARN -> warn++;
                case RISK -> risk++;
                case UNKNOWN -> unknown++;
            }
        }

        return new Summary(good, info, warn, risk, unknown);
    }

    private static Finding antivirus(Facts f) {

        Optional<Boolean> on = f.antivirusEnabled();

        if (on.isEmpty()) {
            return new Finding("Antivirus", Status.UNKNOWN,
                    "Couldn't read whether antivirus is running.",
                    "Open Windows Security › Virus & threat protection and check it yourself.");
        }
        if (!on.get()) {
            return new Finding("Antivirus", Status.RISK,
                    "No antivirus appears to be running.",
                    "Turn on Windows Security (Virus & threat protection), or start your antivirus.");
        }
        if (f.antivirusUpToDate().isPresent() && !f.antivirusUpToDate().get()) {
            return new Finding("Antivirus", Status.WARN,
                    "Antivirus is on, but its definitions look out of date.",
                    "Open Windows Security and update its protection definitions.");
        }
        return new Finding("Antivirus", Status.GOOD,
                "Antivirus is on.", "");
    }

    private static Finding firewall(Facts f) {

        Optional<Boolean> on = f.firewallOn();

        if (on.isEmpty()) {
            return new Finding("Firewall", Status.UNKNOWN,
                    "Couldn't read the firewall state.",
                    "Check Windows Defender Firewall in Settings.");
        }
        if (!on.get()) {
            return new Finding("Firewall", Status.RISK,
                    "The firewall is off, so the laptop is more open on networks.",
                    "Turn the Windows Defender Firewall back on for all network types.");
        }
        return new Finding("Firewall", Status.GOOD, "The firewall is on.", "");
    }

    private static Finding driveEncryption(Facts f) {

        Optional<Boolean> on = f.driveEncryptionOn();

        if (on.isEmpty()) {
            return new Finding("Drive encryption", Status.UNKNOWN,
                    "Couldn't read drive encryption (this often needs an administrator).",
                    "Check BitLocker in Settings › Privacy & security › Device encryption.");
        }
        if (!on.get()) {
            return new Finding("Drive encryption", Status.WARN,
                    "This drive isn't encrypted. If the laptop is lost or stolen, the files can be read.",
                    "Turn on BitLocker device encryption to protect the files at rest.");
        }
        return new Finding("Drive encryption", Status.GOOD,
                "The drive is encrypted, so the files are protected if the laptop goes missing.", "");
    }

    private static Finding signIn(Facts f) {

        Optional<Boolean> auto = f.autoLoginOn();

        if (auto.isEmpty()) {
            return new Finding("Sign-in", Status.UNKNOWN,
                    "Couldn't read whether Windows signs in automatically.", "");
        }
        if (auto.get()) {
            return new Finding("Sign-in", Status.RISK,
                    "Windows signs in automatically — anyone who opens the laptop is straight in.",
                    "Turn off automatic sign-in so a password or PIN is needed.");
        }
        return new Finding("Sign-in", Status.GOOD, "A sign-in is required when the laptop starts.", "");
    }

    private static Finding lockOnWake(Facts f) {

        Optional<Boolean> locks = f.locksOnWake();

        if (locks.isEmpty()) {
            return new Finding("Lock on wake", Status.UNKNOWN,
                    "Couldn't read whether the screen locks after it sleeps.", "");
        }
        if (!locks.get()) {
            return new Finding("Lock on wake", Status.WARN,
                    "The screen doesn't ask for a sign-in after it sleeps.",
                    "Set the screen to require a sign-in on wake (Settings › Accounts › Sign-in options).");
        }
        return new Finding("Lock on wake", Status.GOOD,
                "The screen locks and asks for a sign-in after it sleeps.", "");
    }

    private static Finding access(String device, Optional<String> setting) {

        if (setting.isEmpty()) {
            return new Finding(device + " access", Status.UNKNOWN,
                    "Couldn't read which apps may use the " + device.toLowerCase() + ".", "");
        }
        if ("Deny".equalsIgnoreCase(setting.get())) {
            return new Finding(device + " access", Status.GOOD,
                    "Apps can't use the " + device.toLowerCase() + ".", "");
        }
        return new Finding(device + " access", Status.INFO,
                "Apps are allowed to use the " + device.toLowerCase() + " (this is the Windows default).",
                "If you'd rather they couldn't, review the list in Settings › Privacy & security.");
    }

    private static Finding startup(Facts f) {

        List<String> items = f.startupItems();

        if (items == null || items.isEmpty()) {
            return new Finding("Start-up programs", Status.GOOD,
                    "Nothing extra is set to run at sign-in.", "");
        }

        String names = String.join(", ", items.size() > 8 ? items.subList(0, 8) : items);
        if (items.size() > 8) {
            names = names + ", and " + (items.size() - 8) + " more";
        }

        return new Finding("Start-up programs", Status.INFO,
                items.size() + " program" + (items.size() == 1 ? "" : "s") + " run at sign-in: " + names + ".",
                "Disable any you don't recognise in Task Manager › Start-up apps.");
    }

}
