package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.dap.DapModels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** What the Disassembly and Memory views make of what the adapter answers. */
class LowLevelViewsTextTest {

    @Test
    void addressesAreComparedAsNumbers() {
        assertEquals(0x1000L, DebugDisassemblyView.parseAddress("0x0000000000001000"));
        assertEquals(0xabL, DebugDisassemblyView.parseAddress(" 0XAB "));
        assertEquals(4096L, DebugDisassemblyView.parseAddress("4096"));
        assertNull(DebugDisassemblyView.parseAddress("main+4"));
        assertNull(DebugDisassemblyView.parseAddress(""));
        assertNull(DebugDisassemblyView.parseAddress(null));
        assertEquals(0xffffffffffffffffL, DebugDisassemblyView.parseAddress("0xffffffffffffffff"), "unsigned");

        List<DapModels.Instruction> code = List.of(
                new DapModels.Instruction("0x0ff8", "", "push", "main", null, -1),
                new DapModels.Instruction("0x00001000", "", "mov", "main", null, -1),
                new DapModels.Instruction("sym+4", "", "ret", "main", null, -1));
        assertEquals(1, DebugDisassemblyView.indexOf(code, "0x1000"));
        assertEquals(2, DebugDisassemblyView.indexOf(code, "sym+4"), "not a number: compared as written");
        assertEquals(-1, DebugDisassemblyView.indexOf(code, "0x2000"));
    }

    @Test
    void aSourceLineIsNamedWhereItChanges() {
        Path main = Path.of("/p/main.c");
        Path util = Path.of("/p/util.c");
        List<DapModels.Instruction> code = List.of(
                new DapModels.Instruction("0x1", "", "a", "", main, 11),
                new DapModels.Instruction("0x2", "", "b", "", main, 11),
                new DapModels.Instruction("0x3", "", "c", "", main, 12),
                new DapModels.Instruction("0x4", "", "d", "", util, 12),
                new DapModels.Instruction("0x5", "", "e", "", null, -1));
        assertEquals("main.c:12", DebugDisassemblyView.sourceLabel(code, 0), "1-based");
        assertEquals("", DebugDisassemblyView.sourceLabel(code, 1), "the same line");
        assertEquals("main.c:13", DebugDisassemblyView.sourceLabel(code, 2));
        assertEquals("util.c:13", DebugDisassemblyView.sourceLabel(code, 3), "another file");
        assertEquals("", DebugDisassemblyView.sourceLabel(code, 4), "no line known");
    }

    @Test
    void memoryReadsAsHexEditorRows() {
        byte[] data = "Hello, world!\n\0\u0001ABC".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        List<String> rows = DebugMemoryView.rows("0x7ffc0a10", data, 3);
        assertEquals(2, rows.size());
        assertEquals("0x00007ffc0a10  48 65 6c 6c 6f 2c 20 77  6f 72 6c 64 21 0a 00 01  Hello, world!...", rows.get(0));
        assertEquals(
                "0x00007ffc0a20  41 42 43 ?? ?? ??                                 ABC   ",
                rows.get(1),
                "unreadable bytes after the data, then nothing");
        List<String> named = DebugMemoryView.rows("main+4", new byte[1], 0);
        assertEquals(1, named.size());
        org.junit.jupiter.api.Assertions.assertTrue(
                named.getFirst().startsWith("main+4  00 ") && named.getFirst().endsWith(" ."),
                "an address that is not a number: written once: " + named);
        assertEquals("+16", DebugMemoryView.rows("sym", new byte[20], 0).get(1).split(" ")[0]);
        assertEquals(List.of(), DebugMemoryView.rows("0x1", new byte[0], 0));
        assertEquals("0x10", DebugMemoryView.literalAddress(" 0x10 "));
        assertNull(DebugMemoryView.literalAddress("16"), "a number without 0x is an expression");
        assertNull(DebugMemoryView.literalAddress("buffer"));
    }

    @Test
    void editBytesReadsHexOrQuotedText() {
        assertArrayEquals(new byte[] {0x48, 0x65, 0x6c}, DebugMemoryView.parseBytes("48 65 6c"));
        assertArrayEquals(new byte[] {0x48, 0x65, 0x6c}, DebugMemoryView.parseBytes(" 48656C "));
        assertArrayEquals(new byte[] {(byte) 0xff, 0x01}, DebugMemoryView.parseBytes("0xff, 0x01"));
        assertArrayEquals("Hé".getBytes(java.nio.charset.StandardCharsets.UTF_8), DebugMemoryView.parseBytes("\"Hé\""));
        assertNull(DebugMemoryView.parseBytes(""));
        assertNull(DebugMemoryView.parseBytes("\"\""), "no bytes");
        assertNull(DebugMemoryView.parseBytes("486"), "half a byte");
        assertNull(DebugMemoryView.parseBytes("zz"));
        assertNull(DebugMemoryView.parseBytes("0x"));
        assertNull(DebugMemoryView.parseBytes("0x123"), "0x is one byte");
    }

    @Test
    void editBytesWritesFromTheFirstChangedByteToTheLast() {
        byte[] data = new byte[32];
        data[17] = 7;
        DebugMemoryView.Edit edit = DebugMemoryView.edit(data, 16, new byte[] {0, 9, 7, 5});
        assertEquals(17, edit.at());
        assertArrayEquals(new byte[] {9, 7, 5}, edit.bytes(), "the unchanged byte between them too");
        assertNull(DebugMemoryView.edit(data, 16, new byte[] {0, 7}), "nothing changed");
        assertEquals("48 65", DebugMemoryView.rowHex(new byte[] {0x48, 0x65}, 0));
        assertEquals("", DebugMemoryView.rowHex(new byte[4], 1), "past the bytes read");
        DapModels.MemoryBlock block = new DapModels.MemoryBlock("0x2000", data, 0);
        assertEquals("0x2010", DebugMemoryView.addressOf(block, 16));
        assertEquals("buf+16", DebugMemoryView.addressOf(new DapModels.MemoryBlock("buf", data, 0), 16));
    }
}
