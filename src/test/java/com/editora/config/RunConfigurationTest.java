package com.editora.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunConfigurationTest {

    @Test
    void compactConstructorDefaultsNulls() {
        RunConfiguration c = new RunConfiguration(null, null, null, null, null, null);
        assertEquals("", c.name());
        assertEquals("java", c.type());
        assertEquals("", c.mainClass());
        assertEquals("", c.beforeLaunch());
    }

    @Test
    void jacksonRoundTrip() throws Exception {
        ObjectMapper m = new ObjectMapper();
        RunConfiguration c = new RunConfiguration(
                "App", "java", "", "com.app.Main", "core", "--x", "-Xmx1g", "/tmp", "FOO=bar", "mvn -q compile");
        RunConfiguration back = m.readValue(m.writeValueAsString(c), RunConfiguration.class);
        assertEquals(c, back);
        assertEquals("FOO=bar", back.env());
        assertEquals("mvn -q compile", back.beforeLaunch());
    }

    /**
     * A configuration saved before {@code kind} was removed must still load. The field is unknown now, so
     * Jackson drops it — which is the whole back-compat story, and the reason the schema step is identity.
     */
    @Test
    void aStoredKindIsIgnoredOnLoad() throws Exception {
        ObjectMapper m = new ObjectMapper();
        RunConfiguration old = m.readValue(
                "{\"name\":\"A\",\"kind\":\"debug\",\"mainClass\":\"com.app.Main\",\"workingDir\":\"\"}",
                RunConfiguration.class);
        assertEquals("A", old.name());
        assertEquals("com.app.Main", old.mainClass());
        // And it does not come back out again: the next write has no `kind` for an older build to misread.
        assertFalse(m.writeValueAsString(old).contains("kind"));
    }

    @Test
    void olderJsonDefaultsEnvToEmpty() throws Exception {
        assertEquals("", new RunConfiguration("A", "M", "", "", "", "").env());
        ObjectMapper m = new ObjectMapper();
        RunConfiguration old =
                m.readValue("{\"name\":\"A\",\"mainClass\":\"M\",\"workingDir\":\"\"}", RunConfiguration.class);
        assertEquals("", old.env());
    }

    /**
     * The run and debug commands must not share an id, or registering the second would silently replace the
     * first and one of the two verbs would become unreachable from the palette.
     */
    @Test
    void runAndDebugCommandIdsAreDistinct() {
        assertEquals("run.config.my-app", RunConfiguration.commandIdFor("My App"));
        assertEquals("debug.config.my-app", RunConfiguration.debugCommandIdFor("My App"));
        assertNotEquals(RunConfiguration.commandIdFor("My App"), RunConfiguration.debugCommandIdFor("My App"));
    }

    /** The debug id sits under {@code debug.} so Chrome's feature rule gates it with the rest of debugging. */
    @Test
    void theDebugCommandIdIsUnderTheDebugPrefix() {
        assertTrue(RunConfiguration.debugCommandIdFor("x").startsWith("debug."));
    }

    /**
     * The exact shape Settings → Run Configurations → <b>Add</b> creates. It is a Java configuration (the
     * type defaults to java) with a blank main class, so it was one click away from sending an empty search
     * string to jdtls and getting an internal NPE back.
     */
    @Test
    void aFreshlyAddedConfigurationIsMissingItsMainClass() {
        RunConfiguration added = new RunConfiguration("New configuration", "", "", "", "", "");
        assertEquals("java", added.type(), "Add creates a Java configuration");
        assertTrue(added.missingMainClass());
    }

    @Test
    void aFilledInJavaConfigurationIsNotMissingItsMainClass() {
        assertFalse(new RunConfiguration("A", "com.example.App", "", "", "", "").missingMainClass());
    }

    /** Whitespace is not a main class — jdtls builds its search pattern from it just the same. */
    @Test
    void aWhitespaceOnlyMainClassCountsAsMissing() {
        assertTrue(new RunConfiguration("A", "   ", "", "", "", "").missingMainClass());
    }

    /** Script types have no main class by design; their own missing-target check covers them. */
    @Test
    void scriptConfigurationsAreNeverMissingAMainClass() {
        for (String type : new String[] {"python", "shell", "make"}) {
            RunConfiguration script = new RunConfiguration("S", type, "app.py", "", "", "", "", "", "", "");
            assertFalse(script.missingMainClass(), type);
        }
    }

    @Test
    void jacksonToleratesMissingFields() throws Exception {
        ObjectMapper m = new ObjectMapper();
        RunConfiguration back = m.readValue("{\"name\":\"A\",\"mainClass\":\"M\"}", RunConfiguration.class);
        assertEquals("A", back.name());
        assertEquals("M", back.mainClass());
        assertEquals("java", back.type());
        assertEquals("", back.args());
    }

    @Test
    void aFileNameInTheMainClassFieldIsDetected() {
        // jdtls answers a file name with an EMPTY classpath and no error, which the launch would otherwise
        // report as "the project hasn't finished importing" — blaming a healthy server for a typo.
        assertTrue(new RunConfiguration("App", "App.java", "", "", "", "").mainClassLooksLikeAFile());
        assertTrue(new RunConfiguration("App", "App.class", "", "", "", "").mainClassLooksLikeAFile());
        assertFalse(new RunConfiguration("App", "com.example.App", "", "", "", "").mainClassLooksLikeAFile());
        assertFalse(new RunConfiguration("App", "", "", "", "", "").mainClassLooksLikeAFile());
    }
}
