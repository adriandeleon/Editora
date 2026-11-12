package com.editora.terminal;

import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_16LE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each platform's command, and that a program's text stays data on every one. */
class DesktopNotifierTest {

    private static final String HOSTILE = "x\" & do shell script \"rm -rf ~\" & \"";

    /** Where the fake finder says a program is, spelled as this platform spells a path. */
    private static String bin(String name) {
        return Path.of("/usr/bin", name).toString();
    }

    private static Function<String, Path> installed(String... names) {
        Set<String> present = Set.of(names);
        return name -> present.contains(name) ? Path.of("/usr/bin", name) : null;
    }

    @Test
    void linuxPrefersNotifySendWithTheTextAsItsOwnArguments() {
        var command = DesktopNotifier.command(
                com.editora.Os.parse("Linux"), "-t", "a <b>", installed("notify-send", "gdbus"));
        assertEquals(List.of(bin("notify-send"), "--app-name=Editora", "--", "-t", "a &lt;b&gt;"), command.argv());
    }

    @Test
    void linuxFallsBackToTheSessionBusWithQuotedStrings() {
        var command = DesktopNotifier.command(com.editora.Os.parse("Linux"), "it's", "back\\slash", installed("gdbus"));
        assertEquals(bin("gdbus"), command.argv().getFirst());
        // The arguments follow "--", or the trailing -1 is taken for an option.
        int separator = command.argv().indexOf("--");
        assertTrue(separator > 0 && separator < command.argv().indexOf("'Editora'"));
        assertEquals("-1", command.argv().getLast());
        assertTrue(command.argv().contains("'it\\'s'"));
        assertTrue(command.argv().contains("'back\\\\slash'"));
    }

    @Test
    void linuxWithNeitherToolPostsNothing() {
        assertNull(DesktopNotifier.command(com.editora.Os.parse("Linux"), "t", "b", installed()));
    }

    @Test
    void macOsReadsTheTextFromArgvInAFixedScript() {
        var argv = DesktopNotifier.command(com.editora.Os.parse("Mac OS X"), HOSTILE, "body", installed())
                .argv();
        assertEquals(HOSTILE, argv.get(argv.size() - 2));
        assertEquals("body", argv.getLast());
        for (String part : argv.subList(0, argv.size() - 2)) assertFalse(part.contains("rm -rf"), part);
    }

    @Test
    void windowsPassesTheTextInTheEnvironmentNotTheScript() {
        var command = DesktopNotifier.command(com.editora.Os.parse("Windows 11"), HOSTILE, "body", installed());
        assertEquals(HOSTILE, command.environment().get(DesktopNotifier.TITLE_VARIABLE));
        String script = new String(Base64.getDecoder().decode(command.argv().getLast()), UTF_16LE);
        assertFalse(script.contains("rm -rf"));
        assertTrue(script.contains("$env:" + DesktopNotifier.TITLE_VARIABLE));
    }

    /** A notifier run that did not succeed is logged, with the program and why, never with the text itself. */
    @Test
    void aFailedRunIsDescribedForTheLog() {
        var ok = new com.editora.process.ProcessRunner.Result(0, "", "");
        assertNull(DesktopNotifier.failure("notify-send", ok));

        var refused = new com.editora.process.ProcessRunner.Result(1, "", "Cannot autolaunch D-Bus\nmore");
        assertEquals(
                "Desktop notification not shown: notify-send exited with 1: Cannot autolaunch D-Bus",
                DesktopNotifier.failure("notify-send", refused));

        var silent = new com.editora.process.ProcessRunner.Result(3, "", "");
        assertEquals(
                "Desktop notification not shown: osascript exited with 3",
                DesktopNotifier.failure("osascript", silent));
    }
}
