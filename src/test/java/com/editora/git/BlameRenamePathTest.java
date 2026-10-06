package com.editora.git;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code git blame} follows whole-file renames and names the file's path <em>in each commit</em>; a blame click
 * must ask for {@code <hash>:<that path>}, not the current one (which does not exist in an older commit).
 */
class BlameRenamePathTest {

    private static final String C1 = "0f620ee98474aeb16bc512409cf52c72d64acab1";
    private static final String C3 = "0cf346f2f5d44977d35879d00255d113fb2686ea";

    /** Real {@code git blame --line-porcelain} of a file created as src/a/Foo.txt, moved to src/b, then edited. */
    private static final String PORCELAIN = C1 + " 1 1 1\n"
            + "author T\nauthor-time 1700000000\nsummary c1\nboundary\nfilename src/a/Foo.txt\n\tone\n"
            + C3 + " 2 2 1\n"
            + "author T\nauthor-time 1700000200\nsummary c3\n"
            + "previous 7cd3dc4d201031d05d6a43671a4d93add9b2d03d src/b/Foo.txt\nfilename src/b/Foo.txt\n\tTWO\n"
            + C1 + " 3 3 1\n"
            + "author T\nauthor-time 1700000000\nsummary c1\nboundary\nfilename src/a/Foo.txt\n\tthree\n";

    @Test
    void aLineOlderThanTheRenameCarriesTheOldPath() {
        List<BlameParser.BlameLine> lines = BlameParser.parse(PORCELAIN);

        assertEquals(3, lines.size());
        assertEquals("src/a/Foo.txt", lines.get(0).path(), "the path in the commit that wrote the line");
        assertNull(lines.get(0).previousPath(), "the commit added the file: no parent side");
        assertEquals("src/b/Foo.txt", lines.get(1).path());
        assertEquals("src/b/Foo.txt", lines.get(1).previousPath());
        assertEquals("src/a/Foo.txt", lines.get(2).path(), "state does not leak from the previous block");
        assertNull(lines.get(2).previousPath());
    }

    @Test
    void aQuotedFilenameIsDecoded() {
        String porcelain = C1 + " 1 1 1\nauthor T\nsummary s\nfilename \"caf\\303\\251.txt\"\n\tx\n";

        assertEquals("café.txt", BlameParser.parse(porcelain).get(0).path());
    }
}
