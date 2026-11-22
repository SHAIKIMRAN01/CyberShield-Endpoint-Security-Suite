package com.cybershield.dao;

import com.cybershield.TestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SettingsDAOTest {

    @TempDir
    Path tempDir;

    private final SettingsDAO settings = new SettingsDAO();

    @BeforeEach
    void setUp() {
        TestSupport.freshEnvironment(tempDir);
    }

    @Test
    void newAccountsGetTheDefaults() {

        assertEquals(SettingsDAO.Settings.DEFAULTS, settings.load(TestSupport.newUser("alice")));

    }

    @Test
    void eachAccountKeepsItsOwnSettings() throws Exception {

        int alice = TestSupport.newUser("alice");

        int bob = TestSupport.newUser("bob");

        settings.save(alice, new SettingsDAO.Settings(12, 45));

        assertEquals(new SettingsDAO.Settings(12, 45), settings.load(alice));

        assertEquals(SettingsDAO.Settings.DEFAULTS, settings.load(bob));

        settings.save(alice, new SettingsDAO.Settings(3, 10));

        assertEquals(new SettingsDAO.Settings(3, 10), settings.load(alice), "saving again replaces the values");

    }

    @Test
    void valuesArePulledIntoTheAllowedRanges() throws Exception {

        int alice = TestSupport.newUser("alice");

        assertEquals(new SettingsDAO.Settings(SettingsDAO.MIN_AUTO_LOCK_MINUTES, SettingsDAO.MAX_CLIPBOARD_CLEAR_SECONDS),
                settings.save(alice, new SettingsDAO.Settings(0, 99_999)));

    }

    @Test
    void eachAccountKeepsItsOwnThemeAndItsOtherSettingsStay() throws Exception {

        int alice = TestSupport.newUser("alice");

        int bob = TestSupport.newUser("bob");

        assertNull(settings.theme(alice), "none chosen yet");

        settings.save(alice, new SettingsDAO.Settings(12, 45));

        settings.saveTheme(alice, "DARK");

        assertEquals("DARK", settings.theme(alice));
        assertEquals(new SettingsDAO.Settings(12, 45), settings.load(alice), "auto-lock and clipboard stay as they were");
        assertNull(settings.theme(bob), "bob's is his own");

        settings.saveTheme(bob, "LIGHT");

        assertEquals(SettingsDAO.Settings.DEFAULTS, settings.load(bob), "a theme alone doesn't change the defaults");

        settings.saveTheme(alice, "LIGHT");

        assertEquals("LIGHT", settings.theme(alice));

    }

    @Test
    void theSignInScreenKeepsTheLastThemeUsed() throws Exception {

        assertNull(settings.appTheme());

        settings.saveAppTheme("DARK");

        assertEquals("DARK", settings.appTheme());

        settings.saveAppTheme("LIGHT");

        assertEquals("LIGHT", settings.appTheme(), "one value, replaced");

    }

    @Test
    void theOriginalSettingsRowStillSuppliesDefaults() throws Exception {

        TestSupport.execute("INSERT INTO settings (id, auto_lock_minutes, clipboard_clear_seconds) VALUES (1, 9, 20)");

        assertEquals(new SettingsDAO.Settings(9, 20), settings.load(TestSupport.newUser("alice")));

    }

}
