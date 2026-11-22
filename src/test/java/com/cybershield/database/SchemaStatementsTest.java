package com.cybershield.database;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The schema is split into statements without being cut by a semicolon in a comment. */
class SchemaStatementsTest {

    @Test
    void commentsGoBeforeTheScriptIsSplit() {

        List<String> statements = DatabaseInitializer.statements("""
                -- First table; with a semicolon in its comment
                CREATE TABLE a (x TEXT); -- and here; too
                CREATE TABLE b
                (
                    -- a column; described
                    y TEXT
                );
                """);

        assertEquals(2, statements.size(), String.valueOf(statements));
        assertEquals("CREATE TABLE a (x TEXT)", statements.get(0).strip());
        assertEquals("CREATE TABLE b\n(\n    \n    y TEXT\n)", statements.get(1).strip());

    }

}
