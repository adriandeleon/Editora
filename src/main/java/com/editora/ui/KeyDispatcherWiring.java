package com.editora.ui;

import com.editora.editor.EditorBuffer;

/**
 * How a window is wired to the key dispatcher: M-g closing a focused tool window, the prefix-key hint, keyboard
 * macros, and the prefix argument (C-u) for the commands that take a count. It lived inside {@code MainController};
 * it is a file of its own only because of that file's size (see {@link WindowCommandHost}).
 */
final class KeyDispatcherWiring {

    private final MainController mc;

    KeyDispatcherWiring(MainController mc) {
        this.mc = mc;
    }

    /**
     * Wires the key dispatcher's first-look hook so that <b>M-g</b>, while a tool window is focused,
     * closes that window and returns focus to the editor (instead of starting the go-to prefix).
     */
    void setKeyDispatcher(com.editora.command.KeyDispatcher dispatcher) {
        mc.toolWindows.setFloatingSceneSetup(dispatcher::install);
        PrefixKeyHint hint = mc.prefixHint;
        if (hint != null) {
            dispatcher.setPrefixListener(hint::prefixChanged); // C-x and a moment: the keys that can follow
        }
        // Keyboard macros: the coordinator is told what was typed and which keys were left to the focused
        // control while recording, and the dispatcher stands aside for the keys a replay sends.
        if (mc.macroCoordinator != null) {
            dispatcher.setMacroCapture(mc.macroCoordinator);
        }
        dispatcher.setPrefixArgumentSupport(
                id -> "edit.setMark".equals(id)
                        || "edit.copyToRegister".equals(id) // C-u cuts the region instead of repeating
                        || mc.toolWindows.isKeyboardCountAware(id)
                        || (mc.macroCoordinator != null && mc.macroCoordinator.isCountAware(id)),
                arg -> mc.toolWindows.setKeyboardPrefixArgument(mc.editing.currentPrefixArg = arg),
                mc.editing::selfInsertRepeat);
        dispatcher.setPreDispatch((token, target) -> {
            if (!"M-g".equals(token)) {
                return false;
            }
            ToolWindow tw = mc.toolWindows.toolWindowOf(target);
            if (tw == null) {
                return false;
            }
            mc.toolWindows.close(tw);
            EditorBuffer b = mc.activeBuffer();
            if (b != null) {
                b.getFocusedArea().requestFocus();
            }
            return true;
        });
    }
}
