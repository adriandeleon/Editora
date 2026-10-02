package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowDisposalTest {

    @Test
    void aThrowingStepDoesNotSkipTheStepsAfterIt() {
        List<String> ran = new ArrayList<>();

        int failed = WindowDisposal.runAll(
                () -> ran.add("language servers"),
                () -> {
                    throw new IllegalStateException("git shutdown failed");
                },
                () -> ran.add("autosave thread"),
                () -> {
                    throw new NoClassDefFoundError("a class that failed to load mid-shutdown");
                },
                () -> ran.add("file watcher"));

        assertEquals(List.of("language servers", "autosave thread", "file watcher"), ran);
        assertEquals(2, failed);
    }

    @Test
    void anOwnerThatWasNeverCreatedIsSkipped() {
        List<String> ran = new ArrayList<>();

        int failed = WindowDisposal.runAll(null, () -> ran.add("present"), null);

        assertEquals(List.of("present"), ran);
        assertEquals(0, failed);
    }
}
