package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a server asks the user, and what it is told back: {@code window/showMessageRequest} for every server,
 * and Metals' input box, quick pick, status items and client commands. The answers come from a scripted
 * {@link LspClientUi}; the session must hand the question over without waiting on it and turn each answer (or
 * a dismissal) into the reply the protocol defines.
 */
class LanguageServerSessionPromptsTest {

    private final List<String> statuses = new ArrayList<>();

    private LanguageServerSession session() {
        var spec = new LspServerRegistry.ServerSpec("scala", List.of("metals"), List.of());
        return new LanguageServerSession(
                spec, Path.of("/tmp"), d -> {}, (type, message) -> statuses.add(type + ":" + message));
    }

    /** A UI whose answers a test completes, recording what it was asked. */
    private static final class ScriptedUi implements LspClientUi {
        final List<String> asked = new ArrayList<>();
        final CompletableFuture<String> answer = new CompletableFuture<>();
        Path opened;
        int openedLine = -1;

        @Override
        public CompletableFuture<String> chooseAction(
                String serverId, MessageType type, String message, List<String> actions) {
            asked.add(serverId + "|" + type + "|" + message + "|" + actions);
            return answer;
        }

        @Override
        public CompletableFuture<String> inputBox(String serverId, String prompt, String value, String placeHolder) {
            asked.add("input|" + prompt + "|" + value + "|" + placeHolder);
            return answer;
        }

        @Override
        public CompletableFuture<String> quickPick(String serverId, String placeHolder, List<PickItem> items) {
            asked.add("pick|" + placeHolder + "|"
                    + items.stream().map(PickItem::id).toList());
            return answer;
        }

        @Override
        public void openLocation(String serverId, Path file, int line, int column) {
            opened = file;
            openedLine = line;
        }
    }

    private static ShowMessageRequestParams importBuild() {
        ShowMessageRequestParams p = new ShowMessageRequestParams();
        p.setType(MessageType.Info);
        p.setMessage("New sbt workspace detected, would you like to import the build?");
        p.setActions(List.of(new MessageActionItem("Import build"), new MessageActionItem("Not now")));
        return p;
    }

    @Test
    void aQuestionIsAskedWithoutWaitingAndTheChosenActionIsTheReply() {
        LanguageServerSession s = session();
        ScriptedUi ui = new ScriptedUi();
        s.setClientUi(ui);

        CompletableFuture<MessageActionItem> reply = s.showMessageRequest(importBuild());

        assertFalse(reply.isDone(), "the reader thread is not held while the user decides");
        assertEquals(
                List.of("scala|Info|New sbt workspace detected, would you like to import the build?"
                        + "|[Import build, Not now]"),
                ui.asked);
        ui.answer.complete("Import build");
        assertEquals("Import build", reply.join().getTitle());
    }

    @Test
    void aClosedQuestionOrNoWindowRepliesNull() {
        LanguageServerSession s = session();
        assertNull(s.showMessageRequest(importBuild()).join(), "no UI installed: nobody can answer");

        ScriptedUi ui = new ScriptedUi();
        s.setClientUi(ui);
        CompletableFuture<MessageActionItem> reply = s.showMessageRequest(importBuild());
        ui.answer.complete(null);
        assertNull(reply.join());
    }

    @Test
    void metalsInputBoxRepliesTheValueOrCancelled() {
        LanguageServerSession s = session();
        ScriptedUi ui = new ScriptedUi();
        s.setClientUi(ui);
        var params = new LanguageServerSession.MetalsInputBoxParams();
        params.prompt = "Enter the name of the new file";
        params.placeHolder = "Name";

        var reply = s.metalsInputBox(params);
        ui.answer.complete("Greeter");

        assertEquals("Greeter", reply.join().value);
        assertNull(reply.join().cancelled);
        assertEquals(List.of("input|Enter the name of the new file|null|Name"), ui.asked);

        ScriptedUi dismissing = new ScriptedUi();
        s.setClientUi(dismissing);
        var cancelled = s.metalsInputBox(params);
        dismissing.answer.complete(null);
        assertTrue(cancelled.join().cancelled);
    }

    @Test
    void metalsQuickPickRepliesTheChosenItemsId() {
        LanguageServerSession s = session();
        ScriptedUi ui = new ScriptedUi();
        s.setClientUi(ui);
        var params = new LanguageServerSession.MetalsQuickPickParams();
        params.placeHolder = "Choose the build tool";
        var sbt = new LanguageServerSession.MetalsQuickPickItem();
        sbt.id = "sbt";
        sbt.label = "sbt";
        var mill = new LanguageServerSession.MetalsQuickPickItem();
        mill.id = "mill";
        mill.label = "Mill";
        params.items = List.of(sbt, mill);

        var reply = s.metalsQuickPick(params);
        ui.answer.complete("mill");

        assertEquals("mill", reply.join().itemId);
        assertEquals(List.of("pick|Choose the build tool|[sbt, mill]"), ui.asked);
        assertTrue(
                s.metalsQuickPick(new LanguageServerSession.MetalsQuickPickParams())
                        .join()
                        .cancelled,
                "nothing to choose from");
    }

    @Test
    void gotoLocationOpensTheFileAndUnknownCommandsAreIgnored() {
        LanguageServerSession s = session();
        ScriptedUi ui = new ScriptedUi();
        s.setClientUi(ui);
        Path file = Path.of("/tmp/demo/Main.scala").toAbsolutePath();
        Location at = new Location(file.toUri().toString(), new Range(new Position(4, 2), new Position(4, 9)));

        s.metalsExecuteClientCommand(new ExecuteCommandParams("metals-goto-location", List.of(at)));
        s.metalsExecuteClientCommand(new ExecuteCommandParams("metals-tree-view-reveal", List.of()));

        assertEquals(file, ui.opened);
        assertEquals(4, ui.openedLine);
        assertTrue(ui.asked.isEmpty());
    }

    @Test
    void metalsStatusSpinsTheLoadingBarUntilHidden() {
        LanguageServerSession s = session();
        var importing = new LanguageServerSession.MetalsStatus();
        importing.text = "$(sync~spin) Importing build";
        var done = new LanguageServerSession.MetalsStatus();
        done.hide = true;

        s.metalsStatus(importing);
        s.metalsStatus(done);

        assertEquals(List.of("Progress:Importing build", "ProgressEnd:null"), statuses);
    }
}
