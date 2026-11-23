package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.config.Settings;
import com.editora.doctor.DoctorCheck;
import com.editora.doctor.DoctorService;
import com.editora.doctor.DoctorStatus;
import com.editora.plugin.PluginDescriptor;
import com.editora.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Doctor's Plugins section, built from a plugin manager over real plugin folders. */
class DoctorCoordinatorTest {

    private static void plugin(Path dir, String folder, String manifest) throws Exception {
        Path p = Files.createDirectories(dir.resolve(folder));
        Files.writeString(p.resolve("plugin.json"), manifest);
    }

    @Test
    void noManagerNoRows() {
        assertTrue(DoctorCoordinator.pluginRows(new Settings(), null).isEmpty());
    }

    @Test
    void pluginsOffIsOneGrayRow(@TempDir Path dir) {
        Settings s = new Settings();
        s.setPluginSupport(false);
        List<DoctorService.CheckSpec> rows = DoctorCoordinator.pluginRows(s, new PluginManager(dir, id -> true));
        assertEquals(1, rows.size());
        assertEquals(DoctorStatus.DISABLED, rows.getFirst().placeholder().status());
        assertNull(rows.getFirst().probe(), "nothing to probe");
    }

    @Test
    void theSummaryCountsWhatRunsAndEachTroubledPluginGetsARow(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(PluginManager.disabledForSession());
        plugin(dir, "good", "{ \"id\": \"good\", \"name\": \"Good\" }");
        plugin(dir, "broken", "{ not json");
        plugin(dir, "slow", "{ \"id\": \"slow\", \"name\": \"Slow\" }");
        plugin(dir, "crash", "{ \"id\": \"crash\", \"name\": \"Crash\" }");
        PluginManager plugins = new PluginManager(dir, id -> true);
        plugins.discover();
        plugins.recordRuntimeProblem(
                "slow",
                new PluginDescriptor.LoadProblem(PluginDescriptor.LoadProblem.Kind.SLOW_START, List.of("900"), ""));
        plugins.recordRuntimeProblem(
                "crash",
                new PluginDescriptor.LoadProblem(PluginDescriptor.LoadProblem.Kind.START_FAILED, List.of(), "boom"));
        Settings s = new Settings();
        s.setPluginSupport(true);

        List<DoctorCheck> rows = DoctorCoordinator.pluginRows(s, plugins).stream()
                .map(DoctorService.CheckSpec::placeholder)
                .toList();
        DoctorCheck summary = rows.getFirst();
        assertEquals("plugins", summary.id());
        assertEquals(DoctorStatus.OK, summary.status());
        assertEquals(tr("doctor.plugins.summary", 3L, plugins.descriptors().size()), summary.detail());

        assertEquals(
                List.of("plugin:broken", "plugin:crash", "plugin:slow"),
                rows.subList(1, rows.size()).stream()
                        .map(DoctorCheck::id)
                        .sorted()
                        .toList(),
                "the good plugin has no row");
        DoctorCheck slow = rows.stream()
                .filter(r -> r.id().equals("plugin:slow"))
                .findFirst()
                .orElseThrow();
        assertEquals(DoctorStatus.WARN, slow.status(), "a slow start is a warning");
        DoctorCheck crash = rows.stream()
                .filter(r -> r.id().equals("plugin:crash"))
                .findFirst()
                .orElseThrow();
        assertEquals(DoctorStatus.MISSING, crash.status());
        assertEquals("doctor.tip.pluginProblem", crash.tipKey());
        DoctorCheck broken = rows.stream()
                .filter(r -> r.id().equals("plugin:broken"))
                .findFirst()
                .orElseThrow();
        assertEquals(DoctorStatus.MISSING, broken.status(), "a load error is a problem whatever ran");
    }
}
