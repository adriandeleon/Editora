package com.editora.test;

import java.util.List;

/**
 * A parser's neutral output for one grouping unit — a single JUnit XML file, or the current batch of stream
 * events for one Go package / Cargo crate / TAP file. {@link TestTreeBuilder#merge} upserts it into the tree.
 * Pure data.
 */
public record ParsedSuite(String suiteName, List<ParsedTest> tests, String suiteStdout, String suiteStderr) {

    public ParsedSuite(String suiteName, List<ParsedTest> tests) {
        this(suiteName, tests, null, null);
    }
}
