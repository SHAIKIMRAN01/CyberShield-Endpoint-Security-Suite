package com.cybershield.notes;

import com.cybershield.TestSupport;
import com.cybershield.vault.VaultService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static com.cybershield.TestSupport.MASTER_PASSWORD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Secure Notes: encrypted in the database, searchable once decrypted, and private to each vault. */
class NoteServiceTest {

    @TempDir
    Path tempDir;

    private final VaultService vaults = new VaultService();

    private final NoteService notes = new NoteService();

    private int alice;

    @BeforeEach
    void setUp() {

        TestSupport.freshEnvironment(tempDir);

        alice = TestSupport.newUser("alice");

        vaults.createVault(alice, MASTER_PASSWORD);

    }

    @AfterEach
    void lock() {
        vaults.lockVault();
    }

    @Test
    void notesAreStoredEncryptedAndReadBackAsWritten() throws Exception {

        Note saved = notes.save(new Note(0, "Bank PIN hint", "The PIN is the year we met, backwards.", null, null));

        assertTrue(saved.id() > 0);
        assertNotNull(saved.createdAt());

        String title = TestSupport.text("SELECT title FROM secure_notes WHERE id = ?", saved.id());
        String content = TestSupport.text("SELECT content FROM secure_notes WHERE id = ?", saved.id());

        assertFalse(title.contains("Bank"), "the title is ciphertext");
        assertFalse(content.contains("PIN"), "the text is ciphertext");

        List<Note> all = notes.all();

        assertEquals(1, all.size());
        assertEquals("Bank PIN hint", all.get(0).title());
        assertEquals("The PIN is the year we met, backwards.", all.get(0).text());

        Note changed = notes.save(all.get(0).withText("Bank PIN hint", "Changed after the new card came."));

        assertEquals(saved.id(), changed.id(), "saving again changes the same note");
        assertEquals("Changed after the new card came.", notes.all().get(0).text());
        assertEquals(1, notes.count());

    }

    @Test
    void searchLooksInsideTitlesAndText() throws Exception {

        notes.save(new Note(0, "Trip plan", "Train at 08:10, hotel near the station.", null, null));
        notes.save(new Note(0, "Cabin Wi-Fi", "Network Cabin-5G", null, null));
        notes.save(new Note(0, "", "Shopping: bread, eggs, coffee", null, null));

        assertEquals(List.of("Trip plan"), notes.search("HOTEL station").stream().map(Note::title).toList());
        assertEquals(1, notes.search("cabin").size());
        assertEquals(0, notes.search("hotel cabin").size(), "every word has to match");
        assertEquals(3, notes.search("  ").size());

        Note untitled = notes.search("coffee").get(0);

        assertEquals("Shopping: bread, eggs, coffee", untitled.displayTitle(), "a note without a title shows its first line");

    }

    @Test
    void deletingAndOtherVaultsCantReachTheNotes() throws Exception {

        Note note = notes.save(new Note(0, "Alice only", "private", null, null));

        vaults.lockVault();

        assertThrows(IllegalStateException.class, notes::all, "notes need the vault unlocked");

        int bob = TestSupport.newUser("bob");

        vaults.createVault(bob, "Bobs-Master-Pass-3!");

        assertTrue(notes.all().isEmpty(), "bob's vault has no notes");
        assertFalse(notes.delete(note), "bob can't delete alice's note");
        assertThrows(SQLException.class, () -> notes.save(note.withText("Taken", "over")), "nor change it");

        vaults.lockVault();

        vaults.unlock(alice, MASTER_PASSWORD);

        assertEquals("private", notes.all().get(0).text());
        assertTrue(notes.delete(notes.all().get(0)));
        assertEquals(0, notes.count());

    }

}
