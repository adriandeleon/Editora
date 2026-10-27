package com.editora.ui;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiPredicate;

import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import com.editora.config.ConfigManager;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspServerRegistry;
import com.editora.text.LspDiagnostic;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Folder trust for the two lemminx servers (XML/pom review 2: E1, D11, C6).
 *
 * <p>lemminx-maven builds a pom's Maven model: it fetches the parents, BOMs, plugins and extensions the pom names,
 * from the repositories the pom names, and loads plugin classes into its own JVM. An untrusted pom therefore never
 * reaches it: it is served by plain lemminx (schema-only editing, with the bundled POM schema) and its tab offers
 * <b>Trust Folder</b>. A pom outside every trusted folder (a loose one, a {@code .pom} in a repository) stays there.
 * Trusting moves the pom to the Maven server; revoking stops that server on the folder and moves it back.
 *
 * <p>The same trust decides whether lemminx may download the schemas an XML file names ("Download schemas: Only in
 * trusted folders"). A folder without a Maven or Gradle build had no way to be trusted, so its files kept two
 * errors (C6): a blocked download now offers Trust Folder on the file, and {@code workspace.trustFolder} trusts
 * the folder from the palette. One per window, owned by {@link LspCoordinator}.
 */
final class XmlFolderTrust {

    /** The palette command that trusts the active file's folder; the notices' button runs it. */
    static final String COMMAND = "workspace.trustFolder";

    private final LspCoordinator lsp;
    private final CoordinatorHost host;
    private final LspManager lspManager;
    private final LspCoordinator.Ops ops;

    /** Buffers a notice was shown on: dismissing hides it for as long as the tab is open (not on every sync). */
    private final Set<EditorBuffer> offered = Collections.newSetFromMap(new WeakHashMap<>());

    /** What {@link #pomTrusted} answered per file, valid while the trust list and the project are what they were. */
    private final Map<Path, Answer> answers = new HashMap<>();

    private record Answer(int trustList, @Nullable Path project, boolean trusted) {}

    /** Asks before trusting (TEST SEAM: a test answers without a dialog). */
    BiPredicate<Window, Path> confirmer = (owner, root) -> {
        TrustPrompts.Prompt prompt = TrustPrompts.folder(owner, root);
        return prompt.alert().showAndWait().orElse(ButtonType.CANCEL) == prompt.trust();
    };

    XmlFolderTrust(LspCoordinator lsp, CoordinatorHost host, LspManager lspManager, LspCoordinator.Ops ops) {
        this.lsp = lsp;
        this.host = host;
        this.lspManager = lspManager;
        this.ops = ops;
    }

    // --- routing -------------------------------------------------------------------------------------

    /**
     * Whether the pom {@code b} may be served by lemminx-maven: the folder that server would run on (the pom's
     * reactor root) is trusted. Asked on every routing decision, so the answer is kept until trust or the project
     * changes; {@link #trustChanged} forgets it too.
     */
    boolean pomTrusted(EditorBuffer b) {
        Path file = lsp.lspPath(b);
        ConfigManager config = ops.config();
        if (file == null || config == null) {
            return false;
        }
        int trustList = config.getTrustStore().roots.hashCode();
        Path project = ops.lspProjectRoot();
        Answer known = answers.get(file);
        if (known != null && known.trustList() == trustList && Objects.equals(known.project(), project)) {
            return known.trusted();
        }
        Path root = lsp.lspRootFor(b, LspServerRegistry.MAVEN_POM_SERVER_ID);
        boolean trusted = root != null && config.getTrustStore().isTrusted(root);
        answers.put(file, new Answer(trustList, project, trusted));
        return trusted;
    }

    /**
     * Whether {@code b} is a {@code pom.xml} that plain lemminx serves only because its folder is not trusted: the
     * Maven server is on, but this pom may not reach it. The pom install banner offers nothing for such a pom (trust
     * comes first); this class offers Trust Folder.
     */
    boolean pomAwaitsTrust(EditorBuffer b) {
        Path file = b == null ? null : lsp.lspPath(b);
        Path name = file == null ? null : file.getFileName();
        return name != null
                && LspServerRegistry.isPomFile(name.toString())
                && ops.lspFeatureEnabled()
                && lsp.serverEnabled(LspServerRegistry.MAVEN_POM_SERVER_ID)
                && !pomTrusted(b);
    }

    // --- notices -------------------------------------------------------------------------------------

    /** After {@code b} was (re)routed: an untrusted pom says, once per tab, why it is not Maven-aware. */
    void afterSync(EditorBuffer b) {
        if (pomAwaitsTrust(b)) {
            if (offered.add(b)) {
                show(b, tr("xml.trust.pomNotice"));
            }
        } else {
            hideOwn(b);
        }
    }

    /**
     * New diagnostics for {@code b}: a schema lemminx was not allowed to download, in a folder that trusting would
     * let it download from, offers Trust Folder on the file (once per tab). A file outside the project has no
     * folder of its own on the server (it shares the loose root), so it keeps the status line only.
     */
    void onDiagnostics(EditorBuffer b, List<LspDiagnostic> diagnostics) {
        if (b == null
                || offered.contains(b)
                || !blockedDownload(diagnostics)
                || !"trusted".equals(host.settings().getXmlSchemaDownloads())) {
            return;
        }
        Path serverRoot = serverRoot(b);
        Path root = serverRoot == null || isLoose(serverRoot, lspManager.looseRoot()) ? null : target(b);
        ConfigManager config = ops.config();
        if (root == null || config == null || config.getTrustStore().isTrusted(root)) {
            return;
        }
        offered.add(b);
        show(b, tr("xml.trust.downloadNotice"));
    }

    /** Whether one of {@code diagnostics} is lemminx's "Downloading external resources is disabled". */
    static boolean blockedDownload(@Nullable List<LspDiagnostic> diagnostics) {
        if (diagnostics == null) {
            return false;
        }
        for (LspDiagnostic d : diagnostics) {
            if (XmlServerNotices.DOWNLOAD_DISABLED.equals(d.code())) {
                return true;
            }
        }
        return false;
    }

    private void show(EditorBuffer b, String message) {
        b.showFileNotice(message, tr("xml.trust.button"), tr("xml.trust.notNow"), () -> {
            if (b == host.activeBuffer()) {
                ops.executeCommand(COMMAND); // the command broadcasts the decision to every window
            }
        });
    }

    /** Hides a notice this class showed on {@code b}; another feature's notice stays. */
    private static void hideOwn(EditorBuffer b) {
        String shown = b.fileNoticeText();
        if (shown != null
                && (shown.equals(tr("xml.trust.pomNotice")) || shown.equals(tr("xml.trust.downloadNotice")))) {
            b.hideFileNotice();
        }
    }

    // --- trusting ------------------------------------------------------------------------------------

    /**
     * A folder was trusted or revoked (here or in another window): forget the routing answers, stop the Maven
     * server on any folder that is no longer trusted, and route every pom again — a newly trusted one moves to the
     * Maven server, a revoked one back to plain lemminx. Notices that no longer apply go.
     */
    void trustChanged() {
        answers.clear();
        lspManager.shutdownUntrusted(LspServerRegistry.MAVEN_POM_SERVER_ID);
        ConfigManager config = ops.config();
        host.forEachBuffer(b -> {
            Path file = lsp.lspPath(b);
            Path name = file == null ? null : file.getFileName();
            boolean pom = name != null && LspServerRegistry.isPomFile(name.toString());
            Path root = pom ? null : target(b);
            boolean nowTrusted = pom
                    ? pomTrusted(b)
                    : root != null && config != null && config.getTrustStore().isTrusted(root);
            if (nowTrusted) {
                hideOwn(b);
                offered.remove(b); // a later revoke may offer again
            }
            if (pom) {
                lsp.syncBufferWhenShown(b);
            }
        });
    }

    /**
     * {@code workspace.trustFolder}: trusts the folder the active file's XML server runs on — the project, or a
     * pom's own Maven root outside it — after the trust prompt (Cancel is its default). {@code broadcast} tells
     * every window (null: only this one re-reads its trust).
     */
    void trustFolder(@Nullable Runnable broadcast) {
        ConfigManager config = ops.config();
        EditorBuffer b = host.activeBuffer();
        Path root = b == null ? ops.lspProjectRoot() : target(b);
        if (config == null || root == null) {
            host.setStatus(tr("xml.trust.noFolder"));
            return;
        }
        Path name = root.getFileName();
        String shown = name == null ? root.toString() : name.toString();
        if (config.getTrustStore().isTrusted(root)) {
            host.setStatus(tr("xml.trust.already", shown));
            return;
        }
        if (!confirmer.test(host.window(), root)) {
            return;
        }
        config.getTrustStore().trust(root);
        config.saveTrust(); // durable: a security decision must survive a crash
        if (broadcast != null) {
            broadcast.run(); // every window, this one included, re-reads its trust (reloadProjectSettings)
        } else {
            lsp.reloadProjectSettings();
        }
        host.syncSettingsWindow(); // the Workspace page's trusted-folders list
        host.setStatus(tr("xml.trust.trusted", shown));
    }

    /** The folder Trust Folder trusts for {@code b} (see {@link #trustTarget}). */
    @Nullable
    Path target(EditorBuffer b) {
        return trustTarget(ops.lspProjectRoot(), lsp.lspPath(b), serverRoot(b), lspManager.looseRoot());
    }

    /** The folder {@code b}'s lemminx runs on (the Maven server's for a pom), or null for a file no lemminx serves. */
    private @Nullable Path serverRoot(EditorBuffer b) {
        Path file = lsp.lspPath(b);
        Path name = file == null ? null : file.getFileName();
        String serverId = name != null && LspServerRegistry.isPomFile(name.toString())
                ? LspServerRegistry.MAVEN_POM_SERVER_ID
                : LspServerRegistry.serverIdFor(b.getLanguage());
        return file != null && serverId != null && com.editora.lsp.XmlServerSettings.isLemminx(serverId)
                ? lsp.lspRootFor(b, serverId)
                : null;
    }

    private static boolean isLoose(Path root, @Nullable Path looseRoot) {
        return looseRoot != null
                && root.getFileSystem() == looseRoot.getFileSystem()
                && root.toAbsolutePath()
                        .normalize()
                        .equals(looseRoot.toAbsolutePath().normalize());
    }

    /**
     * Pure: the folder to trust for {@code file} — the open project when the file is in it (the folder a build's
     * trust prompt records too); else the folder its XML server runs on, unless that is Editora's shared root of
     * files outside the project (trusting it would trust every loose file); else the project. Null: nothing to trust.
     */
    static @Nullable Path trustTarget(
            @Nullable Path project, @Nullable Path file, @Nullable Path serverRoot, @Nullable Path looseRoot) {
        Path p = project == null ? null : project.toAbsolutePath().normalize();
        Path f = file == null ? null : file.toAbsolutePath().normalize();
        if (p != null && f != null && f.getFileSystem() == p.getFileSystem() && f.startsWith(p)) {
            return project;
        }
        if (serverRoot != null && !isLoose(serverRoot, looseRoot)) {
            return serverRoot;
        }
        return project;
    }
}
