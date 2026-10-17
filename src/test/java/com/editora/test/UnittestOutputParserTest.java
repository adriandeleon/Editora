package com.editora.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code python -m unittest -v} output (Python 3.13, captured) into Test Results — the no-pytest fallback. */
class UnittestOutputParserTest {

    private static final String OUTPUT = """
            test_add (test_m.T.test_add) ... ok
            test_doc (test_m.T.test_doc)
            Adds numbers. ... ok
            test_err (test_m.T.test_err) ... ERROR
            test_fail (test_m.T.test_fail) ... FAIL
            test_skip (test_m.T.test_skip) ... skipped 'later'
            test_sub (test_m.T.test_sub) ...\s
              test_sub (test_m.T.test_sub) (i=1) ... FAIL

            ======================================================================
            ERROR: test_err (test_m.T.test_err)
            ----------------------------------------------------------------------
            Traceback (most recent call last):
              File "/w/test_m.py", line 17, in test_err
                raise ValueError("boom")
            ValueError: boom

            ======================================================================
            FAIL: test_fail (test_m.T.test_fail)
            ----------------------------------------------------------------------
            Traceback (most recent call last):
              File "/w/test_m.py", line 14, in test_fail
                self.assertEqual(1, 2)
            AssertionError: 1 != 2

            Stdout:
            noise

            ----------------------------------------------------------------------
            Ran 6 tests in 0.004s

            FAILED (failures=2, errors=1, skipped=1)
            """;

    private static Map<String, ParsedTest> run(String output) {
        UnittestOutputParser p = new UnittestOutputParser();
        List<ParsedSuite> all = new ArrayList<>();
        for (String line : output.split("\n", -1)) {
            all.addAll(p.onLine(line, true));
        }
        all.addAll(p.onExit(1));
        Map<String, ParsedTest> last = new LinkedHashMap<>();
        for (ParsedSuite s : all) {
            for (ParsedTest t : s.tests()) {
                last.put(t.id(), t); // later deltas upsert, as TestTreeBuilder does
            }
        }
        return last;
    }

    @Test
    void everyResultAndItsFailure() {
        Map<String, ParsedTest> r = run(OUTPUT);
        assertEquals(TestStatus.PASSED, r.get("test_m.T#test_add").status());
        assertEquals(TestStatus.PASSED, r.get("test_m.T#test_doc").status(), "result after the docstring line");
        assertEquals(TestStatus.SKIPPED, r.get("test_m.T#test_skip").status());
        assertEquals(TestStatus.FAILED, r.get("test_m.T#test_sub").status(), "a failing subtest fails its test");
        ParsedTest err = r.get("test_m.T#test_err");
        assertEquals(TestStatus.ERROR, err.status());
        assertEquals("ValueError: boom", err.failureMessage());
        ParsedTest fail = r.get("test_m.T#test_fail");
        assertEquals("AssertionError: 1 != 2", fail.failureMessage(), "not the captured stdout after it");
        assertTrue(fail.stackTrace().contains("File \"/w/test_m.py\", line 14"));
    }

    @Test
    void theOlderFormNamesTheClassOnly() {
        Map<String, ParsedTest> r = run("test_add (pkg.test_m.T) ... ok\n");
        assertEquals(TestStatus.PASSED, r.get("pkg.test_m.T#test_add").status());
    }
}
