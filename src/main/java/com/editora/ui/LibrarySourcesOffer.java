package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspManager;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Offers, once, to download Java library sources. Without them jdtls names a library method's parameters {@code
 * arg0, arg1} in completion and shows no Javadoc: a JavaFX developer's first {@code stage.setRes} completed to
 * {@code setResizable(boolean arg0)} (r/JavaFX dry run, 2026-11-27). Downloading every dependency's sources at
 * import is off by default — it fetches from Maven Central — so the first time a Maven project's Java server is
 * ready, its file says so, with Download Sources, which turns the setting on and imports the project afresh (Clean
 * Java Workspace and Restart: a running jdtls applies the setting only at an import) so the sources come now. A
 * Gradle library's sources come from its build, so a Gradle project is not offered them. Shown once per install
 * ({@link Settings#isJavaSourcesOffered}); Not now only hides it.
 */
final class LibrarySourcesOffer {

    /** The build file of a project whose dependencies' sources the setting fetches (Maven only). */
    static final List<String> BUILD_FILES = List.of("pom.xml");

    private final CoordinatorHost host;
    private final LspManager lspManager;
    private final Runnable applyDownloadSetting;
    private final Runnable reimport;

    LibrarySourcesOffer(CoordinatorHost host, LspManager lspManager, Runnable applyDownloadSetting, Runnable reimport) {
        this.host = host;
        this.lspManager = lspManager;
        this.applyDownloadSetting = applyDownloadSetting;
        this.reimport = reimport;
    }

    /** Whether to offer: downloads off, never offered, and a Java file whose project's build was loaded. Pure. */
    static boolean shouldOffer(boolean downloadOn, boolean offered, boolean javaFile, boolean buildLoaded) {
        return !downloadOn && !offered && javaFile && buildLoaded;
    }

    /** jdtls on {@code root} is ready: offer on the active Java file when {@link #shouldOffer} and it has a build. */
    void atReady(@Nullable Path root) {
        Settings settings = host.settings();
        EditorBuffer b = host.activeBuffer();
        Path file = b == null ? null : b.getPath();
        if (b == null || file == null || root == null) {
            return;
        }
        boolean javaFile = file.toString().endsWith(".java") && file.startsWith(root);
        if (!shouldOffer(
                settings.isJavaDownloadSources(),
                settings.isJavaSourcesOffered(),
                javaFile,
                javaFile && !lspManager.buildNotLoaded(file))) {
            return;
        }
        Path project = root;
        // Whether the root has a build file is a look at the disk: off the FX thread (PERF-01, Pauta 6.2.0).
        com.editora.perf.OffFx.then(
                "LibrarySourcesOffer.build",
                () -> BUILD_FILES.stream().anyMatch(name -> Files.isRegularFile(project.resolve(name))),
                found -> {
                    if (found.or(false) && b == host.activeBuffer() && !settings.isJavaSourcesOffered()) {
                        show(b, settings);
                    }
                });
    }

    private void show(EditorBuffer b, Settings settings) {
        settings.setJavaSourcesOffered(true); // offered once: Not now only hides it
        host.requestSave(); // off the FX thread (PERF-01, Pauta 6.2.0)
        b.showFileNotice(tr("lsp.sourcesOffer.notice"), tr("lsp.sourcesOffer.button"), tr("xml.trust.notNow"), () -> {
            settings.setJavaDownloadSources(true);
            host.requestSave();
            host.syncSettingsWindow(); // the LSP page's "Download library sources" row
            applyDownloadSetting.run(); // every running Java server is told
            b.hideFileNotice();
            host.setStatus(tr("status.lsp.sourcesOffer.on"));
            reimport.run(); // Clean Java Workspace and Restart: the fresh import fetches the sources
        });
    }
}
