package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.config.Breakpoint;
import com.editora.config.BreakpointExport;
import com.editora.config.MemberBreakpoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The pure parts of {@link BreakpointGroups} and of the groups in {@link BreakpointsPicker}. */
class BreakpointGroupsTest {

    private static BreakpointsPicker.Entry entry(String file, int line, Breakpoint bp) {
        return new BreakpointsPicker.Entry(Path.of(file), file, line, bp.withLine(line), null);
    }

    @Test
    void groupsAreListedOnceInReadingOrder() {
        List<BreakpointsPicker.Entry> all = List.of(
                entry("/p/A.java", 1, Breakpoint.plain(0, "a").withGroup("beta")),
                entry("/p/A.java", 2, Breakpoint.plain(0, "b")),
                entry("/p/B.java", 3, Breakpoint.plain(0, "c").withGroup("Alpha")),
                entry("/p/B.java", 4, Breakpoint.plain(0, "d").withGroup("beta")),
                BreakpointsPicker.Entry.of(MemberBreakpoint.method("p.A", "run").withGroup("Gamma")));
        assertEquals(List.of("Alpha", "beta", "Gamma"), BreakpointsPicker.groups(all));
        assertEquals(
                List.of(1, 4),
                BreakpointsPicker.inGroup(all, "beta").stream()
                        .map(BreakpointsPicker.Entry::line)
                        .toList());
        assertEquals("Gamma", BreakpointsPicker.groupOf(all.getLast()));
    }

    /** An absolute folder on any system (a bare "/work" has no drive on Windows). */
    private static final Path BASE =
            Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();

    @Test
    void aFileIsWrittenRelativeToTheProjectAndReadBack() {
        Path root = BASE.resolve("app");
        Path elsewhere = BASE.resolve("other").resolve("X.java");
        Path main = root.resolve("src").resolve("Main.java");
        assertEquals("src/Main.java", BreakpointGroups.fileText(main, main.toString(), root));
        assertEquals(elsewhere.toString(), BreakpointGroups.fileText(elsewhere, elsewhere.toString(), root));
        assertEquals(elsewhere.toString(), BreakpointGroups.fileText(elsewhere, elsewhere.toString(), null));
        assertEquals(main, BreakpointGroups.fileOf("src/Main.java", root));
        assertEquals(elsewhere, BreakpointGroups.fileOf(elsewhere.toString(), root));
        assertNull(BreakpointGroups.fileOf("src/Main.java", null), "relative, with no project to resolve it");
        assertNull(BreakpointGroups.fileOf("  ", root));
    }

    @Test
    void aGroupNamesItsFile() {
        assertEquals("breakpoints.json", BreakpointGroups.fileName(""));
        assertEquals("breakpoints-Checkout-flow.json", BreakpointGroups.fileName("Checkout flow"));
        assertEquals("breakpoints.json", BreakpointGroups.fileName("///"));
    }

    @Test
    void anExportKeepsWholeFileLinesAndLeavesMembersToTheirList() {
        Path root = BASE.resolve("app");
        BreakpointsPicker.Entry line = entry(
                root.resolve("a.rb").toString(), 7, Breakpoint.plain(0, "x = 1").withGroup("G"));
        BreakpointsPicker.Entry member = BreakpointsPicker.Entry.of(MemberBreakpoint.method("p.A", "run"));
        List<BreakpointExport.FileBreakpoint> out = BreakpointGroups.exported(List.of(line, member), root);
        assertEquals(1, out.size());
        assertEquals("a.rb", out.getFirst().file());
        assertEquals(7, out.getFirst().breakpoint().line());
        assertEquals("G", out.getFirst().breakpoint().group());
    }
}
