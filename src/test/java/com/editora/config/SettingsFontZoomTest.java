package com.editora.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies the persisted text-zoom factor round-trips through the JSON settings serialization. */
class SettingsFontZoomTest {

    @Test
    void fontZoomRoundTripsThroughJson() throws Exception {
        Settings s = new Settings();
        s.setFontZoom(1.2);

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(s);
        Settings back = mapper.readValue(json, Settings.class);

        org.junit.jupiter.api.Assertions.assertTrue(json.contains("fontZoom"), "fontZoom should be written");
        assertEquals(1.2, back.getFontZoom(), 1e-9);
    }
}
