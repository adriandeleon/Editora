package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import com.editora.text.LspDiagnostic;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ESLint, Prettier and the JS/TS actions on save in a real window (JS review F), against stand-in Node scripts that
 * print what the real tools print: ESLint's {@code --format json} report (a {@code no-var} error with its fix) and
 * Prettier's formatted text. The TypeScript server is off, so the problems seen are ESLint's alone.
 */
@Tag("fx")
class EslintPrettierFxTest {

    @TempDir
    Path work;

    /** ESLint 9's JSON report for {@code var}; with --fix-dry-run, the fixed text as {@code output}. Logs its call. */
    private static final String FAKE_ESLINT = """
            const fs = require('fs');
            const path = require('path');
            const args = process.argv.slice(2);
            const text = fs.readFileSync(0, 'utf8');
            fs.appendFileSync(path.join(__dirname, 'calls.log'), JSON.stringify({cwd: process.cwd(), args,
                flat: process.env.ESLINT_USE_FLAT_CONFIG}) + '\\n');
            const file = args[args.indexOf('--stdin-filename') + 1];
            const fix = args.includes('--fix-dry-run');
            const messages = [];
            const re = /\\bvar\\b/g;
            let m;
            while ((m = re.exec(text)) !== null) {
              const before = text.slice(0, m.index);
              const line = before.split('\\n').length;
              const column = m.index - (before.lastIndexOf('\\n') + 1) + 1;
              messages.push({ruleId: 'no-var', severity: 2, message: 'Unexpected var, use let or const instead.',
                line, column, nodeType: 'VariableDeclaration', messageId: 'unexpectedVar', endLine: line,
                endColumn: column + 3, fix: {range: [m.index, m.index + 3], text: 'let'}});
            }
            const result = {filePath: file, messages: fix ? [] : messages, suppressedMessages: [],
              errorCount: fix ? 0 : messages.length, fatalErrorCount: 0, warningCount: 0,
              fixableErrorCount: fix ? 0 : messages.length, fixableWarningCount: 0, usedDeprecatedRules: []};
            if (fix && messages.length) result.output = text.replace(/\\bvar\\b/g, 'let'); else result.source = text;
            process.stdout.write(JSON.stringify([result]));
            process.exitCode = !fix && messages.length ? 1 : 0;
            """;

    /** Prettier: spaces around {@code =}. */
    private static final String FAKE_PRETTIER = """
            const fs = require('fs');
            const text = fs.readFileSync(0, 'utf8');
            process.stdout.write(text.replace(/[ \\t]*=[ \\t]*/g, ' = '));
            """;

    private static boolean nodeOnPath() {
        return com.editora.run.PythonInterpreters.onAugmentedPath("node").isPresent();
    }

    private static Path project(Path root) throws Exception {
        Files.writeString(root.resolve("package.json"), "{\"name\":\"p\",\"devDependencies\":{\"prettier\":\"^3\"}}");
        Files.writeString(root.resolve("eslint.config.js"), "export default [];\n");
        Path eslint =
                Files.createDirectories(root.resolve("node_modules/eslint/bin")).resolve("eslint.js");
        Files.writeString(eslint, FAKE_ESLINT);
        Files.writeString(
                Files.createDirectories(root.resolve("node_modules/prettier/bin"))
                        .resolve("prettier.cjs"),
                FAKE_PRETTIER);
        return eslint.getParent().resolve("calls.log");
    }

    private static FxWindowFixture window(Path cfg) throws Exception {
        return FxWindowFixture.create(Files.createDirectories(cfg), shared -> {
            shared.getSettings().setLspSupport(true); // ESLint's problems are shown through it
            shared.getSettings().setTypescriptLspEnabled(false); // no server: the problems are ESLint's alone
        });
    }

    private static <T> T waitFor(Callable<T> read, java.util.function.Predicate<T> done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        T now = FxTestSupport.callOnFx(read);
        while (!done.test(now) && System.nanoTime() < deadline) {
            Thread.sleep(25);
            FxTestSupport.drainFx();
            now = FxTestSupport.callOnFx(read);
        }
        return now;
    }

    private static List<LspDiagnostic> problemsOf(MainController c, Path file) throws Exception {
        LspCoordinator lsp = FxTestSupport.field(c, "lspCoordinator");
        Map<Path, List<LspDiagnostic>> problems = FxTestSupport.field(lsp, "problems");
        return problems.getOrDefault(com.editora.config.PathKeys.canonical(file), List.of());
    }

    private static String text(MainController c) throws Exception {
        return FxTestSupport.callOnFx(() -> c.activeBuffer().getArea().getText());
    }

    @Test
    void eslintsProblemsShowFixAllAppliesItsFixesAndPrettierFormats() throws Exception {
        assumeTrue(nodeOnPath(), "the stand-in tools are Node.js scripts");
        FxTestSupport.bootToolkit();
        try (FxWindowFixture fx = window(work.resolve("cfg"))) {
            MainController c = fx.controller;
            Path root = fx.useProject();
            Path log = project(root);
            fx.shared.getTrustStore().trust(root);
            Path app = Files.writeString(
                    Files.createDirectories(root.resolve("src")).resolve("app.js"), "var x=1\n");
            FxTestSupport.runOnFx(() -> c.openAndNavigate(app, 1));

            List<LspDiagnostic> seen = waitFor(() -> problemsOf(c, app), l -> !l.isEmpty());
            assertEquals(1, seen.size(), "ESLint's no-var error is a problem of the file");
            LspDiagnostic d = seen.get(0);
            assertEquals("eslint", d.source());
            assertEquals("no-var", d.code());
            assertEquals(List.of(0, 0, 0, 3), List.of(d.startLine(), d.startCol(), d.endLine(), d.endCol()));
            String call = Files.readAllLines(log).get(0);
            assertTrue(
                    call.contains("\"cwd\":\"" + root.toRealPath().toString().replace("\\", "\\\\") + "\"")
                            || call.contains("\"cwd\":\"" + root.toString().replace("\\", "\\\\") + "\""),
                    "run from the configuration's folder: " + call);
            assertTrue(call.contains("\"flat\":\"true\""), call);
            assertTrue(call.contains("--stdin-filename"), call);

            com.editora.command.CommandRegistry registry = FxTestSupport.field(c, "registry");
            FxTestSupport.runOnFx(() -> registry.run("eslint.fixAll"));
            assertEquals("let x=1\n", waitFor(() -> text(c), "let x=1\n"::equals));
            assertTrue(waitFor(() -> problemsOf(c, app), List::isEmpty).isEmpty(), "what is left: nothing");

            FxTestSupport.runOnFx(() -> registry.run("lsp.formatDocument"));
            assertEquals("let x = 1\n", waitFor(() -> text(c), "let x = 1\n"::equals), "the project's Prettier");
            FxTestSupport.runOnFx(() -> c.activeBuffer().getArea().undo());
            assertEquals("let x=1\n", text(c), "one undo step takes the format back");

            // Typing: checked again after the pause, with no save.
            int calls = Files.readAllLines(log).size();
            FxTestSupport.runOnFx(() -> c.activeBuffer().getArea().appendText("var z\n"));
            List<LspDiagnostic> again = waitFor(() -> problemsOf(c, app), l -> l.size() == 1);
            assertEquals(1, again.size());
            assertEquals(1, again.get(0).startLine(), "the new line's var");
            assertTrue(Files.readAllLines(log).size() > calls);
        }
    }

    @Test
    void anUntrustedFolderNeverRunsEslintAndSaysWhyOnce() throws Exception {
        assumeTrue(nodeOnPath(), "the stand-in tools are Node.js scripts");
        FxTestSupport.bootToolkit();
        try (FxWindowFixture fx = window(work.resolve("cfg"))) {
            MainController c = fx.controller;
            Path root = fx.useProject();
            Path log = project(root);
            Path app = Files.writeString(root.resolve("app.js"), "var x=1\n");
            FxTestSupport.runOnFx(() -> c.openAndNavigate(app, 1));
            String notice = waitFor(() -> c.activeBuffer().fileNoticeText(), n -> n != null);
            assertEquals(tr("eslint.trust.notice"), notice);
            assertFalse(Files.exists(log), "ESLint never ran");
            assertTrue(problemsOf(c, app).isEmpty());

            com.editora.command.CommandRegistry registry = FxTestSupport.field(c, "registry");
            FxTestSupport.runOnFx(() -> registry.run("lsp.formatDocument"));
            FxTestSupport.drainFx();
            Thread.sleep(300);
            FxTestSupport.drainFx();
            assertEquals("var x=1\n", text(c), "auto: no Prettier from an untrusted folder");

            // Trust Folder (the notice's button runs the same command): ESLint runs, the notice goes.
            LspCoordinator lsp = FxTestSupport.field(c, "lspCoordinator");
            FxTestSupport.runOnFx(() -> {
                lsp.xmlTrust().confirmer = (owner, folder) -> true;
                registry.run(XmlFolderTrust.COMMAND);
            });
            assertEquals(1, waitFor(() -> problemsOf(c, app), l -> !l.isEmpty()).size(), "checked once trusted");
            assertTrue(Files.exists(log));
            assertEquals(null, FxTestSupport.callOnFx(() -> c.activeBuffer().fileNoticeText()));
        }
    }

    @Test
    void anExplicitSaveFixesThenFormatsAndSavesAgainAsOneUndoStep() throws Exception {
        assumeTrue(nodeOnPath(), "the stand-in tools are Node.js scripts");
        FxTestSupport.bootToolkit();
        try (FxWindowFixture fx = window(work.resolve("cfg"))) {
            MainController c = fx.controller;
            Path root = fx.useProject();
            project(root);
            fx.shared.getTrustStore().trust(root);
            FxTestSupport.runOnFx(() -> {
                fx.shared.getSettings().setEslintFixOnSave(true);
                fx.shared.getSettings().setJsFormatOnSave(true);
            });
            Path app = Files.writeString(root.resolve("app.js"), "var x=1\n");
            FxTestSupport.runOnFx(() -> c.openAndNavigate(app, 1));
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> c.activeBuffer().getArea().appendText("var y=2\n"));
            com.editora.command.CommandRegistry registry = FxTestSupport.field(c, "registry");
            FxTestSupport.runOnFx(() -> registry.run("file.save"));

            String expected = "let x = 1\nlet y = 2\n";
            String onDisk = waitFor(() -> Files.readString(app), expected::equals);
            assertEquals(expected, onDisk, "fixed, formatted and saved again");
            assertEquals(expected, text(c));
            assertFalse(FxTestSupport.callOnFx(() -> c.activeBuffer().isDirty()));
            FxTestSupport.runOnFx(() -> c.activeBuffer().getArea().undo());
            assertEquals("var x=1\nvar y=2\n", text(c), "one undo takes back both actions");
        }
    }
}
