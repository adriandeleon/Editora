package com.editora.dap;

import java.nio.file.Path;

import org.eclipse.lsp4j.debug.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Adapters put strings that are not file paths into a frame's {@code Source.path}. One the platform rejects
 * as a path must cost that frame its file, not the whole stack: on Windows {@code <node_internals>/…} and
 * {@code jdt://contents/…} are illegal ({@code < > : ?}), and the failed conversion emptied the call stack at
 * every stop.
 */
class DapClientSourcePathTest {

    private static Source source(String path) {
        Source s = new Source();
        s.setPath(path);
        return s;
    }

    @Test
    void aStringThatIsNotALegalPathHasNoFile() {
        // NUL is the one character no platform accepts, so this fails the same way everywhere.
        assertNull(DapClient.sourcePath(source("<node_internals>\u0000/internal/modules/cjs/loader")));
    }

    @Test
    void anOrdinaryPathIsKept() {
        assertEquals(Path.of("/work/app/Main.java"), DapClient.sourcePath(source("/work/app/Main.java")));
    }

    @Test
    void aFrameWithoutSourceHasNoFile() {
        assertNull(DapClient.sourcePath(null));
        assertNull(DapClient.sourcePath(new Source()));
    }

    @Test
    void aJdtSourceKeepsTheAdaptersUriWhichAPathWouldMangle() {
        // B5: as a Path, "jdt://contents/…" became "jdt:/contents/…", which jdtls cannot serve the text of
        org.eclipse.lsp4j.debug.Source jdt = new org.eclipse.lsp4j.debug.Source();
        jdt.setPath("jdt://contents/java.base/java.lang/String.class?=p/%3Cjava.lang%28String.class");
        org.junit.jupiter.api.Assertions.assertEquals(jdt.getPath(), DapClient.sourceUri(jdt));
        org.eclipse.lsp4j.debug.Source file = new org.eclipse.lsp4j.debug.Source();
        file.setPath("/work/Main.java");
        org.junit.jupiter.api.Assertions.assertNull(DapClient.sourceUri(file));
        org.junit.jupiter.api.Assertions.assertNull(DapClient.sourceUri(null));
    }
}
