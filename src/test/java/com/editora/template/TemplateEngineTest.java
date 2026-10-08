package com.editora.template;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.editora.config.ConfigManager;
import com.editora.template.TemplateEngine.TemplateVar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertEquals("x", v.get(0).defaultValue()); // the first default given is the wizard's pre-fill
        assertEquals(
                "y", TemplateEngine.discoverVariables("${a:y}", "${a:z}").get(0).defaultValue());
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
    void renderResolvesVarsAndReportsTheCursorOffset() {
        TemplateEngine.Rendered r = TemplateEngine.render("Hi ${author}!\n${cursor}after", vars(Map.of()));
        assertEquals("Hi Ada!\nafter", r.text());
        assertTrue(r.hasCursor());
        assertEquals("Hi Ada!\n".length(), r.caret());
        // No ${cursor}: the caret goes to the end, and the caller can tell it was not marked.
        TemplateEngine.Rendered plain = TemplateEngine.render("abc", vars(Map.of()));
        assertEquals(3, plain.caret());
        assertFalse(plain.hasCursor());
    }

    @Test
    void renderFallsBackToDefaultForUnknownVar() {
        assertEquals(
                "hello Ada",
                TemplateEngine.render("${greeting:hello} ${author}", vars(Map.of()))
                        .text());
    }

    // --- T8: a template body is a file, not a snippet ---------------------------------------------

    @Test
    void dollarsThatAreNotATemplateVariableAreWrittenAsTheyAre() {
        String[] literal = {
            "echo \"$HOME\" \"$1\" \"$@\" $# $$ $? $0",
            "awk '{print $1}'",
            "price: $5 and $name",
            "${arr[0]} ${#arr[@]} ${1:-default} ${!ref} ${var%suffix} $(date) $((1+2))",
            "printf(\"a\\n\"); // C:\\dir and TeX \\\\ and \\$ and \\}",
            "}{ ${ ${} ${:x} ${9lives}",
        };
        for (String body : literal) {
            assertEquals(body, TemplateEngine.render(body, vars(Map.of())).text(), body);
            assertTrue(TemplateEngine.discoverVariables(body).isEmpty(), "nothing to ask for in: " + body);
        }
    }

    @Test
    void aDoubledDollarBeforeABraceWritesALiteralReference() {
        TemplateEngine.Rendered r = TemplateEngine.render("$${HOME}/bin ${author} $${cursor} $$ $$x", vars(Map.of()));
        assertEquals("${HOME}/bin Ada ${cursor} $$ $$x", r.text());
        assertFalse(r.hasCursor(), "an escaped ${cursor} is text");
        assertTrue(TemplateEngine.discoverVariables("$${HOME} $${title:x}").isEmpty());
    }

    @Test
    void aVariableNobodyAnsweredStaysVisibleRatherThanVanishing() {
        // Not built-in, no default, no answer: the reference is kept, so the mistake shows in the file.
        assertEquals(
                "a ${mystery} b",
                TemplateEngine.render("a ${mystery} b", vars(Map.of())).text());
        // A built-in with nothing to say is empty, as before.
        TemplateVariableResolver noFile =
                new TemplateVariableResolver(Map.of(), "", "", "", "", "", "", LocalDateTime.of(2026, 1, 2, 3, 4));
        assertEquals("[]", TemplateEngine.render("[${baseName}]", noFile).text());
        assertEquals("[x]", TemplateEngine.render("[${baseName:x}]", noFile).text());
    }

    @Test
    void onlyTheFirstCursorCounts() {
        TemplateEngine.Rendered r = TemplateEngine.render("a${cursor}b${cursor}c", vars(Map.of()));
        assertEquals("abc", r.text());
        assertEquals(1, r.caret());
    }

    /** Every bundled template renders exactly as it did before templates stopped using the snippet parser. */
    @Test
    void bundledTemplatesRenderAsTheyAlwaysHave(@TempDir Path tmp) throws Exception {
        String golden;
        try (var in = TemplateEngineTest.class.getResourceAsStream("/com/editora/template/bundled-golden.txt")) {
            assertNotNull(in, "golden file missing");
            golden = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        TemplateRegistry reg = new TemplateRegistry(new ConfigManager(tmp.resolve("cfg")));
        Map<String, String> answers = Map.of(
                "className",
                "Widget",
                "baseName",
                "sample",
                "title",
                "My Title",
                "summary",
                "Sum",
                "description",
                "Desc",
                "packageName",
                "pkg");
        LocalDateTime now = LocalDateTime.of(2026, 1, 2, 3, 4);
        StringBuilder sb = new StringBuilder();
        for (Template t : reg.bundledTemplates()) {
            sb.append("=== ").append(t.id()).append('\n');
            TemplateVariableResolver pre = new TemplateVariableResolver(answers, "Ada", "Proj", "", "", "/d", "", now);
            if (t.isMultiFile()) {
                for (TemplateFile f : t.files()) {
                    sb.append("--- ")
                            .append(TemplateEngine.expand(f.path(), pre))
                            .append('\n')
                            .append(TemplateEngine.render(f.body(), pre).text())
                            .append("\n--- end\n");
                }
            } else {
                String fileName = TemplateEngine.expand(t.fileName(), pre);
                TemplateVariableResolver vars =
                        new TemplateVariableResolver(answers, "Ada", "Proj", "", fileName, "/d", "/d/" + fileName, now);
                TemplateEngine.Rendered r = TemplateEngine.render(t.body(), vars);
                sb.append("--- ")
                        .append(fileName)
                        .append(" caret=")
                        .append(r.caret())
                        .append('\n')
                        .append(r.text())
                        .append("\n--- end\n");
            }
        }
        assertEquals(golden, sb.toString());
        assertEquals(9, reg.bundledTemplates().size(), "README and docs say nine templates ship");
    }

    // --- T5: which variables the wizard asks for --------------------------------------------------

    private static Template bundled(Path tmp, String id) {
        return new TemplateRegistry(new ConfigManager(tmp.resolve("cfg")))
                .bundledTemplates().stream()
                        .filter(t -> t.id().equals(id))
                        .findFirst()
                        .orElseThrow();
    }

    private static List<String> asked(Template t, boolean projectKnown) {
        return TemplateEngine.promptedVariables(t, new TemplateEngine.Context(projectKnown)).stream()
                .map(TemplateVar::name)
                .toList();
    }

    @Test
    void multiFileTemplatesAskForTheirMainName(@TempDir Path tmp) {
        // Python Project always created package "app"; HTML + CSS always created index.html.
        List<TemplateVar> python =
                TemplateEngine.promptedVariables(bundled(tmp, "python-project"), TemplateEngine.Context.NONE);
        assertEquals(
                List.of("packageName", "description"),
                python.stream().map(TemplateVar::name).toList());
        assertEquals("app", python.get(0).defaultValue(), "the template default is the pre-fill");
        assertEquals(List.of("baseName", "title"), asked(bundled(tmp, "html-bundle"), false));
    }

    @Test
    void aSingleJavaFileDerivesItsPackageFromTheFolderInsteadOfAsking(@TempDir Path tmp) {
        assertEquals(List.of("className"), asked(bundled(tmp, "java-class"), false));
        Template java = new Template("j", "J", "", "java", "${baseName:Main}.java", "package ${packageName};", null);
        assertEquals(List.of("baseName"), asked(java, false));
        // The same variable in a non-Java single file has nothing to derive it from.
        Template py = new Template("p", "P", "", "python", "setup.py", "name=${packageName:app}", null);
        assertEquals(List.of("packageName"), asked(py, false));
    }

    @Test
    void projectNameIsAskedOnlyWhenThereIsNoProject() {
        Template t = new Template("r", "R", "", "", "README.md", "# ${projectName:My Project}\n${author}", null);
        assertEquals(List.of("projectName"), asked(t, false));
        assertEquals(List.of(), asked(t, true));
    }

    @Test
    void aPromptedBuiltInTakesItsAnswerEverywhere() {
        // The wizard's answer for packageName must reach every use, with or without a default.
        TemplateVariableResolver vars = new TemplateVariableResolver(
                Map.of("packageName", "shop"), "", "", "", "", "/d", "", LocalDateTime.of(2026, 1, 2, 3, 4));
        assertEquals(
                "shop/shop",
                TemplateEngine.render("${packageName:app}/${packageName}", vars).text());
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

    // --- T11: containment is about where the file really lands ------------------------------------

    @Test
    void aSymlinkedFolderInsideTheTargetCannotLeadOutOfIt(@TempDir Path tmp) throws Exception {
        Path dir = java.nio.file.Files.createDirectories(tmp.resolve("target"));
        Path outside = java.nio.file.Files.createDirectories(tmp.resolve("outside"));
        try {
            java.nio.file.Files.createSymbolicLink(dir.resolve("link"), outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("no symbolic links here: " + e);
        }
        java.nio.file.Files.createDirectories(dir.resolve("real"));
        assertNull(TemplateEngine.resolveTargetPath(dir, "link/pwn.txt", vars(Map.of())));
        assertNull(TemplateEngine.resolveTargetPath(dir, "link/deeper/pwn.txt", vars(Map.of())));
        assertNotNull(TemplateEngine.resolveTargetPath(dir, "real/ok.txt", vars(Map.of())));
        assertNotNull(TemplateEngine.resolveTargetPath(dir, "new/ok.txt", vars(Map.of())));
        // A target folder that is itself reached through a link is fine: containment is canonical on both sides.
        Path alias = tmp.resolve("alias");
        java.nio.file.Files.createSymbolicLink(alias, dir);
        assertNotNull(TemplateEngine.resolveTargetPath(alias, "real/ok.txt", vars(Map.of())));
    }

    @Test
    void anExpandedPathMustBeARelativePortableFilePath(@TempDir Path dir) {
        TemplateVariableResolver hostile = vars(Map.of("n", "../../evil", "w", "sub\\..\\..\\x", "q", "what?"));
        for (String bad : new String[] {
            "${n}.txt",
            "/etc/x",
            "../x",
            "a/../../x",
            "ok/${n}",
            "${w}",
            "C:\\x",
            "  ",
            "sub/",
            "${q}.txt",
            "CON",
            "a/./b"
        }) {
            assertNull(TemplateEngine.resolveTargetPath(dir, bad, hostile), bad);
        }
        assertEquals(
                dir.toAbsolutePath().normalize().resolve("src/pkg/main.py"),
                TemplateEngine.resolveTargetPath(dir, "src/${p:pkg}/main.py", hostile));
        assertNotNull(TemplateEngine.resolveTargetPath(dir, ".gitignore", hostile));
    }
}
