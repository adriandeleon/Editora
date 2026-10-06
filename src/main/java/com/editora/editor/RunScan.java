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
     * @param shellRun whether shell scripts are runnable
     * @param javaShebang whether the file starts with a {@code java --source N} shebang
     */
    record Inputs(
            boolean eligible,
            boolean httpEligible,
            boolean testEligible,
            boolean mainEligible,
            String language,
            String name,
            boolean shellRun,
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
            runnable = true;
            line = pythonRunLine(text); // the __main__ guard, else the first line
        } else if (in.eligible() && in.shellRun() && "shell".equals(language)) {
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
        Map<Integer, JavaTestScanner.TestTarget> testLines = Map.of();
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

    private static final Pattern PYTHON_MAIN_GUARD =
            Pattern.compile("^\\s*if\\s+__name__\\s*==\\s*['\"]__main__['\"]\\s*:");

    /** The gutter Run line for a Python script: the {@code if __name__ == "__main__":} guard, else line 0. */
    static int pythonRunLine(String text) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (PYTHON_MAIN_GUARD.matcher(lines[i]).find()) {
                return i;
            }
        }
        return 0;
    }
}
