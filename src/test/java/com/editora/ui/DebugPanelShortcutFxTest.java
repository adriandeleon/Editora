package com.editora.ui;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.dap.DapManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Debug panel's gdb-style single-key shortcuts (live only while the panel is focused): c=continue,
 * p=pause, k=stop, r=restart, n=step over, s=step into, f=step out, u=run to cursor. They fire the matching
 * toolbar control (respecting its enabled state), swallow the key so the stack/variables lists don't treat
 * it as type-ahead, and leave modified chords (e.g. C-x o focus cycling) to the global dispatcher.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DebugPanelShortcutFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A {@link DebugPanel.Actions} proxy that records the name of each control method invoked. */
    private static DebugPanel.Actions recording(List<String> calls) {
        return (DebugPanel.Actions) Proxy.newProxyInstance(
                DebugPanel.Actions.class.getClassLoader(), new Class[] {DebugPanel.Actions.class}, (p, m, a) -> {
                    calls.add(m.getName());
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    return null;
                });
    }

    private static KeyEvent key(KeyCode code, boolean control) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "", code, false, control, false, false);
    }

    private static KeyEvent shifted(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "", code, true, false, false, false);
    }

    /**
     * Review C2: typing in the panel ("work") restarted and then stopped the program. Stop and Restart need Shift;
     * a bare k or r does nothing and is left to the list it was typed in; a capital N does not step.
     */
    @Test
    void stopAndRestartNeedShiftAndBareLettersDoNotEndTheProgram() throws Exception {
        Object[] r = FxTestSupport.callOnFx(() -> {
            List<String> invoked = new ArrayList<>();
            DebugPanel panel = new DebugPanel(recording(invoked));
            panel.setState(DapManager.State.SUSPENDED);
            KeyEvent bareK = key(KeyCode.K, false);
            FxTestSupport.call(panel, "handleDebugKey", new Class[] {KeyEvent.class}, bareK);
            KeyEvent bareR = key(KeyCode.R, false);
            FxTestSupport.call(panel, "handleDebugKey", new Class[] {KeyEvent.class}, bareR);
            panel.fireEvent(shifted(KeyCode.N));
            List<String> afterBare = List.copyOf(invoked);
            panel.fireEvent(shifted(KeyCode.K));
            panel.fireEvent(shifted(KeyCode.R));
            return new Object[] {afterBare, List.copyOf(invoked), bareK.isConsumed(), bareR.isConsumed()};
        });
        assertEquals(List.of(), r[0], "bare k, bare r and Shift+N fire nothing");
        assertEquals(List.of("stop", "restart"), r[1], "Shift+K stops, Shift+R restarts");
        assertFalse((Boolean) r[2], "a bare k is not swallowed: it is type-ahead in the list");
        assertFalse((Boolean) r[3]);
    }

    @Test
    void suspendedSingleKeysFireTheMatchingControls() throws Exception {
        List<String> calls = FxTestSupport.callOnFx(() -> {
            List<String> invoked = new ArrayList<>();
            DebugPanel panel = new DebugPanel(recording(invoked));
            panel.setState(DapManager.State.SUSPENDED); // enables continue + the step controls + run-to-cursor
            for (KeyCode c : List.of(KeyCode.C, KeyCode.N, KeyCode.S, KeyCode.F, KeyCode.U)) {
                panel.fireEvent(key(c, false)); // goes through the real installed event filter
            }
            return invoked;
        });
        assertTrue(calls.contains("start"), "c → resume/continue (start)");
        assertTrue(calls.contains("stepOver"), "n → step over");
        assertTrue(calls.contains("stepInto"), "s → step into");
        assertTrue(calls.contains("stepOut"), "f → step out");
        assertTrue(calls.contains("runToCursor"), "u → run to cursor");
    }

    @Test
    void runningSingleKeysFirePauseAndStopButNotDisabledSteps() throws Exception {
        List<String> calls = FxTestSupport.callOnFx(() -> {
            List<String> invoked = new ArrayList<>();
            DebugPanel panel = new DebugPanel(recording(invoked));
            panel.setState(DapManager.State.RUNNING); // pause + stop enabled; the step controls are disabled
            panel.fireEvent(key(KeyCode.P, false));
            panel.fireEvent(shifted(KeyCode.K));
            panel.fireEvent(key(KeyCode.N, false)); // step over is disabled while running
            return invoked;
        });
        assertTrue(calls.contains("pause"), "p → pause");
        assertTrue(calls.contains("stop"), "Shift+K → stop");
        assertFalse(calls.contains("stepOver"), "a disabled control does not fire");
    }

    @Test
    void aDisabledKeyIsConsumedWhileAModifiedChordAndUnmappedLetterAreLeftAlone() throws Exception {
        boolean[] r = FxTestSupport.callOnFx(() -> {
            List<String> invoked = new ArrayList<>();
            DebugPanel panel = new DebugPanel(recording(invoked));
            panel.setState(DapManager.State.INACTIVE); // the step controls are disabled; start is enabled
            // Invoke the handler directly so we can assert on the very event object (Event.fireEvent copies it).
            KeyEvent disabledStep = key(KeyCode.N, false);
            FxTestSupport.call(panel, "handleDebugKey", new Class[] {KeyEvent.class}, disabledStep);
            KeyEvent ctrlC = key(KeyCode.C, true); // C-c: a global chord, not our bare-c resume
            FxTestSupport.call(panel, "handleDebugKey", new Class[] {KeyEvent.class}, ctrlC);
            KeyEvent unmapped = key(KeyCode.A, false);
            FxTestSupport.call(panel, "handleDebugKey", new Class[] {KeyEvent.class}, unmapped);
            return new boolean[] {
                disabledStep.isConsumed(),
                invoked.contains("stepOver"),
                ctrlC.isConsumed(),
                invoked.contains("start"),
                unmapped.isConsumed()
            };
        });
        assertTrue(r[0], "a mapped-but-disabled debug key is swallowed (no stray list type-ahead)");
        assertFalse(r[1], "…but its action does not fire while disabled");
        assertFalse(r[2], "a modified chord (C-c) is not consumed here — the global dispatcher gets it");
        assertFalse(r[3], "…and it does not trigger the bare-c resume");
        assertFalse(r[4], "an unmapped letter falls through untouched");
    }
}
