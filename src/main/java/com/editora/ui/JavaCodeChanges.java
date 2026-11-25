package com.editora.ui;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Java code changes and frame control in a debug session: hot code replace (a saved file loaded into the running
 * program, at once or by Apply Code Changes), Restart Frame with the call stack's menu, and Smart Step Into. Part of
 * {@link DebugCoordinator}.
 */
final class JavaCodeChanges {

    private final DebugCoordinator d;

    JavaCodeChanges(DebugCoordinator d) {
        this.d = d;
    }

    private DapManager.CodeReplaceMode codeReplaceMode() {
        return DapManager.CodeReplaceMode.of(d.host.settings().getDebugHotCodeReplace());
    }

    private static boolean isJavaSource(@Nullable Path file) {
        return file != null
                && file.getFileName() != null
                && file.getFileName()
                        .toString()
                        .toLowerCase(java.util.Locale.ROOT)
                        .endsWith(".java");
    }

    /**
     * A user save (not an autosave) reached the language server. During a Java session in {@code auto} mode
     * the change goes into the running program at once and the console says what happened; in {@code manual}
     * mode the status bar says how to apply it. Every Java session that can take it, not only the one shown (a
     * client and its server). FX thread.
     */
    void sourceSaved(EditorBuffer buffer) {
        Path file = buffer == null ? null : buffer.getPath();
        if (!isJavaSource(file)) {
            return;
        }
        d.forEachSession(() -> {
            if (!d.dap().canReplaceCode()) {
                return;
            }
            d.s().savedSinceReplace.add(file);
            switch (codeReplaceMode()) {
                case AUTO -> requestCodeReplace(file, false);
                case MANUAL -> d.host.setStatus(tr("debug.hcr.manualHint"));
                case OFF -> {
                    // the user chose to keep the running code as it is
                }
            }
        });
    }

    /**
     * A session started or ended: a replace still running belongs to the one before, and so do the saves it has
     * not applied. Left set, the running flag held every later save and Apply Code Changes of the window, which
     * then did nothing at all (review D101).
     */
    void forgetCodeReplace(DapManager.State state) {
        if (state != DapManager.State.STARTING && state != DapManager.State.INACTIVE) {
            return;
        }
        d.s().codeReplaceSession++;
        d.s().codeReplaceRunning = false;
        d.s().codeReplaceAgain = null;
        d.s().savedSinceReplace.clear();
        d.s().lastCodeReplaceError = "";
    }

    /**
     * A reasonless refusal said with its likely reason ({@link CodeReplaceJdk}): the program's Java (its runtime
     * chip) and the one jdtls builds a file without a build tool for; null when that cannot be told.
     */
    private @Nullable String explainCodeReplace(@Nullable String message) {
        Path file = d.dap().sessionFile();
        if (!CodeReplaceJdk.reasonless(message) || file == null) {
            return null;
        }
        if (!com.editora.perf.Looked.allKnown(() -> JavaProjectRoot.find(file))) {
            JavaProjectRoot.warmLater(file); // not looked at yet: the reason is told once it is (PERF-01)
            return null;
        }
        if (JavaProjectRoot.find(file) != null) {
            return null; // a build project is compiled for its own release, not jdtls's default
        }
        return CodeReplaceJdk.explain(
                message, CodeReplaceJdk.programMajor(d.panel().runtimeText()), d.lspManager.javaDefaultMajor(file));
    }

    /** A Java session started running: let jdtls build what it will compare saved changes with. */
    void primeCodeReplace(DapManager.State state) {
        if (state == DapManager.State.RUNNING && codeReplaceMode() != DapManager.CodeReplaceMode.OFF) {
            d.dap().primeCodeReplace();
        }
    }

    /** {@code debug.applyCodeChanges}: loads what is saved into the running program (auto and manual modes). */
    void applyCodeChanges() {
        if (!d.dap().canReplaceCode()) {
            d.host.setStatus(tr("debug.hcr.noSession"));
            return;
        }
        if (codeReplaceMode() == DapManager.CodeReplaceMode.OFF) {
            d.host.setStatus(tr("debug.hcr.off"));
            return;
        }
        EditorBuffer active = d.host.activeBuffer();
        Path file = active != null && isJavaSource(active.getPath())
                ? active.getPath()
                : d.dap().sessionFile();
        if (active != null && active.isDirty() && isJavaSource(active.getPath())) {
            d.panel().appendNotice(tr("debug.hcr.unsavedNote")); // only saved files are compiled
        }
        requestCodeReplace(file, true);
    }

    /** Starts a replace, or folds this request into the one running (one more round once it ends). */
    private void requestCodeReplace(@Nullable Path file, boolean asked) {
        if (d.s().codeReplaceRunning) {
            d.s().codeReplaceAgain = file;
            return;
        }
        d.s().codeReplaceRunning = true;
        d.s().lastCodeReplaceError = "";
        Set<Path> covered = new LinkedHashSet<>(d.s().savedSinceReplace);
        long session = d.s().codeReplaceSession;
        d.host.setStatus(tr("debug.hcr.applying"));
        d.dap()
                .applyCodeChanges(
                        file,
                        d.asThisSession(
                                outcome -> { // answered as the session that asked
                                    if (session != d.s().codeReplaceSession) {
                                        return; // its session ended meanwhile, which reset all of this (review D101)
                                    }
                                    d.s().codeReplaceRunning = false;
                                    reportCodeReplace(outcome, covered, asked);
                                    Path again = d.s().codeReplaceAgain;
                                    d.s().codeReplaceAgain = null;
                                    if (again != null && d.dap().canReplaceCode()) {
                                        requestCodeReplace(again, false);
                                    }
                                }));
    }

    /** Says how a replace ended: status bar always, Debug console for anything the user must act on. */
    private void reportCodeReplace(DapManager.CodeReplaceOutcome outcome, Set<Path> covered, boolean asked) {
        switch (outcome.result()) {
            case APPLIED -> {
                String names = CodeReplaceText.appliedNames(outcome.classes(), covered);
                d.panel().appendNotice(tr("debug.hcr.applied", names));
                if (outcome.message() == null || outcome.message().isEmpty()) {
                    d.host.setStatus(tr("debug.hcr.appliedStatus", names));
                } else {
                    // Methods that were running keep their old code (java-debug's "obsolete methods"): say how to
                    // run one again with the new code (review 2, B103).
                    explainOldCode();
                    d.host.setStatus(tr("debug.hcr.appliedOldStatus", names));
                }
                d.s().savedSinceReplace.removeAll(covered);
                codeReplaced(covered);
            }
            case NOTHING_CHANGED -> {
                d.s().savedSinceReplace.removeAll(covered); // the program already runs what these files say
                codeReplaced(covered);
                d.host.setStatus(tr(asked ? "debug.hcr.nothing" : "debug.hcr.nothingQuiet"));
            }
            case COMPILE_ERRORS -> {
                d.panel().appendNotice(tr("debug.hcr.compileErrors"));
                d.host.setStatus(tr("debug.hcr.compileErrors"));
            }
            case JAVAC_CLASSES -> {
                d.panel().appendNotice(tr("debug.hcr.javacClasses"));
                d.host.setStatus(tr("debug.hcr.javacClassesStatus"));
            }
            case FAILED -> {
                String explained = explainCodeReplace(outcome.message());
                if (explained != null && !explained.equals(d.s().lastCodeReplaceError)) {
                    d.panel().appendNotice(tr("debug.hcr.notice", explained));
                } else if (explained != null
                        || CodeReplaceText.saidAlready(outcome.message(), d.s().lastCodeReplaceError)) {
                    d.panel().appendNotice(tr("debug.hcr.restartNeeded")); // the adapter's event gave the reason
                } else {
                    d.panel().appendNotice(tr("debug.hcr.failed", outcome.message()));
                }
                d.host.setStatus(tr("debug.hcr.failedStatus"));
            }
            case BUILD_FAILED -> {
                d.panel().appendNotice(tr("debug.hcr.buildFailed"));
                d.host.setStatus(tr("debug.hcr.buildFailed"));
            }
            case NO_SESSION -> d.host.setStatus(tr("debug.hcr.noSession"));
        }
    }

    /**
     * Says how to run a method left on its old code again — Restart Frame, or, when the only such method is the
     * thread's first one ({@code main}), restarting the program (review 3, B209). java-debug names an old-code frame
     * {@code Obsolete method()} only in the stack it stops on after the replace, so the hint waits for that stack (a
     * second at most; then it reads the stack shown).
     */
    private void explainOldCode() {
        // While java-debug replaces, the program reads as running: the stack it stops on again names the frames.
        List<String> shown = d.panel().callStack().stream()
                .map(DapModels.StackFrameInfo::name)
                .toList();
        if (shown.stream().anyMatch(DebugPanelText::isObsoleteFrame)) {
            d.panel().appendNotice(CodeReplaceText.oldCodeHint(shown)); // that stack came before the answer
            return;
        }
        long asked = ++d.s().oldCodeHintAsked;
        d.s().oldCodeHintPending = asked;
        javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1));
        wait.setOnFinished(e -> {
            if (d.s().oldCodeHintPending == asked) {
                d.s().oldCodeHintPending = 0;
                d.panel()
                        .appendNotice(CodeReplaceText.oldCodeHint(d.panel().callStack().stream()
                                .map(DapModels.StackFrameInfo::name)
                                .toList()));
            }
        });
        wait.play();
    }

    /** A stack arrived: say the waiting old-code hint from it (B209). */
    void oldCodeHintFrom(List<DapModels.StackFrameInfo> frames) {
        if (d.s().oldCodeHintPending != 0) {
            d.s().oldCodeHintPending = 0;
            d.panel()
                    .appendNotice(CodeReplaceText.oldCodeHint(
                            frames.stream().map(DapModels.StackFrameInfo::name).toList()));
        }
    }

    /**
     * The running program now runs what {@code files} say on disk: a successful replace, or one that found
     * nothing to change. Breakpoint and frame lines of these files no longer need mapping to launch-time lines.
     */
    private void codeReplaced(Set<Path> files) {
        d.rebaselineLines(files); // B1: the code that runs matches the saved file again
    }

    /**
     * java-debug's own hot code replace events. A replace it could not finish leaves the program on the old code
     * somewhere: that is said in the console. Progress events need no words — Editora reports the outcome.
     */
    void codeReplaceNotice(String changeType, String rawMessage) {
        String explained = "ERROR".equals(changeType) ? explainCodeReplace(rawMessage) : null;
        String message = explained != null ? explained : rawMessage;
        String text = CodeReplaceText.notice(changeType, message);
        if ("ERROR".equals(changeType)) {
            d.s().lastCodeReplaceError = message == null ? "" : message.strip();
        }
        if (text != null) {
            d.panel().appendNotice(tr("debug.hcr.notice", text));
        }
    }

    /**
     * {@code debug.restartFrame}: runs the selected call-stack frame's method again from its start (the top frame
     * when none is selected). Paused Java sessions only; the adapter says when a frame cannot be restarted.
     */
    void restartFrame() {
        restartFrame(d.s().shownFrame);
    }

    private void restartFrame(DapModels.@Nullable StackFrameInfo frame) {
        if (d.dap().state() != DapManager.State.SUSPENDED || frame == null) {
            d.host.setStatus(tr("debug.frame.notPaused"));
            return;
        }
        if (!d.dap().supportsRestartFrame()) {
            d.host.setStatus(tr("debug.restartFrame.unsupported"));
            return;
        }
        List<DapModels.StackFrameInfo> frames = d.panel().callStack();
        if (frames.contains(frame) && !canRestartFrame(true, true, frame, frames)) {
            // The thread's first method: nothing called it, so nothing can call it again (review 3, B209).
            d.host.setStatus(tr("debug.restartFrame.bottom"));
            return;
        }
        d.dap().restartFrame(frame.id());
    }

    /**
     * Whether the call-stack menu offers Restart Frame for {@code frame} of {@code frames}: a paused session whose
     * adapter restarts frames, and not the bottom frame of a complete stack (nothing called it, so nothing can
     * call it again).
     */
    static boolean canRestartFrame(
            boolean suspended,
            boolean supported,
            DapModels.StackFrameInfo frame,
            List<DapModels.StackFrameInfo> frames) {
        if (!suspended || !supported || frame == null || frames == null || frames.isEmpty()) {
            return false;
        }
        int index = frames.indexOf(frame);
        return index >= 0 && index < frames.size() - 1;
    }

    /** The call-stack context menu for {@code frame} (Restart Frame). */
    List<javafx.scene.control.MenuItem> stackMenuItems(
            DapModels.StackFrameInfo frame, List<DapModels.StackFrameInfo> frames) {
        javafx.scene.control.MenuItem restart = new javafx.scene.control.MenuItem(tr("debugpanel.restartFrame"));
        com.editora.editor.RowReasons.because(
                restart,
                DebugPanelText.restartFrameReason(
                        d.dap().state() == DapManager.State.SUSPENDED, d.dap().supportsRestartFrame(), frame, frames));
        restart.setOnAction(e -> restartFrame(frame));
        com.editora.editor.MenuChords.tag(restart, "debug.restartFrame"); // the selected frame is the shown one
        return List.of(restart, d.stepFilters().menuItem(frame));
    }

    /** Debug: Don't Step Into This… ({@code debug.dontStepIntoFrame}), for the frame shown. */
    void dontStepIntoFrame() {
        d.stepFilters().dontStepInto(null);
    }

    /** The last Smart Step Into picker shown (null before the first). */
    private @Nullable QuickOpen<DapModels.StepTarget> smartStepPicker;

    /**
     * {@code debug.smartStepInto}: lists the calls on the current line and steps into the one picked — straight
     * into it when there is only one. Plain Step Into is unchanged.
     */
    void smartStepInto() {
        if (d.dap().state() != DapManager.State.SUSPENDED) {
            d.host.setStatus(tr("debug.frame.notPaused"));
            return;
        }
        if (!d.dap().supportsStepInTargets()) {
            d.host.setStatus(tr("debug.smartStep.unsupported"));
            return;
        }
        String lineText = shownLineText();
        d.dap().stepInTargets(found -> {
            List<DapModels.StepTarget> targets = StepTargetOrder.sort(found, lineText);
            if (targets.isEmpty()) {
                d.host.setStatus(tr("debug.smartStep.none"));
            } else if (targets.size() == 1) {
                d.dap().stepIntoTarget(targets.get(0).id());
            } else {
                QuickOpen<DapModels.StepTarget> picker = smartStepPicker = new QuickOpen<>(
                        tr("debug.smartStep.title"),
                        tr("debug.smartStep.prompt"),
                        () -> targets,
                        DapModels.StepTarget::label,
                        t -> t.line() >= 0 ? tr("debug.smartStep.line", t.line() + 1) : "",
                        t -> d.dap().stepIntoTarget(t.id()));
                picker.setOverlayHost(d.host.overlayHost());
                picker.show(d.host.window());
            }
        });
    }

    /** The text of the stopped line Smart Step Into lists the calls of ("" when its file is not open). */
    private String shownLineText() {
        DapModels.StackFrameInfo frame = d.s().shownFrame;
        if (frame == null || frame.file() == null) {
            return "";
        }
        EditorBuffer b = d.ops.bufferForPath(frame.file());
        int line = frame.line();
        return b == null || line < 0 || line >= b.getArea().getParagraphs().size()
                ? ""
                : b.getArea().getParagraph(line).getText();
    }

    /** A Smart Step Into list belongs to one stop: it closes once the program runs on or the session ends. */
    void closeSmartStepPicker(DapManager.State state) {
        if (!d.shown()) {
            return; // another session's: the editor shows the focused one's
        }
        QuickOpen<DapModels.StepTarget> picker = smartStepPicker;
        if (picker != null && state != DapManager.State.SUSPENDED) {
            smartStepPicker = null;
            picker.hide();
        }
    }
}
