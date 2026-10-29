package com.editora.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.editora.install.InstallCatalog.Kind;
import com.editora.install.InstallCatalog.Lang;
import com.editora.install.InstallCatalog.Step;
import com.editora.install.PinnedVersions.Freshness;
import com.editora.install.PinnedVersions.Pin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinnedVersionsTest {

    @TempDir
    Path config;

    private static Pin pin(String stepId) {
        return PinnedVersions.forStep(stepId).orElseThrow();
    }

    // --- the table -------------------------------------------------------------------------------------

    @Test
    void everyPinIsACatalogStepAndCarriesTheCatalogsVersion() {
        assertEquals(
                InstallCatalog.JDTLS_VERSION + "." + InstallCatalog.JDTLS_BUILD,
                pin("jdtls").version());
        assertEquals(InstallCatalog.JS_DEBUG_VERSION, pin("js-debug").version());
        assertEquals(InstallCatalog.LEMMINX_MAVEN_VERSION, pin("lemminx-maven").version());
        assertEquals(InstallCatalog.GLS_VERSION, pin("gls").version());
        assertEquals(InstallCatalog.METALS_VERSION, pin("metals").version());
        assertEquals(InstallCatalog.KOTLIN_LSP_VERSION, pin("kotlin-lsp").version());
        assertEquals(InstallCatalog.CLOJURE_LSP_VERSION, pin("clojure-lsp").version());
        assertEquals(InstallCatalog.LEMMINX_VERSION, pin("lemminx").version());
        assertEquals(InstallCatalog.SHELLCHECK_VERSION, pin("shellcheck").version());
        assertEquals(InstallCatalog.SHFMT_VERSION, pin("shfmt").version());
        for (Pin p : PinnedVersions.all()) {
            assertEquals(p.stepId(), p.step().id());
            assertFalse(p.destSubpath().isBlank(), p.stepId() + " installs into a folder of its own");
        }
    }

    @Test
    void everyInstallerOfAnExactReleaseHasAPin() {
        // A new pinned installer without a pin would never be offered an update once its pin moves.
        Set<Kind> exact =
                EnumSet.of(Kind.PINNED_TOOL, Kind.PINNED_SERVER, Kind.JVM_LSP_JAR, Kind.COURSIER_LSP, Kind.JVM_LSP_ZIP);
        List<Step> steps = new ArrayList<>();
        for (Lang lang : Lang.values()) {
            steps.addAll(InstallCatalog.steps(lang));
        }
        for (String server : InstallCatalog.installableServerIds()) {
            steps.addAll(InstallCatalog.serverInstall(server).orElse(List.of()));
        }
        for (String tool : InstallCatalog.PINNED_TOOL_IDS) {
            steps.addAll(InstallCatalog.toolInstall(tool).orElseThrow());
        }
        for (Step s : steps) {
            boolean pinnedRelease = exact.contains(s.kind())
                    || s.id().equals("jdtls")
                    || s.id().equals("js-debug")
                    || s.id().equals(InstallCatalog.WEB_LS_PACKAGE); // npm, pinned with a receipt (HTML A12)
            assertEquals(
                    pinnedRelease,
                    PinnedVersions.forStep(s.id()).isPresent(),
                    s.id() + (pinnedRelease ? " installs an exact release" : " installs whatever is latest"));
        }
    }

    @Test
    void serversFindTheirPin() {
        assertEquals("jdtls", PinnedVersions.forServer("java").orElseThrow().stepId());
        assertEquals(
                "clojure-lsp", PinnedVersions.forServer("clojure").orElseThrow().stepId());
        assertEquals(
                "lemminx-maven",
                PinnedVersions.forServer("maven-pom").orElseThrow().stepId());
        assertEquals("lemminx", PinnedVersions.forServer("xml").orElseThrow().stepId(), "XML review A3");
        for (String web : List.of("html", "css", "json")) {
            // one package, one pin (HTML review A12)
            assertEquals(
                    InstallCatalog.WEB_LS_PACKAGE,
                    PinnedVersions.forServer(web).orElseThrow().stepId(),
                    web);
        }
        assertTrue(PinnedVersions.forServer("python").isEmpty(), "Pyright installs whatever npm has");
        assertTrue(PinnedVersions.forServer(null).isEmpty());
    }

    /** lemminx is shown as the server it is, with the vscode-xml release it came from (XML review A3). */
    @Test
    void lemminxIsLabelledWithTheServerAndTheRelease() {
        assertEquals(
                InstallCatalog.LEMMINX_SERVER_VERSION + " · vscode-xml " + InstallCatalog.LEMMINX_VERSION,
                pin("lemminx").label());
        assertEquals("vscode-xml 0.28.0", PinnedVersions.label("lemminx", "0.28.0"));
    }

    /** Settings shows which lemminx it runs once the install recorded it; another copy just by its path (A3). */
    @Test
    void settingsLeadsEditorasOwnCopyWithItsRecordedVersion(@TempDir Path config) throws IOException {
        Path dir = Files.createDirectories(config.resolve("plugins/lsp/xml"));
        Path program = Files.writeString(dir.resolve("lemminx"), "");
        List<String> argv = List.of(program.toString());

        assertEquals("/p", PinnedVersions.withVersion("xml", argv, config, "/p"), "no receipt: nothing to add");
        new InstallReceipt("lemminx", InstallCatalog.LEMMINX_VERSION, null).write(dir);
        assertEquals(pin("lemminx").label() + " — /p", PinnedVersions.withVersion("xml", argv, config, "/p"));
        assertEquals("/p", PinnedVersions.withVersion("xml", List.of("/usr/bin/lemminx"), config, "/p"));
        assertEquals("/p", PinnedVersions.withVersion("json", argv, config, "/p"), "a server with no pin");
        assertEquals("", PinnedVersions.withVersion("xml", argv, config, ""), "not found: no detail");
    }

    // --- freshness -------------------------------------------------------------------------------------

    @Test
    void freshnessComparesWithThePin() {
        Pin gls = pin("gls");
        assertEquals(Freshness.CURRENT, PinnedVersions.freshness(gls, InstallCatalog.GLS_VERSION));
        assertEquals(Freshness.OUTDATED, PinnedVersions.freshness(gls, "0.4.7"));
        assertEquals(Freshness.CURRENT, PinnedVersions.freshness(gls, "0.5.0"), "newer than the pin is not stale");
        assertEquals(Freshness.UNKNOWN, PinnedVersions.freshness(gls, null));
        assertEquals(Freshness.UNKNOWN, PinnedVersions.freshness(gls, " "));
    }

    @Test
    void theReceiptSaysWhichVersionIsInstalled() throws IOException {
        Pin gls = pin("gls");
        Path dir = Files.createDirectories(config.resolve(gls.destSubpath()));
        Files.writeString(dir.resolve("gls-9.9.9.jar"), "the files say otherwise");
        new InstallReceipt("gls", "0.4.1", null).write(dir);

        assertEquals("0.4.1", PinnedVersions.installedVersion(gls, dir));
        assertEquals(Freshness.OUTDATED, PinnedVersions.installedFreshness(gls, config));
    }

    @Test
    void anotherStepsReceiptDoesNotCount() throws IOException {
        Pin gls = pin("gls");
        Path dir = Files.createDirectories(config.resolve(gls.destSubpath()));
        new InstallReceipt("metals", "0.4.8", null).write(dir);

        assertNull(PinnedVersions.installedVersion(gls, dir));
        assertEquals(Freshness.UNKNOWN, PinnedVersions.installedFreshness(gls, config));
    }

    @Test
    void nothingInstalledHasNoFreshness() {
        assertNull(PinnedVersions.installedFreshness(pin("kotlin-lsp"), config));
        assertNull(PinnedVersions.installedFreshness(pin("kotlin-lsp"), null));
    }

    // --- installs from before receipts -----------------------------------------------------------------

    @Test
    void jdtlsIsReadFromItsCoreBundle() throws IOException {
        Path dir = Files.createDirectories(config.resolve("plugins/lsp/java/plugins"))
                .getParent();
        Files.writeString(dir.resolve("plugins/org.eclipse.jdt.ls.core_1.61.0.202607301809.jar"), "");

        assertEquals("1.61.0.202607301809", PinnedVersions.installedVersion(pin("jdtls"), dir));
        assertEquals(Freshness.OUTDATED, PinnedVersions.installedFreshness(pin("jdtls"), config));
    }

    @Test
    void jarServersAreReadFromTheirJarsName() throws IOException {
        Path maven = Files.createDirectories(config.resolve("plugins/lsp/maven"));
        Files.writeString(maven.resolve("org.eclipse.lemminx-0.30.1.jar"), "");
        Files.writeString(maven.resolve("lemminx-maven-0.11.1.jar"), "");
        Path groovy = Files.createDirectories(config.resolve("plugins/lsp/groovy"));
        Files.writeString(groovy.resolve("gls-0.4.8.jar"), "");
        Path scala = Files.createDirectories(config.resolve("plugins/lsp/scala/lib"));
        Files.writeString(scala.resolve("metals_2.13-1.6.2.jar"), "");

        assertEquals("0.11.1", PinnedVersions.fingerprint("lemminx-maven", maven));
        assertEquals("0.4.8", PinnedVersions.fingerprint("gls", groovy));
        assertEquals("1.6.2", PinnedVersions.fingerprint("metals", scala.getParent()));
    }

    @Test
    void jsDebugIsReadFromItsBundle() throws IOException {
        Path dir = config.resolve("plugins/dap/javascript");
        Path bundle = Files.createDirectories(dir.resolve("js-debug/src")).resolve("dapDebugServer.js");
        Files.writeString(bundle, "else n=r;return n}var Kd=\"js-debug\",Uu=\"1.112.0\",KH=\"ms-vscode\",Q0=");

        assertEquals("1.112.0", PinnedVersions.fingerprint("js-debug", dir));
        assertEquals(Freshness.OUTDATED, PinnedVersions.installedFreshness(pin("js-debug"), config));
    }

    @Test
    void aServerWhoseFilesSayNothingIsUnknown() throws IOException {
        Path kotlin = Files.createDirectories(config.resolve("plugins/lsp/kotlin/server/bin"));
        Files.writeString(kotlin.resolve("kotlin-language-server"), "#!/bin/sh");

        assertEquals(Freshness.UNKNOWN, PinnedVersions.installedFreshness(pin("kotlin-lsp"), config));
    }

    @Test
    void toolsAreReadFromWhatTheyPrint() {
        assertEquals(
                "2026.10.15-11.42.38",
                PinnedVersions.fromVersionOutput(
                        "clojure-lsp", "clojure-lsp 2026.10.15-11.42.38\nclj-kondo 2026.08.05-SNAPSHOT (c50ca52)"));
        assertEquals(
                "0.11.0",
                PinnedVersions.fromVersionOutput(
                        "shellcheck",
                        "ShellCheck - shell script analysis tool\nversion: 0.11.0\nlicense: GNU General Public License"));
        assertEquals("3.14.1", PinnedVersions.fromVersionOutput("shfmt", "v3.14.1\n"));
        assertNull(PinnedVersions.fromVersionOutput("shellcheck", "ShellCheck - shell script analysis tool"));
        assertNull(PinnedVersions.fromVersionOutput("gls", "0.4.8"), "a jar server is not asked");
        assertNull(PinnedVersions.fromVersionOutput("shfmt", null));
    }

    // --- display and ownership -------------------------------------------------------------------------

    @Test
    void jdtlsIsShownWithItsBuildDate() {
        assertEquals("1.61.0 (2026-07-30 build)", PinnedVersions.label("jdtls", "1.61.0.202607301809"));
        assertEquals("0.4.8", PinnedVersions.label("gls", "0.4.8"));
        assertEquals("odd", PinnedVersions.label("jdtls", "odd"));
    }

    @Test
    void aCommandInsideThePinsFolderIsEditorasCopy() throws IOException {
        Path dir = Files.createDirectories(config.resolve("plugins/lsp/maven"));

        assertTrue(PinnedVersions.anyInside(
                List.of("java", "-cp", dir + "/*", "org.eclipse.lemminx.XMLServerLauncher"), dir));
        assertTrue(PinnedVersions.anyInside(List.of(dir.resolve("sub/launcher").toString()), dir));
        assertFalse(PinnedVersions.anyInside(List.of("/usr/bin/lemminx", "-Xmx1g", "relative/path"), dir));
        assertFalse(PinnedVersions.anyInside(List.of(dir + "-other/x"), dir), "a sibling folder is not inside");
        assertFalse(PinnedVersions.anyInside(List.of(dir.toString()), null));
    }

    /**
     * One folder reached two ways — as macOS's {@code /var} is {@code /private/var} — still matches, for a file
     * inside it that does not exist yet as well as for one that does.
     */
    @Test
    void aFolderReachedThroughALinkIsStillTheSameFolder() throws IOException {
        Path realDir = Files.createDirectories(config.resolve("real/plugins/lsp/groovy"));
        Path link = config.resolve("linked");
        try {
            Files.createSymbolicLink(link, config.resolve("real"));
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links are not available here: " + e);
        }
        Path viaLink = link.resolve("plugins/lsp/groovy");

        assertTrue(
                PinnedVersions.anyInside(List.of(viaLink.resolve("not-yet.jar").toString()), realDir));
        assertTrue(
                PinnedVersions.anyInside(List.of(realDir.resolve("not-yet.jar").toString()), viaLink));
        assertTrue(PinnedVersions.anyInside(List.of(viaLink + "/*"), realDir));
    }
}
