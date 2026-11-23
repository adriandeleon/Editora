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

    /** A note of a big-endian core: name "CORE", {@code desc} padded to four bytes. */
    private static byte[] note32(int type, byte[] desc) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(12 + 8 + ((desc.length + 3) & ~3));
        b.putInt(5).putInt(desc.length).putInt(type);
        b.put("CORE".getBytes(StandardCharsets.US_ASCII)).put(new byte[4]);
        b.put(desc);
        return b.array();
    }

    /** {@code NT_FILE}'s description of a 32-bit core mapping {@code mapped}. */
    private static byte[] files32(List<String> mapped) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.nio.ByteBuffer head = java.nio.ByteBuffer.allocate(8 + mapped.size() * 12);
        head.putInt(mapped.size()).putInt(4096);
        for (int i = 0; i < mapped.size(); i++) {
            head.putInt(0x8000 + i).putInt(0x9000 + i).putInt(0);
        }
        out.writeBytes(head.array());
        for (String m : mapped) {
            out.writeBytes(m.getBytes(StandardCharsets.UTF_8));
            out.write(0);
        }
        return out.toByteArray();
    }

    @Test
    void aBigEndian32BitCoreIsReadPastSegmentsAndNotesItDoesNotUse(@TempDir Path dir) throws Exception {
        java.io.ByteArrayOutputStream notes = new java.io.ByteArrayOutputStream();
        notes.writeBytes(note32(3, new byte[8])); // a process note too short to hold the name
        byte[] info = new byte[44];
        byte[] name = "shelf".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(name, 0, info, 28, name.length);
        notes.writeBytes(note32(3, info));
        notes.writeBytes(note32(0x46494c45, files32(List.of("/lib/ld.so", "/srv/shelf"))));
        notes.writeBytes(note32(0x46494c45, files32(List.of("/other")))); // only the first file note counts
        notes.writeBytes(new byte[] {-1, -1, -1, -1, 0, 0, 0, 0, 0, 0, 0, 1}); // a broken note ends the reading

        int phoff = 52;
        int notesAt = phoff + 3 * 32;
        java.nio.ByteBuffer elf = java.nio.ByteBuffer.allocate(notesAt + notes.size());
        elf.put(new byte[] {0x7f, 'E', 'L', 'F', 1, 2, 1, 0}).put(new byte[8]);
        elf.putShort((short) 4).putShort((short) 8).putInt(1); // ET_CORE, MIPS, version
        elf.putInt(0).putInt(phoff).putInt(0); // entry, phoff, shoff
        elf.putInt(0).putShort((short) 52).putShort((short) 32).putShort((short) 3); // flags, ehsize, phent, phnum
        elf.putShort((short) 0).putShort((short) 0).putShort((short) 0);
        elf.putInt(1)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(0); // PT_LOAD
        elf.putInt(4)
                .putInt(notesAt)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(4); // empty PT_NOTE
        elf.putInt(4)
                .putInt(notesAt)
                .putInt(0)
                .putInt(0)
                .putInt(notes.size())
                .putInt(0)
                .putInt(0)
                .putInt(4);
        elf.put(notes.toByteArray());
        Path core = Files.write(dir.resolve("core.be"), elf.array());
        assertEquals(Path.of("/srv/shelf"), CoreDumps.programOf(core));

        byte[] notElf = elf.array().clone();
        for (int i = 0; i < 4; i++) {
            byte[] bad = elf.array().clone();
            bad[i] ^= 0x20;
            assertNull(CoreDumps.programOf(Files.write(dir.resolve("bad" + i), bad)), "magic byte " + i);
        }
        notElf[16] = 0;
        notElf[17] = 2; // big-endian ET_EXEC
        assertNull(CoreDumps.programOf(Files.write(dir.resolve("exec.be"), notElf)));
    }

    @Test
    void aFileNoteThatIsCutShortListsWhatItHas() {
        java.nio.ByteBuffer tiny = java.nio.ByteBuffer.allocate(4);
        assertEquals(List.of(), CoreDumps.mappedFiles(tiny, false), "not even the count");
        java.nio.ByteBuffer none = java.nio.ByteBuffer.allocate(16);
        assertEquals(List.of(), CoreDumps.mappedFiles(none, true), "no mappings");
        java.nio.ByteBuffer claims = java.nio.ByteBuffer.allocate(16);
        claims.putLong(0, 5);
        assertEquals(List.of(), CoreDumps.mappedFiles(claims, true), "more mappings than it holds");
        byte[] cut = files32(List.of("/a", "/b"));
        java.nio.ByteBuffer unterminated = java.nio.ByteBuffer.wrap(java.util.Arrays.copyOf(cut, cut.length - 1));
        assertEquals(List.of("/a", "/b"), CoreDumps.mappedFiles(unterminated, false), "the last name runs to the end");
        java.nio.ByteBuffer fewer = java.nio.ByteBuffer.wrap(java.util.Arrays.copyOf(cut, cut.length - 3));
        assertEquals(List.of("/a"), CoreDumps.mappedFiles(fewer, false), "names that are not there are not listed");
        assertNull(CoreDumps.pick(List.of(" "), ""), "a blank name is no program");
        assertNull(CoreDumps.pick(List.of("bad\u0000name"), ""));
        assertEquals(Path.of("/x/b"), CoreDumps.pick(List.of("/x/a", "/x/b"), "b"));
    }
}
