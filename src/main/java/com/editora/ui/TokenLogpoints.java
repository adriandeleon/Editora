package com.editora.ui;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.LogpointTokens;
import org.jspecify.annotations.Nullable;

/**
 * Logpoints whose message Editora writes, not the adapter: one with a token ({@link LogpointTokens}), or any logpoint of
 * an adapter that does not log them. The stop is held quiet ({@link DebugSession#suspendHeld}), the message written to
 * the console, and the program goes on. Part of {@link DebugCoordinator}, whose session it uses.
 */
final class TokenLogpoints {

    private final DebugCoordinator d;

    TokenLogpoints(DebugCoordinator d) {
        this.d = d;
    }

    /**
     * The message of the logpoint the program stopped on ({@code reason}) at the top frame's line, when Editora writes
     * it: it uses a token ({@link LogpointTokens}), so the adapter was sent it as a plain breakpoint, or the adapter does
     * not log logpoints at all (netcoredbg ignores {@code logMessage} and stops, after checking the condition). Null for
     * any other stop.
     */
    @Nullable
    String toWrite(String reason, List<DapModels.StackFrameInfo> frames) {
        if (!"breakpoint".equals(reason) || frames.isEmpty()) {
            return null;
        }
        DapModels.StackFrameInfo top = frames.getFirst();
        Path file = top.file();
        if (file == null) {
            return null;
        }
        boolean adapterLogs = d.dap().supportsLogPoints();
        DapModels.LineBreakpoint lb = d.sentBreakpointAt(
                file,
                top.line(),
                b -> b.column() <= 0
                        && (LogpointTokens.uses(b.logMessage())
                                || (!adapterLogs || !b.filters().none())
                                        && b.logMessage() != null
                                        && !b.logMessage().isBlank()));
        return lb == null ? null : lb.logMessage();
    }

    /**
     * Writes a token logpoint's message to the console as the adapter writes a logpoint's: its {@code {expression}}
     * parts evaluated in the top frame one after another, the tokens from the stack and the thread. Then the program
     * goes on, unless the user acted first (a step, Stop) or it stopped again meanwhile.
     */
    void logAndContinue(int threadId, String message, List<DapModels.StackFrameInfo> frames) {
        // The stopped session's own: another may be in front by the time the answers come.
        DebugSession session = d.s();
        DapManager dap = session.dap;
        int serial = session.stopSerial;
        DebugPanel panel = d.panel();
        DapModels.StackFrameInfo top = frames.getFirst();
        List<String> expressions = LogpointTokens.expressions(message);
        Map<String, String> values = new LinkedHashMap<>();
        Runnable write = () -> dap.threadName(threadId, name -> {
            if (serial != session.stopSerial || dap.state() != DapManager.State.SUSPENDED) {
                return;
            }
            String threadName = name.isEmpty() ? String.valueOf(threadId) : name;
            LogpointTokens.Stop stop = new LogpointTokens.Stop(
                    DebugPanelText.frameName(top.name()),
                    frames.size() > 1 ? DebugPanelText.frameName(frames.get(1).name()) : "",
                    frames.stream().map(DebugPanelText::frameText).toList(),
                    threadId,
                    threadName);
            panel.appendOutput(LogpointTokens.expand(message, e -> values.getOrDefault(e, ""), stop) + "\n", "console");
            dap.resume();
        });
        evaluateEach(session, top.id(), serial, expressions, 0, values, write);
    }

    /**
     * Evaluates {@code expressions} from {@code index} on in frame {@code frameId} of {@code session} into
     * {@code values}, then runs {@code then}; stops when the program moved on meanwhile.
     */
    private void evaluateEach(
            DebugSession session,
            int frameId,
            int serial,
            List<String> expressions,
            int index,
            Map<String, String> values,
            Runnable then) {
        if (index >= expressions.size()) {
            then.run();
            return;
        }
        if (serial != session.stopSerial) {
            return;
        }
        String expression = expressions.get(index);
        session.dap.evaluateFull(expression, frameId, "watch", r -> {
            values.put(expression, r == null ? "" : DebugValues.display(r.result()));
            evaluateEach(session, frameId, serial, expressions, index + 1, values, then);
        });
    }
}
