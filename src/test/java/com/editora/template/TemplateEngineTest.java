package com.editora.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.editora.snippet.ParsedSnippet;
import com.editora.snippet.TabStop;
import com.editora.template.TemplateEngine.TemplateVar;

/** Unit tests for the pure template engine (discovery, substitution, path resolution). */
class TemplateEngineTest {

    private TemplateVariableResolver vars(Map<String, String> answers) {
        return new TemplateVariableResolver(answers, "Ada", "Proj", "", "Foo.java", "/d", "/d/Foo.java",
                LocalDateTime.of(2026, 6, 10, 9, 30, 0));
    }

    @Test
    void discoverVariablesExcludesBuiltinsAndKeepsDefaults() {
        List<TemplateVar> v = TemplateEngine.discoverVariables(
                "class ${className:Main} by ${author} on ${date} ${cursor} ${title}");
        // author/date/cursor are built-in → excluded; className (with default) + title remain, in order.
        assertEquals(List.of("className", "title"), v.stream().map(TemplateVar::name).toList());
        assertEquals("Main", v.get(0).defaultValue());
        assertEquals("", v.get(1).defaultValue());
    }

    @Test
    void discoverVariablesIsDistinctAcrossTexts() {
        List<TemplateVar> v = TemplateEngine.discoverVariables("${a}${b}", "${a:x}");
        assertEquals(List.of("a", "b"), v.stream().map(TemplateVar::name).toList());
        assertEquals("", v.get(0).defaultValue()); // first occurrence (no default) wins
    }

    @Test
    void substituteResolvesVarsAndCursorBecomesFinalCaret() {
        ParsedSnippet p = TemplateEngine.substitute("Hi ${author}!\n${cursor}", vars(Map.of()));
        assertEquals("Hi Ada!\n", p.text());
        // ${cursor} → $0: a single final-caret stop at the end.
        assertEquals(1, p.stops().size());
        TabStop s = p.stops().get(0);
        assertTrue(s.isFinal());
        assertEquals(p.text().length(), s.ranges().get(0)[0]);
    }

    @Test
    void substituteFallsBackToDefaultForUnknownVar() {
        ParsedSnippet p = TemplateEngine.substitute("${greeting:hello} ${author}", vars(Map.of()));
        assertEquals("hello Ada", p.text());
    }

    @Test
    void expandResolvesAFileNamePattern() {
        assertEquals("Widget.java",
                TemplateEngine.expand("${className:Main}.java", vars(Map.of("className", "Widget"))));
        // className is not built-in and not answered → its :default "Main" is used.
        assertEquals("Main.java", TemplateEngine.expand("${className:Main}.java", vars(Map.of())));
    }

    @Test
    void resolveTargetPathStaysInsideDir() {
        Path dir = Path.of("/work/proj");
        // baseName is a built-in derived from the file name "Foo.java" → "Foo".
        Path ok = TemplateEngine.resolveTargetPath(dir, "${baseName:index}.css", vars(Map.of()));
        assertEquals(dir.resolve("Foo.css").toAbsolutePath().normalize(), ok);
    }

    @Test
    void resolveTargetPathRejectsTraversal() {
        assertNull(TemplateEngine.resolveTargetPath(Path.of("/work/proj"), "../escape.txt", vars(Map.of())));
    }
}
