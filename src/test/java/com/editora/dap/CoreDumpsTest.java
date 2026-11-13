package com.editora.dap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreDumpsTest {

    @Test
    void aLinuxCoreNamesItsProgram(@TempDir Path dir) throws Exception {
        Path core = FakeCoreFile.write(
                dir.resolve("core.4242"),
                "orders-service-with-a-long-name",
                List.of("/usr/lib/ld-linux.so.2", "/srv/app/orders-service-with-a-long-name", "/usr/lib/libc.so.6"));
        assertEquals(
                Path.of("/srv/app/orders-service-with-a-long-name"),
                CoreDumps.programOf(core),
                "the file named as the process (cut to 15 characters), not the first mapped");

        Path plain = FakeCoreFile.write(dir.resolve("core.1"), "", List.of("/srv/app/shelf", "/usr/lib/libc.so.6"));
        assertEquals(Path.of("/srv/app/shelf"), CoreDumps.programOf(plain), "no name: the first file mapped");
    }

    @Test
    void anythingElseNamesNoProgram(@TempDir Path dir) throws Exception {
        assertNull(CoreDumps.programOf(Files.writeString(dir.resolve("notes.txt"), "not a core")));
        assertNull(CoreDumps.programOf(Files.write(dir.resolve("short"), new byte[] {0x7f, 'E', 'L', 'F'})));
        assertNull(CoreDumps.programOf(dir.resolve("missing")));
        byte[] program = Files.readAllBytes(FakeCoreFile.write(dir.resolve("core"), "x", List.of("/x")));
        program[16] = 2; // ET_EXEC: a program, not its core
        assertNull(CoreDumps.programOf(Files.write(dir.resolve("program"), program)));
        assertNull(CoreDumps.pick(List.of(), "x"));
    }

    @Test
    void theProgramsLanguageIsReadFromItsBytes(@TempDir Path dir) throws Exception {
        byte[] padding = new byte[(1 << 20) - 5]; // the marker straddles the first and second chunk
        Path go = dir.resolve("go");
        Files.write(go, padding);
        Files.write(
                go,
                "\u00ff Go buildinf:".getBytes(StandardCharsets.ISO_8859_1),
                java.nio.file.StandardOpenOption.APPEND);
        assertEquals(DebugLanguages.GO, CoreDumps.languageOf(go));
        Path rust = Files.writeString(dir.resolve("rust"), "panicked at /rustc/1234abcd/library/core/src/option.rs");
        assertEquals(DebugLanguages.RUST, CoreDumps.languageOf(rust));
        assertEquals(DebugLanguages.CPP, CoreDumps.languageOf(Files.writeString(dir.resolve("c"), "\u007fELF main")));
        assertEquals(DebugLanguages.CPP, CoreDumps.languageOf(dir.resolve("gone")), "unreadable: CodeLLDB says so");
    }

    @Test
    void delveOpensGoCoresAndCodelldbTheRest() {
        // A path with a space; a quote, which a Windows path cannot have, is lldbQuoted's below.
        Path program = Path.of("/srv/my app/shelf");
        Path core = Path.of("/tmp/core 1");
        Map<String, Object> go = CoreDumps.launch(DebugLanguages.GO, program, core);
        assertEquals("core", go.get("mode"));
        assertEquals(program.toString(), go.get("program"));
        assertEquals(core.toString(), go.get("coreFilePath"));
        assertTrue(CoreDumps.isCore(go));

        Map<String, Object> lldb = CoreDumps.launch(DebugLanguages.RUST, program, core);
        assertEquals(
                List.of("target create " + CoreDumps.lldbQuoted(program.toString()) + " --core "
                        + CoreDumps.lldbQuoted(core.toString())),
                lldb.get("targetCreateCommands"));
        assertEquals("\"/tmp/core \\\"1\\\"\"", CoreDumps.lldbQuoted("/tmp/core \"1\""));
        assertEquals(List.of(), lldb.get("processCreateCommands"), "nothing is started");
        assertEquals(List.of("rust"), lldb.get("sourceLanguages"));
        assertTrue(CoreDumps.isCore(lldb));
        assertEquals("\"C:\\\\dumps\\\\a.dmp\"", CoreDumps.lldbQuoted("C:\\dumps\\a.dmp"));
    }

    @Test
    void aLaunchJsonConfigurationOpeningACoreIsKnown() {
        assertTrue(CoreDumps.isCore(Map.of("targetCreateCommands", List.of("target create -c core"))));
        assertTrue(CoreDumps.isCore(Map.of("type", "cppdbg", "coreDumpPath", "/tmp/core")));
        assertFalse(CoreDumps.isCore(Map.of("type", "lldb", "program", "/bin/app")));
        assertFalse(CoreDumps.isCore(Map.of("mode", "debug")));
        assertFalse(CoreDumps.isCore(Map.of("targetCreateCommands", List.of("target create /bin/app"))));
    }
}
