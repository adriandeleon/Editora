package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistersTest {

    @Test
    void holdsTextAndPositionsByName() {
        Registers r = new Registers();
        assertNull(r.get('a'));
        r.put('a', new Registers.Text("hello"));
        r.put('p', new Registers.Position("/w/a.txt", 5));
        r.put('a', new Registers.Text("again")); // replaces
        assertEquals(new Registers.Text("again"), r.get('a'));
        assertEquals(new Registers.Position("/w/a.txt", 5), r.get('p'));
        assertEquals("[a, p]", r.all().keySet().toString(), "listed by name");
    }

    @Test
    void positionsFollowEditsToTheirOwnFileOnly() {
        Registers r = new Registers();
        r.put('p', new Registers.Position("/w/a.txt", 10));
        r.put('q', new Registers.Position("/w/b.txt", 10));
        r.put('t', new Registers.Text("x"));
        r.shift("/w/a.txt", 0, 0, 4); // four characters typed at the start of a.txt
        assertEquals(14, ((Registers.Position) r.get('p')).offset());
        assertEquals(10, ((Registers.Position) r.get('q')).offset(), "b.txt untouched");
        assertEquals(new Registers.Text("x"), r.get('t'));
        r.shift("/w/a.txt", 20, 3, 0); // an edit after it
        assertEquals(14, ((Registers.Position) r.get('p')).offset());
        r.shift("/w/a.txt", 12, 5, 1); // its text removed: to where the edit started
        assertEquals(12, ((Registers.Position) r.get('p')).offset());
    }

    @Test
    void knowsWhichFilesHavePositions() {
        Registers r = new Registers();
        r.put('p', new Registers.Position("/w/a.txt", 1));
        assertTrue(r.hasPositionIn("/w/a.txt"));
        assertFalse(r.hasPositionIn("/w/b.txt"));
    }
}
