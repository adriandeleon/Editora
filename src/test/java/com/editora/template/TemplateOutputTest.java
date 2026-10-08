package com.editora.template;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.editora.config.ConfigManager;
import com.editora.editorconfig.EditorConfigProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Planning and writing a template's files against a temp directory (no toolkit). */
class TemplateOutputTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 2, 3, 4);

    private static Template bundled(Path tmp, String id) {
        return new TemplateRegistry(new ConfigManager(tmp.resolve("cfg")))
                .bundledTemplates().stream()
                        .filter(t -> t.id().equals(id))
                        .findFirst()
                        .orElseThrow();
    }

    private static TemplateOutput.Request request(Template t, Path dir, Map<String, String> answers) {
        return request(t, dir, answers, null, null);
    }

    private static TemplateOutput.Request request(
            Template t,
            Path dir,
            Map<String, String> answers,
            Path projectRoot,
            Function<Path, EditorConfigProperties> rules) {
        return new TemplateOutput.Request(t, dir, answers, "Ada", "Proj", projectRoot, NOW, rules);
    }

    private static List<String> tree(Path dir) throws Exception {
        try (var s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    // --- T6: plan first, write second --------------------------------------------------------------

    @Test
    void thePlanListsEveryFileAndWhichOnesAlreadyExistWithoutWritingAnything(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("proj"));
        Files.writeString(dir.resolve("README.md"), "MINE");
        Files.writeString(dir.resolve("pyproject.toml"), "MINE");
        TemplateOutput.Plan plan =
                TemplateOutput.plan(request(bundled(tmp, "python-project"), dir, Map.of("packageName", "shop")));

        assertFalse(plan.refused());
        assertEquals(6, plan.targets().size());
        assertEquals(
                List.of("pyproject.toml", "README.md"),
                plan.existing().stream().map(TemplateOutput.Target::relative).toList());
        assertEquals(
                List.of("src/shop/__init__.py", "src/shop/main.py", "tests/test_main.py", ".gitignore"),
                plan.missing().stream().map(TemplateOutput.Target::relative).toList());
        assertEquals(List.of("README.md", "pyproject.toml"), tree(dir), "planning writes nothing");
    }

    @Test
    void writingCreatesOnlyTheMissingFilesAndSaysWhichWereKept(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("proj"));
        Files.writeString(dir.resolve("README.md"), "MINE");
        TemplateOutput.Outcome outcome = TemplateOutput.write(
                TemplateOutput.plan(request(bundled(tmp, "python-project"), dir, Map.of("packageName", "shop"))));

        assertFalse(outcome.failed());
        assertEquals(5, outcome.created().size());
        assertEquals(
                List.of("README.md"),
                outcome.skipped().stream().map(TemplateOutput.Target::relative).toList());
        assertEquals("MINE", Files.readString(dir.resolve("README.md")), "an existing file is never overwritten");
        assertTrue(Files.readString(dir.resolve("src/shop/main.py")).contains("Hello from shop"));
    }

    @Test
    void aFileThatAppearsAfterThePlanIsStillNotOverwritten(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("proj"));
        TemplateOutput.Plan plan = TemplateOutput.plan(request(bundled(tmp, "html-bundle"), dir, Map.of()));
        Files.writeString(dir.resolve("index.css"), "RACE");
        TemplateOutput.Outcome outcome = TemplateOutput.write(plan);
        assertEquals("RACE", Files.readString(dir.resolve("index.css")));
        assertEquals(1, outcome.created().size());
        assertEquals(1, outcome.skipped().size());
    }

    @Test
    void aWriteThatFailsMidwayReportsExactlyWhatWasCreated(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("proj"));
        Template t = new Template(
                "three",
                "Three",
                "",
                "",
                "",
                "",
                List.of(
                        new TemplateFile("a.txt", "a"),
                        new TemplateFile("blocked/b.txt", "b"),
                        new TemplateFile("c.txt", "c")));
        TemplateOutput.Plan plan = TemplateOutput.plan(request(t, dir, Map.of()));
        Files.writeString(dir.resolve("blocked"), "a file where a folder is needed");
        TemplateOutput.Outcome outcome = TemplateOutput.write(plan);

        assertTrue(outcome.failed());
        assertEquals("blocked/b.txt", outcome.failedAt().relative());
        assertNotNull(outcome.error());
        assertEquals(
                List.of("a.txt"),
                outcome.created().stream().map(TemplateOutput.Target::relative).toList());
        assertFalse(Files.exists(dir.resolve("c.txt")), "it stops at the failure");
    }

    @Test
    void anEscapingPathRefusesTheWholeTemplate(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("proj"));
        Template t = new Template(
                "esc",
                "Esc",
                "",
                "",
                "",
                "",
                List.of(new TemplateFile("ok.txt", "ok"), new TemplateFile("../${name}.txt", "out")));
        TemplateOutput.Plan plan = TemplateOutput.plan(request(t, dir, Map.of("name", "pwn")));
        assertTrue(plan.refused());
        assertEquals("../pwn.txt", plan.refusedPath());
        assertTrue(TemplateOutput.write(plan).created().isEmpty());
        assertEquals(List.of(), tree(tmp.resolve("proj")), "not even the file before the bad one");

        Template single = new Template("s", "S", "", "", "${name}.txt", "x", null);
        assertTrue(TemplateOutput.plan(request(single, dir, Map.of("name", "../up")))
                .refused());
        assertTrue(
                TemplateOutput.plan(request(single, dir, Map.of("name", "a?b"))).refused());
    }

    @Test
    void aSymlinkedFolderCannotReceiveAFile(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("target"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        try {
            Files.createSymbolicLink(dir.resolve("link"), outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("no symbolic links here: " + e);
        }
        Template t = new Template(
                "esc",
                "Esc",
                "",
                "",
                "",
                "",
                List.of(new TemplateFile("ok.txt", "ok"), new TemplateFile("link/pwn.txt", "pwned")));
        TemplateOutput.Plan plan = TemplateOutput.plan(request(t, dir, Map.of()));
        assertTrue(plan.refused(), "the reviewer's reproduction: link/pwn.txt landed in outside/");
        assertEquals(List.of(), tree(outside));
        // The writer re-checks too, for a link that appears after the plan.
        org.junit.jupiter.api.Assertions.assertThrows(
                java.io.IOException.class, () -> TemplateOutput.create(dir, dir.resolve("link/late.txt"), "x"));
        assertEquals(List.of(), tree(outside));
    }

    // --- T13: the file to open is the one that marks the cursor -------------------------------------

    @Test
    void thePrimaryFileIsTheOneWithTheCursorNotTheFirstWritten(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("proj"));
        TemplateOutput.Outcome outcome = TemplateOutput.write(
                TemplateOutput.plan(request(bundled(tmp, "python-project"), dir, Map.of("packageName", "shop"))));
        TemplateOutput.Target primary = outcome.primary();
        assertEquals("src/shop/main.py", primary.relative());
        assertEquals(primary.text().indexOf("print(\"Hello"), primary.caret());
        // No cursor anywhere: the first file created.
        Template plain = new Template(
                "p", "P", "", "", "", "", List.of(new TemplateFile("a.txt", "a"), new TemplateFile("b.txt", "b")));
        Path other = Files.createDirectories(tmp.resolve("other"));
        assertEquals(
                "a.txt",
                TemplateOutput.write(TemplateOutput.plan(request(plain, other, Map.of())))
                        .primary()
                        .relative());
    }

    // --- T5 / T12: the Java package comes from the folder -------------------------------------------

    @Test
    void theJavaClassTemplateDeclaresThePackageOfItsFolder(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("proj");
        Path pkg = Files.createDirectories(root.resolve("src/main/java/demo"));
        Template java = bundled(tmp, "java-class");
        TemplateOutput.Plan plan = TemplateOutput.plan(request(java, pkg, Map.of("className", "Bar"), root, null));
        TemplateOutput.Target target = plan.targets().get(0);
        assertEquals("Bar.java", target.relative());
        assertTrue(target.text().startsWith("package demo;\n\npublic class Bar {"), target.text());
        assertEquals(target.text().indexOf("\n    }"), target.caret(), "the caret follows the package line");

        // Outside a source root there is no package line — and no stray blank lines either.
        Path loose = Files.createDirectories(root.resolve("docs"));
        TemplateOutput.Target none = TemplateOutput.plan(request(java, loose, Map.of("className", "Bar"), root, null))
                .targets()
                .get(0);
        assertTrue(none.text().startsWith("public class Bar {"), none.text());
        // A template may also use the bare name.
        Template named = new Template("n", "N", "", "java", "X.java", "// in ${packageName:none}", null);
        assertEquals(
                "// in demo\n",
                TemplateOutput.plan(request(named, pkg, Map.of(), root, null))
                        .targets()
                        .get(0)
                        .text());
        assertEquals(
                "// in none\n",
                TemplateOutput.plan(request(named, loose, Map.of(), root, null))
                        .targets()
                        .get(0)
                        .text());
    }

    @Test
    void anUntitledBufferGetsTheSuggestedNameAndNoFile(@TempDir Path tmp) {
        TemplateOutput.Untitled u =
                TemplateOutput.untitled(request(bundled(tmp, "java-class"), null, Map.of("className", "Foo")));
        assertEquals("Foo.java", u.fileName());
        assertTrue(u.rendered().text().startsWith("public class Foo {"));
        assertTrue(u.rendered().hasCursor());
    }

    // --- T18: what goes to disk ----------------------------------------------------------------------

    @Test
    void writtenFilesEndWithANewlineAndAShebangFileIsExecutable(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("sh"));
        Map<String, String> answers = Map.of("baseName", "run", "summary", "s");
        TemplateOutput.write(TemplateOutput.plan(request(bundled(tmp, "shell-script"), dir, answers)));
        TemplateOutput.write(TemplateOutput.plan(request(bundled(tmp, "zsh-script"), dir, answers)));
        TemplateOutput.write(
                TemplateOutput.plan(request(bundled(tmp, "markdown-doc"), dir, Map.of("baseName", "doc"))));

        String sh = Files.readString(dir.resolve("run.sh"));
        assertTrue(sh.endsWith("main \"$@\"\n"), "final newline, and $@ survives: " + sh);
        assertFalse(sh.endsWith("\n\n"));
        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertTrue(Files.isExecutable(dir.resolve("run.sh")), "a file that starts with #! is executable");
            assertTrue(Files.isExecutable(dir.resolve("run.zsh")));
            assertFalse(Files.isExecutable(dir.resolve("doc.md")), "nothing else is");
        }
    }

    @Test
    void aUserShellTemplateKeepsItsDollars(@TempDir Path tmp) throws Exception {
        // The reviewer's reproduction: this wrote `echo "" ""`.
        Template sh = new Template(
                "sh-user",
                "ShUser",
                "",
                "",
                "run.sh",
                "#!/bin/sh\necho \"$HOME\" \"$1\"\nawk '{print $1}'\n${cursor}",
                null);
        Path dir = Files.createDirectories(tmp.resolve("sh2"));
        TemplateOutput.write(TemplateOutput.plan(request(sh, dir, Map.of())));
        assertEquals("#!/bin/sh\necho \"$HOME\" \"$1\"\nawk '{print $1}'\n", Files.readString(dir.resolve("run.sh")));
    }

    @Test
    void theProjectsEditorConfigDecidesLineEndingsAndTheFinalNewline() {
        EditorConfigProperties crlf = new EditorConfigProperties(null, null, null, "crlf", null, true, null, null);
        assertEquals("a\r\n    \r\nb\r\n", TemplateOutput.finish("a\n    \nb", crlf));
        EditorConfigProperties noFinal = new EditorConfigProperties(null, null, null, null, null, null, false, null);
        assertEquals("a\nb", TemplateOutput.finish("a\nb", noFinal));
        assertEquals("a\nb", TemplateOutput.finish("a\nb\n", noFinal));
        assertEquals("a\nb\n", TemplateOutput.finish("a\nb", null));
        assertEquals("a\nb\n", TemplateOutput.finish("a\nb\n", EditorConfigProperties.EMPTY));
        assertEquals("", TemplateOutput.finish("", null), "an empty file stays empty");
    }

    @Test
    void theRulesAreAskedPerFile(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("proj"));
        EditorConfigProperties crlf = new EditorConfigProperties(null, null, null, "crlf", null, null, null, null);
        Function<Path, EditorConfigProperties> rules =
                p -> p.getFileName().toString().endsWith(".css") ? crlf : EditorConfigProperties.EMPTY;
        TemplateOutput.write(TemplateOutput.plan(request(bundled(tmp, "html-bundle"), dir, Map.of(), null, rules)));
        assertTrue(Files.readString(dir.resolve("index.css")).contains("\r\n"));
        assertFalse(Files.readString(dir.resolve("index.html")).contains("\r"));
        assertNull(
                TemplateOutput.write(TemplateOutput.plan(request(bundled(tmp, "html-bundle"), dir, Map.of())))
                        .primary(),
                "nothing left to create");
    }
}
