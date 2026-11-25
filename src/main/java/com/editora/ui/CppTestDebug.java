package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * C and C++ tests under the debugger, for {@link DebugCoordinator}: Debug Test at Caret and a test's ▶ (its Debug
 * Test) in a GoogleTest, Catch2 or doctest file ({@link com.editora.test.CppTests}) — the test program built and
 * debugged as Debug File debugs it, run with the test's filter. FX thread.
 */
final class CppTestDebug {

    private final DebugCoordinator d;

    CppTestDebug(DebugCoordinator d) {
        this.d = d;
    }

    /**
     * Debug Test at Caret in a C or C++ test file (GoogleTest, Catch2, doctest — {@link com.editora.test.CppTests}): its
     * program built and debugged as Debug File debugs it, run with the filter of the test at the caret, or every test
     * outside one. False — nothing done — for any other buffer.
     */
    boolean debugAtCaret(@Nullable EditorBuffer b, Runnable otherwise) {
        if (b == null || b.getPath() == null || !("c".equals(b.getLanguage()) || "cpp".equals(b.getLanguage()))) {
            return false;
        }
        int caret = DebugCoordinator.caretLine(
                b); // the test is found off the FX thread (PERF-01): the command goes on once it is
        var found = d.scan(b, "cppTest:" + caret, CppTestAt::of, () -> {
            if (!debugAtCaret(b, otherwise)) {
                otherwise.run();
            }
        });
        if (found == null) {
            return true; // asked again once the scan answers
        }
        CppTestAt at = found.value();
        if (at == null) {
            return false;
        }
        CppTestAt here = at.at(caret);
        debugCppTests(b, here.kind(), here.filter());
        return true;
    }

    /** A C or C++ test file's framework and its tests, as scanned; {@link #at} the one a line is in. */
    private record CppTestAt(
            com.editora.test.ToolTestKind kind,
            String text,
            @Nullable String filter) {

        static @Nullable CppTestAt of(String text) {
            com.editora.test.ToolTestKind kind = com.editora.test.CppTests.framework(text);
            return kind == null ? null : new CppTestAt(kind, text, null);
        }

        CppTestAt at(int line) {
            com.editora.test.CppTests.Test t = com.editora.test.CppTests.at(kind, text, line);
            return new CppTestAt(kind, text, t == null ? null : t.filter());
        }
    }

    /** A C or C++ test's ▶ (its editor menu's Debug Test): that test, or every test of the file on its first line. */
    void debugGlyph(EditorBuffer b, com.editora.test.JavaTestScanner.TestTarget target) {
        String text = b.getContent();
        com.editora.test.ToolTestKind kind = com.editora.test.CppTests.framework(text);
        if (kind == null || b.getPath() == null) {
            return;
        }
        String filter = null;
        if (target.methodName() != null) {
            for (com.editora.test.CppTests.Test t : com.editora.test.CppTests.scan(kind, text)) {
                if (t.line() == target.line()) {
                    filter = t.filter();
                }
            }
        }
        debugCppTests(b, kind, filter);
    }

    private void debugCppTests(EditorBuffer b, com.editora.test.ToolTestKind kind, @Nullable String filter) {
        Path path = b.getPath();
        if (path == null) {
            return;
        }
        d.debugNativeTests(
                path,
                "cpp".equals(b.getLanguage()),
                com.editora.test.CppTests.filterArgs(kind, filter == null ? List.of() : List.of(filter)));
    }
}
