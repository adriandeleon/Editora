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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Go program is told by its own section; another program, or another file, is not one. */
class GoBinariesTest {

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
        assertTrue(GoBinaries.isGo(Files.write(dir.resolve("go"), elf(List.of(".text", ".go.buildinfo", ".data")))));
        assertTrue(GoBinaries.isGo(Files.write(dir.resolve("old"), elf(List.of(".text", ".gopclntab")))), "Go < 1.18");
        assertFalse(GoBinaries.isGo(Files.write(dir.resolve("c"), elf(List.of(".text", ".data", ".bss")))));
    }

    @Test
    void aMachOProgramWithGosBuildInfoIsGo(@TempDir Path dir) throws Exception {
        assertTrue(GoBinaries.isGo(Files.write(dir.resolve("go"), machO(List.of("__data", "__go_buildinfo")))));
        assertFalse(GoBinaries.isGo(Files.write(dir.resolve("c"), machO(List.of("__data", "__bss")))));
    }

    @Test
    void anythingElseIsNot(@TempDir Path dir) throws Exception {
        assertFalse(GoBinaries.isGo(Files.writeString(dir.resolve("script"), "#!/bin/sh\necho .go.buildinfo\n")));
        assertFalse(GoBinaries.isGo(Files.write(dir.resolve("empty"), new byte[0])));
        assertFalse(GoBinaries.isGo(dir.resolve("missing")));
        byte[] cut = elf(List.of(".go.buildinfo"));
        assertFalse(
                GoBinaries.isGo(Files.write(dir.resolve("cut"), java.util.Arrays.copyOf(cut, 80))),
                "section headers past its end");
        byte[] wild = elf(List.of(".go.buildinfo"));
        ByteBuffer.wrap(wild).order(ByteOrder.LITTLE_ENDIAN).putShort(0x3C, (short) 60000);
        assertFalse(GoBinaries.isGo(Files.write(dir.resolve("wild"), wild)), "an absurd section count");
        assertFalse(GoBinaries.isGo(dir), "a folder");
    }

    @Test
    void thisJavaIsNotGo() {
        String java = ProcessHandle.current().info().command().orElse("");
        if (!java.isEmpty()) {
            assertFalse(GoBinaries.isGo(Path.of(java)));
        }
    }
}
