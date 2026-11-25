package com.editora.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C and C++ test files — GoogleTest, Catch2, doctest — as their ▶ glyphs and Test at Caret read them, the filters
 * that run some of their tests (checked against the real programs), and their XML reports (real ones) as Test
 * Results reads them.
 */
class CppTestsTest {

    private static final Path WORK = Path.of("/work");

    private static String at(String relative) {
        return WORK.resolve(relative).toString();
    }

    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of(Objects.requireNonNull(CppTestsTest.class.getResource("tool-reports/" + name))
                .toURI()));
    }

    private static ParsedTest test(List<ParsedSuite> suites, String name) {
        return suites.stream()
                .flatMap(s -> s.tests().stream())
                .filter(t -> name.equals(t.methodName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " in " + suites));
    }

    private static final String GTEST = """
            #include <gtest/gtest.h>
            int add(int a, int b) { return a + b; }
            TEST(Math, Adds) {
              EXPECT_EQ(add(1, 2), 3);
            }
            TEST_F(Fix, UsesFixture) { SUCCEED(); }
            TEST_P(P, Even) { EXPECT_EQ(GetParam() % 2, 0); }
            TYPED_TEST(Typed, Works) {}

            int helper() { return 1; }
            """;

    private static final String CATCH = """
            #include <catch2/catch_test_macros.hpp>
            TEST_CASE("multiplies", "[math]") {
              REQUIRE(2 * 3 == 6);
            }
            TEST_CASE_METHOD(Fixture, "with a fixture") {}
            SCENARIO("a story") {}
            TEMPLATE_TEST_CASE("sized", "", int, long) {}
            TEST_CASE("odd, name [x] * ~y \\\\ \\"q\\"") {}
            // TEST_CASE("commented out") is still a line that starts with a comment
            """;

    private static final String DOCTEST = """
            #define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
            #include <doctest/doctest.h>
            TEST_CASE("subtracts") { CHECK(1 == 1); }
            TEST_SUITE("suite") {
              TEST_CASE("in suite") {
                CHECK(true);
              }
            }
            SCENARIO("a story") {}
            TEST_CASE_TEMPLATE("typed", T, int, char) {}
            TEST_CASE("odd, name") {}
            """;

    @Test
    void theFrameworkIsKnownByWhatTheFileIncludes() {
        assertEquals(ToolTestKind.GTEST, CppTests.framework(GTEST));
        assertEquals(ToolTestKind.GTEST, CppTests.framework("#include \"gmock/gmock.h\"\n"));
        assertEquals(ToolTestKind.CATCH2, CppTests.framework(CATCH));
        assertEquals(ToolTestKind.CATCH2_V2, CppTests.framework("#include \"catch.hpp\"\n"));
        assertEquals(ToolTestKind.CATCH2_V2, CppTests.framework("#include <catch2/catch.hpp>\n"));
        assertEquals(ToolTestKind.DOCTEST, CppTests.framework(DOCTEST));
        assertEquals(ToolTestKind.DOCTEST, CppTests.framework("#include \"doctest.h\"\n"));
        assertNull(CppTests.framework("#include <vector>\nint main() {}\n"));
    }

    @Test
    void googleTestsAreNamedAndFilteredAsTheProgramRunsThem() {
        List<CppTests.Test> tests = CppTests.scan(ToolTestKind.GTEST, GTEST);
        assertEquals(
                List.of(
                        new CppTests.Test(2, "Math.Adds", "Math.Adds"),
                        new CppTests.Test(5, "Fix.UsesFixture", "Fix.UsesFixture"),
                        new CppTests.Test(6, "P.Even", "*/P.Even/*"),
                        new CppTests.Test(7, "Typed.Works", "Typed/*.Works")),
                tests);
        assertEquals(
                List.of("--gtest_filter=Math.Adds:*/P.Even/*"),
                CppTests.filterArgs(ToolTestKind.GTEST, List.of("Math.Adds", "*/P.Even/*")));
    }

    @Test
    void theTestAtTheCaretIsTheOneWhoseBodyHoldsIt() {
        assertEquals("Math.Adds", CppTests.at(ToolTestKind.GTEST, GTEST, 2).name());
        assertEquals("Math.Adds", CppTests.at(ToolTestKind.GTEST, GTEST, 3).name());
        assertEquals("Math.Adds", CppTests.at(ToolTestKind.GTEST, GTEST, 4).name());
        assertEquals(
                "Fix.UsesFixture", CppTests.at(ToolTestKind.GTEST, GTEST, 5).name());
        assertNull(CppTests.at(ToolTestKind.GTEST, GTEST, 1), "above every test");
        assertNull(CppTests.at(ToolTestKind.GTEST, GTEST, 9), "a helper after the tests");
        assertEquals("in suite", CppTests.at(ToolTestKind.DOCTEST, DOCTEST, 5).name());
    }

    @Test
    void catchTestsAreNamedAndTheirSpecsEscaped() {
        List<CppTests.Test> tests = CppTests.scan(ToolTestKind.CATCH2, CATCH);
        assertEquals(
                List.of("multiplies", "with a fixture", "Scenario: a story", "sized", "odd, name [x] * ~y \\ \"q\""),
                tests.stream().map(CppTests.Test::name).toList());
        assertEquals("sized - *", tests.get(3).filter(), "a template's cases: one per type");
        // As the real Catch2 3.8.1 matched it: the name's , [ ] * ~ \ and " escaped.
        assertEquals("odd\\, name \\[x\\] \\* \\~y \\\\ \\\"q\\\"", tests.get(4).filter());
        assertEquals(List.of("multiplies,odd"), CppTests.filterArgs(ToolTestKind.CATCH2, List.of("multiplies", "odd")));
    }

    @Test
    void doctestCasesAreNamedAndTheirCommasEscaped() {
        List<CppTests.Test> tests = CppTests.scan(ToolTestKind.DOCTEST, DOCTEST);
        assertEquals(
                List.of("subtracts", "in suite", "  Scenario: a story", "typed", "odd, name"),
                tests.stream().map(CppTests.Test::name).toList());
        assertEquals("typed<*>", tests.get(3).filter());
        assertEquals("odd\\, name", tests.get(4).filter());
        assertEquals(
                List.of("--test-case=subtracts,odd\\, name"),
                CppTests.filterArgs(ToolTestKind.DOCTEST, List.of("subtracts", "odd\\, name")));
    }

    @Test
    void aStringIsReadWithItsEscapes() {
        assertEquals("a \"b\" \\ c", CppTests.firstString("(\"a \\\"b\\\" \\\\ c\", x)"));
        assertNull(CppTests.firstString("(Fixture)"));
    }

    @Test
    void googleTestsReportIsReadIntoRowsPerFile() throws Exception {
        List<ParsedSuite> suites = ToolTestKind.GTEST.readReport(fixture("gtest.xml"), WORK);
        assertEquals(
                List.of("tests/gt_test.cpp"),
                suites.stream().map(ParsedSuite::suiteName).toList());
        ParsedTest fails = test(suites, "Math.Fails");
        assertEquals(TestStatus.FAILED, fails.status());
        assertEquals("Expected equality of these values:", fails.failureMessage());
        assertTrue(fails.stackTrace().contains("Which is: 3"));
        assertEquals(at("tests/gt_test.cpp"), fails.sourceFileHint());
        assertEquals(4, fails.sourceLineHint());
        assertEquals(TestStatus.PASSED, test(suites, "Math.Adds").status());
        assertEquals(TestStatus.SKIPPED, test(suites, "Other.Skips").status());
        assertEquals(TestStatus.FAILED, test(suites, "Nums/P.Even/1").status());
        assertEquals(TestStatus.PASSED, test(suites, "Nums/P.Even/0").status());
        assertTrue(ToolTestKind.GTEST.readReport("not xml", WORK).isEmpty());
    }

    @Test
    void catchsReportNamesTheFailedCheckItsSectionAndItsLine() throws Exception {
        List<ParsedSuite> suites = ToolTestKind.CATCH2.readReport(fixture("catch2.xml"), WORK);
        ParsedTest fails = test(suites, "fails here");
        assertEquals(TestStatus.FAILED, fails.status());
        assertEquals("REQUIRE( mul(2, 2) == 5 )", fails.failureMessage());
        assertTrue(fails.stackTrace().contains("with expansion:\n  4 == 5"), fails.stackTrace());
        assertTrue(fails.stackTrace().contains("in section: first"), fails.stackTrace());
        assertEquals(5, fails.sourceLineHint(), "the failed check's line");
        assertEquals(at("tests/catch_test.cpp"), fails.sourceFileHint());
        assertEquals(TestStatus.PASSED, test(suites, "multiplies").status());
        assertEquals(3, test(suites, "multiplies").sourceLineHint());
        ParsedTest skipped = test(suites, "Skipped one");
        assertEquals(TestStatus.SKIPPED, skipped.status());
        assertEquals("later", skipped.failureMessage());
    }

    @Test
    void doctestsReportIsReadTheSameWay() throws Exception {
        List<ParsedSuite> suites = ToolTestKind.DOCTEST.readReport(fixture("doctest.xml"), WORK);
        assertEquals(
                List.of("tests/doctest_test.cpp"),
                suites.stream().map(ParsedSuite::suiteName).toList());
        ParsedTest fails = test(suites, "sub fails");
        assertEquals(TestStatus.FAILED, fails.status());
        assertEquals("CHECK( sub(3, 1) == 5 )", fails.failureMessage());
        assertEquals(TestStatus.PASSED, test(suites, "in suite").status());
        assertEquals(TestStatus.PASSED, test(suites, "  Scenario: a story").status());
        // A narrowed run lists the cases its filter left out, as skipped: Test Results drops them.
        String narrowed = fixture("doctest.xml")
                .replace("<TestCase name=\"subtracts\"", "<TestCase skipped=\"true\" name=\"subtracts\"");
        assertEquals(
                TestStatus.SKIPPED,
                test(ToolTestKind.DOCTEST.readReport(narrowed, WORK), "subtracts")
                        .status());
        assertTrue(ToolTestKind.DOCTEST.reportsFilteredAsSkipped());
    }

    @Test
    void aRunIsToldToReportAndWidenedAndNarrowedByItsOwnFilter() {
        Path report = WORK.resolve("r.xml");
        assertEquals(
                List.of("./gt", "--gtest_filter=Math.Adds", "--gtest_output=xml:" + report),
                ToolTestKind.GTEST.withReport(List.of("./gt", "--gtest_filter=Math.Adds"), report, List.of()));
        assertEquals(
                List.of("./ct", "--reporter", "console::out=-", "--reporter", "xml::out=" + report),
                ToolTestKind.CATCH2.withReport(List.of("./ct"), report, List.of()));
        assertEquals(
                List.of("./dt", "--reporters=xml", "--out=" + report),
                ToolTestKind.DOCTEST.withReport(List.of("./dt"), report, List.of()));

        assertEquals(List.of("./gt"), ToolTestKind.GTEST.wholeFile(List.of("./gt", "--gtest_filter=Math.Adds")));
        assertEquals(List.of("./ct"), ToolTestKind.CATCH2.wholeFile(List.of("./ct", "multiplies")));
        assertEquals(List.of("./dt"), ToolTestKind.DOCTEST.wholeFile(List.of("./dt", "--test-case=subtracts")));

        assertEquals(
                List.of("./gt", "--gtest_filter=Math.Fails:Nums/P.Even/1"),
                ToolTestKind.GTEST.filtered(List.of("./gt"), List.of("Math.Fails", "Nums/P.Even/1")));
        assertEquals(
                List.of("./ct", "fails here,odd\\, name"),
                ToolTestKind.CATCH2.filtered(List.of("./ct"), List.of("fails here", "odd, name")));
        assertEquals(
                List.of("./dt", "--test-case=sub fails,odd\\, name"),
                ToolTestKind.DOCTEST.filtered(List.of("./dt"), List.of("sub fails", "odd, name")));
    }
}
