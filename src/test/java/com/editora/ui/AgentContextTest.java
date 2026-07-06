package com.editora.ui;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure tests for {@link AgentCoordinator#formatContext} — the one-line context header prefixed to an
 *  agent prompt (path/cursor/selection). */
class AgentContextTest {

    @BeforeAll
    static void initMessages() {
        Messages.init("en");
    }

    @Test
    void noSelectionIsJustPathAndLine() {
        assertEquals(
                "Context: src/Foo.java, cursor at line 23", AgentCoordinator.formatContext("src/Foo.java", 23, ""));
        assertEquals(
                "Context: src/Foo.java, cursor at line 23", AgentCoordinator.formatContext("src/Foo.java", 23, null));
    }

    @Test
    void shortSelectionIsAppended() {
        assertEquals(
                "Context: src/Foo.java, cursor at line 23, selected: \"int x = 1;\"",
                AgentCoordinator.formatContext("src/Foo.java", 23, "int x = 1;"));
    }

    @Test
    void longSelectionIsTruncatedWithEllipsis() {
        String selection = "x".repeat(250);
        String result = AgentCoordinator.formatContext("src/Foo.java", 1, selection);
        assertEquals("Context: src/Foo.java, cursor at line 1, selected: \"" + "x".repeat(200) + "…\"", result);
    }

    @Test
    void multilineSelectionStaysOneLine() {
        assertEquals(
                "Context: src/Foo.java, cursor at line 5, selected: \"a\\nb\"",
                AgentCoordinator.formatContext("src/Foo.java", 5, "a\nb"));
    }
}
