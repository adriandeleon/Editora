package com.editora.editor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.editora.http.HttpFile;
import com.editora.run.MainMethodScanner;
import com.editora.run.MakefileTargets;
import com.editora.test.JavaTestScanner;

/**
 * What a buffer's text says about running it: whether the file is runnable and on which gutter lines the Run
 * glyphs belong (a script's entry line, each {@code .http} request, each Makefile target, each JUnit test,
 * each project {@code main}).
 *
 * <p>Pure, and a function of its arguments alone, so {@link EditorBuffer} can evaluate it on a background
 * thread after an edit settles: the scanners strip comments, run regexes and split the whole text, which is
 * milliseconds on the FX thread at every typing pause for a file near the size cap.
 */
final class RunScan {

    private RunScan() {}

    /**
     * Which scans apply, decided on the FX thread from the buffer's state.
     *
     * @param eligible the run feature is on and the file is small enough to scan
     * @param httpEligible an {@code .http} file with the HTTP client on
     * @param testEligible a Java file whose JUnit tests get gutter glyphs
     * @param mainEligible a Java file in a project whose {@code main} methods get gutter glyphs
     * @param name the file's name (a compact source file is recognized by it)
     * @param javaShebang whether the file starts with a {@code java --source N} shebang
     */
    record Inputs(
            boolean eligible,
            boolean httpEligible,
            boolean testEligible,
            boolean mainEligible,
            String language,
            String name,
            boolean javaShebang) {

        /** Whether any scan reads the text — otherwise the document need not be materialized at all. */
        boolean needsText() {
            return eligible || httpEligible || testEligible || mainEligible;
        }
    }

    /**
     * @param runnable the file can be run as one file, script or test (project mains do not count)
     * @param line the single entry line, or -1 when there is none or the glyphs come from a line set
     */
    record Result(
            boolean runnable,
            int line,
            List<Integer> httpLines,
            Map<Integer, String> makeTargets,
            Map<Integer, JavaTestScanner.TestTarget> testLines,
            Map<Integer, MainMethodScanner.MainMethod> mainLines) {}

    static Result scan(Inputs in, String text) {
        boolean runnable;
        int line;
        List<Integer> httpLines = List.of();
        Map<Integer, String> makeTargets = Map.of();
        Map<Integer, JavaTestScanner.TestTarget> pythonTests = Map.of();
        String language = in.language();
        if (in.httpEligible()) {
            httpLines = HttpFile.parse(text).stream()
                    .map(HttpFile.Request::startLine)
                    .toList();
            runnable = !httpLines.isEmpty();
            line = -1; // .http uses the line set, not a single entry line
        } else if (in.eligible() && "makefile".equals(language)) {
            // Each rule target gets its own gutter ▶ (running `make <target>`) — the .http multi-glyph model.
            Map<Integer, String> targets = new LinkedHashMap<>();
            for (MakefileTargets.Target t : MakefileTargets.parse(text)) {
                targets.put(t.line(), t.name());
            }
            makeTargets = targets;
            runnable = !targets.isEmpty();
            line = -1; // Makefile uses the target line map, not a single entry line
        } else if (in.eligible() && "python".equals(language)) {
            pythonTests = pythonTestLines(in.name(), text);
            line = pythonTests.isEmpty() ? pythonRunLine(in.name(), text) : -1; // tests: their own ▶ each
            runnable = line >= 0 || !pythonTests.isEmpty();
        } else if (in.eligible() && "shell".equals(language) && !ShellFiles.isStartupFile(in.name())) {
            // (A sourced startup file — .bashrc, .xinitrc, .envrc … — is never run: no ▶ for it.)
            // Gated like Python, by the Run feature alone: shell Run used to also need LSP and the Bash server
            // toggle (both off for a new user), so a .sh had no ▶ until someone found that out.
            runnable = true;
            line = 0; // run the whole script from the top (the shebang line, if any)
        } else if (in.eligible()
                && "java".equals(language)
                && (CompactSource.isLaunchable(in.name(), text)
                        || (in.javaShebang() && CompactSource.hasTopLevelMain(text)))) {
            // A .java compact source, or an extensionless file with a `java --source N` shebang.
            runnable = true;
            line = CompactSource.mainLine(text);
        } else {
            runnable = false;
            line = -1;
        }
        // JUnit test glyphs are additive to whatever the run type above decided (a test file is usually neither
        // compact-source nor a script), so they OR into runnable and get their own line→target map.
        Map<Integer, JavaTestScanner.TestTarget> testLines = pythonTests;
        if (in.testEligible()) {
            Map<Integer, JavaTestScanner.TestTarget> tl = new LinkedHashMap<>();
            for (JavaTestScanner.TestTarget t : JavaTestScanner.scan(text)) {
                tl.put(t.line(), t);
            }
            testLines = tl;
            runnable = runnable || !tl.isEmpty();
        }
        // Project main-method glyphs — deliberately do NOT flip `runnable` (that stays "single-file/script/test
        // runnable", so the generic Run File / file.run never mis-launches a project class as one source file);
        // the gutter draws these via a separate gate (see the run-slot `enabled` supplier + isRunGlyphLine).
        Map<Integer, MainMethodScanner.MainMethod> mainLines = Map.of();
        if (in.mainEligible()) {
            Map<Integer, MainMethodScanner.MainMethod> ml = new LinkedHashMap<>();
            for (MainMethodScanner.MainMethod m : MainMethodScanner.scan(text)) {
                ml.put(m.line(), m);
            }
            mainLines = ml;
        }
        return new Result(runnable, line, httpLines, makeTargets, testLines, mainLines);
    }

    /**
     * The {@code __main__} guard in any of its spellings: either quote, either order, in parentheses, or as the
     * first operand of a longer condition ({@code if __name__ == "__main__" and not DEBUG:}).
     */
    private static final Pattern PYTHON_MAIN_GUARD = Pattern.compile(
            "^\\s*if\\s*\\(?\\s*(?:__name__\\s*==\\s*(['\"])__main__\\1|(['\"])__main__\\2\\s*==\\s*__name__)");

    /** Files that are imported, never run: a stub, and (unless they have a guard) a package or pytest's conftest. */
    private static boolean pythonLibraryFile(String name) {
        return name != null && (name.equals("__init__.py") || name.equals("conftest.py"));
    }

    /**
     * The gutter Run line for a Python script: its {@code __main__} guard (one inside a string or docstring
     * does not count), else line 0 — or -1, no ▶ at all, for a {@code .pyi} stub, and for {@code __init__.py}
     * and {@code conftest.py} without a guard (imported, never run).
     */
    static int pythonRunLine(String name, String text) {
        if (name != null && name.endsWith(".pyi")) {
            return -1;
        }
        String[] lines = text.split("\n", -1);
        String[] code = com.editora.test.PytestScanner.blank(text).split("\n", -1);
        for (int i = 0; i < lines.length && i < code.length; i++) {
            // The blanked line still starts with `if` only when the line is code, not text in a string.
            if (code[i].stripLeading().startsWith("if")
                    && PYTHON_MAIN_GUARD.matcher(lines[i]).find()) {
                return i;
            }
        }
        return pythonLibraryFile(name) ? -1 : 0;
    }

    /** As {@link #pythonRunLine(String, String)} for a file of no particular name. */
    static int pythonRunLine(String text) {
        return pythonRunLine(null, text);
    }

    /**
     * The pytest ▶ glyphs of a test file ({@code test_*.py}, {@code *_test.py}): one per test function, test
     * class and test method, plus one on the first line for the whole file when no test sits there. A test is
     * carried as a {@link JavaTestScanner.TestTarget}: {@code className} is the test class, or {@code ""} at
     * module level; {@code methodName} is the function, or null for a class (or, with {@code ""}, the file).
     * Empty for any other file, and for a test file with no tests (it runs as a script).
     */
    static Map<Integer, JavaTestScanner.TestTarget> pythonTestLines(String name, String text) {
        if (!com.editora.test.PytestScanner.isTestFile(name)) {
            return Map.of();
        }
        List<com.editora.test.PytestScanner.TestTarget> tests = com.editora.test.PytestScanner.scan(text);
        if (tests.isEmpty()) {
            return Map.of();
        }
        Map<Integer, JavaTestScanner.TestTarget> out = new LinkedHashMap<>();
        if (tests.get(0).line() > 0) {
            out.put(0, new JavaTestScanner.TestTarget(0, "", null, false)); // the whole file
        }
        for (com.editora.test.PytestScanner.TestTarget t : tests) {
            String cls = t.className() == null ? "" : t.className();
            out.put(t.line(), new JavaTestScanner.TestTarget(t.line(), cls, t.functionName(), false));
        }
        return out;
    }
}
