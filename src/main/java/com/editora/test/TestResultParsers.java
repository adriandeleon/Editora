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
            case NPM -> new TapParser();
        };
    }

    /** File-based (JUnit XML on disk) tools are driven by polling the reports dir; the rest parse the stream. */
    public static boolean isFileBased(BuildTool tool) {
        return tool == BuildTool.MAVEN || tool == BuildTool.GRADLE;
    }
}
