package com.editora.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunConfigurationTest {

    @Test
    void compactConstructorDefaultsNulls() {
        RunConfiguration c = new RunConfiguration(null, null, null, null, null, null, null);
        assertEquals("", c.name());
        assertEquals("run", c.kind());
        assertEquals("", c.mainClass());
        assertFalse(c.isDebug());
    }

    @Test
    void isDebugIgnoresCase() {
        assertTrue(new RunConfiguration("x", "DEBUG", "M", "", "", "", "").isDebug());
        assertTrue(new RunConfiguration("x", "debug", "M", "", "", "", "").isDebug());
        assertFalse(new RunConfiguration("x", "run", "M", "", "", "", "").isDebug());
    }

    @Test
    void jacksonRoundTrip() throws Exception {
        ObjectMapper m = new ObjectMapper();
        RunConfiguration c =
                new RunConfiguration("App", "debug", "com.app.Main", "core", "--x", "-Xmx1g", "/tmp", "FOO=bar");
        RunConfiguration back = m.readValue(m.writeValueAsString(c), RunConfiguration.class);
        assertEquals(c, back);
        assertEquals("FOO=bar", back.env());
    }

    @Test
    void backCompatConstructorAndOlderJsonDefaultEnvToEmpty() throws Exception {
        // A config saved before `env` existed must still load (and the 7-arg ctor keeps old call sites working).
        assertEquals("", new RunConfiguration("A", "run", "M", "", "", "", "").env());
        ObjectMapper m = new ObjectMapper();
        RunConfiguration old = m.readValue(
                "{\"name\":\"A\",\"kind\":\"run\",\"mainClass\":\"M\",\"workingDir\":\"\"}", RunConfiguration.class);
        assertEquals("", old.env());
    }

    @Test
    void jacksonToleratesMissingFields() throws Exception {
        ObjectMapper m = new ObjectMapper();
        RunConfiguration back = m.readValue("{\"name\":\"A\",\"mainClass\":\"M\"}", RunConfiguration.class);
        assertEquals("A", back.name());
        assertEquals("M", back.mainClass());
        assertEquals("run", back.kind());
        assertEquals("", back.args());
    }
}
