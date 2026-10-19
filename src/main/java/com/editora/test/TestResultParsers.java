package com.editora.test;

import com.editora.build.BuildTool;

/** Picks the right {@link TestResultParser} for a build tool, and classifies file- vs stream-based tools. */
public final class TestResultParsers {

    private TestResultParsers() {}

    public static TestResultParser forTool(BuildTool tool) {
        return switch (tool) {
            case MAVEN, GRADLE -> new JUnitXmlParser(tool);
            case GO -> new GoTestJsonParser();
            case CARGO -> new CargoTestParser();
            // Clojure's runners print no format a parser reads (isTestRun is false for them): never fed. Nor are
            // the Scala tools' runs read as test runs yet (TestRunRecognizer).
            case NPM, CLOJURE, LEIN, BABASHKA, SBT, MILL, SCALA_CLI -> new TapParser();
        };
    }

    /** File-based (JUnit XML on disk) tools are driven by polling the reports dir; the rest parse the stream. */
    public static boolean isFileBased(BuildTool tool) {
        return tool == BuildTool.MAVEN || tool == BuildTool.GRADLE;
    }
}
