package com.editora.ui;

/**
 * Where Debug ▶ (F5) in a Java file goes (review A102): a JUnit test file has no {@code main} of its own, and
 * "debug the project's only main class" ran the whole application while the test's breakpoints waited. As in
 * IntelliJ and VS Code, Debug in such a file debugs the test at the caret, or the whole class when the caret is
 * outside a test, through the same path as the gutter's Debug Test. Pure, so the decision is unit-tested.
 */
final class DebugTestRoute {

    /** What Debug ▶ does with the active file. */
    enum Route {
        /** Launch the file (its own {@code main}, or the project's) as before. */
        LAUNCH,
        /** Debug the test method at the caret. */
        TEST_AT_CARET,
        /** Debug the whole test class (the caret is not inside a test). */
        TEST_CLASS
    }

    private DebugTestRoute() {}

    /**
     * @param java the file is Java
     * @param compactSource a compact source file or a {@code --source} shebang script (always launched)
     * @param testClass the file declares a JUnit test class (the scanner found its class line)
     * @param ownMain the file declares a {@code main} method of its own (that wins: it is a program)
     * @param testAtCaret the caret is in a test method, or between the class line and the first test
     */
    static Route route(boolean java, boolean compactSource, boolean testClass, boolean ownMain, boolean testAtCaret) {
        if (!java || compactSource || !testClass || ownMain) {
            return Route.LAUNCH;
        }
        return testAtCaret ? Route.TEST_AT_CARET : Route.TEST_CLASS;
    }
}
