package com.editora.ui;

import java.util.List;

import com.editora.build.BuildAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one filter rule both build-tool views use (review B29). */
class BuildActionsFilterTest {

    private static final List<BuildAction.Section> SECTIONS = List.of(
            new BuildAction.Section(
                    "Lifecycle",
                    List.of(
                            new BuildAction.Task("clean", List.of("clean")),
                            new BuildAction.Task("package", List.of("package")))),
            new BuildAction.Section(
                    "spring-boot",
                    List.of(
                            new BuildAction.Task("spring-boot:run", List.of("spring-boot:run")),
                            new BuildAction.Task("spring-boot:repackage", List.of("spring-boot:repackage"))),
                    true,
                    "Goals of spring-boot 3.5.3"));

    private static List<String> titles(List<BuildAction.Section> sections) {
        return sections.stream().map(BuildAction.Section::title).toList();
    }

    @Test
    void substringNotFuzzy() {
        assertEquals(List.of(), BuildActionsFilter.filtered(SECTIONS, "sbr"), "fuzzy subsequences do not match");
        List<BuildAction.Section> run = BuildActionsFilter.filtered(SECTIONS, "boot:run");
        assertEquals(List.of("spring-boot"), titles(run));
        assertEquals(1, run.get(0).rows().size());
        assertTrue(run.get(0).collapsed(), "the collapsed flag survives a row match");
        assertEquals("Goals of spring-boot 3.5.3", run.get(0).tooltip(), "and so does the tooltip");
    }

    @Test
    void aSectionTitleMatchKeepsTheWholeSection() {
        List<BuildAction.Section> life = BuildActionsFilter.filtered(SECTIONS, "life");
        assertEquals(List.of("Lifecycle"), titles(life));
        assertEquals(2, life.get(0).rows().size());
    }

    @Test
    void normalizeStripsAndLowerCases() {
        assertEquals("pack", BuildActionsFilter.normalize("  PACK "));
        assertEquals("", BuildActionsFilter.normalize(null));
    }
}
