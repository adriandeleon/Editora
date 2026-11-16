package com.editora.dap;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Go's goroutine options as Delve is told them ({@link GoGoroutines}). */
class GoGoroutinesTest {

    @Test
    void aGoLaunchGetsTheOptions() {
        Map<String, Object> launch = Map.of("type", "go", "request", "launch", "mode", "debug");
        Map<String, Object> out = GoGoroutines.decorate(
                launch, new GoGoroutines.Options(true, List.of("job", "region"), "-with label job=*"));
        assertEquals(true, out.get("hideSystemGoroutines"));
        assertEquals(List.of("job", "region"), out.get("showPprofLabels"));
        assertEquals("-with label job=*", out.get("goroutineFilters"));
        assertEquals("debug", out.get("mode"), "the rest as it was");
    }

    @Test
    void whatIsOffIsNotSentAndALaunchJsonsOwnIsKept() {
        Map<String, Object> plain = GoGoroutines.decorate(
                Map.of("type", "go", "request", "attach"), new GoGoroutines.Options(false, List.of(), " "));
        assertFalse(plain.containsKey("hideSystemGoroutines"));
        assertFalse(plain.containsKey("showPprofLabels"));
        assertFalse(plain.containsKey("goroutineFilters"));
        Map<String, Object> own = GoGoroutines.decorate(
                Map.of("type", "go", "request", "launch", "hideSystemGoroutines", false), GoGoroutines.Options.DEFAULT);
        assertEquals(false, own.get("hideSystemGoroutines"), "launch.json's own");
        Map<String, Object> python = Map.of("type", "python", "request", "launch");
        assertSame(python, GoGoroutines.decorate(python, GoGoroutines.Options.DEFAULT), "Delve's only");
    }

    @Test
    void aRunningSessionIsToldInDelvesConsole() {
        assertEquals(
                List.of(
                        "dlv config hideSystemGoroutines false",
                        "dlv config goroutineFilters -with label job=resize-1",
                        "dlv config showPprofLabels -clear",
                        "dlv config showPprofLabels job"),
                GoGoroutines.liveCommands(new GoGoroutines.Options(false, List.of("job"), "-with label job=resize-1")));
        assertEquals(
                List.of(
                        "dlv config hideSystemGoroutines true",
                        "dlv config goroutineFilters ",
                        "dlv config showPprofLabels -clear",
                        "dlv config showPprofLabels *"),
                GoGoroutines.liveCommands(GoGoroutines.Options.DEFAULT),
                "an empty filter clears it");
    }

    @Test
    void theLabelsSettingNamesKeys() {
        assertEquals(List.of("job", "region"), GoGoroutines.labels(" job, region job "));
        assertEquals(List.of("*"), GoGoroutines.labels("*"));
        assertEquals(List.of(), GoGoroutines.labels(""));
    }
}
