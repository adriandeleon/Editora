package com.editora.dap;

import java.nio.file.Path;

import org.eclipse.lsp4j.debug.Module;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** How {@link DapClient} reads the sources and modules adapters list. */
class DapLoadedSourcesTest {

    private static final Path ABS =
            Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("loop.js");

    private static Source source(String name, String path, Integer ref) {
        Source s = new Source();
        s.setName(name);
        s.setPath(path);
        s.setSourceReference(ref);
        return s;
    }

    @Test
    void aFileIsAFileAndAnInternalIsAReference() {
        DapModels.LoadedSource file = DapClient.loadedSource(source(null, ABS.toString(), 0), DapPaths.LOCAL);
        assertEquals(ABS, file.file());
        assertEquals("loop.js", file.name(), "named after its file when the adapter gives no name");
        DapModels.LoadedSource internal =
                DapClient.loadedSource(source("<node_internals>/fs", "<node_internals>/fs", 1726), DapPaths.LOCAL);
        assertNull(internal.file());
        assertEquals(1726, internal.sourceReference());
        assertFalse(file.key().equals(internal.key()));
        // A file path with a reference is read through the reference: the adapter may have rewritten it.
        assertNull(DapClient.loadedSource(source("x.js", ABS.toString(), 5), DapPaths.LOCAL)
                .file());
        assertNull(DapClient.loadedSource(null, DapPaths.LOCAL));
        assertNull(DapClient.loadedSource(source(null, null, null), DapPaths.LOCAL), "names nothing");
        assertEquals(
                "r:3:anon",
                DapClient.loadedSource(source("anon", null, 3), DapPaths.LOCAL).key(),
                "no path: told apart by reference");
    }

    @Test
    void aModuleKeepsWhatTheAdapterSays() {
        Module m = new Module();
        m.setId(Either.forLeft(4));
        m.setName("libc.so.6");
        m.setPath("/usr/lib/libc.so.6");
        m.setSymbolStatus("Public symbols only");
        m.setIsUserCode(false);
        DapModels.ModuleInfo info = DapClient.moduleInfo(m);
        assertEquals("4", info.id());
        assertEquals("Public symbols only", info.symbolStatus());
        assertEquals(Boolean.FALSE, info.userCode());
        Module named = new Module();
        named.setName("json");
        assertEquals("json", DapClient.moduleInfo(named).id(), "no id: its name");
        assertNull(DapClient.moduleInfo(new Module()));
    }
}
