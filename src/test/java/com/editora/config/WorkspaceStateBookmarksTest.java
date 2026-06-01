package com.editora.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Verifies that bookmarks (a record) survive JSON serialization in WorkspaceState. */
class WorkspaceStateBookmarksTest {

    @Test
    void bookmarksRoundTripThroughJson() throws Exception {
        WorkspaceState state = new WorkspaceState();
        state.getBookmarks().put("/tmp/a.txt",
                List.of(new Bookmark(3, "todo", "int x = 1;"), new Bookmark(10, "", "return x;")));

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(state);
        WorkspaceState back = mapper.readValue(json, WorkspaceState.class);

        Map<String, List<Bookmark>> marks = back.getBookmarks();
        assertEquals(1, marks.size());
        List<Bookmark> a = marks.get("/tmp/a.txt");
        assertEquals(2, a.size());
        assertEquals(new Bookmark(3, "todo", "int x = 1;"), a.get(0));
        assertEquals(10, a.get(1).line());
        assertEquals("", a.get(1).note());
        assertEquals("return x;", a.get(1).lineText());
    }
}
