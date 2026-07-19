package com.editora.test;

import java.nio.file.Path;
import java.util.List;

/**
 * The seam every per-tool parser implements. Two disjoint modes, picked by {@link TestResultParsers}:
 * <ul>
 *   <li><b>Stream tools</b> (Go/Cargo/npm) fold raw process output via {@link #onLine} into zero-or-more suite
 *       deltas, and flush any end-of-run state in {@link #onExit} (e.g. Cargo's trailing {@code failures:}
 *       block). {@link #consoleLine} lets a parser rewrite what the raw console shows (Go turns {@code -json}
 *       back into readable text).</li>
 *   <li><b>File tools</b> (Maven/Gradle) parse an authoritative JUnit XML report via {@link #parseReportFile};
 *       the coordinator decides <em>when</em> to read files (it polls the reports dir).</li>
 * </ul>
 * Implementations are pure (only {@link JUnitXmlParser} does I/O, on a file it is handed) and hold just
 * parse-in-progress scratch state.
 */
public interface TestResultParser {

    /** Folds one raw output line into zero-or-more suite deltas (stream tools). Default: nothing. */
    default List<ParsedSuite> onLine(String line, boolean stderr) {
        return List.of();
    }

    /** Parses one report file into a suite, or {@code null} if it is not a (complete) report (file tools). */
    default ParsedSuite parseReportFile(Path file) {
        return null;
    }

    /** End-of-run flush after the process exits. Default: nothing. */
    default List<ParsedSuite> onExit(int code) {
        return List.of();
    }

    /**
     * What the raw Build Output console should show for {@code raw}. Default: the line verbatim. Go returns the
     * decoded {@code output} text (so a {@code -json} run stays human-readable in the console), or {@code null}
     * to suppress a non-output event.
     */
    default String consoleLine(String raw, boolean stderr) {
        return raw;
    }
}
