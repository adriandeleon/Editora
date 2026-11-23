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

    /** Every block (open ones too), as "KIND header..close", in open order. */
    private static List<String> all(String text) {
        return ShellScan.scan(text).blocks().stream()
                .map(b -> b.kind() + " " + b.headerLine() + ".." + b.closeLine())
                .toList();
    }

    @Test
    void testBracketsHoldOperatorsAndParensThatAreNotSubshells() {
        String sh = "if [[ $a == x && ( $b < c ) || -n \"$d\" ]]; then\n  :\nfi\n[[ -f x ]] && {\n  :\n}\n";
        assertEquals(List.of("IF 0..2", "BRACE 3..5"), all(sh));
        assertEquals(List.of(), all("echo [[ x ]] then\n[[ a ]]x\n"), "only a [[ at a command's start is a test");
        assertEquals(List.of(), all("[[ a\\ b ]]\n"));
    }

    @Test
    void aContinuedLineKeepsItsCommandGoing() {
        assertEquals(List.of("IF 0..3"), all("if true \\\n  && false; then\n  :\nfi\n"));
        assertEquals(
                List.of(), all("echo a\\ b \\if then\nx#y if\n"), "an escaped or mid-word character is not special");
        assertEquals(List.of(), all("echo \\"), "a trailing backslash");
    }

    @Test
    void operatorsStartANewCommand() {
        assertEquals(
                List.of("IF 0..0", "LOOP 1..1", "BRACE 2..2"),
                all("true && if a; then b; fi\nfalse || while c; do d; done\ne |& { f; }\n"));
        assertEquals(List.of("IF 0..0"), all("x &>log; if a; then b; fi &\n"));
        assertEquals(List.of(), all("echo >&2 if\necho 2>&- then\n"), "a word after >&2 is an argument");
        assertEquals(List.of(), all("cat > if\ncat >| fi\n"), "a redirection's target is never a keyword");
    }

    @Test
    void processSubstitutionsAndArraysAreParenBlocks() {
        assertEquals(List.of("PAREN 0..2", "IF 1..1"), all("diff <(\n  if a; then b; fi\n) x\n"));
        assertEquals(List.of("PAREN 0..0"), all("tee >(wc -l) x\n"));
        assertEquals(List.of("PAREN 0..3"), all("arr=(\n  if\n  { then\n)\nfi\n"), "words of an array are data");
    }

    @Test
    void quotedFormsHideKeywords() {
        String sh = "x=$'it\\'s if'\ny=`echo \\` fi`\nz=$\"then\"\nw=${a:-\"}\"}${b:-${c}}\nv=\"${q:-'}\"\n"
                + "u=${p:-'}'}\nt=${r//\\}/x}${s:-`if`}${t:-$(x)}\nif a; then b; fi\n";
        assertEquals(List.of("PAREN 6..6", "IF 7..7"), all(sh));
        ShellScan.Result r = ShellScan.scan(sh);
        assertFalse(r.endsInString());
        assertTrue(ShellScan.scan("echo \"open\n").endsInString());
        assertTrue(ShellScan.scan("x=${a\n").endsInString());
        assertEquals(ShellScan.IN_STRING, ShellScan.scan("echo `a\nb`\n").startState(1));
        assertEquals(ShellScan.IN_STRING, ShellScan.scan("echo $'a\nb'\n").startState(1));
        assertEquals(ShellScan.IN_STRING, ShellScan.scan("echo \"$'a\nb\"\n").startState(1), "no $'…' inside \"…\"");
        assertEquals(
                ShellScan.IN_STRING,
                ShellScan.scan("echo \"`a\nb`\" \"\\\"\n\"\n").startState(1));
    }

    @Test
    void arithmeticNestsAndKeepsKeywordsOut() {
        String sh = "x=$((1 + (2 * 3)))\ny=$[1 + [2]]\n(( (z) > 1 ))\nw=$(( $(echo 1) + ${#a} ))\nif a; then b; fi\n";
        assertEquals(List.of("PAREN 3..3", "IF 4..4"), all(sh));
        assertEquals(List.of("LOOP 0..2"), all("for ((i = 0; i < 3; i++)); do\n  :\ndone\n"));
    }

    @Test
    void caseItemTerminatorsAndPatternForms() {
        String sh = "case $x in\n  a) one ;&\n  (b|c) two ;;&\n  \"q\"|'r'|$v) three ;;\n  ;;\n  *) four\nesac\n";
        assertEquals(
                List.of("CASE 0..6", "CASE_ITEM 1..1", "CASE_ITEM 2..2", "CASE_ITEM 3..3", "CASE_ITEM 5..5"), all(sh));
        assertEquals(List.of("CASE 0..1"), all("case x in\nesac\n"));
        assertEquals(List.of("CASE 0..0"), all("case x in esac"));
        assertEquals(List.of("CASE 0..-1"), all("case x foo\n"), "only `in` ends a case header");
    }

    @Test
    void functionKeywordForms() {
        assertEquals(
                List.of("BRACE 0..2", "BRACE 3..5", "PAREN 6..8"),
                all("function a () {\n  :\n}\nfunction b\n{\n}\nfunction c (\n  :\n)\n"));
        assertEquals(List.of("BRACE 0..0"), all("function d() { :; }"));
        assertEquals(List.of("PAREN 0..0"), all("function e ( x )"), "( not followed by ) is the body");
    }

    @Test
    void timeCoprocAndBangPrefixACommand() {
        assertEquals(
                List.of("IF 0..0", "LOOP 1..1", "IF 2..2"),
                all("time if a; then b; fi\ncoproc while c; do d; done\n! if e; then f; fi\n"));
        assertEquals(List.of(), all("[[ ! -f x ]]\necho !x if\n"));
    }

    @Test
    void bodiesAndClosersWithoutTheirOpener() {
        ShellScan.Result r = ShellScan.scan("  then\n  x\nelse\nelif y\ndo\nfi\ndone\nesac\n}\n)\n");
        assertTrue(r.blocks().isEmpty(), r.blocks().toString());
        assertEquals(ShellScan.BODY, r.lastEvent(0), "a fragment's then still starts a body");
        assertEquals(Kind.IF, r.lastBlock(0).kind());
        assertEquals(ShellScan.BODY, r.lastEvent(4));
        assertEquals(ShellScan.NONE, r.lastEvent(3), "an elif without an if opens nothing");
        ShellScan.Result elif = ShellScan.scan("if a; then\nelif b; then\nfi\n");
        assertEquals(ShellScan.BODY, elif.lastEvent(1));
    }

    @Test
    void forAndSelectHeadersReadWordsUntilDo() {
        assertEquals(List.of("LOOP 0..2"), all("select x in if then fi; do\n  break\ndone\n"));
        assertEquals(List.of("LOOP 0..0"), all("for x in a b do; do :; done"));
    }

    @Test
    void assignmentsLeaveTheCommandPositionOpen() {
        assertEquals(List.of("IF 0..0"), all("A=1 B+=2 c[1]=3 if x; then y; fi"));
        assertEquals(List.of(), all("d[=1 if x\n1x=2 if\n"), "not assignments: the next word is an argument");
    }

    @Test
    void hereDocDelimiterForms() {
        String sh = "cat <<\"A\" <<\\B\nbody a\nA\nbody b\nB\ncat << E'N'D\nx\nEND\ncat <<- \tT ;\n\tT\n";
        ShellScan.Result r = ShellScan.scan(sh);
        assertEquals(List.of("HEREDOC 0..2", "HEREDOC 0..4", "HEREDOC 5..7", "HEREDOC 8..9"), all(sh));
        assertEquals("A", r.blocks().get(0).delimiter());
        assertEquals("B", r.blocks().get(1).delimiter());
        assertEquals("END", r.blocks().get(2).delimiter());
        assertEquals(ShellScan.IN_HEREDOC, r.startState(3));
        assertTrue(r.blocks().get(3).dash());
        assertEquals("HEREDOC[0..2]", r.blocks().get(0).toString());
    }

    @Test
    void hereDocEdgeCases() {
        assertEquals(List.of("HEREDOC 0..1"), all("cat <<EOF\r\nEOF\r\n"), "a CRLF delimiter line");
        assertEquals(List.of("HEREDOC 0..-1"), all("cat <<EOF\nnever"), "the text ends first");
        assertEquals(List.of(), all("cat << ;\n"), "no delimiter, no body");
        assertNull(ShellScan.scan("echo\n").heredocAt(1));
    }

    @Test
    void resultAccessors() {
        ShellScan.Result r = ShellScan.scan("a\n\t  b\n");
        assertEquals(3, r.lineCount());
        assertEquals(2, r.lineStart(1));
        assertEquals("\t  ", r.indentOf(1));
        assertEquals("", r.indentOf(2));
        assertEquals(1, ShellScan.scan(null).lineCount());
        ShellScan.Result open = ShellScan.scan("if a; then\n  while b; do\n");
        assertEquals(Kind.LOOP, open.innermostOpen(Kind.LOOP).kind());
        assertEquals(0, open.innermostOpen(Kind.IF).headerLine());
        assertNull(open.innermostOpen(Kind.CASE));
        assertTrue(ShellScan.isMeta('|'));
        assertFalse(ShellScan.isMeta('x'));
    }
}
