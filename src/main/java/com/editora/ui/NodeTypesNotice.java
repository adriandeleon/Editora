package com.editora.ui;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.lsp.NodeTypesHint;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The one-time "Node.js APIs have no types in this folder" hint (Node review B9), said where it can be read. It was
 * posted while the file was being opened, so the "Opened …" line, and then the server's "Loaded and ready", replaced
 * it at once: it was only ever in the message log (JS review B108). It now takes the place of the server's ready line
 * for that server root, or, when the server is already running, follows the open.
 */
final class NodeTypesNotice {

    /** Folders the hint was said (or is waiting to be said) for: once each per window. */
    private final Set<Path> hinted = new HashSet<>();

    /** Server roots whose ready line says something else instead: the hint, or the TypeScript note (B109). */
    private final java.util.Map<Path, String> pending = new java.util.HashMap<>();

    /** Server roots the TypeScript-version note was given for (once each per window). */
    private final Set<Path> versionTold = new HashSet<>();

    /**
     * Whether a JavaScript file deserves the hint: it uses Node.js built-ins, no {@code @types/node} is installed
     * above it, type acquisition is off, and its folder has not had it. Pure apart from the file test.
     */
    static boolean wanted(String language, String text, Path path, boolean typeAcquisition) {
        return !typeAcquisition
                && ("javascript".equals(language) || "javascriptreact".equals(language))
                && path.getParent() != null
                && NodeTypesHint.usesNodeApis(text)
                && !NodeTypesHint.hasNodeTypes(path);
    }

    /**
     * A JavaScript file opened under the TypeScript server on {@code root}: the hint, when {@link #wanted}, is said
     * through {@code say} after this event if that server is {@code ready}, else in place of its ready line.
     */
    void opened(
            String language,
            String text,
            Path path,
            @Nullable Path root,
            boolean typeAcquisition,
            boolean ready,
            Consumer<String> say) {
        Path folder = path.getParent();
        if (folder == null || hinted.contains(folder) || !wanted(language, text, path, typeAcquisition)) {
            return;
        }
        hinted.add(folder);
        if (ready || root == null) {
            Platform.runLater(() -> say.accept(tr("status.lsp.nodeTypesHint"))); // after this open's "Opened …"
        } else {
            pending.putIfAbsent(root.toAbsolutePath().normalize(), tr("status.lsp.nodeTypesHint"));
        }
    }

    /**
     * Says, in place of {@code root}'s ready line, that the folder's own TypeScript {@code folderVersion} is not the
     * one running ({@code runningVersion}) because the folder is not trusted: results may differ from the
     * project's own {@code tsc} (JS review B109). Once per root and window.
     */
    void folderTypeScriptIgnored(Path root, String folderVersion, String runningVersion) {
        Path r = root.toAbsolutePath().normalize();
        if (versionTold.add(r)) {
            pending.put(
                    r,
                    tr(
                            "status.lsp.tsFolderVersionIgnored",
                            folderVersion,
                            runningVersion,
                            tr("command.lsp.trustProjectSettings")));
        }
    }

    /** The status line for {@code root}'s server becoming ready: what waits for it, else {@code readyLine}. */
    String atReady(@Nullable Path root, String readyLine) {
        String waiting =
                root == null ? null : pending.remove(root.toAbsolutePath().normalize());
        return waiting != null ? waiting : readyLine;
    }
}
