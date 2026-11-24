package com.editora.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each runner's report as Test Results reads it — real reports of each runner — and its run's arguments. */
class ToolTestKindTest {

    private static final Path WORK = Path.of("/work");

    /** {@code relative} in {@link #WORK}, as this system writes the path. */
    private static String at(String relative) {
        return WORK.resolve(relative).toString();
    }

    private static String fixture(String name) throws Exception {
        return Files.readString(
                Path.of(Objects.requireNonNull(ToolTestKindTest.class.getResource("tool-reports/" + name))
                        .toURI()));
    }

    private static ParsedTest test(List<ParsedSuite> suites, String name) {
        return suites.stream()
                .flatMap(s -> s.tests().stream())
                .filter(t -> name.equals(t.methodName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " in " + suites));
    }

    private static int count(List<ParsedSuite> suites) {
        return suites.stream().mapToInt(s -> s.tests().size()).sum();
    }

    /** What a console parser makes of {@code text}, merged into a tree as Test Results does. */
    private static List<ParsedSuite> console(TestResultParser parser, String text) {
        TestNode root = new TestNode(TestNodeKind.ROOT, null, null);
        for (String line : text.split("\n", -1)) {
            parser.onLine(line, false).forEach(s -> TestTreeBuilder.merge(root, s));
        }
        parser.onExit(1).forEach(s -> TestTreeBuilder.merge(root, s));
        List<ParsedSuite> out = new ArrayList<>();
        for (TestNode suite : root.children()) {
            List<ParsedTest> tests = new ArrayList<>();
            for (TestNode t : suite.children()) {
                tests.add(new ParsedTest(
                        t.className(),
                        t.methodName(),
                        t.status(),
                        t.durationMs(),
                        t.failureType(),
                        t.failureMessage(),
                        t.stackTrace(),
                        null,
                        t.sourceFileHint(),
                        t.sourceLineHint()));
            }
            out.add(new ParsedSuite(suite.displayName(), tests));
        }
        return out;
    }

    @Test
    void jestsReportNamesEachTestByItsDescribesAndGivesItsLineAndFailure() throws Exception {
        List<ParsedSuite> suites = ToolTestKind.JEST.readReport(fixture("jest.json"), WORK);
        assertEquals("tr.test.js", suites.getFirst().suiteName(), "the file from where it ran");
        assertEquals(4, count(suites));
        ParsedTest fails = test(suites, "calc › fails");
        assertEquals(TestStatus.FAILED, fails.status());
        assertEquals(3, fails.sourceLineHint());
        assertEquals(at("tr.test.js"), fails.sourceFileHint());
        assertTrue(fails.failureMessage().startsWith("Error: expect(received).toBe(expected)"), fails.failureMessage());
        assertTrue(fails.stackTrace().contains("tr.test.js:3:39"), "its stack, to jump to the assertion");
        assertEquals(TestStatus.SKIPPED, test(suites, "calc › skips").status());
        assertEquals(TestStatus.PASSED, test(suites, "top").status());
    }

    @Test
    void vitestsReportIsJestsShapeWithoutLines() throws Exception {
        List<ParsedSuite> suites = ToolTestKind.VITEST.readReport(fixture("vitest.json"), WORK);
        assertEquals(4, count(suites));
        ParsedTest fails = test(suites, "calc › fails");
        assertEquals(TestStatus.FAILED, fails.status());
        assertEquals(0, fails.sourceLineHint(), "Vitest gives none: its title is looked for");
        assertTrue(fails.failureMessage().startsWith("AssertionError: expected 2 to be 3"));
        assertEquals(TestStatus.SKIPPED, test(suites, "calc › skips").status());
    }

    @Test
    void rspecsReportNamesExamplesByTheirFullDescription() throws Exception {
        List<ParsedSuite> suites = ToolTestKind.RSPEC.readReport(fixture("rspec.json"), WORK);
        assertEquals("calc_spec.rb", suites.getFirst().suiteName());
        ParsedTest fails = test(suites, "Calc fails");
        assertEquals(TestStatus.FAILED, fails.status());
        assertEquals("RSpec::Expectations::ExpectationNotMetError", fails.failureType());
        assertEquals("expected: 3", fails.failureMessage());
        assertEquals(at("calc_spec.rb"), fails.sourceFileHint());
        assertEquals(5, fails.sourceLineHint());
        assertTrue(fails.stackTrace().contains("./calc_spec.rb:6:in"));
        assertEquals(TestStatus.PASSED, test(suites, "Calc adds").status());
        ParsedTest skips = test(suites, "Calc nested skips");
        assertEquals(TestStatus.SKIPPED, skips.status());
        assertEquals("later", skips.failureMessage());
    }

    @Test
    void phpunitsJunitLogGroupsDataSetsUnderTheirClass() throws Exception {
        List<ParsedSuite> suites = ToolTestKind.PHPUNIT.readReport(fixture("phpunit.xml"), WORK);
        assertEquals(1, suites.size(), "the data sets' own suite is the class's");
        assertEquals("CalcTest", suites.getFirst().suiteName());
        assertEquals(5, count(suites));
        ParsedTest fails = test(suites, "testFails");
        assertEquals(TestStatus.FAILED, fails.status());
        assertEquals("Failed asserting that 2 is identical to 3.", fails.failureMessage());
        assertEquals(at("CalcTest.php"), fails.sourceFileHint());
        assertEquals(6, fails.sourceLineHint());
        assertEquals(TestStatus.SKIPPED, test(suites, "testSkips").status());
        assertEquals(
                TestStatus.FAILED,
                test(suites, "testData with data set \"two\"").status());
        assertEquals(
                TestStatus.PASSED, test(suites, "testData with data set #0").status());
    }

    @Test
    void denosJunitReportKeepsStepsAndLines() throws Exception {
        List<ParsedSuite> suites = ToolTestKind.DENO.readReport(fixture("deno.xml"), WORK);
        assertEquals("./calc_test.ts", suites.getFirst().suiteName());
        ParsedTest fails = test(suites, "fails");
        assertEquals(TestStatus.FAILED, fails.status());
        assertEquals("Uncaught Error: boom", fails.failureMessage());
        assertEquals(at("calc_test.ts"), fails.sourceFileHint(), "Deno's class is its file");
        assertEquals(2, fails.sourceLineHint());
        assertEquals(TestStatus.SKIPPED, test(suites, "skips").status());
        assertEquals(TestStatus.PASSED, test(suites, "steps > inner").status());
    }

    @Test
    void dartsJsonEventsGiveFullNamesLinesErrorsAndPrints() throws Exception {
        List<ParsedSuite> suites = ToolTestKind.DART.readReport(fixture("dart.json"), WORK);
        assertEquals("test/calc_test.dart", suites.getFirst().suiteName());
        assertEquals(4, count(suites), "the loading test is not one");
        ParsedTest fails = test(suites, "calc fails");
        assertEquals(TestStatus.FAILED, fails.status());
        assertEquals("Expected: <3>", fails.failureMessage());
        assertEquals(at("test/calc_test.dart"), fails.sourceFileHint());
        assertEquals(8, fails.sourceLineHint());
        assertTrue(fails.stackTrace().contains("test/calc_test.dart 9:7"));
        ParsedTest skips = test(suites, "calc skips");
        assertEquals(TestStatus.SKIPPED, skips.status());
        assertEquals("Skip: later", skips.failureMessage());
        assertEquals("hello\n", test(suites, "top").stdout());
    }

    @Test
    void minitestsVerboseConsoleGivesEachTestThenItsFailure() throws Exception {
        List<ParsedSuite> suites = console(ToolTestKind.MINITEST.consoleParser(WORK), fixture("minitest.txt"));
        assertEquals(5, count(suites));
        ParsedTest fails = test(suites, "test_fails");
        assertEquals(TestStatus.FAILED, fails.status());
        assertEquals("Expected: 3", fails.failureMessage());
        assertEquals(at("calc_test.rb"), fails.sourceFileHint());
        assertEquals(7, fails.sourceLineHint());
        ParsedTest errors = test(suites, "test_errors");
        assertEquals(TestStatus.ERROR, errors.status());
        assertEquals("ArgumentError: boom", errors.failureMessage());
        assertTrue(errors.stackTrace().contains("calc_test.rb:10:in"));
        assertEquals(TestStatus.SKIPPED, test(suites, "test_skips").status());
        assertEquals(TestStatus.PASSED, test(suites, "test_0001_works fine").status());
        assertTrue(suites.stream().anyMatch(s -> "Spec style".equals(s.suiteName())));
    }

    @Test
    void railsMinitestFailureFollowsItsTestWithoutItsRerunLine() throws Exception {
        List<ParsedSuite> suites = console(ToolTestKind.MINITEST.consoleParser(WORK), fixture("rails-minitest.txt"));
        assertEquals(2, count(suites));
        ParsedTest fails = test(suites, "test_fails");
        assertEquals(TestStatus.FAILED, fails.status());
        assertEquals("Expected: 3", fails.failureMessage());
        assertEquals(at("test/models/calc_test.rb"), fails.sourceFileHint());
        assertTrue(!fails.stackTrace().contains("bin/rails test"), fails.stackTrace());
        assertEquals(TestStatus.PASSED, test(suites, "test_adds").status());
    }

    @Test
    void exunitsTraceGivesEachTestItsLineAndTheFailureBlock() throws Exception {
        List<ParsedSuite> suites = console(ToolTestKind.EXUNIT.consoleParser(WORK), fixture("exunit.txt"));
        assertEquals("CalcTest", suites.getFirst().suiteName());
        assertEquals(4, count(suites));
        ParsedTest fails = test(suites, "test fails");
        assertEquals(TestStatus.FAILED, fails.status());
        assertEquals("Assertion with == failed", fails.failureMessage());
        assertEquals(at("test/calc_test.exs"), fails.sourceFileHint());
        assertEquals(8, fails.sourceLineHint());
        assertTrue(fails.stackTrace().contains("test/calc_test.exs:9: (test)"));
        assertEquals(TestStatus.SKIPPED, test(suites, "test group skipped").status());
        ParsedTest adds = test(suites, "test adds two numbers");
        assertEquals(TestStatus.PASSED, adds.status());
        assertEquals(4, adds.sourceLineHint());
    }

    @Test
    void exunitLeavesOutWhatAFilterExcluded() {
        TestResultParser p = ToolTestKind.EXUNIT.consoleParser(WORK);
        String text = "CalcTest [test/calc_test.exs]\n  * test adds [L#4]\r  * test adds (excluded) [L#4]\n"
                + "  * test fails [L#8]\r  * test fails (1.2s) [L#8]\n";
        List<ParsedSuite> suites = console(p, text);
        assertEquals(1, count(suites));
        assertEquals(1200, test(suites, "test fails").durationMs());
    }

    @Test
    void reportsThatAreNotReportsAreEmpty() {
        for (ToolTestKind k : ToolTestKind.values()) {
            assertEquals(List.of(), k.readReport("", WORK), k.name());
            assertEquals(List.of(), k.readReport("not a report", WORK), k.name());
        }
    }

    @Test
    void eachRunIsToldToReportAndOptionsGoBeforeTheTestsOwnArguments() {
        Path r = Path.of("/tmp/r");
        String rs = r.toString();
        assertEquals(
                List.of("deno", "test", "-A", "a_test.ts", "--junit-path=" + rs, "--", "x"),
                ToolTestKind.DENO.withReport(List.of("deno", "test", "-A", "a_test.ts", "--", "x"), r, List.of()));
        assertEquals(
                List.of("rspec", "a_spec.rb", "--format", "progress", "--format", "json", "--out", rs),
                ToolTestKind.RSPEC.withReport(List.of("rspec", "a_spec.rb"), r, List.of()));
        assertEquals(
                List.of("rspec", "a_spec.rb", "--format", "documentation", "--format", "json", "--out", rs),
                ToolTestKind.RSPEC.withReport(List.of("rspec", "a_spec.rb"), r, List.of("documentation")));
        assertEquals(
                List.of("mix", "test", "test/a_test.exs:4", "--trace"),
                ToolTestKind.EXUNIT.withReport(List.of("mix", "test", "test/a_test.exs:4"), r, List.of()));
        assertEquals(
                List.of("dart", "test", "test/a_test.dart", "--file-reporter", "json:" + rs),
                ToolTestKind.DART.withReport(List.of("dart", "test", "test/a_test.dart"), r, List.of()));
    }

    @Test
    void theTestAtTheCaretsNarrowingIsTakenOutForTheWholeFile() {
        assertEquals(
                List.of("node", "jest.js", "--runInBand", "a.test.js"),
                ToolTestKind.JEST.wholeFile(List.of("node", "jest.js", "--runInBand", "-t", "^x$", "a.test.js")));
        assertEquals(
                List.of("bundle", "exec", "rspec", "spec/a_spec.rb"),
                ToolTestKind.RSPEC.wholeFile(List.of("bundle", "exec", "rspec", "spec/a_spec.rb:12")));
        assertEquals(
                List.of("ruby", "-Itest", "test/a_test.rb"),
                ToolTestKind.MINITEST.wholeFile(List.of("ruby", "-Itest", "test/a_test.rb", "-n", "test_x")));
        assertEquals(
                List.of("ruby", "bin/rails", "test", "test/a_test.rb"),
                ToolTestKind.MINITEST.wholeFile(List.of("ruby", "bin/rails", "test", "test/a_test.rb:6")));
        assertEquals(
                List.of("php", "phpunit", "tests/ATest.php"),
                ToolTestKind.PHPUNIT.wholeFile(List.of("php", "phpunit", "--filter", "/::m$/", "tests/ATest.php")));
        assertEquals(
                List.of("mix", "test", "test/a_test.exs"),
                ToolTestKind.EXUNIT.wholeFile(List.of("mix", "test", "test/a_test.exs:8")));
        assertEquals(
                List.of("dart", "test", "test/a_test.dart"),
                ToolTestKind.DART.wholeFile(List.of("dart", "test", "test/a_test.dart", "--plain-name", "calc adds")));
        assertEquals(
                List.of("deno", "test", "-A", "a_test.ts", "--", "--filter", "kept"),
                ToolTestKind.DENO.wholeFile(
                        List.of("deno", "test", "-A", "--filter", "/^x$/", "a_test.ts", "--", "--filter", "kept")));
    }

    @Test
    void rerunsNarrowToTheirTestsByEachRunnersOwnFilter() {
        assertEquals(
                List.of("jest.js", "a.test.js", "-t", "^(calc adds|top\\(1\\))$"),
                ToolTestKind.JEST.filtered(List.of("jest.js", "a.test.js"), List.of("calc › adds", "top(1)")));
        assertEquals(
                List.of("vitest.mjs", "run", "a.test.ts", "-t", "^(calc( | > )adds)$"),
                ToolTestKind.VITEST.filtered(List.of("vitest.mjs", "run", "a.test.ts"), List.of("calc › adds")));
        assertEquals(
                List.of("rspec", "a_spec.rb", "--example", "Calc fails"),
                ToolTestKind.RSPEC.filtered(List.of("rspec", "a_spec.rb"), List.of("Calc fails")));
        assertEquals(
                List.of("ruby", "a_test.rb", "-n", "/^(test_a|test_0001_b\\?)$/"),
                ToolTestKind.MINITEST.filtered(List.of("ruby", "a_test.rb"), List.of("test_a", "test_0001_b?")));
        assertEquals(
                List.of("phpunit", "ATest.php", "--filter", "/::(testData|testA)(?: .*)?$/"),
                ToolTestKind.PHPUNIT.filtered(
                        List.of("phpunit", "ATest.php"), List.of("testData with data set #0", "testA")));
        assertEquals(
                List.of("mix", "test", "a.exs", "--only", "test:test fails", "--only", "test:test b"),
                ToolTestKind.EXUNIT.filtered(List.of("mix", "test", "a.exs"), List.of("test fails", "test b")));
        assertEquals(
                List.of("dart", "test", "a.dart", "--name", "^(calc adds)$"),
                ToolTestKind.DART.filtered(List.of("dart", "test", "a.dart"), List.of("calc adds")));
        assertEquals(
                List.of("deno", "test", "a.ts", "--filter", "/^(steps|a\\/b)$/", "--", "x"),
                ToolTestKind.DENO.filtered(
                        List.of("deno", "test", "a.ts", "--", "x"), List.of("steps > inner", "a/b")));
        assertEquals(List.of("x"), ToolTestKind.DART.filtered(List.of("x"), List.of()), "no names: the whole file");
    }

    @Test
    void aNodeLaunchSaysWhichRunnerItIs() {
        assertEquals(
                ToolTestKind.JEST,
                ToolTestKind.ofNodeArgv(List.of("/n/node", "/p/node_modules/jest/bin/jest.js", "--runInBand", "f")));
        assertEquals(
                ToolTestKind.VITEST,
                ToolTestKind.ofNodeArgv(List.of("node", "/p/node_modules/vitest/vitest.mjs", "run", "f")));
        assertEquals(ToolTestKind.DENO, ToolTestKind.ofNodeArgv(List.of("/home/u/.deno/bin/deno", "test", "-A", "f")));
        assertNull(ToolTestKind.ofNodeArgv(List.of("deno", "run", "-A", "f")));
        assertNull(ToolTestKind.ofNodeArgv(List.of("node", "--test", "f")));
        assertNull(ToolTestKind.ofNodeArgv(List.of("node", "/p/node_modules/vitest/vitest.mjs", "f")), "watching");
    }

    @Test
    void anRspecFilesFormatsStayOnTheConsole() {
        assertEquals(
                List.of("documentation"), ToolTestKind.rspecFormats("--require spec_helper\n--format documentation"));
        assertEquals(List.of("d", "p"), ToolTestKind.rspecFormats("-fd -f p --format json"));
        assertEquals(List.of(), ToolTestKind.rspecFormats(null));
    }
}
