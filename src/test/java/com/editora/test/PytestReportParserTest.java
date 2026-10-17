package com.editora.test;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** pytest's {@code --junitxml} report (xunit1) into Test Results (decision 3). */
class PytestReportParserTest {

    private static final String REPORT = """
            <?xml version="1.0" encoding="utf-8"?><testsuites><testsuite name="pytest" errors="0" failures="1" \
            skipped="1" tests="3" time="0.02"><testcase classname="tests.test_shop" name="test_add" \
            file="tests/test_shop.py" line="3" time="0.004"><failure message="assert 1 == 2">def test_add():
            &gt;       assert 1 == 2
            E       assert 1 == 2

            tests/test_shop.py:5: AssertionError</failure></testcase><testcase classname="tests.test_shop.TestShop" \
            name="test_buy[1-2]" file="tests/test_shop.py" line="8" time="0.000" /><testcase \
            classname="tests.test_shop.TestShop" name="test_skip" file="tests/test_shop.py" line="11" time="0">\
            <skipped type="pytest.skip" message="later">tests/test_shop.py:12: later</skipped></testcase>\
            </testsuite></testsuites>""";

    @Test
    void oneSuitePerModuleOrClassWithLocations() {
        List<ParsedSuite> suites = PytestReportParser.parse(REPORT);
        assertEquals(
                List.of("tests.test_shop", "tests.test_shop.TestShop"),
                suites.stream().map(ParsedSuite::suiteName).toList());
        ParsedTest add = suites.get(0).tests().get(0);
        assertEquals(TestStatus.FAILED, add.status());
        assertEquals("assert 1 == 2", add.failureMessage());
        assertTrue(add.stackTrace().endsWith("tests/test_shop.py:5: AssertionError"));
        assertEquals("tests/test_shop.py", add.sourceFileHint());
        assertEquals(4, add.sourceLineHint(), "xunit1's 0-based line, 1-based");
        assertEquals(4, add.durationMs());
        assertEquals(TestStatus.PASSED, suites.get(1).tests().get(0).status());
        assertEquals(TestStatus.SKIPPED, suites.get(1).tests().get(1).status());
    }

    @Test
    void nodeIdsForRerun() {
        assertEquals(
                "tests/test_shop.py::test_add",
                PytestReportParser.nodeId("tests/test_shop.py", "tests.test_shop", "test_add"));
        assertEquals(
                "tests/test_shop.py::TestShop::test_buy[1-2]",
                PytestReportParser.nodeId("tests/test_shop.py", "tests.test_shop.TestShop", "test_buy[1-2]"));
        assertEquals(
                "tests/test_shop.py::TestShop",
                PytestReportParser.nodeId("tests/test_shop.py", "tests.test_shop.TestShop", null));
        assertEquals("tests/test_shop.py", PytestReportParser.nodeId("tests\\test_shop.py", "tests.test_shop", null));
    }

    @Test
    void anIncompleteReportIsNoResult() {
        assertTrue(PytestReportParser.parse("<testsuites><testsuite").isEmpty());
        assertTrue(PytestReportParser.parse("").isEmpty());
    }
}
