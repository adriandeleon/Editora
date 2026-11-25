package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.i18n.Messages;
import com.editora.toolchain.JkitCli;
import com.editora.toolchain.ToolInstall;
import com.editora.toolchain.ToolRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The words and names behind Tools ▸ Java Toolchain, with no window. */
class ToolchainCommandsTest {

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    private static ToolInstall install(
            String tool, String version, String vendor, boolean fx, String source, String id) {
        return new ToolInstall(tool, version, vendor, fx, Path.of("/t/" + id), source, id);
    }

    @Test
    void sdkmanrcNamesEachInstallTheWaySdkmanDoes() {
        assertEquals(
                "21.0.9-tem",
                ToolchainCommands.sdkmanValue(install("jdk", "21.0.9", "temurin", false, "sdkman", "21.0.9-tem")));
        assertEquals(
                "21.0.9-tem",
                ToolchainCommands.sdkmanValue(install("jdk", "21.0.9+10", "temurin", false, "jkit", "x")));
        assertEquals(
                "21.0.9.fx-zulu", ToolchainCommands.sdkmanValue(install("jdk", "21.0.9", "zulu", true, "other", "x")));
        assertNull(ToolchainCommands.sdkmanValue(install("jdk", "21.0.9", "redhat", false, "other", "x")));
        assertEquals(
                "3.9.11", ToolchainCommands.sdkmanValue(install("maven", "3.9.11", null, false, "jkit", "3.9.11")));
        assertEquals("8.14", ToolchainCommands.sdkmanValue(install("gradle", "8.14+1", null, false, "jkit", "x")));
        assertNull(ToolchainCommands.sdkmanValue(install("maven", "3.9.11", null, false, "other", "x")));
    }

    @Test
    void projectSettingsNameEachInstallSoAnotherMachineFindsIt() {
        assertEquals(
                "21.0.9+10-temurin",
                ToolchainCommands.settingsValue(
                        install("jdk", "21.0.9+10", "temurin", false, "jkit", "21.0.9+10-temurin")));
        assertEquals(
                "21.0.9-zulu-fx",
                ToolchainCommands.settingsValue(install("jdk", "21.0.9", "zulu", true, "other", "z")));
        assertEquals(
                "21.0.9-redhat",
                ToolchainCommands.settingsValue(install("jdk", "21.0.9", "redhat", false, "other", "r")));
        assertEquals(
                Path.of("/t/j").toString(),
                ToolchainCommands.settingsValue(install("jdk", "21", null, false, "other", "j")));
        assertEquals(
                Path.of("/t/m").toString(),
                ToolchainCommands.settingsValue(install("maven", "3", null, false, "other", "m")));
    }

    @Test
    void jkitInstallsOnlyWhatItCanName() {
        assertEquals(
                List.of("install", "jdk@21.0.9", "temurin"),
                ToolchainCommands.jkitArgs(ToolRequest.parse("jdk", "21.0.9-tem")));
        assertNull(ToolchainCommands.jkitArgs(ToolRequest.parse("jdk", "22.1.0-xyz"))); // a vendor jkit cannot name
        assertNull(ToolchainCommands.jkitArgs(ToolRequest.parse("jdk", "/opt/jdk")));
        assertEquals(List.of("install", "jdk@21"), ToolchainCommands.jkitArgs(ToolRequest.parse("jdk", "21")));
        assertEquals(
                List.of("install", "maven@3.9.11"), ToolchainCommands.jkitArgs(ToolRequest.parse("maven", "3.9.11")));
    }

    @Test
    void theQuestionsNameWhatTheyAreAbout() {
        ToolInstall jdk = install("jdk", "21.0.9+10", "temurin", false, "jkit", "21.0.9+10-temurin");
        Confirm.Question remove = ToolchainCommands.removeQuestion(jdk);
        assertTrue(remove.header().contains("JDK 21.0.9+10 · temurin"));
        assertTrue(remove.message().contains(jdk.home().toString()));
        assertEquals("Remove", remove.action());

        Confirm.Choice where = ToolchainCommands.whereQuestion(jdk, "21.0.9-tem");
        assertTrue(where.message().contains("java=21.0.9-tem"));
        assertEquals(List.of("Write jkit.toml", "Write .sdkmanrc", "Editora Project Settings"), where.answers());

        Confirm.Question upgrade = ToolchainCommands.upgradeQuestion(List.of(
                new JkitCli.UpgradeLine("jdk", 21, "temurin", false, "21.0.5+11", "21.0.9+10", "available", null),
                new JkitCli.UpgradeLine("maven", 3, null, false, "3.9.9", "3.9.11", "available", null)));
        assertTrue(upgrade.header().startsWith("2 newer builds"));
        assertTrue(upgrade.message().contains("• JDK 21 temurin: 21.0.5+11 → 21.0.9+10"));
        assertTrue(upgrade.message().contains("• Maven 3: 3.9.9 → 3.9.11"));
        assertTrue(ToolchainCommands.upgradeQuestion(
                        List.of(new JkitCli.UpgradeLine("gradle", 8, null, false, "8.1", "8.14", "available", null)))
                .header()
                .startsWith("One newer build"));
    }

    @Test
    void theChoiceGoesWhereTheProjectAlreadyKeepsIt() {
        assertEquals(ToolchainCommands.Where.JKIT_TOML, ToolchainCommands.preferredWhere(true, true, true));
        assertEquals(ToolchainCommands.Where.SDKMANRC, ToolchainCommands.preferredWhere(false, true, true));
        assertEquals(ToolchainCommands.Where.JKIT_TOML, ToolchainCommands.preferredWhere(false, false, true));
        assertEquals(ToolchainCommands.Where.SDKMANRC, ToolchainCommands.preferredWhere(true, false, false));
    }

    @Test
    void aPinNamesExactlyTheInstall() {
        ToolInstall zuluFx = install("jdk", "21.0.9+10", "zulu", true, "jkit", "21.0.9+10-zulu-fx");
        assertEquals(new JkitCli.PinSpec("jdk", "21.0.9+10", "zulu", true), ToolchainCommands.pinFor("jdk", zuluFx));
        ToolInstall graal = install("jdk", "25.0.1", "graalvm-ce", false, "jkit", "25.0.1-graalvm-ce");
        assertEquals(
                new JkitCli.PinSpec("graalvm", "25.0.1", "graalvm-ce", false),
                ToolchainCommands.pinFor("graalvm", graal));
        ToolInstall sbt = install("sbt", "1.11.7", null, false, "jkit", "1.11.7");
        assertEquals(new JkitCli.PinSpec("sbt", "1.11.7", null, false), ToolchainCommands.pinFor("sbt", sbt));
    }

    @Test
    void jkitsAnswersReadAsPopups() {
        JkitCli.Status status = new JkitCli.Status(
                java.nio.file.Path.of("/src/app"),
                List.of("/src/app/jkit.toml"),
                List.of("jkit.toml:3: unknown tool 'ant', skipped"),
                null,
                null,
                List.of(
                        new JkitCli.StatusTool(
                                "jdk",
                                new JkitCli.PinSpec("jdk", "21", null, false),
                                null,
                                "project",
                                null,
                                null,
                                "jkit sync"),
                        new JkitCli.StatusTool(
                                "maven", null, null, "global", "/m", "/h/.jkit/tools/maven/3.9.11", null),
                        new JkitCli.StatusTool("gradle", null, null, "none", null, null, null)));
        String text = ToolchainCommands.statusText(status);
        // The project's folder as this system writes it (\src\app on Windows).
        assertTrue(text.startsWith("Project " + java.nio.file.Path.of("/src/app") + " (jkit.toml)"), text);
        assertTrue(text.contains("! jkit.toml:3: unknown tool 'ant', skipped"));
        assertTrue(text.contains("JDK: pins 21 → not installed"));
        assertTrue(text.contains("\n    jkit sync"));
        assertTrue(text.contains("Maven: global default /h/.jkit/tools/maven/3.9.11"));
        assertTrue(text.contains("Gradle: none"));

        List<JkitCli.DoctorCheck> checks = List.of(
                new JkitCli.DoctorCheck("home", "ok", "jkit 0.4.0", null),
                new JkitCli.DoctorCheck("hook", "warn", "hook not loaded", "jkit hook --install"));
        assertEquals("One thing to look at.", ToolchainCommands.doctorHeader(checks));
        assertEquals(
                "✓ jkit 0.4.0\n! hook not loaded\n    Fix: jkit hook --install", ToolchainCommands.doctorText(checks));
        assertEquals(
                "2 checks failed.",
                ToolchainCommands.doctorHeader(List.of(
                        new JkitCli.DoctorCheck("a", "fail", "x", null),
                        new JkitCli.DoctorCheck("b", "fail", "y", null))));

        Confirm.Question prune = ToolchainCommands.pruneQuestion(new JkitCli.Prune(
                List.of(install("jdk", "21.0.5+11", "temurin", false, "jkit", "21.0.5+11-temurin")),
                List.of("/h/.jkit/tmp/install-1"),
                List.of(),
                200L * 1024 * 1024));
        assertEquals("Free 200 MB?", prune.header());
        assertTrue(prune.message().contains("• JDK 21.0.5+11 · temurin"));
        assertTrue(prune.message().contains("one leftover"));

        Confirm.Question unverified = ToolchainCommands.unverifiedQuestion(List.of(
                new JkitCli.SyncLine("jdk", "would-install", "21.0.9+10 temurin"),
                new JkitCli.SyncLine("jdk", "would-refuse", "21.0.9 openlogic")));
        assertTrue(unverified.message().startsWith("• JDK 21.0.9 openlogic\n"), unverified.message());
        assertEquals("Install Anyway", unverified.action());
    }

    @Test
    void doctorsJkitRowNamesTheFirstFailureAndItsFix() {
        assertNull(DoctorCoordinator.jkitFailure(List.of(new JkitCli.DoctorCheck("hook", "warn", "no hook", "x"))));
        assertEquals(
                "global maven is gone — jkit use maven@3.9.11 --global",
                DoctorCoordinator.jkitFailure(List.of(
                        new JkitCli.DoctorCheck("home", "ok", "fine", null),
                        new JkitCli.DoctorCheck(
                                "links", "fail", "global maven is gone", "jkit use maven@3.9.11 --global"))));
    }

    @Test
    void jkitsVendorsForGraalvmAreGraalvmBased() {
        assertTrue(ToolchainCommands.vendorChoices(false).contains("temurin"));
        assertTrue(ToolchainCommands.vendorChoices(true).stream().allMatch(ToolInstall::graalvmVendor));
        assertTrue(ToolchainCommands.vendorChoices(true).contains("mandrel"));
    }

    @Test
    void theSettingsDialogWritesOnlyWhatChanged() {
        JkitCli.ConfigSetting fromFile =
                new JkitCli.ConfigSetting("jdk.vendor", "zulu", "file", "JKIT_JDK_VENDOR", "temurin", "");
        JkitCli.ConfigSetting fromDefault =
                new JkitCli.ConfigSetting("graalvm.vendor", "graalvm-ce", "default", null, "graalvm-ce", "");
        assertEquals("zulu", JkitSettingsDialog.initial(fromFile));
        assertEquals("", JkitSettingsDialog.initial(fromDefault), "jkit's default is shown as unset");
        assertEquals(
                java.util.Map.of("jdk.vendor", "", "shell.auto_switch", "true"),
                JkitSettingsDialog.changes(List.of(
                        new JkitSettingsDialog.Row("jdk.vendor", "zulu", () -> ""),
                        new JkitSettingsDialog.Row("graalvm.vendor", "", () -> " "),
                        new JkitSettingsDialog.Row("shell.auto_switch", "", () -> "true"))));
    }

    @Test
    void availableBuildsReadAsAPickerRow() {
        assertEquals(
                "JDK 25 · temurin · 25.0.1+8 · LTS",
                ToolchainCommands.availableLabel(
                        new JkitCli.Available("jdk", "25.0.1+8", 25, "temurin", false, true, true, false, false)));
        assertEquals(
                "JDK 23 · zulu · 23.0.2 · end of life",
                ToolchainCommands.availableLabel(
                        new JkitCli.Available("jdk", "23.0.2", 23, "zulu", false, false, false, false, false)));
        assertEquals(
                "Maven · 3.9.11",
                ToolchainCommands.availableLabel(
                        new JkitCli.Available("maven", "3.9.11", 3, null, false, false, true, false, true)));
        assertEquals("Gradle", ToolchainCommands.toolName("gradle"));
        assertEquals("ant", ToolchainCommands.toolName("ant"));
    }
}
