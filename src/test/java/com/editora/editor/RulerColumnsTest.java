package com.editora.editor;

import java.util.List;
import java.util.Map;

import com.editora.editorconfig.EditorConfigProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RulerColumnsTest {

    private static final Map<String, List<Integer>> PER_LANGUAGE =
            Map.of("python", List.of(79, 88), "markdown", List.of());

    @Test
    void theLanguagesOwnListWinsOverTheGlobalOne() {
        assertEquals(List.of(80, 120), RulerColumns.resolve(List.of(120, 80), PER_LANGUAGE, "java", null));
        assertEquals(List.of(79, 88), RulerColumns.resolve(List.of(80), PER_LANGUAGE, "python", null));
        assertEquals(List.of(), RulerColumns.resolve(List.of(80), PER_LANGUAGE, "markdown", null), "none for it");
        assertEquals(List.of(80), RulerColumns.resolve(List.of(80), null, null, null));
    }

    @Test
    void editorConfigReplacesTheFirstColumnOrHidesThemAll() {
        assertEquals(List.of(100, 120), RulerColumns.resolve(List.of(80, 120), Map.of(), "java", 100));
        assertEquals(List.of(72), RulerColumns.resolve(List.of(), Map.of(), "java", 72), "even with none set");
        assertEquals(List.of(), RulerColumns.resolve(List.of(80), Map.of(), "java", EditorConfigProperties.OFF));
    }

    @Test
    void columnsAreCleanedSortedAndUnique() {
        assertEquals(
                List.of(1, 80, 1000), RulerColumns.clean(java.util.Arrays.asList(80, null, 0, -3, 1001, 1000, 80, 1)));
    }

    @Test
    void parsesAndFormatsTheGlobalList() {
        assertEquals(List.of(80, 120), RulerColumns.parse(" 120, 80 "));
        assertEquals(List.of(80, 100, 120), RulerColumns.parse("80 100,120"));
        assertEquals(List.of(), RulerColumns.parse("  "));
        assertEquals(List.of(), RulerColumns.parse(null));
        assertNull(RulerColumns.parse("80, abc"));
        assertNull(RulerColumns.parse("0"));
        assertNull(RulerColumns.parse("1001"));
        assertEquals("80, 120", RulerColumns.format(List.of(80, 120)));
        assertEquals("", RulerColumns.format(List.of()));
    }

    @Test
    void parsesAndFormatsThePerLanguageForm() {
        Map<String, List<Integer>> read = RulerColumns.parseByLanguage("java: 100; python: 79, 88\nmarkdown:");
        assertEquals(Map.of("java", List.of(100), "python", List.of(79, 88), "markdown", List.of()), read);
        assertEquals("java: 100; python: 79, 88; markdown:", RulerColumns.formatByLanguage(read));
        assertEquals(Map.of(), RulerColumns.parseByLanguage(""));
        assertNull(RulerColumns.parseByLanguage("java 100"), "no colon");
        assertNull(RulerColumns.parseByLanguage(": 100"), "no language");
        assertNull(RulerColumns.parseByLanguage("java: x"));
    }

    @Test
    void aLanguageListIsFoundWhateverTheCaseItWasTypedIn() {
        assertEquals(List.of(100), RulerColumns.resolve(List.of(80), Map.of("Java", List.of(100)), "java", null));
        assertEquals(Map.of("java", List.of(100)), RulerColumns.parseByLanguage("Java: 100"), "stored lower-cased");
    }

    @Test
    void saysWhatIsWrongWithATypedList() {
        assertNull(RulerColumns.problem("80, 120"));
        assertNull(RulerColumns.problem(""));
        assertEquals(
                new RulerColumns.Problem(RulerColumns.Problem.Kind.NOT_A_COLUMN, ";"),
                RulerColumns.problem("80 ; 120"));
        assertEquals(
                new RulerColumns.Problem(RulerColumns.Problem.Kind.NOT_A_COLUMN, "2000"),
                RulerColumns.problem("80, 2000"));
        List<String> known = List.of("java", "python", "shell");
        assertNull(RulerColumns.byLanguageProblem("Java: 100; python: 79, 88; shell:", known));
        assertEquals(
                new RulerColumns.Problem(RulerColumns.Problem.Kind.UNKNOWN_LANGUAGE, "bash"),
                RulerColumns.byLanguageProblem("java: 100; bash: 80", known));
        assertEquals(
                new RulerColumns.Problem(RulerColumns.Problem.Kind.NO_LANGUAGE, "java 100"),
                RulerColumns.byLanguageProblem("java 100", known));
        assertEquals(
                new RulerColumns.Problem(RulerColumns.Problem.Kind.NOT_A_COLUMN, "x"),
                RulerColumns.byLanguageProblem("java: x", known));
    }
}
