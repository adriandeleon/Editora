package com.editora.logviewer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogFileNamesTest {

    @Test
    void rotatedAndConventionalNamesAreLogs() {
        for (String name : new String[] {
            "app.log",
            "error.LOG",
            "app.log.1",
            "app.log.2026-10-06",
            "app.log.2026-10-06.3",
            "app.log.old",
            "access_log",
            "error_log",
            "access_log.1",
            "syslog",
            "syslog.1",
            "catalina.out",
            "nohup.out",
            "dmesg"
        }) {
            assertTrue(LogFileNames.isLog(name), name);
        }
    }

    @Test
    void otherNamesAreNot() {
        for (String name : new String[] {
            "notes.txt",
            "catalog.json",
            "login.js",
            "app.log.gz",
            "blog.md",
            "changelog",
            "server.out",
            "Makefile",
            "messages",
            ""
        }) {
            assertFalse(LogFileNames.isLog(name), name);
        }
        assertFalse(LogFileNames.isLog(null));
    }

    @Test
    void ambiguousNamesAreWorthSniffing() {
        assertTrue(LogFileNames.mayBeLog("server.out"));
        assertTrue(LogFileNames.mayBeLog("worker.err"));
        assertTrue(LogFileNames.mayBeLog("messages"));
        assertTrue(LogFileNames.mayBeLog(".xsession-errors"), "a dotfile has no extension");
        assertFalse(LogFileNames.mayBeLog("notes.txt"), "a text file with timestamps in it is still notes");
        assertFalse(LogFileNames.mayBeLog("Main.java"));
    }
}
