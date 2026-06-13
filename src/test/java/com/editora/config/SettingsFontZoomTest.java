package com.editora.config;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies the persisted text-zoom factor round-trips through the TOML settings serialization. */
class SettingsFontZoomTest {

    @Test
    void fontZoomRoundTripsThroughToml() throws Exception {
        Settings s = new Settings();
        s.setFontZoom(1.2);

        TomlMapper mapper = new TomlMapper();
        String toml = mapper.writeValueAsString(s);
        Settings back = mapper.readValue(toml, Settings.class);

        org.junit.jupiter.api.Assertions.assertTrue(toml.contains("fontZoom"), "fontZoom should be written");
        assertEquals(1.2, back.getFontZoom(), 1e-9);
    }
}
