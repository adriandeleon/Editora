package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure halves of the Bash server workarounds (Bash review B2, B7, C4). */
class BashShimsTest {

    private static Range range(int l1, int c1, int l2, int c2) {
        return new Range(new Position(l1, c1), new Position(l2, c2));
    }

    private static Diagnostic shellcheck(Range r) {
        Diagnostic d = new Diagnostic(r, "Double quote to prevent globbing and word splitting.");
        d.setSource("shellcheck");
        d.setCode("SC2086");
        return d;
    }

    @Test
    void aCodePointColumnBecomesUtf16() {
        assertEquals(12, BashShims.utf16Column("echo \"😀😀\" $1 end", 10), "two astral characters before it");
        assertEquals(5, BashShims.utf16Column("echo \"é\"", 5), "a BMP letter counts once either way");
        assertEquals(0, BashShims.utf16Column("😀", 0));
        assertEquals(2, BashShims.utf16Column("😀", 1));
        assertEquals(4, BashShims.utf16Column("😀", 3), "past the end, one unit per column");
    }

    /** {@code echo "😀😀" $1 end}: ShellCheck says 10-12 for {@code $1}, which is 12-14 in UTF-16 (B2). */
    @Test
    void shellCheckDiagnosticsOnALineWithEmojiMoveToTheRightCharacters() {
        String text = "#!/bin/bash\necho \"😀😀\" $1 end\necho \"ok\" $1 end\n";
        Diagnostic onEmoji = shellcheck(range(1, 10, 1, 12));
        Diagnostic plain = shellcheck(range(2, 10, 2, 12));
        Diagnostic parser = new Diagnostic(range(1, 10, 1, 12), "tree-sitter is already UTF-16");
        parser.setSource("bash-language-server");
        BashShims.diagnosticsToUtf16(new ArrayList<>(List.of(onEmoji, plain, parser)), text);
        assertEquals(range(1, 12, 1, 14), onEmoji.getRange());
        assertEquals(range(2, 10, 2, 12), plain.getRange(), "an ASCII line is unchanged");
        assertEquals(range(1, 10, 1, 12), parser.getRange(), "only ShellCheck's columns are code points");
    }

    @Test
    void textWithoutAstralCharactersIsLeftAlone() {
        Diagnostic d = shellcheck(range(0, 10, 0, 12));
        BashShims.diagnosticsToUtf16(List.of(d), "echo \"ok\" $1 end\n");
        assertEquals(range(0, 10, 0, 12), d.getRange());
        BashShims.diagnosticsToUtf16(List.of(d), null); // a document that is not open: nothing to measure against
        assertEquals(range(0, 10, 0, 12), d.getRange());
    }

    /** "Apply fix for SC2086" turned the line into {@code echo "😀😀"" "$1 end}; its quotes now go round {@code $1}. */
    @Test
    void aShellCheckQuickFixInsertsItsQuotesWhereTheyBelong() {
        String uri = "file:///p/utf.sh";
        String text = "echo \"😀😀\" $1 end\n";
        CodeAction fix = new CodeAction("Apply fix for SC2086");
        fix.setDiagnostics(List.of(shellcheck(range(0, 10, 0, 12))));
        fix.setEdit(new WorkspaceEdit(Map.of(
                uri, List.of(new TextEdit(range(0, 12, 0, 12), "\""), new TextEdit(range(0, 10, 0, 10), "\"")))));
        CodeAction other = new CodeAction("someone else's");
        Diagnostic foreign = new Diagnostic(range(0, 10, 0, 12), "x");
        foreign.setSource("other");
        other.setDiagnostics(List.of(foreign));
        other.setEdit(new WorkspaceEdit(Map.of(uri, List.of(new TextEdit(range(0, 10, 0, 10), "x")))));
        List<Either<Command, CodeAction>> actions = List.of(Either.forRight(fix), Either.forRight(other));

        BashShims.codeActionsToUtf16(actions, uri, text);

        List<TextEdit> edits = fix.getEdit().getChanges().get(uri);
        assertEquals(range(0, 14, 0, 14), edits.get(0).getRange());
        assertEquals(range(0, 12, 0, 12), edits.get(1).getRange());
        assertEquals(range(0, 12, 0, 14), fix.getDiagnostics().get(0).getRange());
        assertEquals(
                range(0, 10, 0, 10),
                other.getEdit().getChanges().get(uri).get(0).getRange(),
                "not ShellCheck's");
        StringBuilder applied = new StringBuilder(text);
        applied.insert(14, '"').insert(12, '"');
        assertEquals("echo \"😀😀\" \"$1\" end\n", applied.toString());
    }

    /**
     * From a call, the server answers {@code includeDeclaration: true} with the call and the {@code log_info() {}}
     * name; its definition covers the whole function. The declaration goes, the call and a recursive call stay.
     */
    @Test
    void theDeclarationIsTheReferenceThatStartsWhereTheDefinitionDoes() {
        Location call = new Location("file:///p/main.sh", range(7, 2, 7, 10));
        Location name = new Location("file:///p/lib/util.sh", range(4, 0, 4, 8));
        Location recursive = new Location("file:///p/lib/util.sh", range(5, 2, 5, 10));
        Location definition = new Location("file:///p/lib/util.sh", range(4, 0, 6, 1));
        assertEquals(
                List.of(call, recursive),
                BashShims.withoutDeclarations(List.of(call, name, recursive), List.of(definition)));
        assertEquals(List.of(call, name), BashShims.withoutDeclarations(List.of(call, name), List.of()));
        assertEquals(List.of(), BashShims.withoutDeclarations(null, List.of(definition)));
    }

    @Test
    void aDefinitionAnswerGivesItsLocationsEitherWay() {
        Location plain = new Location("file:///a.sh", range(1, 0, 3, 1));
        assertEquals(List.of(plain), BashShims.locations(Either.forLeft(List.of(plain))));
        LocationLink link = new LocationLink("file:///a.sh", range(1, 0, 3, 1), range(1, 0, 1, 4));
        assertEquals(
                List.of(new Location("file:///a.sh", range(1, 0, 1, 4))),
                BashShims.locations(Either.forRight(List.of(link))));
        assertEquals(List.of(), BashShims.locations(null));
    }

    @Test
    void shellScriptsAndIncludeFilesAreReadAgainOthersAreNot() {
        assertTrue(BashShims.reanalysable(Path.of("/p/lib/util.sh")));
        assertTrue(BashShims.reanalysable(Path.of("/p/lib/common.bash")));
        assertTrue(BashShims.reanalysable(Path.of("/p/lib/defs.inc")));
        assertTrue(BashShims.reanalysable(Path.of("/p/test/a.bats")));
        assertTrue(BashShims.reanalysable(Path.of("/p/x.ksh")));
        assertTrue(BashShims.reanalysable(Path.of("/home/u/.bashrc")));
        assertFalse(BashShims.reanalysable(Path.of("/p/README.md")));
        assertFalse(BashShims.reanalysable(Path.of("/p/tool")), "no extension: telling would need a read");
        assertFalse(BashShims.reanalysable(Path.of("/")));
    }
}
