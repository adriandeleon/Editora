package com.editora.jstools;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How ESLint, Prettier and Biome are started, and what their output means (JS review F). */
class JsToolRunTest {

    private static final Path ROOT = Path.of("/p").toAbsolutePath();
    private static final Path FILE = ROOT.resolve("src/a.ts");
    private static final List<String> NODE = List.of("/n/bin/node");

    private static JsTools.Tool tool(JsTools.Kind kind, String script) {
        return new JsTools.Tool(kind, ROOT.resolve(script), true, ROOT);
    }

    @Test
    void eslintReadsStdinAsTheFileFromItsConfigurationsFolder() {
        var config = new JsTools.EslintConfig(ROOT, ROOT.resolve(".eslintrc.json"), false);
        var inv = JsToolRun.eslint(
                tool(JsTools.Kind.ESLINT, "node_modules/eslint/bin/eslint.js"),
                config,
                FILE,
                NODE,
                Path.of("/n/bin"),
                false);
        assertEquals(
                List.of(
                        "/n/bin/node",
                        ROOT.resolve("node_modules/eslint/bin/eslint.js").toString(),
                        "--format",
                        "json",
                        "--stdin",
                        "--stdin-filename",
                        FILE.toString()),
                inv.argv());
        assertEquals(ROOT, inv.dir());
        assertEquals("false", inv.env().get("ESLINT_USE_FLAT_CONFIG"), "an .eslintrc: ESLint 9 told to read it");
        assertTrue(
                inv.env().get("PATH").startsWith(Path.of("/n/bin") + File.pathSeparator), "the project's Node first");

        var flat = new JsTools.EslintConfig(ROOT, ROOT.resolve("eslint.config.js"), true);
        var fix = JsToolRun.eslint(tool(JsTools.Kind.ESLINT, "e.js"), flat, FILE, NODE, null, true);
        assertEquals("--fix-dry-run", fix.argv().get(fix.argv().size() - 1));
        assertEquals("true", fix.env().get("ESLINT_USE_FLAT_CONFIG"));
        assertFalse(fix.env().containsKey("PATH"), "a Node.js from PATH: PATH as it is");
    }

    @Test
    void prettierTakesARangeAndBiomeFormatsWholeFilesFromItsConfigurationsFolder() {
        var prettier = new JsTools.FormatPlan(
                JsTools.Formatter.PRETTIER,
                tool(JsTools.Kind.PRETTIER, "node_modules/prettier/bin/prettier.cjs"),
                ROOT,
                null);
        var whole = JsToolRun.format(prettier, FILE, NODE, null, null);
        assertEquals(List.of("--stdin-filepath", FILE.toString()), whole.argv().subList(2, 4));
        var ranged = JsToolRun.format(prettier, FILE, NODE, null, new int[] {10, 42});
        assertEquals(
                List.of("--stdin-filepath", FILE.toString(), "--range-start", "10", "--range-end", "42"),
                ranged.argv().subList(2, 8));

        Path sub = ROOT.resolve("pkg");
        var biome = new JsTools.FormatPlan(
                JsTools.Formatter.BIOME, tool(JsTools.Kind.BIOME, "node_modules/@biomejs/biome/bin/biome"), sub, null);
        var b = JsToolRun.format(biome, FILE, NODE, null, new int[] {1, 2});
        assertEquals(List.of("format", "--stdin-file-path=" + FILE), b.argv().subList(2, 4));
        assertEquals(4, b.argv().size(), "no range for Biome");
        assertEquals(sub, b.dir());
        assertNull(b.env().get("ESLINT_USE_FLAT_CONFIG"));
    }

    @Test
    void aFormattersOutputIsTheTextAndAnythingElseIsItsFirstErrorLine() {
        var ok = JsToolRun.formatOutcome(
                JsTools.Kind.PRETTIER, new JsToolRun.Output(0, "x = 1;\n", "", false, false), "x=1\n");
        assertTrue(ok.ok());
        assertEquals("x = 1;\n", ok.text());
        var empty = JsToolRun.formatOutcome(JsTools.Kind.BIOME, new JsToolRun.Output(0, "", "", false, false), "x=1\n");
        assertFalse(empty.ok(), "nothing back for a file with text: never empties the buffer");
        assertEquals("Biome: no output", empty.error());
        assertTrue(JsToolRun.formatOutcome(JsTools.Kind.BIOME, new JsToolRun.Output(0, "", "", false, false), "")
                .ok());
        var failed = JsToolRun.formatOutcome(
                JsTools.Kind.PRETTIER,
                new JsToolRun.Output(
                        2, "", "\n[error] a.ts: SyntaxError: ';' expected. (1:5)\n[error] > 1 | x =\n", false, false),
                "x =");
        assertEquals("Prettier: [error] a.ts: SyntaxError: ';' expected. (1:5)", failed.error());
        assertEquals(
                "Prettier: timed out",
                JsToolRun.formatOutcome(JsTools.Kind.PRETTIER, new JsToolRun.Output(-1, "", "", false, true), "x")
                        .error());
    }

    /** Prettier with {@code endOfLine: "crlf"}: read as LF, as the buffer holds it (Python review 2, A103). */
    @Test
    void aCrlfResultIsReadAsLf() {
        var crlf = JsToolRun.formatOutcome(
                JsTools.Kind.PRETTIER, new JsToolRun.Output(0, "x = 1;\r\ny = 2;\r\n", "", false, false), "x=1\ny=2\n");
        assertEquals("x = 1;\ny = 2;\n", crlf.text());
    }

    @Test
    void eslintExitsOneWithProblemsAndTwoWithAReasonAfterItsOops() {
        var problems = JsToolRun.eslintOutcome(
                new JsToolRun.Output(
                        1,
                        "[{\"messages\":[{\"ruleId\":\"semi\",\"severity\":2,\"message\":\"m\","
                                + "\"line\":1,\"column\":1}]}]",
                        "",
                        false,
                        false),
                "x");
        assertEquals(1, problems.report().problems().size());
        String crash = "\nOops! Something went wrong! :(\n\nESLint: 9.12.0\n\n"
                + "ESLint couldn't find an eslint.config.(js|mjs|cjs) file.\n\nFrom ESLint v9.0.0, …\n";
        var failed = JsToolRun.eslintOutcome(new JsToolRun.Output(2, "", crash, false, false), "x");
        assertNull(failed.report());
        assertEquals("ESLint: ESLint couldn't find an eslint.config.(js|mjs|cjs) file.", failed.error());
        var garbage = JsToolRun.eslintOutcome(new JsToolRun.Output(0, "plugin says hi", "", false, false), "x");
        assertTrue(garbage.error().startsWith("ESLint: "));
    }

    @Test
    void theEnvironmentAddsOnlyWhatItNeeds() {
        assertEquals(Map.of(), JsToolRun.env(null, null, "/usr/bin"));
        assertEquals(Map.of("PATH", Path.of("/n").toString()), JsToolRun.env(Path.of("/n"), null, ""));
    }
}
