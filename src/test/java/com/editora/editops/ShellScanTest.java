package com.editora.editops;

import java.util.List;

import com.editora.editops.ShellScan.Block;
import com.editora.editops.ShellScan.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shell scanner behind shell folding and indentation: blocks, keywords only where reserved, here-docs. */
class ShellScanTest {

    /** "KIND header..close" for every closed block, in open order. */
    private static List<String> closed(String text) {
        return ShellScan.scan(text).blocks().stream()
                .filter(b -> b.closeLine() >= 0)
                .map(b -> b.kind() + " " + b.headerLine() + ".." + b.closeLine())
                .toList();
    }

    @Test
    void keywordBlocksPairUp() {
        String sh = """
                if [ -n "$x" ]; then
                    for f in *.txt; do
                        echo "$f"
                    done
                elif true; then
                    :
                else
                    while read -r l; do :; done < in.txt
                fi
                """;
        assertEquals(List.of("IF 0..8", "LOOP 1..3", "LOOP 7..7"), closed(sh));
    }

    @Test
    void functionsFoldFromTheirNameLineInEveryForm() {
        String sh = """
                plain() {
                    :
                }
                function kw {
                    :
                }
                spaced ()
                {
                    :
                }
                lib::helper() (
                    :
                )
                """;
        assertEquals(List.of("BRACE 0..2", "BRACE 3..5", "BRACE 6..9", "PAREN 10..12"), closed(sh));
    }

    @Test
    void caseItemsAreBlocksInsideTheCase() {
        String sh = """
                case "$1" in
                    start|run)
                        go
                        ;;
                    (stop) halt ;;
                    *)
                        usage
                esac
                """;
        assertEquals(List.of("CASE 0..7", "CASE_ITEM 1..3", "CASE_ITEM 4..4", "CASE_ITEM 5..6"), closed(sh));
    }

    @Test
    void wordsThatOnlyLookLikeKeywordsOpenNothing() {
        String sh = """
                echo what to do then fi
                # if this were code, then …
                printf '%s\\n' "if x; then" 'do'
                done_count=0
                x=${#arr[@]} # a $# is not a comment: if
                echo }
                """;
        ShellScan.Result r = ShellScan.scan(sh);
        assertTrue(r.blocks().isEmpty(), r.blocks().toString());
        assertTrue(r.openAtEnd().isEmpty());
    }

    @Test
    void stringsAndSubstitutionsSpanLines() {
        String sh = """
                usage="Usage:
                  deploy()  { not a function }
                "
                out=$(
                    if a; then b; fi
                )
                """;
        ShellScan.Result r = ShellScan.scan(sh);
        assertEquals(ShellScan.IN_STRING, r.startState(1));
        assertEquals(ShellScan.IN_STRING, r.startState(2));
        assertEquals(ShellScan.CODE, r.startState(3));
        assertEquals(List.of("PAREN 3..5", "IF 4..4"), closed(sh));
    }

    @Test
    void hereDocBodiesAreDataUpToTheirDelimiter() {
        String sh = """
                cat <<EOF >out
                if x; then
                {
                EOF
                cat <<-'END' | sort
                \tdone
                \tEND
                echo after
                """;
        ShellScan.Result r = ShellScan.scan(sh);
        assertEquals(List.of("HEREDOC 0..3", "HEREDOC 4..6"), closed(sh));
        for (int line : new int[] {1, 2, 3, 5, 6}) {
            assertEquals(ShellScan.IN_HEREDOC, r.startState(line), "line " + line);
        }
        assertEquals("END", r.heredocAt(5).delimiter());
        assertTrue(r.heredocAt(5).dash());
        assertEquals(ShellScan.CODE, r.startState(7));
        assertTrue(r.openAtEnd().isEmpty());
    }

    @Test
    void anIndentedDelimiterDoesNotEndAPlainHereDoc() {
        ShellScan.Result r = ShellScan.scan("f() {\n    cat <<EOF\n    hi\n    EOF\n}\necho after\n");
        Block h = r.blocks().stream()
                .filter(b -> b.kind() == Kind.HEREDOC)
                .findFirst()
                .orElseThrow();
        assertEquals(-1, h.closeLine(), "bash reads to the end of the file");
        assertEquals(ShellScan.IN_HEREDOC, r.startState(5));
    }

    @Test
    void arithmeticAndHereStringsAreNotHereDocs() {
        String sh = "x=$((1 << 2))\n(( y <<= 1 ))\ngrep a <<< \"$s\"\necho done\n";
        ShellScan.Result r = ShellScan.scan(sh);
        assertTrue(r.blocks().isEmpty(), r.blocks().toString());
        assertEquals(ShellScan.CODE, r.startState(3));
    }

    @Test
    void lineEventsTellWhatABodyOrACloserDid() {
        ShellScan.Result r = ShellScan.scan("case $x in\n  a)\n    b\n    ;;\n");
        assertEquals(ShellScan.BODY, r.lastEvent(0));
        assertEquals(ShellScan.BODY, r.lastEvent(1));
        assertEquals(Kind.CASE_ITEM, r.lastBlock(1).kind());
        assertEquals(ShellScan.NONE, r.lastEvent(2));
        assertEquals(ShellScan.CLOSE, r.lastEvent(3));
        assertEquals(1, r.lastBlock(3).headerLine());
        assertNull(r.innermostOpen(Kind.CASE_ITEM));
        assertEquals(Kind.CASE, r.openAtEnd().get(0).kind());
        assertFalse(r.endsInString());
    }
}
