package com.editora.dap;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What the Debug window asks about a stopped program: call stacks (and more of them), scopes, variables and
 * their lazy values, evaluations from the console, a hover or a watch, setting a value, loaded sources and
 * modules.
 *
 * <p>Moved out of {@link DapManager} for size: the code is the manager's, reaching its session state through
 * {@link #dap}, and runs on the same threads with the same session checks as it did there.
 */
final class InspectionRequests {
    private final DapManager dap;

    InspectionRequests(DapManager dap) {
        this.dap = dap;
    }

    /** The sources the debuggee has loaded (callback on the FX thread; empty with no session, or on failure). */
    void loadedSources(Consumer<List<DapModels.LoadedSource>> cb) {
        DapClient c = dap.client;
        if (c == null) {
            cb.accept(List.of());
            return;
        }
        c.loadedSources()
                .whenComplete((list, e) -> dap.fx(() -> {
                    if (dap.client == c) {
                        cb.accept(list == null ? List.of() : list);
                    }
                }));
    }

    /** The modules the debuggee has loaded (callback on the FX thread; empty with no session, or on failure). */
    void modules(Consumer<List<DapModels.ModuleInfo>> cb) {
        DapClient c = dap.client;
        if (c == null) {
            cb.accept(List.of());
            return;
        }
        c.modules()
                .whenComplete((list, e) -> dap.fx(() -> {
                    if (dap.client == c) {
                        cb.accept(list == null ? List.of() : list);
                    }
                }));
    }

    /**
     * Sets the breakpoints of {@code source}, code the debugger has only by reference, for this session (callback on the
     * FX thread with one status per line; empty with no session).
     */
    void updateReferenceBreakpoints(
            DapModels.LoadedSource source,
            List<DapModels.LineBreakpoint> lines,
            Consumer<List<DapModels.BreakpointStatus>> cb) {
        DapClient c = dap.client;
        if (c == null) {
            cb.accept(List.of());
            return;
        }
        c.setReferenceBreakpoints(source, lines)
                .whenComplete((statuses, e) -> dap.fx(() -> {
                    if (dap.client == c) {
                        cb.accept(statuses == null ? List.of() : statuses);
                    }
                }));
    }

    /**
     * The text of a loaded source that is not a file, as the adapter gives it (callback on the FX thread: the text,
     * or null when the adapter could not give it).
     */
    void sourceContent(DapModels.LoadedSource source, Consumer<@Nullable String> cb) {
        DapClient c = dap.client;
        if (c == null) {
            cb.accept(null);
            return;
        }
        c.sourceContent(source).whenComplete((text, e) -> dap.fx(() -> cb.accept(e == null ? text : null)));
    }

    /**
     * A thread dump: every thread of the session with its status and call stack, in the order the adapter lists them
     * (callback on the FX thread). Every thread is asked for its stack, a running one too — an adapter that stops the
     * whole program without saying so still walks it — and one it refuses has none. Empty with no session.
     */
    void threadStacks(Consumer<List<DapModels.ThreadStack>> cb) {
        DapClient c = dap.client;
        if (c == null) {
            cb.accept(List.of());
            return;
        }
        dap.threadStatuses(statuses -> {
            List<CompletableFuture<List<DapModels.StackFrameInfo>>> asked = new ArrayList<>();
            for (DapModels.ThreadStatus t : statuses) {
                asked.add(c.stackTrace(t.id(), false).exceptionally(e -> List.of()));
            }
            CompletableFuture.allOf(asked.toArray(CompletableFuture[]::new))
                    .whenComplete((v, e) -> dap.fx(() -> {
                        if (dap.client != c) {
                            return;
                        }
                        List<DapModels.ThreadStack> out = new ArrayList<>();
                        for (int i = 0; i < statuses.size(); i++) {
                            List<DapModels.StackFrameInfo> frames = asked.get(i).getNow(List.of());
                            out.add(new DapModels.ThreadStack(
                                    statuses.get(i),
                                    frames == null ? List.of() : dap.javaLaunch.originalFrames(frames)));
                        }
                        cb.accept(out);
                    }));
        });
    }

    /** The first {@link DapClient#FIRST_FRAMES} frames of {@code threadId}'s stack (callback on the FX thread). */
    void stackTrace(int threadId, Consumer<List<DapModels.StackFrameInfo>> cb) {
        DapClient c = dap.client;
        if (c == null) {
            cb.accept(List.of());
            return;
        }
        c.stackPage(threadId, true, 0, DapClient.FIRST_FRAMES).whenComplete((page, e) -> {
            List<DapModels.StackFrameInfo> frames = page == null ? null : page.frames();
            dap.fxAfterLooks(frames, () -> {
                if (dap.client == c) {
                    dap.shownStack = page == null ? DapModels.StackPage.EMPTY : page;
                    cb.accept(dap.javaLaunch.originalFrames(frames));
                }
            });
        });
    }

    /**
     * The frames of {@code threadId}'s stack below the first {@code loaded}, up to {@link DapClient#STACK_FRAME_LIMIT}
     * in all (callback on the FX thread; an empty page when the session moved on or the adapter could not say).
     */
    void moreFrames(int threadId, int loaded, Consumer<DapModels.StackPage> cb) {
        DapClient c = dap.client;
        if (c == null || dap.state != DapManager.State.SUSPENDED || loaded >= DapClient.STACK_FRAME_LIMIT) {
            cb.accept(DapModels.StackPage.EMPTY);
            return;
        }
        long epoch = dap.sessionEpoch;
        long stops = dap.stopCount;
        c.stackPage(threadId, true, loaded, DapClient.STACK_FRAME_LIMIT - loaded)
                .whenComplete((page, e) -> {
                    List<DapModels.StackFrameInfo> frames = page == null ? null : page.frames();
                    dap.fxAfterLooks(frames, () -> {
                        if (!dap.isCurrent(epoch, c)
                                || stops != dap.stopCount
                                || dap.state != DapManager.State.SUSPENDED
                                || page == null) {
                            cb.accept(DapModels.StackPage.EMPTY);
                            return;
                        }
                        cb.accept(new DapModels.StackPage(
                                dap.javaLaunch.originalFrames(frames), page.depth(), page.more()));
                    });
                });
    }

    void scopes(int frameId, Consumer<List<DapModels.ScopeInfo>> cb) {
        DapClient c = dap.client;
        if (c == null) {
            cb.accept(List.of());
            return;
        }
        c.scopes(frameId)
                .whenComplete((scopes, e) -> dap.fx(() -> {
                    if (dap.client == c) {
                        cb.accept(scopes == null ? List.of() : scopes);
                    }
                }));
    }

    /** One page of a container's children — see {@link DapClient#variables(int, String, int, int)}. */
    void variables(int ref, @Nullable String filter, int start, int count, Consumer<List<DapModels.VariableInfo>> cb) {
        DapClient c = dap.client;
        if (c == null) {
            cb.accept(List.of());
            return;
        }
        // One request per stop however many views ask (the tree and the inline values both read Locals: B207).
        dap.lazyValues
                .reply(ref + "/" + filter + "/" + start + "/" + count, () -> c.variables(ref, filter, start, count))
                .thenCompose(vars -> dap.lazyValues.resolveAll(vars, child -> c.variables(child, null, 0, 0)))
                .thenCompose(vars -> repairStrings(c, vars))
                .whenComplete((vars, e) -> dap.fx(() -> {
                    if (dap.client == c) {
                        cb.accept(vars == null ? List.of() : vars);
                    }
                }));
    }

    /**
     * Loads one row the stop's budget left unloaded ({@link DapModels.ValueNote#NOT_LOADED}): a click on it. {@code
     * cb} gets the resolved row (FX thread), or the row as it was when the adapter did not answer.
     */
    void loadValue(DapModels.VariableInfo row, Consumer<DapModels.VariableInfo> cb) {
        DapClient c = dap.client;
        if (c == null) {
            cb.accept(row);
            return;
        }
        dap.lazyValues
                .resolveNow(row, child -> c.variables(child, null, 0, 0))
                .thenCompose(r -> repairStrings(c, List.of(r)))
                .whenComplete((rows, e) -> dap.fx(() -> {
                    if (dap.client == c) {
                        cb.accept(rows == null || rows.isEmpty() ? row : rows.get(0));
                    }
                }));
    }

    /**
     * Shows numbers in hexadecimal (or not) from the next request on, in this session and the next ones; the caller
     * fetches the values again. FX thread.
     */
    void setHexValues(boolean on) {
        dap.shared.hexValues = on;
        DapClient c = dap.client;
        if (c != null) {
            c.setHexValues(on);
        }
        dap.lazyValues.clear(); // the answers so far are in the other format
    }

    /**
     * The {@code evaluate} context the adapter is sent for {@code context}: PSES answers a {@code repl} evaluation in
     * its console, which a debug session has none of (the result came back empty), so the Debug console asks it as a
     * {@code watch}, which returns the value — and still runs the expression, an assignment included.
     */
    private String adapterContext(String context) {
        return "repl".equals(context) && "powershell".equals(dap.sessionLanguage) ? "watch" : context;
    }

    private CompletableFuture<List<DapModels.VariableInfo>> repairStrings(
            DapClient c, List<DapModels.VariableInfo> vars) {
        return dap.javaDebugSession()
                ? JdwpStringRepair.repairAll(c, vars).thenApply(JavaValueText::tidyAll) // B108: lambdas
                : CompletableFuture.completedFuture(vars);
    }

    private CompletableFuture<DapModels.EvalResult> repairString(DapClient c, DapModels.EvalResult result) {
        return dap.javaDebugSession()
                ? JdwpStringRepair.repair(c, result).thenApply(JavaValueText::tidy) // B108: lambdas
                : CompletableFuture.completedFuture(result);
    }

    void evaluate(String expression, int frameId, String context, Consumer<ConsoleEvaluation> cb) {
        DapClient c = dap.client;
        if (c == null) {
            cb.accept(ConsoleEvaluation.value(""));
            return;
        }
        boolean console = "repl".equals(context);
        if (console) {
            dap.consoleEvaluations++;
        }
        c.evaluateFull(
                        dap.adapterExpression(expression),
                        frameId,
                        adapterContext(context)) // the full answer: a string can be repaired
                .thenCompose(full -> repairString(c, full))
                .whenComplete((full, e) -> dap.fx(() -> {
                    if (console && dap.client == c) {
                        dap.consoleEvaluations = Math.max(0, dap.consoleEvaluations - 1);
                    }
                    if (dap.client == c) {
                        String r = full == null ? null : full.result();
                        cb.accept(
                                e != null
                                        ? ConsoleEvaluation.failure(
                                                isTimeout(e)
                                                        ? DapManager.stillEvaluating(c.evaluationWaitSeconds())
                                                        : DapManager.msg(e))
                                        : ConsoleEvaluation.value(r == null ? "" : r));
                    }
                }));
    }

    private static boolean isTimeout(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (x instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** Full evaluate (result + expandable reference + type) for watches and the hover value popup.
     *  Failures deliver an {@code EvalResult} with the error text and no children. */
    void evaluateFull(String expression, int frameId, String context, Consumer<DapModels.EvalResult> cb) {
        DapClient c = dap.client;
        if (c == null) {
            cb.accept(new DapModels.EvalResult("", 0, null));
            return;
        }
        // Code the user asked to run (the evaluate field, the Evaluate window, Quick Evaluate) holds the stopped
        // thread as a console evaluation does: a Step meanwhile is refused, saying why, rather than queued.
        boolean code = "repl".equals(context);
        if (code) {
            dap.consoleEvaluations++;
        }
        c.evaluateFull(dap.adapterExpression(expression), frameId, adapterContext(context))
                .thenCompose(r -> repairString(c, r))
                .whenComplete((r, e) -> dap.fx(() -> {
                    if (code && dap.client == c) {
                        dap.consoleEvaluations = Math.max(0, dap.consoleEvaluations - 1);
                    }
                    if (dap.client == c) {
                        cb.accept(
                                e != null
                                        ? DapModels.EvalResult.failure(
                                                code && isTimeout(e)
                                                        ? DapManager.stillEvaluating(c.evaluationWaitSeconds())
                                                        : DapManager.msg(e))
                                        : (r == null ? new DapModels.EvalResult("", 0, null) : r));
                    }
                }));
    }

    /** Hover evaluate (context "hover"): failures deliver {@code null} — hovering a non-variable
     *  identifier (keyword, type name) is normal and must show nothing, not an error. */
    void evaluateHover(String expression, int frameId, Consumer<String> cb) {
        DapClient c = dap.client;
        if (c == null) {
            cb.accept(null);
            return;
        }
        c.evaluateFull(dap.adapterExpression(expression), frameId, "hover")
                .thenCompose(r -> repairString(c, r))
                .whenComplete((r, e) -> dap.fx(() -> {
                    if (dap.client == c) {
                        cb.accept(e != null || r == null ? null : r.result());
                    }
                }));
    }

    /**
     * {@link #setVariable(int, String, String, Consumer)}, and when the adapter cannot set a variable of that type
     * (java-debug sets only primitives and Strings: an {@code Integer}, a record or an enum failed with "Set value
     * is not supported yet for type java.lang.Integer"), assigns it instead by evaluating {@code assignTo = value}
     * in frame {@code frameId}, as IntelliJ does (review 3, B208). {@code assignTo} null: no such fallback.
     */
    void setVariable(int ref, String name, String value, @Nullable String assignTo, int frameId, Consumer<String> cb) {
        DapClient c = dap.client;
        if (c == null) {
            return; // no session: nothing was set
        }
        c.setVariable(ref, name, value)
                .whenComplete((r, e) -> dap.fx(() -> {
                    if (dap.client != c) {
                        return;
                    }
                    if (e == null) {
                        cb.accept(r == null ? value : r);
                    } else if (SetValueText.unsupportedType(DapManager.msg(e)) && assignTo != null && frameId >= 0) {
                        assign(c, name, SetValueText.assignment(assignTo, value), frameId, cb);
                    } else {
                        dap.listener.onError(
                                SetValueText.unsupportedType(DapManager.msg(e))
                                        ? tr("debug.setValue.unsupported", name)
                                        : DapManager.msg(e));
                    }
                }));
    }

    /**
     * Set Value on a watch: assigns {@code value} to {@code expression} in frame {@code frameId}. Where the adapter
     * assigns to an expression ({@code setExpression}: debugpy, js-debug) it is asked to; else, when the watch names a
     * variable of the frame's scopes ({@code variableRef}, that scope's reference; 0 when none), that variable is set
     * ({@code setVariable}: Delve, CodeLLDB); else the assignment is evaluated ({@code expression = value}: java-debug).
     * {@code cb} gets the value it has now; a failure goes to {@link DapManager.Listener#onError}.
     */
    void setExpression(String expression, String value, int frameId, int variableRef, Consumer<String> cb) {
        DapClient c = dap.client;
        if (c == null) {
            return;
        }
        if (c.supportsSetExpression()) {
            c.setExpression(expression, value, frameId)
                    .whenComplete((r, e) -> dap.fx(() -> {
                        if (dap.client != c) {
                            return;
                        }
                        if (e == null) {
                            cb.accept(r);
                        } else {
                            dap.listener.onError(tr("debug.setValue.failed", expression, DapManager.msg(e)));
                        }
                    }));
        } else if (variableRef > 0) {
            setVariable(variableRef, expression.strip(), value, expression, frameId, cb);
        } else {
            assign(c, expression, SetValueText.assignment(expression, value), frameId, cb);
        }
    }

    /**
     * Sets a value no container of the debugger's holds (a type renderer's child) by evaluating {@code assignTo =
     * value} in frame {@code frameId}; {@code cb} gets the new value, a failure goes to {@link DapManager.Listener#onError}.
     */
    void assignValue(String name, String assignTo, String value, int frameId, Consumer<String> cb) {
        DapClient c = dap.client;
        if (c != null) {
            assign(c, name, SetValueText.assignment(assignTo, value), frameId, cb);
        }
    }

    private void assign(DapClient c, String name, String assignment, int frameId, Consumer<String> cb) {
        c.evaluateFull(assignment, frameId, "repl")
                .thenCompose(r -> repairString(c, r))
                .whenComplete((r, e) -> dap.fx(() -> {
                    if (dap.client != c) {
                        return;
                    }
                    if (e != null || r == null) {
                        dap.listener.onError(tr("debug.setValue.failed", name, e == null ? "" : DapManager.msg(e)));
                    } else {
                        cb.accept(r.result());
                    }
                }));
    }
}
