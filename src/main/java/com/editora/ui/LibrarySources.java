package com.editora.ui;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javafx.stage.FileChooser;
import javafx.stage.Window;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.JavaLibrarySources;
import com.editora.lsp.LspManager;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The sources of the library classes Go to Definition opens: a class file whose library has none is decompiled, and
 * its tab says so with Download Sources (jdtls looks for the library's sources jar in the local repository and on
 * Maven Central) and, when none is found, Choose Sources… (a {@code -sources.jar} or zip on disk). A tab whose library
 * got sources later, by either or by jdtls's own download, shows them. {@link LspCoordinator}'s, apart for its size.
 */
final class LibrarySources {

    /** How long Download Sources waits for jdtls's download before it offers Choose Sources…. */
    static final long DOWNLOAD_WAIT_MS = 20_000;

    /** An open library tab: its class file and the project file whose Java server serves it. */
    private record Tab(String uri, Path anchor) {}

    private final LspManager lspManager;
    private final Consumer<String> status;
    private final Map<EditorBuffer, Tab> tabs = new java.util.WeakHashMap<>();
    private final Map<EditorBuffer, javafx.animation.PauseTransition> waiting = new java.util.WeakHashMap<>();

    LibrarySources(LspManager lspManager, Consumer<String> status) {
        this.lspManager = lspManager;
        this.status = status;
        lspManager.setOnSourceInvalidated(this::sourcesAttached);
    }

    /** A library tab was opened on {@code uri}: when its library has no sources, its bar offers them. */
    void opened(EditorBuffer buffer, @Nullable String uri, @Nullable Path anchor) {
        if (uri == null || anchor == null || JavaLibrarySources.archiveName(uri) == null) {
            return;
        }
        tabs.put(buffer, new Tab(uri, anchor));
        resolve(buffer, attachment -> {
            if (attachment != null && attachment.error() == null && !attachment.attached()) {
                offerDownload(buffer);
            }
        });
    }

    /** Whether {@code buffer} is a library tab this knows. */
    boolean isLibraryTab(@Nullable EditorBuffer buffer) {
        return buffer != null && tabs.containsKey(buffer);
    }

    /** Download Sources on {@code buffer}'s library ({@code lsp.downloadLibrarySources}). */
    void download(@Nullable EditorBuffer buffer) {
        if (buffer == null || !tabs.containsKey(buffer)) {
            status.accept(tr("status.librarySources.notLibrary"));
            return;
        }
        Tab tab = tabs.get(buffer);
        String jar = JavaLibrarySources.archiveName(tab.uri());
        buffer.hideFileNotice();
        status.accept(tr("status.librarySources.looking", jar));
        // jdtls looks for the sources when a class file without them is read, waiting a moment; a download that
        // takes longer ends in its SourceInvalidated event (sourcesAttached)
        lspManager.classFileContents(
                tab.anchor(),
                tab.uri(),
                content -> resolve(buffer, attachment -> {
                    if (attachment != null && attachment.attached()) {
                        reload(buffer);
                        return;
                    }
                    var wait = new javafx.animation.PauseTransition(javafx.util.Duration.millis(DOWNLOAD_WAIT_MS));
                    wait.setOnFinished(e -> {
                        waiting.remove(buffer);
                        resolve(buffer, again -> {
                            if (again != null && again.attached()) {
                                reload(buffer);
                            } else {
                                status.accept(tr("status.librarySources.notFound", jar));
                                offerChoose(buffer);
                            }
                        });
                    });
                    waiting.put(buffer, wait);
                    wait.play();
                }));
    }

    /** Choose Sources… for {@code buffer}'s library ({@code lsp.attachLibrarySources}): a sources archive on disk. */
    void choose(@Nullable EditorBuffer buffer) {
        if (buffer == null || !tabs.containsKey(buffer)) {
            status.accept(tr("status.librarySources.notLibrary"));
            return;
        }
        Tab tab = tabs.get(buffer);
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("librarySources.chooseTitle", JavaLibrarySources.archiveName(tab.uri())));
        chooser.getExtensionFilters()
                .addAll(
                        new FileChooser.ExtensionFilter(tr("librarySources.archives"), "*.jar", "*.zip"),
                        new FileChooser.ExtensionFilter(tr("librarySources.allFiles"), "*.*"));
        Window window = buffer.getArea().getScene() == null
                ? null
                : buffer.getArea().getScene().getWindow();
        java.io.File chosen = chooser.showOpenDialog(window);
        if (chosen == null) {
            return;
        }
        resolve(
                buffer,
                current -> lspManager.executeCommand(
                        tab.anchor(),
                        JavaLibrarySources.UPDATE,
                        JavaLibrarySources.request(
                                tab.uri(), current == null ? null : current.jarPath(), chosen.getAbsolutePath()),
                        (result, error) -> {
                            JavaLibrarySources.Attachment done =
                                    error == null ? JavaLibrarySources.attachment(result) : null;
                            if (done == null || done.error() != null) {
                                status.accept(tr(
                                        "status.librarySources.attachFailed",
                                        done == null || done.error() == null ? String.valueOf(error) : done.error()));
                                return;
                            }
                            reload(buffer);
                        }));
    }

    /** jdtls attached sources to the libraries at {@code roots}: their open tabs show them. */
    private void sourcesAttached(Set<String> roots) {
        new java.util.ArrayList<>(tabs.entrySet())
                .stream()
                        .filter(e -> JavaLibrarySources.inAny(e.getValue().uri(), roots))
                        .forEach(e -> reload(e.getKey()));
    }

    /** Reads {@code buffer}'s class file again, its caret kept on its line, the bar gone once it has sources. */
    private void reload(EditorBuffer buffer) {
        Tab tab = tabs.get(buffer);
        javafx.animation.PauseTransition wait = waiting.remove(buffer);
        if (wait != null) {
            wait.stop();
        }
        if (tab == null) {
            return;
        }
        lspManager.classFileContents(tab.anchor(), tab.uri(), content -> {
            if (content == null) {
                return;
            }
            if (!content.equals(buffer.getArea().getText())) {
                int line = buffer.getArea().getCurrentParagraph();
                buffer.setContent(content);
                buffer.getArea()
                        .moveTo(Math.min(line, buffer.getArea().getParagraphs().size() - 1), 0);
                buffer.getArea().requestFollowCaret();
            }
            resolve(buffer, attachment -> {
                if (attachment != null && attachment.attached()) {
                    buffer.hideFileNotice();
                    status.accept(tr("status.librarySources.attached", JavaLibrarySources.archiveName(tab.uri())));
                }
            });
        });
    }

    private void offerDownload(EditorBuffer buffer) {
        Tab tab = tabs.get(buffer);
        if (tab != null) {
            buffer.showFileNotice(
                    tr("librarySources.none", JavaLibrarySources.archiveName(tab.uri())),
                    tr("librarySources.download"),
                    tr("librarySources.dismiss"),
                    () -> download(buffer));
        }
    }

    private void offerChoose(EditorBuffer buffer) {
        Tab tab = tabs.get(buffer);
        if (tab != null) {
            buffer.showFileNotice(
                    tr("librarySources.notFound", JavaLibrarySources.archiveName(tab.uri())),
                    tr("librarySources.choose"),
                    tr("librarySources.dismiss"),
                    () -> choose(buffer));
        }
    }

    /** jdtls's answer to which sources {@code buffer}'s library has; null when it gives none. */
    private void resolve(EditorBuffer buffer, Consumer<JavaLibrarySources.@Nullable Attachment> cb) {
        Tab tab = tabs.get(buffer);
        if (tab == null) {
            cb.accept(null);
            return;
        }
        lspManager.executeCommand(
                tab.anchor(),
                JavaLibrarySources.RESOLVE,
                JavaLibrarySources.request(tab.uri(), null, null),
                (result, error) -> cb.accept(error == null ? JavaLibrarySources.attachment(result) : null));
    }
}
