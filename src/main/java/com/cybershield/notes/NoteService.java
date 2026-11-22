package com.cybershield.notes;

import com.cybershield.services.ActivityLog;
import com.cybershield.services.EncryptionService;
import com.cybershield.vault.Vault;
import com.cybershield.vault.VaultSession;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Secure notes of the open vault. Titles and text are encrypted with the vault key
 * before they reach the database, and decrypted only in memory. Searching works on
 * the decrypted notes in memory. Every method needs the vault unlocked.
 */
public class NoteService {

    private final NoteDAO dao = new NoteDAO();

    private final EncryptionService encryption = EncryptionService.getInstance();

    /** The open vault's notes, most recently changed first. */
    public List<Note> all() throws SQLException {

        Vault vault = VaultSession.current();

        List<Note> notes = new ArrayList<>();

        for (NoteDAO.Row row : dao.list(vault.id())) {
            notes.add(new Note(row.id(), encryption.decrypt(row.title()), encryption.decrypt(row.content()), row.createdAt(), row.updatedAt()));
        }

        return notes;

    }

    /** Notes whose title or text contain every word of the query. */
    public List<Note> search(String query) throws SQLException {

        return all().stream().filter(note -> note.matches(query)).toList();

    }

    /** Saves a new note (id 0) or changes an existing one, and returns it as stored. */
    public Note save(Note note) throws SQLException {

        Vault vault = VaultSession.current();

        String title = encryption.encrypt(note.title() == null ? "" : note.title());

        String text = encryption.encrypt(note.text() == null ? "" : note.text());

        if (note.id() == 0) {

            int id = dao.insert(vault.userId(), vault.id(), title, text);

            ActivityLog.record(ActivityLog.Action.NOTE_CREATED, note.displayTitle());

            String when = dao.updatedAt(id);

            return new Note(id, note.title(), note.text(), when, when);

        }

        if (!dao.update(note.id(), vault.id(), title, text)) {
            throw new SQLException("That note isn't in this vault any more.");
        }

        ActivityLog.record(ActivityLog.Action.NOTE_SAVED, note.displayTitle());

        return new Note(note.id(), note.title(), note.text(), note.createdAt(), dao.updatedAt(note.id()));

    }

    public boolean delete(Note note) throws SQLException {

        boolean deleted = dao.delete(note.id(), VaultSession.current().id());

        if (deleted) {
            ActivityLog.record(ActivityLog.Action.NOTE_DELETED, note.displayTitle());
        }

        return deleted;

    }

    public int count() throws SQLException {

        return dao.count(VaultSession.current().id());

    }

}
