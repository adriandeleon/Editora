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

    /**
     * The exact shape Settings → Run Configurations → <b>Add</b> creates. It is a Java configuration (the
     * type defaults to java) with a blank main class, so it was one click away from sending an empty search
     * string to jdtls and getting an internal NPE back.
     */
    @Test
    void aFreshlyAddedConfigurationIsMissingItsMainClass() {
        RunConfiguration added = new RunConfiguration("New configuration", "run", "", "", "", "", "");
        assertEquals("java", added.type(), "Add creates a Java configuration");
        assertTrue(added.missingMainClass());
    }

    @Test
    void aFilledInJavaConfigurationIsNotMissingItsMainClass() {
        assertFalse(new RunConfiguration("A", "run", "com.example.App", "", "", "", "").missingMainClass());
    }

    /** Whitespace is not a main class — jdtls builds its search pattern from it just the same. */
    @Test
    void aWhitespaceOnlyMainClassCountsAsMissing() {
        assertTrue(new RunConfiguration("A", "run", "   ", "", "", "", "").missingMainClass());
    }

    /** Script types have no main class by design; their own missing-target check covers them. */
    @Test
    void scriptConfigurationsAreNeverMissingAMainClass() {
        for (String type : new String[] {"python", "shell", "make"}) {
            RunConfiguration script = new RunConfiguration("S", "run", type, "app.py", "", "", "", "", "", "", "");
            assertFalse(script.missingMainClass(), type);
        }
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

    @Test
    void aFileNameInTheMainClassFieldIsDetected() {
        // jdtls answers a file name with an EMPTY classpath and no error, which the launch would otherwise
        // report as "the project hasn't finished importing" — blaming a healthy server for a typo.
        assertTrue(new RunConfiguration("App", "run", "App.java", "", "", "", "").mainClassLooksLikeAFile());
        assertTrue(new RunConfiguration("App", "run", "App.class", "", "", "", "").mainClassLooksLikeAFile());
        assertFalse(new RunConfiguration("App", "run", "com.example.App", "", "", "", "").mainClassLooksLikeAFile());
        assertFalse(new RunConfiguration("App", "run", "", "", "", "", "").mainClassLooksLikeAFile());
    }
}
