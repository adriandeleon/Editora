package com.editora.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissingProgramTest {

    @Test
    void recognizesTheJdksNotFoundWording() {
        assertTrue(
                MissingProgram.notFound(
                        "Cannot run program \"pnpm\" (in directory \"/p\"): Exec failed, error: 2 (No such file or directory)"));
        assertTrue(MissingProgram.notFound("Cannot run program \"npm\": error=2, No such file or directory"));
        assertTrue(MissingProgram.notFound("CreateProcess error=2, The system cannot find the file specified"));
        assertFalse(MissingProgram.notFound("Cannot run program \"x\": error=13, Permission denied"));
        assertFalse(MissingProgram.notFound(null));
    }

    @Test
    void namesWhatToDoPerProgram() {
        assertEquals(
                "status.run.nodeMissing",
                MissingProgram.forProgram("/usr/bin/node", "").key());
        assertEquals(
                "status.run.npmMissing",
                MissingProgram.forProgram("C:\\n\\npm.cmd", null).key());
        MissingProgram.Message pnpm = MissingProgram.forProgram("pnpm", "pnpm-lock.yaml");
        assertEquals("status.run.managerMissingFrom", pnpm.key());
        assertArrayEquals(new Object[] {"pnpm", "pnpm-lock.yaml"}, pnpm.args());
        assertEquals(
                "status.run.managerMissing",
                MissingProgram.forProgram("yarn", "").key());
        assertEquals(
                "status.run.bunMissing",
                MissingProgram.forProgram("bun", "bun.lock").key());
        assertNull(MissingProgram.forProgram("java", ""));
    }
}
