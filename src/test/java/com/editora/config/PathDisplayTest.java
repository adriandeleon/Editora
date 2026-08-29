package com.editora.config;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathDisplayTest {

    @Test
    void collapsesHomeOnlyOnAWholeSegment() {
        assertEquals(
                "~/.cargo/bin/rust-analyzer",
                PathDisplay.collapseHome("/home/adl/.cargo/bin/rust-analyzer", "/home/adl"));
        assertEquals("~", PathDisplay.collapseHome("/home/adl", "/home/adl"));
        // A sibling directory that merely shares the prefix is left alone — it is a different folder.
        assertEquals("/home/adl2/bin/gopls", PathDisplay.collapseHome("/home/adl2/bin/gopls", "/home/adl"));
    }

    @Test
    void toleratesAHomePropertyWrittenWithATrailingSeparator() {
        assertEquals("~/bin/gopls", PathDisplay.collapseHome("/home/adl/bin/gopls", "/home/adl/"));
    }

    @Test
    void collapsesWindowsPathsToo() {
        assertEquals(
                "~\\AppData\\node.exe",
                PathDisplay.collapseHome("C:\\Users\\adl\\AppData\\node.exe", "C:\\Users\\adl"));
    }

    @Test
    void anAbsentOrRootHomeCollapsesNothing() {
        assertEquals("/usr/bin/git", PathDisplay.collapseHome("/usr/bin/git", ""));
        // Otherwise every absolute path on the machine would render as "~".
        assertEquals("/usr/bin/git", PathDisplay.collapseHome("/usr/bin/git", "/"));
    }

    @Test
    void nullsAndBlanksComeBackEmptyRatherThanAsTheWordNull() {
        assertEquals("", PathDisplay.collapseHome(null, "/home/adl"));
        assertEquals("", PathDisplay.of(null));
    }

    @Test
    void ofRendersAPathThroughTheSameRules() {
        String home = System.getProperty("user.home", "");
        assertEquals(PathDisplay.collapseHome(home + "/x.txt"), PathDisplay.of(Path.of(home, "x.txt")));
    }
}
