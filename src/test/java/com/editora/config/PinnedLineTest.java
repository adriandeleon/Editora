package com.editora.config;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Values pinned to lines, as stored. */
class PinnedLineTest {

    @Test
    void blankAndRepeatedExpressionsAreDroppedAndTheTextIsCapped() {
        PinnedLine p = new PinnedLine(3, List.of(" total ", "", "total", "items.size()"), "x".repeat(300));
        assertEquals(List.of("total", "items.size()"), p.expressions());
        assertEquals(PinnedLine.MAX_LINE_TEXT, p.lineText().length());
        assertEquals(List.of(), new PinnedLine(0, null, null).expressions());
    }

    @Test
    void theStoreKeepsThemPerProjectAndFileAndAnOlderFileHasNone() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        BreakpointStore store = new BreakpointStore();
        store.pinsBucket("p").put("/a/Main.java", List.of(new PinnedLine(4, List.of("total"), "total += i;")));
        BreakpointStore back = mapper.readValue(mapper.writeValueAsString(store), BreakpointStore.class);
        assertEquals(store.pinsBucket("p"), back.pinsBucket("p"));
        assertTrue(back.pinsBucket("other").isEmpty());
        BreakpointStore v4 = mapper.readValue("{\"schemaVersion\":4,\"byProject\":{}}", BreakpointStore.class);
        assertTrue(v4.getPins().isEmpty());
        assertEquals(5, BreakpointStore.SCHEMA_VERSION);
    }
}
