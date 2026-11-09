package com.editora.dap;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Go program is told by its own section; another program, or another file, is not one. */
class ProgramFilesTest {

    /** A 64-bit little-endian ELF file with sections named {@code names} (the name table last). */
    static byte[] elf(List<String> names) {
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        table.write(0);
        int[] offsets = new int[names.size() + 1];
        List<String> all = new java.util.ArrayList<>(names);
        all.add(".shstrtab");
        for (int i = 0; i < all.size(); i++) {
            offsets[i] = table.size();
            table.writeBytes(all.get(i).getBytes(StandardCharsets.US_ASCII));
            table.write(0);
        }
        byte[] strings = table.toByteArray();
        int namesAt = 64;
        int shoff = namesAt + strings.length;
        int count = all.size() + 1; // the null section first
        ByteBuffer b = ByteBuffer.allocate(shoff + count * 64).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {0x7f, 'E', 'L', 'F', 2, 1, 1});
        b.putLong(0x28, shoff);
        b.putShort(0x3A, (short) 64);
        b.putShort(0x3C, (short) count);
        b.putShort(0x3E, (short) (count - 1));
        b.put(namesAt, strings);
        for (int i = 0; i < all.size(); i++) {
            int h = shoff + (i + 1) * 64;
            b.putInt(h, offsets[i]);
            if (i == all.size() - 1) {
                b.putLong(h + 0x18, namesAt);
                b.putLong(h + 0x20, strings.length);
            }
        }
        return b.array();
    }

    /** A 64-bit Mach-O file with one {@code __DATA} segment holding sections {@code sections}. */
    static byte[] machO(List<String> sections) {
        int cmdSize = 72 + 80 * sections.size();
        ByteBuffer b = ByteBuffer.allocate(32 + cmdSize).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, 0xfeedfacf);
        b.putInt(16, 1);
        b.putInt(20, cmdSize);
        b.putInt(32, 0x19);
        b.putInt(36, cmdSize);
        b.put(40, "__DATA".getBytes(StandardCharsets.US_ASCII));
        b.putInt(32 + 64, sections.size());
        for (int i = 0; i < sections.size(); i++) {
            b.put(32 + 72 + i * 80, sections.get(i).getBytes(StandardCharsets.US_ASCII));
        }
        return b.array();
    }

    @Test
    void anElfProgramWithGosBuildInfoIsGo(@TempDir Path dir) throws Exception {
        assertTrue(ProgramFiles.isGo(Files.write(dir.resolve("go"), elf(List.of(".text", ".go.buildinfo", ".data")))));
        assertTrue(
                ProgramFiles.isGo(Files.write(dir.resolve("old"), elf(List.of(".text", ".gopclntab")))), "Go < 1.18");
        assertFalse(ProgramFiles.isGo(Files.write(dir.resolve("c"), elf(List.of(".text", ".data", ".bss")))));
    }

    @Test
    void aMachOProgramWithGosBuildInfoIsGo(@TempDir Path dir) throws Exception {
        assertTrue(ProgramFiles.isGo(Files.write(dir.resolve("go"), machO(List.of("__data", "__go_buildinfo")))));
        assertFalse(ProgramFiles.isGo(Files.write(dir.resolve("c"), machO(List.of("__data", "__bss")))));
    }

    @Test
    void anythingElseIsNot(@TempDir Path dir) throws Exception {
        assertFalse(ProgramFiles.isGo(Files.writeString(dir.resolve("script"), "#!/bin/sh\necho .go.buildinfo\n")));
        assertFalse(ProgramFiles.isGo(Files.write(dir.resolve("empty"), new byte[0])));
        assertFalse(ProgramFiles.isGo(dir.resolve("missing")));
        byte[] cut = elf(List.of(".go.buildinfo"));
        assertFalse(
                ProgramFiles.isGo(Files.write(dir.resolve("cut"), java.util.Arrays.copyOf(cut, 80))),
                "section headers past its end");
        byte[] wild = elf(List.of(".go.buildinfo"));
        ByteBuffer.wrap(wild).order(ByteOrder.LITTLE_ENDIAN).putShort(0x3C, (short) 60000);
        assertFalse(ProgramFiles.isGo(Files.write(dir.resolve("wild"), wild)), "an absurd section count");
        assertFalse(ProgramFiles.isGo(dir), "a folder");
    }

    @Test
    void thisJavaIsNotGo() {
        String java = ProcessHandle.current().info().command().orElse("");
        if (!java.isEmpty()) {
            assertFalse(ProgramFiles.isGo(Path.of(java)));
        }
    }

    @Test
    void aNativeProgramWithDebugInformationIsToldAndRustsFromIt(@TempDir Path dir) throws Exception {
        assertEquals(
                ProgramFiles.Kind.NATIVE,
                ProgramFiles.kind(Files.write(dir.resolve("c"), elf(List.of(".text", ".debug_info", ".debug_line")))));
        assertEquals(
                ProgramFiles.Kind.RUST,
                ProgramFiles.kind(
                        Files.write(dir.resolve("r"), elf(List.of(".text", ".debug_info", ".debug_gdb_scripts")))));
        assertEquals(
                ProgramFiles.Kind.GO,
                ProgramFiles.kind(Files.write(dir.resolve("g"), elf(List.of(".debug_info", ".go.buildinfo")))),
                "Go before native: Delve debugs it");
        assertEquals(null, ProgramFiles.kind(Files.write(dir.resolve("stripped"), elf(List.of(".text", ".data")))));
        assertEquals(List.of(), List.copyOf(ProgramFiles.sections(dir.resolve("missing"))));
        assertTrue(ProgramFiles.sections(dir.resolve("c")).contains(".debug_line"));
    }

    /** A 32-bit big-endian ELF file with sections named {@code names} (the name table last). */
    static byte[] elf32BigEndian(List<String> names) {
        java.io.ByteArrayOutputStream table = new java.io.ByteArrayOutputStream();
        table.write(0);
        List<String> all = new java.util.ArrayList<>(names);
        all.add(".shstrtab");
        int[] offsets = new int[all.size()];
        for (int i = 0; i < all.size(); i++) {
            offsets[i] = table.size();
            table.writeBytes(all.get(i).getBytes(StandardCharsets.US_ASCII));
            table.write(0);
        }
        byte[] strings = table.toByteArray();
        int namesAt = 64;
        int shoff = namesAt + strings.length;
        int count = all.size() + 1;
        ByteBuffer b = ByteBuffer.allocate(shoff + count * 40).order(ByteOrder.BIG_ENDIAN);
        b.put(new byte[] {0x7f, 'E', 'L', 'F', 1, 2, 1});
        b.putInt(0x20, shoff);
        b.putShort(0x2E, (short) 40);
        b.putShort(0x30, (short) count);
        b.putShort(0x32, (short) (count - 1));
        b.put(namesAt, strings);
        for (int i = 0; i < all.size(); i++) {
            int h = shoff + (i + 1) * 40;
            b.putInt(h, offsets[i]);
            if (i == all.size() - 1) {
                b.putInt(h + 0x10, namesAt);
                b.putInt(h + 0x14, strings.length);
            }
        }
        return b.array();
    }

    @Test
    void a32BitBigEndianElfIsReadAndRustsOtherSectionTellsIt(@TempDir Path dir) throws Exception {
        assertEquals(
                ProgramFiles.Kind.RUST,
                ProgramFiles.kind(Files.write(dir.resolve("r32"), elf32BigEndian(List.of(".debug_info", ".rustc")))));
        assertEquals(
                ProgramFiles.Kind.NATIVE,
                ProgramFiles.kind(Files.write(dir.resolve("c32"), elf32BigEndian(List.of(".text", ".debug_info")))));
    }

    @Test
    void headersThatSayNothingSensibleNameNoSections(@TempDir Path dir) throws Exception {
        byte[] noHeaders = elf(List.of(".debug_info"));
        ByteBuffer.wrap(noHeaders).order(ByteOrder.LITTLE_ENDIAN).putLong(0x28, 0);
        assertTrue(ProgramFiles.sections(Files.write(dir.resolve("noshoff"), noHeaders))
                .isEmpty());
        byte[] badIndex = elf(List.of(".debug_info"));
        ByteBuffer.wrap(badIndex).order(ByteOrder.LITTLE_ENDIAN).putShort(0x3E, (short) 999);
        assertTrue(ProgramFiles.sections(Files.write(dir.resolve("badindex"), badIndex))
                .isEmpty());
        byte[] smallEntries = elf(List.of(".debug_info"));
        ByteBuffer.wrap(smallEntries).order(ByteOrder.LITTLE_ENDIAN).putShort(0x3A, (short) 8);
        assertTrue(ProgramFiles.sections(Files.write(dir.resolve("small"), smallEntries))
                .isEmpty());
        assertTrue(
                ProgramFiles.sections(Files.write(dir.resolve("text"), new byte[40]))
                        .isEmpty(),
                "neither format");

        byte[] noCommands = machO(List.of("__go_buildinfo"));
        ByteBuffer.wrap(noCommands).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 0);
        assertTrue(ProgramFiles.sections(Files.write(dir.resolve("m0"), noCommands))
                .isEmpty());
        byte[] tinyCommand = machO(List.of("__go_buildinfo"));
        ByteBuffer.wrap(tinyCommand).order(ByteOrder.LITTLE_ENDIAN).putInt(36, 4);
        assertTrue(ProgramFiles.sections(Files.write(dir.resolve("m1"), tinyCommand))
                .isEmpty());
        byte[] otherCommand = machO(List.of("__go_buildinfo"));
        ByteBuffer.wrap(otherCommand).order(ByteOrder.LITTLE_ENDIAN).putInt(32, 0x1b);
        assertTrue(
                ProgramFiles.sections(Files.write(dir.resolve("m2"), otherCommand))
                        .isEmpty(),
                "not a segment");
    }
}
