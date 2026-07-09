package com.editora.markwhen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the Timeline → JSON serializer (round-trips through Jackson). */
class MarkwhenJsonTest {

    private static JsonNode json(String mw) throws Exception {
        return new ObjectMapper().readTree(MarkwhenJson.toJson(MarkwhenParser.parse(mw)));
    }

    @Test
    void serializesTitleTagColorsEventsAndGroups() throws Exception {
        String mw = "title: Demo\n#Travel: blue\n\n# Trips\n2023-01: Kickoff #Travel\n2023-03 / 2023-06: Phase\n";
        JsonNode root = json(mw);
        assertEquals("Demo", root.get("title").asText());
        assertEquals("Travel", root.get("tagColors").get(0).get("name").asText());
        assertEquals("blue", root.get("tagColors").get(0).get("color").asText());

        JsonNode group = root.get("nodes").get(0);
        assertEquals("group", group.get("type").asText());
        assertEquals("Trips", group.get("name").asText());

        JsonNode ev = group.get("children").get(0);
        assertEquals("event", ev.get("type").asText());
        assertEquals("Kickoff #Travel", ev.get("label").asText());
        assertEquals("2023-01-01", ev.get("start").asText());
        assertEquals("MONTH", ev.get("startGranularity").asText());
        assertTrue(ev.get("end").isNull());
        assertEquals("Travel", ev.get("tags").get(0).asText());

        JsonNode range = group.get("children").get(1);
        assertEquals("2023-03-01", range.get("start").asText());
        assertEquals("2023-06-01", range.get("end").asText());
    }

    @Test
    void emptyTimelineIsValidJson() throws Exception {
        JsonNode root = json("// nothing here\n");
        assertTrue(root.get("title").isNull());
        assertTrue(root.get("nodes").isEmpty());
        assertFalse(root.get("tagColors").elements().hasNext());
    }
}
