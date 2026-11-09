package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Where netcoredbg and vscode-php-debug are found: a configured path, Editora's install, a VS Code extension. */
class InstalledAdapterTest {

    private static final boolean WINDOWS = java.io.File.pathSeparatorChar == ';';

    private static Path file(Path p) throws Exception {
        Files.createDirectories(p.getParent());
        return Files.writeString(p, "");
    }

    @Test
    void aConfiguredFileOrFolderWins(@TempDir Path home) throws Exception {
        Path js = file(home.resolve("php-debug/extension/out/phpDebug.js"));
        InstalledAdapter php = InstalledAdapter.PHP_DEBUG;
        assertEquals(Optional.of(js), php.locate(js.toString(), home));
        assertEquals(Optional.of(js), php.locate(home.resolve("php-debug").toString(), home), "an unpacked .vsix");
        assertEquals(Optional.empty(), php.locate(home.resolve("none").toString(), home));
        assertEquals(Optional.empty(), php.locate("\0", home), "not a path");
    }

    @Test
    void elseEditorasInstallThenTheNewestVsCodeExtension(@TempDir Path home) throws Exception {
        InstalledAdapter cs = InstalledAdapter.NETCOREDBG;
        String exe = WINDOWS ? "netcoredbg.exe" : "netcoredbg";
        assertEquals(Optional.empty(), cs.locate("", home));
        Path ext = home.resolve(".vscode/extensions");
        file(ext.resolve("muhammad-sammy.csharp-2.80.0/.debugger/netcoredbg/" + exe));
        Path newer = file(ext.resolve("muhammad-sammy.csharp-2.93.1/.debugger/netcoredbg/" + exe));
        file(ext.resolve("ms-dotnettools.csharp-2.99.0/.debugger/" + exe)); // Microsoft's: vsdbg, not this
        assertEquals(Optional.of(newer), cs.locate(null, home));
        Path installed = file(home.resolve(".editora/plugins/dap/csharp/netcoredbg/" + exe));
        assertEquals(Optional.of(installed), cs.locate(" ", home), "Editora's own install first");
        assertEquals(
                home.resolve(".editora-dev/plugins/dap/csharp"), cs.roots(home).getLast());
    }

    @Test
    void eachLanguageHasItsOwn() {
        assertSame(InstalledAdapter.NETCOREDBG, DapManager.installedAdapter(DebugLanguages.CSHARP));
        assertSame(InstalledAdapter.PHP_DEBUG, DapManager.installedAdapter(DebugLanguages.PHP));
        assertEquals(null, DapManager.installedAdapter(DebugLanguages.RUBY), "rdbg is the gem's program");
    }
}
