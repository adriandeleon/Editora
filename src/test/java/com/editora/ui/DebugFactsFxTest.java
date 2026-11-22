package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.perf.Looked;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link DebugFacts}: a look that fails (a remote host gone) stops its launch and says why, instead of starting
 * another look forever; another session's launch looks at the same time as one already looking.
 */
@Tag("fx")
class DebugFactsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void realPlanner() {
        DebugPlanning.planner = (name, task) -> Thread.ofVirtual().name(name).start(task);
    }

    @Test
    void aLookThatFailsSaysWhyAndIsNotTriedAgainForever() throws Exception {
        DebugPlanning.planner = (name, task) -> task.run();
        Looked<String, Boolean> disk = new Looked<>("test-look", key -> {
            throw new java.io.UncheckedIOException(new java.io.IOException("host gone"));
        });
        List<String> said = new ArrayList<>();
        int[] again = {0};
        DebugFacts facts = new DebugFacts((tool, root) -> {}, () -> said.add("busy"), why -> said.add("failed"));
        FxTestSupport.runOnFx(() -> {
            Object session = new Object();
            Runnable[] retry = new Runnable[1];
            retry[0] = () -> {
                again[0]++;
                facts.known("look", session, () -> disk.get("x"), retry[0]);
            };
            assertFalse(facts.known("look", session, () -> disk.get("x"), retry[0]));
        });
        assertEquals(List.of("failed"), said, "said once");
        assertEquals(0, again[0], "the launch did not look again");
    }

    @Test
    void anotherSessionLooksWhileOneIsLookingTheSameOneIsBusy() throws Exception {
        List<Runnable> held = new ArrayList<>();
        DebugPlanning.planner = (name, task) -> held.add(task);
        Looked<String, Boolean> disk = new Looked<>("test-look-2", key -> true);
        List<String> said = new ArrayList<>();
        DebugFacts facts = new DebugFacts((tool, root) -> {}, () -> said.add("busy"), why -> said.add("failed"));
        Object one = new Object();
        Object two = new Object();
        FxTestSupport.runOnFx(() -> {
            facts.known("look", one, () -> disk.get("a"), () -> {});
            facts.known("look", two, () -> disk.get("b"), () -> {});
            facts.known("look", one, () -> disk.get("a"), () -> {});
        });
        assertEquals(2, held.size(), "one look per session");
        assertEquals(List.of("busy"), said, "the same session's second launch is refused");
    }
}
