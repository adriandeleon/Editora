package com.editora.ui;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every stop must report its top frame through {@link DebugPanel.Actions#selectFrame}: that call is what
 * sets the execution line and reloads variables and watches. It used to ride on the call-stack selection
 * listener, which stays silent when the new top frame equals the previous one — exactly what debugpy
 * delivers each time the same breakpoint is hit again — so the panel kept showing the previous stop's values.
 */
@Tag("fx")
class DebugPanelReStopFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static DebugPanel.Actions recording(List<DapModels.StackFrameInfo> selected) {
        return (DebugPanel.Actions) Proxy.newProxyInstance(
                DebugPanel.Actions.class.getClassLoader(), new Class[] {DebugPanel.Actions.class}, (p, m, a) -> {
                    if (m.getName().equals("selectFrame")) {
                        selected.add((DapModels.StackFrameInfo) a[0]);
                    }
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

    private static List<DapModels.StackFrameInfo> stack(int topId, int topLine) {
        Path file = Path.of("loop.py");
        return List.of(
                new DapModels.StackFrameInfo(topId, "work", file, topLine, 1),
                new DapModels.StackFrameInfo(3, "<module>", file, 4, 1));
    }

    @Test
    void aStopWithAnIdenticalTopFrameStillSelectsIt() throws Exception {
        List<DapModels.StackFrameInfo> selected = FxTestSupport.callOnFx(() -> {
            List<DapModels.StackFrameInfo> calls = new ArrayList<>();
            DebugPanel panel = new DebugPanel(recording(calls));
            panel.setState(DapManager.State.SUSPENDED);
            panel.setCallStack(stack(2, 1)); // first hit
            panel.setState(DapManager.State.RUNNING); // Continue
            panel.setState(DapManager.State.SUSPENDED);
            panel.setCallStack(stack(2, 1)); // the same breakpoint again: identical records
            return calls;
        });
        assertEquals(2, selected.size(), "one selectFrame per stop: " + selected);
        assertEquals(2, selected.get(1).id());
    }

    /** Controls: a changed top frame is reported once (not twice), and a user click still goes through. */
    @Test
    void aChangedTopFrameIsReportedExactlyOnceAndClicksStillSelect() throws Exception {
        List<DapModels.StackFrameInfo> selected = FxTestSupport.callOnFx(() -> {
            List<DapModels.StackFrameInfo> calls = new ArrayList<>();
            DebugPanel panel = new DebugPanel(recording(calls));
            panel.setState(DapManager.State.SUSPENDED);
            panel.setCallStack(stack(2, 1));
            panel.setCallStack(stack(7, 2));
            javafx.scene.control.ListView<DapModels.StackFrameInfo> list = FxTestSupport.field(panel, "stack");
            list.getSelectionModel().select(1); // the user picks the caller frame
            return calls;
        });
        assertEquals(
                List.of(2, 7, 3),
                selected.stream().map(DapModels.StackFrameInfo::id).toList());
    }
}
