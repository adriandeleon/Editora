package com.editora.template;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.editora.snippet.ParsedSnippet;
import com.editora.snippet.TabStop;
import com.editora.template.TemplateEngine.TemplateVar;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure template engine (discovery, substitution, path resolution). */
class TemplateEngineTest {

    private TemplateVariableResolver vars(Map<String, String> answers) {
        return new TemplateVariableResolver(
                answers, "Ada", "Proj", "", "Foo.java", "/d", "/d/Foo.java", LocalDateTime.of(2026, 6, 10, 9, 30, 0));
    }

    @Test
    void discoverVariablesExcludesBuiltinsAndKeepsDefaults() {
        List<TemplateVar> v =
                TemplateEngine.discoverVariables("class ${className:Main} by ${author} on ${date} ${cursor} ${title}");
        // author/date/cursor are built-in → excluded; className (with default) + title remain, in order.
        assertEquals(
                List.of("className", "title"), v.stream().map(TemplateVar::name).toList());
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
    void discoverForNewFilePromptsFileIdentityUsedInTheFileNamePattern() {
        // ${baseName} in the file-name pattern can't be derived for a new file, so it must be prompted
        // (keeping its :default as the pre-fill); ${author}/${date} stay auto-resolved.
        List<TemplateVar> v = TemplateEngine.discoverVariablesForNewFile(
                "${baseName:Main}.java", "// ${author}\nclass ${baseName} {}\n${date}");
        assertEquals(List.of("baseName"), v.stream().map(TemplateVar::name).toList());
        assertEquals("Main", v.get(0).defaultValue());
    }

    @Test
    void discoverForNewFileLeavesBodyOnlyFileIdentityAutoDerived() {
        // baseName used ONLY in the body (fixed file name) is still derived, not prompted.
        List<TemplateVar> v = TemplateEngine.discoverVariablesForNewFile("Main.java", "class ${baseName} by ${author}");
        assertTrue(v.isEmpty());
    }

    @Test
    void promptedBaseNameFlowsIntoTheFileNameExpansion() {
        // With the prompted answer, ${baseName:Main}.java resolves to the user's value (answers win).
        assertEquals("Widget.java", TemplateEngine.expand("${baseName:Main}.java", vars(Map.of("baseName", "Widget"))));
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
        assertEquals(
                "Widget.java", TemplateEngine.expand("${className:Main}.java", vars(Map.of("className", "Widget"))));
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

    @Test
    void expandCollapsesADoubledExtensionFromABaseNameWithItsOwnExtension() {
        // The bug: ${baseName:document}.md + a baseName that already ends in ".md" produced "x.md.md".
        assertEquals(
                "todo-july-2-2026.md",
                TemplateEngine.expand("${baseName:document}.md", vars(Map.of("baseName", "todo-july-2-2026.md"))));
        // A plain baseName still gets the single extension appended.
        assertEquals(
                "todo-july-2-2026.md",
                TemplateEngine.expand("${baseName:document}.md", vars(Map.of("baseName", "todo-july-2-2026"))));
    }

    @Test
    void collapseDuplicateExtensionOnlyTouchesIdenticalTrailingExtensions() {
        assertEquals("foo.md", TemplateEngine.collapseDuplicateExtension("foo.md.md"));
        assertEquals("a/b/foo.js", TemplateEngine.collapseDuplicateExtension("a/b/foo.js.js"));
        // Different extensions are left alone.
        assertEquals("types.d.ts", TemplateEngine.collapseDuplicateExtension("types.d.ts"));
        assertEquals("archive.tar.gz", TemplateEngine.collapseDuplicateExtension("archive.tar.gz"));
        assertEquals("app.min.js", TemplateEngine.collapseDuplicateExtension("app.min.js"));
        // Nothing to collapse.
        assertEquals("foo.md", TemplateEngine.collapseDuplicateExtension("foo.md"));
        assertEquals("noext", TemplateEngine.collapseDuplicateExtension("noext"));
        // A dotted directory name is not mistaken for an extension.
        assertEquals("a.b/foo", TemplateEngine.collapseDuplicateExtension("a.b/foo"));
        assertNull(TemplateEngine.collapseDuplicateExtension(null));
        assertEquals("", TemplateEngine.collapseDuplicateExtension(""));
    }
}
