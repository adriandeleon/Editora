package com.editora.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class NoteStoreTest {

    private final ObjectMapper json = new ObjectMapper();

    private static PersonalNote note(String body, NoteScope scope) {
        FileIdentity f = new FileIdentity("/p/x.txt", "/p/x.txt", 10, 1, "h");
        TextAnchor a = new TextAnchor(3, 0, 3, 5, "hello", "be", "fore");
        return PersonalNote.create(f, scope, a, body, List.of("todo", "config"));
    }

    @Test
    void bucketsAreIsolatedPerProject() {
        NoteStore store = new NoteStore();
        store.bucket("").put("/a.txt", List.of(note("global", NoteScope.LINE)));
        store.bucket("proj1").put("/a.txt", List.of(note("p1", NoteScope.LINE)));
        assertEquals(1, store.bucket("").size());
        assertEquals(1, store.bucket("proj1").size());
        assertEquals("global", store.bucket("").get("/a.txt").get(0).body());
        assertEquals("p1", store.bucket("proj1").get("/a.txt").get(0).body());
    }

    @Test
    void roundTripsThroughJacksonWithDefaultMapper() throws Exception {
        NoteStore store = new NoteStore();
        store.bucket("").put("/p/x.txt", List.of(note("check this", NoteScope.RANGE),
                note("a line note", NoteScope.LINE)));
        String text = json.writeValueAsString(store);
        NoteStore back = json.readValue(text, NoteStore.class);

        assertEquals(NoteStore.SCHEMA_VERSION, back.getSchemaVersion());
        List<PersonalNote> notes = back.bucket("").get("/p/x.txt");
        assertEquals(2, notes.size());
        assertEquals("check this", notes.get(0).body());
        assertEquals(NoteScope.RANGE, notes.get(0).scope());
        assertEquals(List.of("todo", "config"), notes.get(0).tags());
        assertEquals(NoteStatus.ACTIVE, notes.get(0).status());
        assertEquals(NoteScope.LINE, notes.get(1).scope());
        assertEquals("hello", notes.get(0).anchor().selectedText());
    }

    @Test
    void mergePreservingOrderKeepsPreviousOrderByIdThenAppendsNew() {
        PersonalNote a = note("a", NoteScope.LINE);
        PersonalNote b = note("b", NoteScope.LINE);
        PersonalNote c = note("c", NoteScope.LINE);
        // current snapshot: b (edited), a, plus a new note c
        PersonalNote bEdited = b.withBody("b2");
        List<PersonalNote> merged = NoteStore.mergePreservingOrder(List.of(a, b), List.of(bEdited, a, c));
        assertEquals(List.of(a.id(), b.id(), c.id()),
                merged.stream().map(PersonalNote::id).toList(), "previous order first, new appended");
        assertEquals("b2", merged.get(1).body(), "uses the up-to-date instance");
        assertNotSame(b, merged.get(1));
    }

    @Test
    void canonicalConstructorNullSafesAndAssignsId() {
        PersonalNote n = new PersonalNote(null, null, null, null, null, null, null, 0, 0);
        assertEquals(NoteScope.LINE, n.scope());
        assertEquals(NoteStatus.ACTIVE, n.status());
        assertEquals(List.of(), n.tags());
        assertEquals("", n.body());
        assertTrue(n.id() instanceof UUID);
        assertEquals(0, n.anchor().line());
    }
}
