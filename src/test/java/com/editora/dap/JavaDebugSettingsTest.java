package com.editora.dap;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What java-debug is told: the field names and shapes of its {@code DebugSettings} (checked in its bytecode). */
class JavaDebugSettingsTest {

    @Test
    void theDefaultsSkipTheJdkAndSyntheticsAndShowStatics() {
        JsonObject json =
                JsonParser.parseString(JavaDebugSettings.defaults().toJson()).getAsJsonObject();
        assertTrue(json.get("showStaticVariables").getAsBoolean());
        JsonObject filters = json.getAsJsonObject("stepFilters");
        assertTrue(filters.get("skipSynthetics").getAsBoolean());
        assertFalse(filters.get("skipConstructors").getAsBoolean(), "constructors are the user's code");
        assertFalse(filters.get("skipStaticInitializers").getAsBoolean());
        String skipped = filters.getAsJsonArray("skipClasses").toString();
        assertTrue(skipped.contains("\"java.*\"") && skipped.contains("\"sun.*\"") && skipped.contains("\"jdk.*\""));
        assertEquals(
                0,
                json.getAsJsonObject("exceptionFilters")
                        .getAsJsonArray("exceptionTypes")
                        .size());
    }

    @Test
    void turnedOffNothingIsSkippedAndStaticsAreHidden() {
        JsonObject json = JsonParser.parseString(new JavaDebugSettings(false, false, List.of("a.B")).toJson())
                .getAsJsonObject();
        assertFalse(json.get("showStaticVariables").getAsBoolean());
        assertEquals(
                0,
                json.getAsJsonObject("stepFilters")
                        .getAsJsonArray("skipClasses")
                        .size());
        assertEquals(
                "a.B",
                json.getAsJsonObject("exceptionFilters")
                        .getAsJsonArray("exceptionTypes")
                        .get(0)
                        .getAsString());
        assertTrue(json.get("exceptionFiltersUpdated").getAsBoolean());
    }

    @Test
    void exceptionTypesAreReadFromCommasSpacesOrSemicolons() {
        assertEquals(
                List.of("java.lang.IllegalStateException", "a.B", "c.D"),
                JavaDebugSettings.parseExceptionTypes(" java.lang.IllegalStateException, a.B;c.D  a.B "));
        assertEquals(List.of(), JavaDebugSettings.parseExceptionTypes("  "));
        assertEquals(List.of(), JavaDebugSettings.parseExceptionTypes(null));
    }
}
