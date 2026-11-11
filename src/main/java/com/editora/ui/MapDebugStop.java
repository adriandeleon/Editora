package com.editora.ui;

import java.util.List;

import com.editora.dap.DapModels;
import org.jspecify.annotations.Nullable;

/**
 * What the debugger tells the Project Map (Map gaps round 3, item 2): the frame it shows — where the program stopped,
 * or the frame picked since — with the stopped thread's call stack, top first, and whether it stopped on an exception
 * there; no frame while the program runs or no session is paused.
 */
record MapDebugStop(DapModels.@Nullable StackFrameInfo frame, List<DapModels.StackFrameInfo> stack, boolean exception) {

    /** Nothing is paused. */
    static final MapDebugStop RUNNING = new MapDebugStop(null, List.of(), false);

    MapDebugStop {
        stack = List.copyOf(stack);
    }
}
