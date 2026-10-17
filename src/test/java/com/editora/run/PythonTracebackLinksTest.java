package com.editora.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Python locations the Run console links (review C13). */
class PythonTracebackLinksTest {

    private static String link(String line) {
        StackTraceLinks.Link l = StackTraceLinks.parse(line);
        return l == null ? null : l.file() + ":" + l.line();
    }

    @Test
    void tracebackFramesOfAnyFileLink() {
        assertEquals("/p/app.py:12", link("  File \"/p/app.py\", line 12, in <module>"));
        // An extensionless script run through its shebang is still Python; a path may hold a quote.
        assertEquals("/p/tool:5", link("  File \"/p/tool\", line 5, in <module>"));
        assertEquals("/p/we\"ird.py:4", link("  File \"/p/we\"ird.py\", line 4"));
        assertEquals("C:\\p\\app.py:7", link("  File \"C:\\p\\app.py\", line 7, in main"));
    }

    @Test
    void pseudoFilesDoNotLink() {
        assertNull(link("  File \"<stdin>\", line 1, in <module>"));
        assertNull(link("  File \"<frozen runpy>\", line 88, in _run_code"));
        assertNull(link("  File \"<string>\", line 1"));
    }

    @Test
    void warningsPytestLintersAndPdbLink() {
        assertEquals("/p/app.py:12", link("/p/app.py:12: DeprecationWarning: utcnow() is deprecated"));
        assertEquals("tests/test_app.py:14", link("tests/test_app.py:14: AssertionError"));
        assertEquals("app.py:3", link("app.py:3:5: error: Name \"x\" is not defined  [name-defined]"));
        assertEquals("/p/app.py:12", link("> /p/app.py(12)main()"));
        assertEquals("C:\\p\\app.py:9", link("C:\\p\\app.py:9: UserWarning: careful"));
    }

    @Test
    void ordinaryTextDoesNotLink() {
        assertNull(link("tests/test_app.py::test_add FAILED"));
        assertNull(link("ratio 3:4 of copy.py files"));
    }
}
