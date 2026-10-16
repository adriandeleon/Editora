package com.editora.run;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Typed program arguments and environment variables, read the way a shell user writes them. */
class ProgramArgsParseTest {

    @Test
    void anApostropheNoLongerSwallowsTheRestOfTheLine() {
        // The old tokenizer turned this into three arguments, the last "OBrien tail\ x $HOME".
        ProgramArgs.UnclosedQuoteException e =
                assertThrows(ProgramArgs.UnclosedQuoteException.class, () -> ProgramArgs.parse("plain O'Brien tail"));
        assertEquals('\'', e.quote());
        assertEquals(
                List.of("plain", "O'Brien", "tail x", "$HOME"), ProgramArgs.parse("plain O\\'Brien tail\\ x $HOME"));
        assertEquals(List.of("it's done"), ProgramArgs.parse("\"it's done\""));
    }

    @Test
    void backslashEscapesFollowShellRules() {
        assertEquals(List.of("a\"b", "c\\d", "$x"), ProgramArgs.parse("\"a\\\"b\" \"c\\\\d\" \"\\$x\""));
        assertEquals(List.of("keep\\n"), ProgramArgs.parse("\"keep\\n\""), "other backslashes in \"…\" are literal");
        assertEquals(List.of("raw\\\"x"), ProgramArgs.parse("'raw\\\"x'"), "nothing is special in '…'");
        assertEquals(
                List.of("C:\\tools\\x.exe"),
                ProgramArgs.parse("C:\\tools\\x.exe"),
                "a Windows path keeps its separators");
        assertEquals(List.of("a", "b c", "d e", "f", ""), ProgramArgs.parse("a \"b c\" 'd e'  f \"\""));
        assertThrows(ProgramArgs.UnclosedQuoteException.class, () -> ProgramArgs.parse("\"never closed"));
        assertEquals(List.of(), ProgramArgs.parse("   "));
        assertEquals(List.of(), ProgramArgs.parse(null));
    }

    @Test
    void variablesExpandOutsideSingleQuotesOnlyWhenAskedTo() {
        Map<String, String> vars = Map.of("HOME", "/home/u", "N", "1");
        assertEquals(
                List.of("/home/u/x", "/home/u", "$HOME", "1x", "$", "$5"),
                ProgramArgs.parse("$HOME/x \"${HOME}\" '$HOME' ${N}x $ $5", vars::get));
        assertEquals(List.of("ab"), ProgramArgs.parse("a${UNSET}b", vars::get), "an unknown name expands to nothing");
    }

    @Test
    void environmentValuesExpandFromTheInheritedEnvironmentAndEarlierEntries() {
        Map<String, String> inherited = Map.of("PATH", "/usr/bin:/bin", "HOME", "/home/u");
        Map<String, String> env =
                EnvVars.resolve("PATH=/opt/x/bin:$PATH FOO='a b' BAR=\"q\\\"x\" BAZ=$FOO LIT='$HOME'", inherited::get);
        assertEquals("/opt/x/bin:/usr/bin:/bin", env.get("PATH"), "prepends instead of setting the literal $PATH");
        assertEquals("a b", env.get("FOO"));
        assertEquals("q\"x", env.get("BAR"), "an escaped quote no longer swallows the next pair");
        assertEquals("a b", env.get("BAZ"), "a later value sees an earlier one");
        assertEquals("$HOME", env.get("LIT"));
        assertEquals(5, env.size());
        assertThrows(ProgramArgs.UnclosedQuoteException.class, () -> EnvVars.resolve("A='x", inherited::get));
        assertEquals(Map.of("OK", "1"), EnvVars.resolve("novalue =orphan OK=1", inherited::get));
    }
}
