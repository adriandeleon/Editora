package com.editora.ui;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import com.editora.build.BuildExecutable;
import com.editora.build.BuildService;
import com.editora.build.OutputStyle;
import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import com.editora.doctor.DoctorProbes;
import com.editora.lsp.JavaRuntimes;
import com.editora.maven.ArchetypeCatalog;
import com.editora.maven.ArchetypeCatalogCache;
import com.editora.maven.ArchetypeCatalogParser;
import com.editora.maven.ArchetypeDescriptor;
import com.editora.maven.ArchetypeGenerate;
import com.editora.maven.CentralVersions;
import com.editora.maven.MavenArchetype;
import com.editora.maven.MavenCoordinates;
import com.editora.maven.MavenPluginGoals;
import com.editora.maven.MavenProjectExtras;
import com.editora.maven.MavenProjectSpec;
import com.editora.maven.PomEdits;
import com.editora.maven.PomUpgrades;
import com.editora.plugin.PluginRegistry;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The <b>New Maven Project</b> wizard: pick an archetype, fill in coordinates, run
 * {@code mvn archetype:generate}, then register the result as an Editora project and open it in its own
 * window. The {@link CoordinatorHost} pattern, like the other feature coordinators.
 *
 * <p>Generation always shells out to Maven — there is no hand-written pom — so the feature is <b>inert
 * without {@code mvn} on PATH</b>. That is checked <em>before</em> the wizard opens rather than after the
 * user has filled in five fields.
 *
 * <p>The pure decisions (coordinate validation, package derivation, the argv) live in {@code com.editora.maven}
 * so they are unit-tested without a toolkit; this class is the wiring.
 */
final class MavenProjectCoordinator {

    /**
     * How long the catalog body may take to stream. Maven Central's is 18 MB (October 2026) and grows about
     * 1 MB a year; the byte cap is {@link ArchetypeCatalogParser#MAX_CATALOG_BYTES}, the request timeout only
     * covers the headers, and this is what stops a trickling server holding "Loading…" forever.
     */
    private static final Duration CATALOG_DEADLINE = Duration.ofMinutes(3);

    private static final String DEFAULT_VERSION = "1.0-SNAPSHOT";

    /** The answer to the archetype consent prompt. */
    enum Consent {
        /** Generate this once; ask again next time. */
        ONCE,
        /** Generate, and do not ask again for this archetype while Editora runs. */
        ALWAYS,
        CANCEL
    }

    /** Window hooks beyond {@link CoordinatorHost} that this feature needs. */
    interface Ops {
        /** Where the wizard starts when no folder was named (active file's dir → project root → home). */
        @Nullable
        Path defaultParentDir();

        /**
         * The open buffer for {@code file}, or null when it is not open in this window.
         *
         * <p>A version update reads <em>and</em> writes through it: read, so the versions are computed from
         * what the user is looking at — unsaved edits included — rather than from the file on disk; write,
         * so the update is one undoable edit that a plain Ctrl-Z takes back. Writing the file instead makes
         * the change arrive through the external-change prompt, where the editor's undo history knows
         * nothing about it and there is no way back short of git.
         */
        com.editora.editor.@Nullable EditorBuffer openBuffer(Path file);

        /**
         * Registers {@code root} as a project and opens it in its own window.
         *
         * @param main the entry point found in the generated sources, or null — when non-null the new
         *     window is seeded with a ready-to-run configuration named after the project, and opens on the
         *     class that will run (jdtls needs it open to resolve a classpath)
         */
        void openProject(Path root, String name, com.editora.maven.GeneratedProject.@Nullable MainClass main);

        /**
         * Whether {@link #openProject} opens the project in a window of its own. When it does, the generated
         * {@code pom.xml} is seeded into <em>that</em> window's session rather than opened here: the pom
         * used to land as a foreign tab in the window the user started from.
         */
        default boolean projectsEnabled() {
            return false;
        }

        /** The root of this window's project, or null — so a module generated inside it stays inside it. */
        default @Nullable Path currentProjectRoot() {
            return null;
        }

        /** Opens a file in the current window (used to land on the generated {@code pom.xml}). */
        void openPath(Path file);

        /** Reveals the Output window, so a generation shows its progress rather than a blank editor. */
        default void openConsole() {}

        /** The active keymap, for {@link TextInputKeymap} on the form's fields. */
        KeymapManager keymap();

        /** Modal consent for an archetype we did not vet. {@code true} to proceed. */
        boolean confirmArchetype(MavenArchetype archetype);

        /** As {@link #confirmArchetype}, with the "do not ask again" answer; the default cannot say ALWAYS. */
        default Consent consentFor(MavenArchetype archetype) {
            return confirmArchetype(archetype) ? Consent.ONCE : Consent.CANCEL;
        }

        /** Re-lists the project tree after files land on disk. */
        void refreshProjectTree();

        /** Opens the Maven actions popup for the project whose {@code pom.xml} folder is {@code root}. */
        default void showActionsAt(@Nullable Path root) {}

        /** Prompts for a custom Maven task and runs it in {@code root}. */
        default void runCustomAt(@Nullable Path root) {}
    }

    /**
     * How a generation is actually launched. Exists so the FX test can assert the argv without forking
     * Maven (the {@code LspManager.setSessionStarterForTest} idiom).
     */
    @FunctionalInterface
    interface Runner {
        void run(@Nullable Path workingDir, List<String> argv, BuildService.Listener listener);
    }

    private final CoordinatorHost host;
    private final Ops ops;
    private final BuildOutputPanel output;
    private final BuildService service = new BuildService();
    private Runner runner = (dir, argv, listener) -> service.run(dir, argv, mavenEnvironment(), listener);

    /**
     * The full catalog for this window, once loaded: the parsed rows (a few thousand), not the 18 MB
     * download, which {@link ArchetypeCatalogCache} keeps on disk so other windows and later sessions start
     * from it and re-fetch at most once a day — conditionally, so an unchanged file costs one round trip.
     */
    private @Nullable List<MavenArchetype> fetched;

    private @Nullable ExecutorService fetchExec;

    /**
     * Archetypes the user chose "Trust and Generate" for, for as long as Editora runs — shared by every
     * window, since the trust is in the archetype, not the window. Not persisted: the trust store keys
     * folders, and a durable "trusted archetypes" list is a store field of its own (see the pass notes).
     */
    private static final java.util.Set<String> SESSION_TRUSTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    MavenProjectCoordinator(CoordinatorHost host, Ops ops, BuildOutputPanel output) {
        this.host = host;
        this.ops = ops;
        this.output = output;
    }

    private @Nullable CommandRegistry registry;

    void registerCommands(CommandRegistry registry) {
        this.registry = registry;
        registry.register(Command.of("maven.newProject", () -> newProject(null)));
        registry.register(Command.of("maven.newProjectHere", this::newProjectHere));
        registry.register(Command.of("maven.updateVersions", this::updateVersions));
    }

    /**
     * The environment the wizard's Maven runs in: the Maven JDK from Settings, as {@code BuildCoordinator}
     * gives a project build. The wizard used to run Maven on whatever {@code java} was on PATH, so a user who
     * set the Maven JDK precisely because their default was unsuitable got the unsuitable one here.
     */
    Map<String, String> mavenEnvironment() {
        return com.editora.run.JdkToolchain.environment(
                host.settings().getMavenJdkHome(), com.editora.process.ProcessRunner.augmentedPath());
    }

    // --- Update Versions ---------------------------------------------------------------------------

    /**
     * Offers to bring the nearest {@code pom.xml}'s dependencies and plugins up to their latest stable
     * versions, <b>showing what would change before anything is written</b>.
     *
     * <p>Both halves are resolved here rather than handing dependencies to
     * {@code versions:use-latest-releases}: that rewrites the file directly, so there would be nothing to
     * preview and no way to decline half of it. The wizard's creation-time option can afford the plugin
     * because a brand-new project has nothing to lose; an existing pom does.
     */
    void updateVersions() {
        updateVersions(nearestPom(ops.defaultParentDir()));
    }

    /** The check in flight, so a second invocation cancels it rather than queueing behind it. */
    private @Nullable VersionCheck runningCheck;

    private static final class VersionCheck {
        final AtomicBoolean cancelled = new AtomicBoolean();
        private final AutoCloseable task;
        private boolean closed;

        VersionCheck(AutoCloseable task) {
            this.task = task;
        }

        /** Closes the background-task handle once, whether the check finished or was cancelled first. */
        void close() {
            if (!closed) {
                closed = true;
                closeQuietly(task);
            }
        }
    }

    /**
     * As {@link #updateVersions()}, for {@code pomFile} — the pom a context menu was opened on. The menu used
     * to call the no-argument form, which checks the <em>active tab's</em> pom: a right-click on
     * {@code moduleB/pom.xml} while editing a file of module A offered to rewrite module A's pom.
     */
    void updateVersions(@Nullable Path pomFile) {
        if (!host.settings().isMavenSupport()) {
            host.setError(tr("status.mavenProject.disabled"));
            return;
        }
        if (runningCheck != null) {
            cancelCheck();
            return;
        }
        if (pomFile == null) {
            host.setError(tr("status.mavenVersions.noPom"));
            return;
        }
        String pom = pomText(pomFile, ops.openBuffer(pomFile));
        PomUpgrades.Plan plan = PomUpgrades.plan(pom == null ? "" : pom);
        if (!plan.hasLookups()) {
            nothingToCheck(pomFile, pom, plan);
            return;
        }
        host.setStatus(tr("status.mavenVersions.checking", plan.coordinates().size()));
        VersionCheck check = new VersionCheck(host.startBackgroundTask(
                tr("status.mavenVersions.checking", plan.coordinates().size())));
        runningCheck = check;
        AtomicBoolean unreachable = new AtomicBoolean();
        fetchExecutor().execute(() -> {
            Map<String, String> latest = CentralVersions.latest(
                    plan.coordinates(),
                    path -> fetchMetadataOrThrow(path, unreachable),
                    lookupPool(),
                    check.cancelled::get);
            Platform.runLater(() -> {
                check.close();
                if (runningCheck == check) {
                    runningCheck = null;
                }
                if (check.cancelled.get()) {
                    return; // cancelCheck already said so
                }
                showUpgrades(pomFile, pom, plan, PomUpgrades.rows(plan, latest), unreachable.get());
            });
        });
    }

    /** Stops the check in flight: the lookups still queued are skipped and nothing is shown. */
    private void cancelCheck() {
        VersionCheck check = runningCheck;
        if (check == null) {
            return;
        }
        runningCheck = null;
        check.cancelled.set(true);
        check.close();
        host.setStatus(tr("status.mavenVersions.cancelled"));
    }

    /**
     * Nothing in this pom can be looked up. Says why — "3 inherited" is a module of a parent, not a pom with
     * nothing in it — and, when the parent pom is on disk, offers to check that one instead: it is where
     * the versions a module inherits are pinned, and the thing a Spring Boot user actually bumps.
     */
    private void nothingToCheck(Path pomFile, @Nullable String pom, PomUpgrades.Plan plan) {
        if (plan.skipped() == 0) {
            host.setStatus(tr("status.mavenVersions.nothingPinned"));
            return;
        }
        String why =
                tr("status.mavenVersions.nothingPinnedSkipped", plan.skippedPropertyDriven(), plan.skippedInherited());
        Path parentPom = parentPomOnDisk(pomFile, pom);
        if (parentPom == null) {
            host.setStatus(why);
            return;
        }
        host.setStatus(why);
        Label body = new Label(why + "\n" + tr("dialog.mavenVersions.parentIs", parentPom));
        body.setWrapText(true);
        body.setMaxWidth(560);
        OverlayInput.show(
                host.overlayHost(),
                tr("dialog.mavenVersions.title"),
                body,
                null,
                tr("dialog.mavenVersions.checkParent"),
                new SimpleBooleanProperty(true),
                () -> updateVersions(parentPom),
                null,
                false);
    }

    /**
     * The parent pom a module's {@code <parent>} points at, when it exists on disk: {@code <relativePath>}
     * (a folder means its {@code pom.xml}), else Maven's default {@code ../pom.xml}. Null for a parent that
     * only lives in a repository (a Spring Boot starter parent, say).
     */
    static @Nullable Path parentPomOnDisk(Path pomFile, @Nullable String pom) {
        if (pomFile == null || pom == null || PomEdits.parent(pom) == null) {
            return null;
        }
        String relative = PomEdits.parentRelativePath(pom);
        if (relative != null && relative.isEmpty()) {
            return null; // an explicitly empty relativePath says "look it up in the repository only"
        }
        Path base = pomFile.toAbsolutePath().normalize().getParent();
        if (base == null) {
            return null;
        }
        try {
            Path candidate =
                    base.resolve(relative == null ? "../pom.xml" : relative).normalize();
            if (Files.isDirectory(candidate)) {
                candidate = candidate.resolve("pom.xml");
            }
            return Files.isRegularFile(candidate)
                            && !candidate.equals(pomFile.toAbsolutePath().normalize())
                    ? candidate
                    : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The {@code pom.xml} at or above {@code from}, or null. */
    private static @Nullable Path nearestPom(@Nullable Path from) {
        for (Path dir = from; dir != null; dir = dir.getParent()) {
            Path pom = dir.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                return pom;
            }
        }
        return null;
    }

    /**
     * The preview: one checked row per artifact that has something newer, grouped by what it is (a BOM
     * import re-versions everything it manages; a plugin is not a dependency), a <b>major</b> tag where the
     * leading number changes, and a line for what was not checked. Nothing is written until accepted, and
     * only the rows left checked are.
     */
    private void showUpgrades(
            Path pomFile,
            @Nullable String pom,
            PomUpgrades.Plan plan,
            List<PomUpgrades.Row> rows,
            boolean unreachable) {
        if (rows.isEmpty()) {
            if (unreachable) {
                host.setError(tr("status.mavenVersions.unreachable"));
            } else if (plan.skipped() > 0) {
                // A module whose only lookup was its parent coordinate (a reactor parent is not on Central):
                // what it inherits is the real question, so offer the parent pom as for a pom with no lookups.
                nothingToCheck(pomFile, pom, plan);
            } else {
                host.setStatus(tr("status.mavenVersions.upToDate"));
            }
            return;
        }
        host.setStatus(tr("status.mavenVersions.found", rows.size()));
        VBox list = new VBox(4);
        List<CheckBox> boxes = new ArrayList<>();
        List<PomUpgrades.Row> byBox = new ArrayList<>();
        BooleanProperty anySelected = new SimpleBooleanProperty(true);
        Runnable sync = () -> anySelected.set(boxes.stream().anyMatch(CheckBox::isSelected));
        for (PomUpgrades.Kind kind : PomUpgrades.Kind.values()) {
            List<PomUpgrades.Row> group =
                    rows.stream().filter(r -> r.kind() == kind).toList();
            if (group.isEmpty()) {
                continue;
            }
            Label heading =
                    new Label(tr("dialog.mavenVersions.group." + kind.name().toLowerCase(java.util.Locale.ROOT)));
            heading.getStyleClass().add("maven-version-group");
            list.getChildren().add(heading);
            for (PomUpgrades.Row row : group) {
                CheckBox box = new CheckBox(rowText(row));
                box.setSelected(true);
                box.getStyleClass().add("maven-version-row");
                box.selectedProperty().addListener((o, a, b) -> sync.run());
                boxes.add(box);
                byBox.add(row);
                if (row.major()) {
                    Label major = new Label(tr("dialog.mavenVersions.major"));
                    major.getStyleClass().addAll("settings-hint", "maven-version-major");
                    list.getChildren().add(new HBox(8, box, major));
                } else {
                    list.getChildren().add(box);
                }
            }
        }
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(260);
        VBox body = new VBox(8, new Label(tr("dialog.mavenVersions.summary", rows.size())), scroll);
        if (unreachable) {
            Label partial = new Label(tr("status.mavenVersions.unreachable"));
            partial.getStyleClass().add("settings-hint");
            body.getChildren().add(partial);
        }
        if (plan.skipped() > 0) {
            Label skipped = new Label(tr(
                    "dialog.mavenVersions.skipped",
                    plan.skipped(),
                    plan.skippedPropertyDriven(),
                    plan.skippedInherited()));
            skipped.getStyleClass().addAll("settings-hint", "maven-version-skipped");
            skipped.setWrapText(true);
            body.getChildren().add(skipped);
        }
        boolean[] accepted = {false};
        OverlayInput.show(
                host.overlayHost(),
                tr("dialog.mavenVersions.title"),
                body,
                null,
                tr("dialog.mavenVersions.apply"),
                anySelected,
                () -> {
                    accepted[0] = true;
                    List<PomUpgrades.Row> chosen = new ArrayList<>();
                    for (int i = 0; i < boxes.size(); i++) {
                        if (boxes.get(i).isSelected()) {
                            chosen.add(byBox.get(i));
                        }
                    }
                    applyRows(pomFile, chosen);
                },
                null,
                false);
        // Cancel (or Escape) leaves the status saying "n updates available" otherwise — which it was, but
        // the user just declined them; say that instead. Checked a pulse later: the accept path hides the
        // card first and runs its action second, in the same pulse.
        onOverlayHidden(() -> Platform.runLater(() -> {
            if (!accepted[0]) {
                host.setStatus(tr("status.mavenVersions.cancelled"));
            }
        }));
    }

    /** {@code key    current  →  next} — the key being the coordinates, or the property name. */
    private static String rowText(PomUpgrades.Row row) {
        String key = row.kind() == PomUpgrades.Kind.PROPERTY ? "${" + row.key() + "}" : row.key();
        return key + "    " + row.current() + "  →  " + row.next();
    }

    /** Runs {@code action} once, the next time the overlay closes (for whatever reason). */
    private void onOverlayHidden(Runnable action) {
        OverlayHost overlay = host.overlayHost();
        if (overlay == null) {
            return;
        }
        javafx.beans.value.ChangeListener<Boolean>[] listener = new javafx.beans.value.ChangeListener[1];
        listener[0] = (obs, was, now) -> {
            if (!now) {
                overlay.showingProperty().removeListener(listener[0]);
                action.run();
            }
        };
        overlay.showingProperty().addListener(listener[0]);
    }

    /** The pom's text as the user has it: the open buffer's, unsaved edits included, else the file's. */
    private @Nullable String pomText(Path pomFile, com.editora.editor.@Nullable EditorBuffer open) {
        return open != null ? open.getContent() : read(pomFile);
    }

    /**
     * Writes the accepted upgrades.
     *
     * <p>Computed from the open buffer when there is one. It used to be computed from the file on disk and
     * then written over the buffer, so a pom with unsaved edits lost every one of them to an "update
     * versions" — the buffer was replaced with disk text plus new version numbers. A buffer that cannot be
     * edited (View mode, a huge or still-loading file) is left alone and says so; a closed pom is replaced
     * atomically, and only if it still holds the bytes the upgrade was computed from.
     */
    void applyUpgrades(Path pomFile, Map<String, String> upgrades) {
        applyEdit(
                pomFile,
                pom -> PomEdits.setPluginVersions(PomEdits.setDependencyVersions(pom, upgrades), upgrades),
                upgrades == null ? 0 : upgrades.size());
    }

    /** As {@link #applyUpgrades}, for the rows the preview left checked (parent and property rows included). */
    void applyRows(Path pomFile, List<PomUpgrades.Row> rows) {
        applyEdit(pomFile, pom -> PomUpgrades.apply(pom, rows), rows == null ? 0 : rows.size());
    }

    private void applyEdit(Path pomFile, java.util.function.UnaryOperator<String> edit, int count) {
        com.editora.editor.EditorBuffer open = ops.openBuffer(pomFile);
        String pom = pomText(pomFile, open);
        if (pom == null) {
            host.setError(tr("status.mavenVersions.noPom"));
            return;
        }
        String out = edit.apply(pom);
        if (out.equals(pom)) {
            host.setStatus(tr("status.mavenVersions.upToDate"));
            return;
        }
        try {
            if (open != null) {
                if (!open.isEditable() || open.isTruncatedLoad()) {
                    host.setError(tr("status.mavenVersions.readOnly"));
                    return;
                }
                if (!NoUndoGuard.allow(open, tr("command.maven.updateVersions"))) {
                    return;
                }
                // One replacement, so the whole update is a single undo step rather than one per artifact —
                // and the buffer goes dirty, so it is the user who decides to save it.
                open.replaceWholeDocument(out);
            } else {
                // Only a closed pom is written on disk, where there is no undo history to belong to anyway.
                if (!com.editora.io.AtomicFileWrite.replaceIfUnchanged(
                        pomFile,
                        pom.getBytes(StandardCharsets.UTF_8),
                        out.getBytes(StandardCharsets.UTF_8),
                        () -> true)) {
                    host.setError(tr("status.mavenVersions.changedOnDisk"));
                    return;
                }
                ops.openPath(pomFile);
            }
            host.setStatus(tr("status.mavenVersions.updated", count));
        } catch (Exception e) {
            host.setError(tr("status.mavenProject.pomEditFailed", String.valueOf(e.getMessage())));
        }
    }

    /**
     * A <b>Maven</b> submenu of the commands that make sense for {@code context}, or null when none do.
     *
     * <p>Shared by every surface that offers it — the editor's right-click on a {@code pom.xml}, and the
     * project tree's menu on both a folder and a {@code pom.xml} row — so they cannot drift into offering
     * different Maven actions. Returns null rather than an empty menu so a caller can simply skip it.
     *
     * <p>The <b>whole</b> "is there anything here?" rule lives here, not at the call sites: Maven off, a
     * folder with no pom above it, or a file that is not itself a {@code pom.xml}. That last one is why a
     * file context is not simply resolved through its parent directory — every file in a Maven project has a
     * pom above it, so doing that would hang a Maven submenu off every {@code .java} file in the tree, saying
     * nothing about which project. The name must be exactly {@code pom.xml} because that is the only file
     * {@code mvn} will run against; an {@code effective-pom.xml} beside a real one would otherwise offer a
     * menu that silently acts on its neighbour.
     */
    javafx.scene.control.@Nullable Menu mavenMenu(@Nullable Path context) {
        if (!host.settings().isMavenSupport() || context == null) {
            return null;
        }
        boolean directory = Files.isDirectory(context);
        if (!directory && !isPomFile(context)) {
            return null;
        }
        Path pom = nearestPom(directory ? context : context.getParent());
        if (pom == null) {
            return null; // no Maven project here — the New Maven Project entry lives elsewhere
        }
        // Every item acts on THIS pom's folder. Rerun Last and Stop are window-wide (they belong to whatever
        // ran last, wherever that was), so a menu that names a pom does not offer them; the palette and the
        // tool window do.
        Path root = pom.getParent();
        javafx.scene.control.Menu menu = new javafx.scene.control.Menu(tr("menu.maven"), Icons.maven());
        menu.getItems()
                .addAll(
                        item("command.maven.updateVersions", Icons.refresh(), () -> updateVersions(pom)),
                        item("command.maven.showActions", Icons.run(), () -> ops.showActionsAt(root)),
                        item("command.maven.runCustom", Icons.terminal(), () -> ops.runCustomAt(root)));
        return menu;
    }

    /** Whether {@code file} is a Maven project file — the exact name {@code mvn} itself looks for. */
    static boolean isPomFile(Path file) {
        return file != null
                && file.getFileName() != null
                && "pom.xml".equals(file.getFileName().toString());
    }

    /** Labels reuse the commands' own titles, so the menu and the palette can never say different things. */
    private static javafx.scene.control.MenuItem item(String titleKey, javafx.scene.Node icon, Runnable action) {
        javafx.scene.control.MenuItem menuItem = new javafx.scene.control.MenuItem(submenuLabel(tr(titleKey)), icon);
        menuItem.setOnAction(e -> action.run());
        return menuItem;
    }

    /**
     * A command title inside the <b>Maven</b> submenu, without the "Maven: " the palette needs: the submenu
     * already says Maven, so "Maven ▸ Maven: Show Actions" read twice (review B24). The prefix is whatever
     * precedes the first ": " — the convention every build-tool command title follows in all six catalogs.
     */
    static String submenuLabel(String title) {
        int colon = title.indexOf(": ");
        return colon > 0 ? title.substring(colon + 2) : title;
    }

    /** Entry point for the project-tree folder menu — generate into {@code folder}. */
    void newProject(@Nullable Path parentDir) {
        // The palette gates maven.* off Settings.mavenSupport for free (Chrome's build-tool prefix rule),
        // but the project-tree context menu does not go through the palette — so re-check here rather than
        // let the tree offer an action that would silently do nothing.
        if (!host.settings().isMavenSupport()) {
            host.setError(tr("status.mavenProject.disabled"));
            return;
        }
        Path base = parentDir != null ? parentDir : ops.defaultParentDir();
        if (base == null) {
            base = Path.of(System.getProperty("user.home", "."));
        }
        List<String> mvn = mavenExecutable();
        // Probe up front: with no pom writer, a missing mvn means the wizard could never finish, and
        // discovering that after five fields have been typed is the worst possible moment to say so.
        if (!DoctorProbes.onPath(mvn)) {
            host.setError(tr("status.mavenProject.noMaven"));
            return;
        }
        chooseArchetype(base, ArchetypeCatalog.merge(fetched == null ? List.of() : fetched));
    }

    /** Palette variant: asks for the folder first (the tree menu supplies one directly). */
    private void newProjectHere() {
        Path base = ops.defaultParentDir();
        host.promptText(
                tr("dialog.mavenProject.locationTitle"),
                tr("dialog.mavenProject.location"),
                base == null ? "" : base.toString(),
                text -> {
                    Path typed = MavenCoordinates.resolveLocation(text, base, userHome());
                    if (typed != null) {
                        newProject(typed);
                    }
                });
    }

    /**
     * The Maven launcher the wizard uses: the user's own — the Settings override, else {@code mvn} on PATH.
     *
     * <p><b>Never a wrapper.</b> This used to go through {@code BuildTool.executable}, which prefers an
     * {@code mvnw} in the directory it is given — and the wizard gave it the <em>target parent folder</em>.
     * A parent that happened to hold another project's wrapper then (a) failed the up-front probe, because
     * {@code ./mvnw} was checked against the JVM's working directory ("Maven not found" with Maven
     * installed), and (b) ran that folder's script to generate an unrelated project with no workspace-trust
     * prompt, which {@code BuildCoordinator.allowRun} exists to require. Generating a project is the user's
     * action with the user's tool; nothing in the neighbourhood gets a say.
     */
    static List<String> userMaven(String override) {
        return BuildExecutable.resolve(List.of(), override, "mvn");
    }

    private List<String> mavenExecutable() {
        return userMaven(host.settings().getMavenCommand());
    }

    // --- Step 1: pick an archetype ----------------------------------------------------------------

    /**
     * Sentinel rows appended to the picker. Compared by <b>identity</b>, never by label — an archetype the
     * user names "Custom archetype…" must stay an archetype (the lesson already recorded for the
     * run-configuration combo's Edit-Configurations row).
     */
    private static final MavenArchetype CUSTOM_ROW = new MavenArchetype("", "", "", "", "", false);

    private static final MavenArchetype LOAD_CATALOG_ROW = new MavenArchetype("", "", "", "", "", false);

    private void chooseArchetype(Path parentDir, List<MavenArchetype> archetypes) {
        QuickOpen<MavenArchetype> picker = new QuickOpen<>(
                tr("dialog.mavenProject.archetypeTitle"),
                tr("dialog.mavenProject.archetypePrompt"),
                () -> {
                    List<MavenArchetype> rows = new ArrayList<>(archetypes);
                    rows.add(CUSTOM_ROW);
                    if (fetched == null) {
                        rows.add(LOAD_CATALOG_ROW);
                    }
                    return rows;
                },
                this::rowLabel,
                this::rowDetail,
                this::rowSearchKey,
                a -> onArchetypeChosen(a, parentDir));
        picker.setPreferredSize(820, 10);
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    private String rowLabel(MavenArchetype a) {
        if (a == CUSTOM_ROW) {
            return tr("dialog.mavenProject.customRow");
        }
        if (a == LOAD_CATALOG_ROW) {
            return tr("dialog.mavenProject.loadCatalogRow");
        }
        return a.artifactId();
    }

    /**
     * {@code groupId:artifactId:version}, then the description. The coordinates always show: Central's
     * catalog has 56 artifactIds published under more than one groupId (two "spring-boot-blank-archetype"
     * rows, say), and the title alone cannot tell them apart — nor does it say which version a curated pin is.
     */
    private String rowDetail(MavenArchetype a) {
        if (a == CUSTOM_ROW || a == LOAD_CATALOG_ROW) {
            return "";
        }
        return a.description().isEmpty() ? a.gav() : a.gav() + "  —  " + a.description();
    }

    /** Typing matches the coordinates and the description, so "spring boot blank" and "org.openjfx" both find rows. */
    private String rowSearchKey(MavenArchetype a) {
        if (a == CUSTOM_ROW || a == LOAD_CATALOG_ROW) {
            return rowLabel(a);
        }
        return a.description().isEmpty() ? a.gav() : a.gav() + " " + a.description();
    }

    private void onArchetypeChosen(MavenArchetype a, Path parentDir) {
        if (a == CUSTOM_ROW) {
            host.promptText(tr("dialog.mavenProject.customTitle"), tr("dialog.mavenProject.customLabel"), "", text -> {
                MavenArchetype custom = MavenCoordinates.parseGav(text);
                if (custom == null) {
                    host.setError(tr("status.mavenProject.badGav"));
                    return;
                }
                showForm(custom, parentDir);
            });
            return;
        }
        if (a == LOAD_CATALOG_ROW) {
            loadCatalog(parentDir);
            return;
        }
        showForm(a, parentDir);
    }

    // --- The optional full-catalog fetch ----------------------------------------------------------

    /**
     * Loads the full catalog: from the disk cache when it is younger than a day, else from the URL — with
     * the cached validators, so an unchanged file answers {@code 304} and the cached rows are kept. A fetch
     * that fails falls back to a stale cache when there is one (with the error still shown), and to the
     * curated list when there is none, so the picker never dead-ends.
     */
    private void loadCatalog(Path parentDir) {
        String url = host.settings().getMavenArchetypeCatalogUrl();
        if (!PluginRegistry.isHttps(url)) {
            host.setError(tr("status.mavenProject.catalogNotHttps"));
            return;
        }
        host.setStatus(tr("status.mavenProject.loadingCatalog"));
        AutoCloseable task = host.startBackgroundTask(tr("status.mavenProject.loadingCatalog"));
        Path configDir = host.configDir();
        Path cacheFile = configDir == null ? null : ArchetypeCatalogCache.file(configDir); // a test host has none
        fetchExecutor().execute(() -> {
            ArchetypeCatalogCache.Entry cached = cacheFile == null ? null : ArchetypeCatalogCache.read(cacheFile);
            long now = System.currentTimeMillis();
            String error = null;
            List<MavenArchetype> parsed = null;
            if (cached != null && cached.isFresh(now)) {
                parsed = cached.archetypes();
            } else {
                try {
                    ArchetypeCatalogCache.Entry entry = fetchCatalog(url, cached, now);
                    parsed = entry.archetypes();
                    storeCatalog(cacheFile, entry);
                } catch (Exception e) {
                    error = String.valueOf(e.getMessage());
                    if (cached != null) {
                        parsed = cached.archetypes(); // stale beats nothing; the error is still reported
                    }
                }
            }
            List<MavenArchetype> result = parsed;
            String err = error;
            Platform.runLater(() -> {
                closeQuietly(task);
                if (result != null) {
                    fetched = result;
                }
                if (err != null) {
                    host.setError(tr("status.mavenProject.catalogFailed", err));
                } else if (result != null) { // no error: a catalog was read
                    host.setStatus(tr("status.mavenProject.catalogLoaded", result.size()));
                }
                // Still let the user proceed with what there is rather than dead-ending.
                chooseArchetype(parentDir, ArchetypeCatalog.merge(fetched == null ? List.of() : fetched));
            });
        });
    }

    /**
     * One GET, conditional when {@code cached} carries validators; streamed straight into the parser, so the
     * document is never held whole — neither as bytes nor as a DOM.
     *
     * @return the parsed rows with the response's validators, or {@code cached} revalidated on a {@code 304}
     */
    private ArchetypeCatalogCache.Entry fetchCatalog(String url, ArchetypeCatalogCache.@Nullable Entry cached, long now)
            throws Exception {
        HttpClient client = httpClients.apply(Duration.ofSeconds(15));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "Editora")
                .GET();
        if (cached != null && !cached.etag().isEmpty()) {
            request.header("If-None-Match", cached.etag());
        }
        if (cached != null && !cached.lastModified().isEmpty()) {
            request.header("If-Modified-Since", cached.lastModified());
        }
        try {
            HttpResponse<InputStream> response =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 304 && cached != null) {
                try (InputStream in = response.body()) {
                    in.readAllBytes(); // a 304 has no body; drain whatever came
                }
                return cached.revalidated(now);
            }
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            try (InputStream in = response.body()) {
                List<MavenArchetype> rows =
                        ArchetypeCatalogParser.parse(in, ArchetypeCatalogParser.MAX_CATALOG_BYTES, CATALOG_DEADLINE);
                return new ArchetypeCatalogCache.Entry(
                        rows,
                        response.headers().firstValue("ETag").orElse(""),
                        response.headers().firstValue("Last-Modified").orElse(""),
                        now);
            }
        } finally {
            client.shutdownNow(); // one client per fetch: give its selector thread back with the answer
        }
    }

    /** Best effort: a cache that cannot be written only means the next load downloads again. */
    private static void storeCatalog(@Nullable Path cacheFile, ArchetypeCatalogCache.Entry entry) {
        if (cacheFile == null) {
            return;
        }
        try {
            ArchetypeCatalogCache.write(cacheFile, entry);
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(MavenProjectCoordinator.class.getName())
                    .log(java.util.logging.Level.FINE, "Could not write " + cacheFile, e);
        }
    }

    private synchronized ExecutorService fetchExecutor() {
        if (fetchExec == null) {
            fetchExec = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "archetype-catalog");
                t.setDaemon(true);
                return t;
            });
        }
        return fetchExec;
    }

    private @Nullable ExecutorService lookupExec;

    /** A small pool for the per-artifact metadata lookups, so forty coordinates are not forty sequential waits. */
    private synchronized ExecutorService lookupPool() {
        if (lookupExec == null) {
            lookupExec = Executors.newFixedThreadPool(CentralVersions.PARALLELISM, r -> {
                Thread t = new Thread(r, "maven-central-lookup");
                t.setDaemon(true);
                return t;
            });
        }
        return lookupExec;
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception ignored) {
            // the background-task handle must be closed on every path; failure here is not actionable
        }
    }

    // --- Step 2: the coordinates form -------------------------------------------------------------

    private void showForm(MavenArchetype archetype, Path parentDir) {
        showForm(archetype, parentDir, null, null);
    }

    /**
     * The form, prefilled from {@code initial}/{@code initialExtras} when it is re-shown — after the consent
     * prompt was cancelled, so the five fields the user filled in are not lost to a "Cancelled." status.
     */
    private void showForm(
            MavenArchetype archetype,
            Path parentDir,
            @Nullable MavenProjectSpec initial,
            @Nullable MavenProjectExtras initialExtras) {
        KeymapManager keymap = ops.keymap();
        TextField nameField = field(keymap, 24);
        TextField locationField = field(keymap, 30);
        TextField groupField = field(keymap, 24);
        TextField versionField = field(keymap, 16);
        TextField packageField = field(keymap, 30);

        locationField.setText(parentDir == null ? "" : parentDir.toString());
        groupField.setText("com.example");
        versionField.setText(DEFAULT_VERSION);

        Label target = new Label();
        target.getStyleClass().add("settings-hint");
        target.setWrapText(true);

        // Name and groupId drive the package until the user edits it by hand — the cloneRepo idiom.
        boolean[] packageEdited = {false};
        boolean[] autoFilling = {false};
        Runnable autoPackage = () -> {
            if (!packageEdited[0]) {
                autoFilling[0] = true;
                packageField.setText(MavenCoordinates.defaultPackage(groupField.getText(), nameField.getText()));
                autoFilling[0] = false;
            }
        };
        packageField.textProperty().addListener((o, a, b) -> {
            if (!autoFilling[0]) {
                packageEdited[0] = true; // user took control of the package
            }
        });

        Button browse = new Button(tr("dialog.mavenProject.browse"));
        browse.setFocusTraversable(false);
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(tr("dialog.mavenProject.locationTitle"));
            File chosen = chooser.showDialog(host.window());
            if (chosen != null) {
                locationField.setText(chosen.toPath().toString());
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        int row = 0;
        grid.add(new Label(tr("dialog.mavenProject.archetype")), 0, row);
        Label chosen = new Label(archetype.gav());
        grid.add(chosen, 1, row++, 2, 1);
        grid.add(new Label(tr("dialog.mavenProject.name")), 0, row);
        grid.add(nameField, 1, row++, 2, 1);
        grid.add(new Label(tr("dialog.mavenProject.location")), 0, row);
        grid.add(locationField, 1, row);
        grid.add(browse, 2, row++);
        grid.add(new Label(tr("dialog.mavenProject.groupId")), 0, row);
        grid.add(groupField, 1, row++, 2, 1);
        grid.add(new Label(tr("dialog.mavenProject.version")), 0, row);
        grid.add(versionField, 1, row++, 2, 1);
        grid.add(new Label(tr("dialog.mavenProject.packageName")), 0, row);
        grid.add(packageField, 1, row++, 2, 1);
        grid.add(target, 1, row, 2, 1);
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        GridPane.setHgrow(locationField, Priority.ALWAYS);
        GridPane.setHgrow(groupField, Priority.ALWAYS);
        GridPane.setHgrow(packageField, Priority.ALWAYS);

        // What the archetype itself asks for, when its jar is already in the local repository (it is after
        // the first generation from it). Shown as rows with the defaults batch mode would apply silently;
        // one without a default blocks Create until filled, instead of failing in Maven's output.
        ArchetypeDescriptor descriptor = descriptorOf(archetype);
        Map<String, TextField> propertyFields = new LinkedHashMap<>();
        for (ArchetypeDescriptor.RequiredProperty property : descriptor.extraProperties()) {
            TextField f = field(keymap, 24);
            f.setText(property.defaultValue() == null ? "" : property.defaultValue());
            propertyFields.put(property.key(), f);
        }

        BooleanProperty valid = new SimpleBooleanProperty(false);
        Path base = parentDir != null ? parentDir : ops.defaultParentDir();
        Runnable revalidate = () -> {
            MavenProjectSpec spec = specFrom(
                    archetype, nameField, locationField, groupField, versionField, packageField, base, userHome());
            Path dir = spec.projectDir();
            // An existing folder is refused only when it holds something: Maven would otherwise generate into
            // a populated folder and half-merge with whatever is already there. An empty one — made ahead of
            // time, or left by a failed attempt — is exactly where the project should go.
            boolean occupied = dir != null && Files.exists(dir) && !isEmptyDirectory(dir);
            String missingProperty = null;
            for (Map.Entry<String, TextField> e : propertyFields.entrySet()) {
                if (e.getValue().getText() == null || e.getValue().getText().isBlank()) {
                    missingProperty = e.getKey();
                    break;
                }
            }
            String problem = spec.firstProblem();
            valid.set(problem == null && !occupied && missingProperty == null);
            target.getStyleClass().remove("overlay-form-error");
            if (problem != null
                    && !(problem.equals("artifactId") && spec.artifactId().isEmpty())) {
                // The reason Create is disabled, not "Will create …" for a name the form refuses.
                target.setText(tr("status.mavenProject.invalid", fieldLabel(problem)));
                target.getStyleClass().add("overlay-form-error");
            } else if (dir == null) {
                target.setText("");
            } else if (occupied) {
                target.setText(tr("dialog.mavenProject.exists", dir));
                target.getStyleClass().add("overlay-form-error");
            } else if (missingProperty != null) {
                target.setText(tr("status.mavenProject.invalid", missingProperty));
                target.getStyleClass().add("overlay-form-error");
            } else {
                target.setText(tr("dialog.mavenProject.willCreate", dir));
            }
        };
        for (TextField f : List.of(nameField, locationField, groupField, versionField, packageField)) {
            f.textProperty().addListener((o, a, b) -> {
                if (f == nameField || f == groupField) {
                    autoPackage.run();
                }
                revalidate.run();
            });
        }
        propertyFields.values().forEach(f -> f.textProperty().addListener((o, a, b) -> revalidate.run()));

        TextField urlField = field(keymap, 30);
        ComboBox<String> releaseCombo = new ComboBox<>();
        releaseCombo.setEditable(true); // a release level need not be one of the installed JDKs
        releaseCombo.setPrefWidth(120);
        TextInputKeymap.install(releaseCombo.getEditor(), keymap);
        // Empty means "keep whatever the archetype wrote": these values are the archetype's to choose, and
        // prefilling quickstart's would impose them on archetypes that use neither. The prompt is the
        // combo's own — the skin binds the editor's prompt to it, so one set on the editor was overwritten.
        urlField.setPromptText("http://www.example.com");
        releaseCombo.setPromptText("17");
        CheckBox updateVersions = new CheckBox(tr("dialog.mavenProject.updateVersions"));
        Label updateHint = new Label(tr("dialog.mavenProject.updateVersionsHint"));
        updateHint.getStyleClass().add("settings-hint");
        updateHint.setWrapText(true);

        GridPane advancedGrid = new GridPane();
        advancedGrid.setHgap(8);
        advancedGrid.setVgap(8);
        advancedGrid.add(new Label(tr("dialog.mavenProject.url")), 0, 0);
        advancedGrid.add(urlField, 1, 0);
        advancedGrid.add(new Label(tr("dialog.mavenProject.javaRelease")), 0, 1);
        advancedGrid.add(releaseCombo, 1, 1);
        GridPane.setHgrow(urlField, Priority.ALWAYS);
        VBox advancedBox = new VBox(8, advancedGrid, updateVersions, updateHint);
        if (!propertyFields.isEmpty()) {
            GridPane propertyGrid = new GridPane();
            propertyGrid.setHgap(8);
            propertyGrid.setVgap(8);
            propertyGrid.getStyleClass().add("maven-archetype-properties");
            int r = 0;
            for (Map.Entry<String, TextField> e : propertyFields.entrySet()) {
                propertyGrid.add(new Label(e.getKey()), 0, r);
                propertyGrid.add(e.getValue(), 1, r++);
                GridPane.setHgrow(e.getValue(), Priority.ALWAYS);
            }
            Label propertiesTitle = new Label(tr("dialog.mavenProject.archetypeProperties"));
            propertiesTitle.getStyleClass().add("settings-hint");
            advancedBox.getChildren().addAll(propertiesTitle, propertyGrid);
        }
        TitledPane advanced = new TitledPane(tr("dialog.mavenProject.advanced"), advancedBox);
        // Filled on first expand rather than up front: discovery walks the JDK install roots, and the
        // common path never opens this section at all.
        advanced.expandedProperty().addListener((o, was, now) -> {
            if (now && releaseCombo.getItems().isEmpty()) {
                for (Integer major : JavaRuntimes.majorsDescending(JavaRuntimes.discover())) {
                    releaseCombo.getItems().add(String.valueOf(major));
                }
            }
        });
        // Collapsed by default — the common path is the five fields above — unless the archetype asks for
        // something it has no default for, which the user has to see to fill in.
        boolean needsInput = descriptor.extraProperties().stream().anyMatch(p -> !p.hasDefault());
        advanced.setExpanded(needsInput);
        advanced.setAnimated(false);
        advanced.getStyleClass().add("dialog-advanced");

        if (initial != null) {
            nameField.setText(initial.artifactId());
            locationField.setText(
                    initial.parentDir() == null ? "" : initial.parentDir().toString());
            groupField.setText(initial.groupId());
            versionField.setText(initial.version());
            packageField.setText(initial.packageName());
        }
        if (initialExtras != null) {
            urlField.setText(initialExtras.url());
            releaseCombo.getEditor().setText(initialExtras.javaRelease());
            updateVersions.setSelected(initialExtras.updateVersions());
            initialExtras.properties().forEach((k, v) -> {
                TextField f = propertyFields.get(k);
                if (f != null) {
                    f.setText(v);
                }
            });
        }
        revalidate.run();

        VBox body = new VBox(10, grid, advanced);

        OverlayInput.show(
                host.overlayHost(),
                tr("dialog.mavenProject.title"),
                body,
                nameField,
                tr("dialog.mavenProject.create"),
                valid,
                () -> {
                    MavenProjectSpec spec = specFrom(
                            archetype,
                            nameField,
                            locationField,
                            groupField,
                            versionField,
                            packageField,
                            base,
                            userHome());
                    Map<String, String> properties = new LinkedHashMap<>();
                    propertyFields.forEach((k, f) -> properties.put(k, f.getText()));
                    MavenProjectExtras extras = new MavenProjectExtras(
                            urlField.getText(),
                            releaseCombo.getEditor().getText(),
                            updateVersions.isSelected(),
                            properties);
                    generate(spec, extras, () -> showForm(archetype, parentDir, spec, extras));
                },
                null,
                false);
    }

    /** The archetype's descriptor from the local repository, or nothing when it is not downloaded yet. */
    private ArchetypeDescriptor descriptorOf(MavenArchetype archetype) {
        try {
            return descriptors.apply(archetype);
        } catch (RuntimeException e) {
            return ArchetypeDescriptor.NONE;
        }
    }

    /** Where descriptors come from: the user's local repository, unless a test says otherwise. */
    private java.util.function.Function<MavenArchetype, ArchetypeDescriptor> descriptors =
            archetype -> ArchetypeDescriptor.readFromLocalRepository(
                    MavenPluginGoals.localRepository(Path.of(userHome())), archetype);

    /** Test seam: the descriptors the form sees, so a test does not depend on what {@code ~/.m2} holds. */
    void setDescriptorsForTest(java.util.function.Function<MavenArchetype, ArchetypeDescriptor> descriptors) {
        this.descriptors = descriptors;
    }

    private static String userHome() {
        return System.getProperty("user.home", ".");
    }

    /** The form's label for a spec problem — "Check the Name field.", never "Check the artifactId field.". */
    static @Nullable String fieldLabel(@Nullable String problem) {
        return switch (problem == null ? "" : problem) {
            case "artifactId" -> tr("dialog.mavenProject.name");
            case "package" -> tr("dialog.mavenProject.packageName");
            case "groupId", "version", "location", "archetype" -> tr("dialog.mavenProject." + problem);
            default -> problem;
        };
    }

    private static boolean isEmptyDirectory(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (java.util.stream.Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        } catch (Exception e) {
            return false; // unreadable: treat as occupied, the safe answer
        }
    }

    private TextField field(KeymapManager keymap, int columns) {
        TextField f = new TextField();
        f.setPrefColumnCount(columns);
        TextInputKeymap.install(f, keymap);
        return f;
    }

    private static MavenProjectSpec specFrom(
            MavenArchetype archetype,
            TextField name,
            TextField location,
            TextField group,
            TextField version,
            TextField pkg,
            @Nullable Path base,
            String userHome) {
        // Absolute, always: a relative location or a ~ is resolved here, so the hint shows the folder the
        // project will really land in rather than text relative to the launcher's working directory.
        Path parent = MavenCoordinates.resolveLocation(location.getText(), base, userHome);
        return new MavenProjectSpec(
                archetype, group.getText(), name.getText(), version.getText(), pkg.getText(), parent);
    }

    // --- Step 3: generate --------------------------------------------------------------------------

    /** Package-visible so the FX test can drive generation past the two cards. */
    void generate(MavenProjectSpec spec) {
        generate(spec, MavenProjectExtras.NONE);
    }

    void generate(MavenProjectSpec spec, MavenProjectExtras extras) {
        generate(spec, extras, null);
    }

    /**
     * @param backToForm re-shows the form with its answers when the consent prompt is cancelled, or null to
     *     report "Cancelled." instead (a caller that had no form)
     */
    private void generate(MavenProjectSpec spec, MavenProjectExtras extras, @Nullable Runnable backToForm) {
        Path projectDir = spec.projectDir(); // set for every valid spec: it has a location and an artifactId
        if (!spec.isValid() || projectDir == null) {
            host.setError(tr("status.mavenProject.invalid", fieldLabel(spec.firstProblem())));
            return;
        }
        // A non-curated archetype is third-party code: archetype:generate downloads and runs its plugin.
        // The workspace-trust gate cannot cover this — it only fires when a repo ships an mvnw, and a
        // brand-new empty directory has none.
        if (!spec.archetype().curated()
                && !SESSION_TRUSTED.contains(spec.archetype().gav())) {
            Consent consent = ops.consentFor(spec.archetype());
            if (consent == Consent.CANCEL) {
                if (backToForm != null) {
                    backToForm.run();
                } else {
                    host.setStatus(tr("status.mavenProject.cancelled"));
                }
                return;
            }
            if (consent == Consent.ALWAYS) {
                SESSION_TRUSTED.add(spec.archetype().gav());
            }
        }
        Path parent;
        try {
            parent = java.util.Objects.requireNonNull(spec.parentDir(), "a valid spec has a folder");
            Files.createDirectories(parent);
        } catch (Exception e) {
            host.setError(tr("status.mavenProject.mkdirFailed", String.valueOf(e.getMessage())));
            return;
        }
        // Generating next to an UNRELATED Maven project fails outright: archetype:generate tries to add the
        // new project as a <module> of whatever project it finds in the working directory, and refuses when
        // that one is not packaging=pom. So when the target holds such a project, the run is detached — a
        // pom-less scratch working directory plus -DoutputDirectory — and Maven simply has no current
        // project to register a module with. An aggregator pom is left attached, where the module is wanted.
        String parentPackaging = packagingOf(parent);
        Path scratch = parentPackaging != null && ArchetypeGenerate.detachFromExistingProject(parentPackaging)
                ? scratchDir()
                : null;
        Path workingDir = scratch != null ? scratch : parent;
        List<String> mvn = mavenExecutable();
        // An archetype that takes the Java release as a property gets it that way rather than as a pom edit.
        MavenProjectExtras applied = extras.forArchetype(descriptorOf(spec.archetype()));
        // Generated INSIDE the scratch dir and moved afterwards, rather than generated into `parent` from a
        // scratch working directory: the module check reads the pom in the OUTPUT directory, so pointing
        // outputDirectory at a folder that already holds a project fails exactly as running there did.
        List<String> argv = scratch != null
                ? ArchetypeGenerate.detachedArgv(mvn, spec, applied.properties(), scratch)
                : ArchetypeGenerate.argv(mvn, spec, applied.properties());
        // A module added to the project this window has open stays in it: no second project, no second
        // window. "Attached" is the aggregator case above, and only when that aggregator is the open one.
        Path currentRoot = ops.currentProjectRoot();
        boolean module = scratch == null
                && parentPackaging != null
                && currentRoot != null
                && projectDir
                        .toAbsolutePath()
                        .normalize()
                        .startsWith(currentRoot.toAbsolutePath().normalize());
        host.setStatus(tr("status.mavenProject.generating", spec.artifactId()));
        AutoCloseable task = host.startBackgroundTask(tr("status.mavenProject.generating", spec.artifactId()));
        ops.openConsole();
        output.started(
                this,
                tr("dialog.mavenProject.outputTab"),
                ArchetypeGenerate.displayCommand(argv),
                OutputStyle.maven(),
                service::stop);
        runner.run(workingDir, argv, new BuildService.Listener() {
            @Override
            public void onStart(String commandLine) {
                // header already shown by started(...)
            }

            @Override
            public void onOutput(String line, boolean stderr) {
                output.appendOutput(MavenProjectCoordinator.this, line, stderr);
            }

            @Override
            public void onExit(int code) {
                closeQuietly(task);
                output.finished(MavenProjectCoordinator.this, code);
                boolean placed = scratch == null || moveIntoPlace(scratch, spec, projectDir);
                if (code == 0 && placed && Files.isDirectory(projectDir)) {
                    afterGenerate(projectDir, spec, applied, module);
                } else {
                    failed(spec);
                }
            }

            @Override
            public void onError(String message) {
                closeQuietly(task);
                output.failed(MavenProjectCoordinator.this, message);
                failed(spec);
            }
        });
    }

    /** The message points at the Output window, so make sure it is in view. */
    private void failed(MavenProjectSpec spec) {
        ops.openConsole();
        host.setError(tr("status.mavenProject.failed", spec.artifactId()));
    }

    /** The packaging of the pom in {@code dir}, or null when it has none (or one that cannot be read). */
    private @Nullable String packagingOf(Path dir) {
        Path pom = dir.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return null;
        }
        // PomEdits rather than PomParser: this needs one element, not a validated model, and a minimal or
        // hand-written aggregator would fail full parsing and be misread as a jar project.
        String text = read(pom);
        String packaging = text == null ? null : PomEdits.packaging(text);
        // A file that exists but yields nothing is still a project — detach rather than risk the failure.
        return packaging == null ? "jar" : packaging;
    }

    /**
     * Moves the generated project out of the scratch directory to where the user asked for it.
     *
     * <p>An atomic rename when both sit on one filesystem, a recursive copy when they do not — the scratch
     * directory is the system temp dir, which is very often a different mount from the user's home.
     *
     * @return false when the project could not be put in place, so the caller reports a failure
     */
    private boolean moveIntoPlace(Path scratch, MavenProjectSpec spec, Path projectDir) {
        Path generated = scratch.resolve(spec.artifactId());
        if (!Files.isDirectory(generated)) {
            return false;
        }
        try {
            Files.createDirectories(projectDir.getParent());
            // An empty target folder is allowed by the form; the rename needs it out of the way.
            if (Files.isDirectory(projectDir) && isEmptyDirectory(projectDir)) {
                Files.delete(projectDir);
            }
            try {
                Files.move(generated, projectDir);
            } catch (java.nio.file.FileSystemException crossDevice) {
                copyRecursively(generated, projectDir);
            }
            return Files.isDirectory(projectDir);
        } catch (Exception e) {
            host.setError(tr("status.mavenProject.moveFailed", String.valueOf(e.getMessage())));
            return false;
        } finally {
            deleteRecursively(scratch);
        }
    }

    private static void copyRecursively(Path from, Path to) throws java.io.IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            }
        }
    }

    private static void deleteRecursively(Path dir) {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best-effort cleanup of a temp dir
                }
            });
        } catch (Exception ignored) {
            // best-effort
        }
    }

    /** A throwaway pom-less directory to run Maven from, or null if one cannot be made. */
    private @Nullable Path scratchDir() {
        try {
            Path dir = Files.createTempDirectory("editora-archetype");
            dir.toFile().deleteOnExit();
            return dir;
        } catch (Exception e) {
            return null; // fall back to running in place — worst case the old failure, with its own message
        }
    }

    /**
     * Applies the Advanced answers to the freshly generated project, then opens it.
     *
     * <p>Ordered so each step sees the last one's work: the pom edits first (so a chosen
     * {@code maven.compiler.release} is in place before anything reads it), then the version update, then
     * the project opens. Every step is best-effort — a failure leaves what the archetype wrote and says so,
     * because a generated project that exists is worth more than one abandoned half-way through a nicety.
     */
    private void afterGenerate(Path projectDir, MavenProjectSpec spec, MavenProjectExtras extras, boolean module) {
        if (extras.editsPom()) {
            editPom(projectDir, pom -> {
                String out = PomEdits.setProjectUrl(pom, extras.url());
                return PomEdits.setProperty(out, "maven.compiler.release", extras.javaRelease());
            });
        }
        if (!extras.updateVersions()) {
            onGenerated(projectDir, spec.artifactId(), module);
            return;
        }
        updateGeneratedVersions(projectDir, spec.artifactId(), module);
    }

    /**
     * Resolves each pinned dependency's and plugin's latest <em>stable</em> version from Maven Central and
     * rewrites the pom — the same path, policy and filter as the Update Versions command.
     *
     * <p>Dependencies used to go through {@code versions:use-latest-releases}, whose idea of a release is
     * "not a SNAPSHOT": it wrote {@code spring-core 7.1.0-M2}, {@code hibernate-core 8.0.0.Beta3} and
     * {@code netty-all 5.0.0.Alpha2} into brand-new projects while the plugin half of the same checkbox
     * filtered pre-releases out. One code path now, so both halves mean the same thing by "latest", and
     * nothing is forked for it.
     *
     * <p>Off the FX thread: one HTTPS GET per artifact. Failures are silent per artifact — see
     * {@link CentralVersions#latest} — and the whole step degrades to leaving the versions the archetype chose.
     */
    private void updateGeneratedVersions(Path projectDir, String artifactId, boolean module) {
        Path pomFile = projectDir.resolve("pom.xml");
        String pom = read(pomFile);
        Map<String, String> dependencies = pom == null ? Map.of() : PomEdits.dependencyVersions(pom);
        Map<String, String> plugins = pom == null ? Map.of() : PomEdits.pluginVersions(pom);
        if (dependencies.isEmpty() && plugins.isEmpty()) {
            finishUpdate(projectDir, artifactId, 0, 0, module);
            return;
        }
        host.setStatus(tr("status.mavenProject.updatingVersions"));
        output.appendOutput(this, tr("status.mavenVersions.checking", dependencies.size() + plugins.size()), false);
        AutoCloseable task = host.startBackgroundTask(tr("status.mavenProject.updatingVersions"));
        fetchExecutor().execute(() -> {
            List<String> all = new ArrayList<>(dependencies.keySet());
            all.addAll(plugins.keySet());
            Map<String, String> latest = CentralVersions.latest(
                    all, path -> fetchMetadataOrThrow(path, new AtomicBoolean()), lookupPool(), () -> false);
            Map<String, String> dependencyUpgrades = CentralVersions.upgradesOnly(dependencies, latest);
            Map<String, String> pluginUpgrades = CentralVersions.upgradesOnly(plugins, latest);
            Platform.runLater(() -> {
                closeQuietly(task);
                if (!dependencyUpgrades.isEmpty() || !pluginUpgrades.isEmpty()) {
                    editPom(
                            projectDir,
                            text -> PomEdits.setPluginVersions(
                                    PomEdits.setDependencyVersions(text, dependencyUpgrades), pluginUpgrades));
                    dependencyUpgrades.forEach((ga, v) ->
                            output.appendOutput(this, tr("status.mavenProject.pluginUpdated", ga, v), false));
                    pluginUpgrades.forEach((ga, v) ->
                            output.appendOutput(this, tr("status.mavenProject.pluginUpdated", ga, v), false));
                }
                finishUpdate(projectDir, artifactId, dependencyUpgrades.size(), pluginUpgrades.size(), module);
            });
        });
    }

    private void finishUpdate(
            Path projectDir, String artifactId, int dependencyUpdates, int pluginUpdates, boolean module) {
        host.setStatus(tr("status.mavenProject.versionsUpdatedCounts", dependencyUpdates, pluginUpdates));
        onGenerated(projectDir, artifactId, module);
    }

    /**
     * One {@code maven-metadata.xml}, or null when it cannot be had — except that the <em>repository</em>
     * being unreachable (no route, refused, unknown host, a connect timeout) is reported as
     * {@link CentralVersions.Unreachable}, so a lookup of forty coordinates does not wait out forty connect
     * timeouts to learn what the first one knew.
     */
    private @Nullable String fetchMetadataOrThrow(String repoPath, AtomicBoolean unreachable) {
        String url = CentralVersions.CENTRAL + repoPath;
        if (!PluginRegistry.isHttps(url)) {
            return null;
        }
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .GET()
                    .build();
            java.net.http.HttpResponse<java.io.InputStream> response =
                    metadataClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                return null;
            }
            try (java.io.InputStream in = response.body()) {
                return new String(PluginRegistry.readCapped(in, MAX_METADATA_BYTES), StandardCharsets.UTF_8);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.net.ConnectException
                | java.net.http.HttpConnectTimeoutException
                | java.net.UnknownHostException e) {
            unreachable.set(true);
            throw new CentralVersions.Unreachable(String.valueOf(e.getMessage()));
        } catch (Exception e) {
            return null; // refused, malformed, timed out mid-body — all just "no answer" for this artifact
        }
    }

    /** A metadata file is a few KB; this bound is what stops a hostile mirror streaming forever. */
    private static final long MAX_METADATA_BYTES = 4L * 1024 * 1024;

    private java.net.http.HttpClient metadataClient() {
        if (metadataClient == null) {
            metadataClient = httpClients.apply(java.time.Duration.ofSeconds(10));
        }
        return metadataClient;
    }

    /** Builds a client with the given connect timeout, for the catalog fetch and the metadata lookups. */
    private java.util.function.Function<Duration, HttpClient> httpClients = connectTimeout -> HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile java.net.http.@Nullable HttpClient metadataClient;

    /** Reads, transforms and writes the pom, doing nothing at all if any part of that fails. */
    private void editPom(Path projectDir, java.util.function.UnaryOperator<String> edit) {
        Path pomFile = projectDir.resolve("pom.xml");
        String pom = read(pomFile);
        if (pom == null) {
            return;
        }
        String out = edit.apply(pom);
        if (out == null || out.equals(pom)) {
            return;
        }
        try {
            Files.writeString(pomFile, out, StandardCharsets.UTF_8);
        } catch (Exception e) {
            host.setError(tr("status.mavenProject.pomEditFailed", String.valueOf(e.getMessage())));
        }
    }

    private @Nullable String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The generated project is on disk; now open it.
     *
     * <p>Three cases. A module of the project this window has open stays here: the tree is refreshed and
     * the module's main class (else its pom) opens in this window — it used to be registered as a second,
     * separate project in a second window. With projects on, the new project opens in a window of its own,
     * seeded with its pom and main class (the pom used to open here, in the <em>old</em> window, as a
     * foreign tab). With projects off, the pom opens here, which is all there is.
     */
    private void onGenerated(Path projectDir, String name, boolean module) {
        host.setStatus(tr("status.mavenProject.created", name));
        ops.refreshProjectTree();
        // Look at what the archetype actually wrote rather than guessing from the coordinates: quickstart
        // gives <package>.App, a webapp archetype gives no main at all. Null simply means no run
        // configuration is seeded.
        com.editora.maven.GeneratedProject.MainClass main = com.editora.maven.GeneratedProject.findMain(projectDir);
        Path pom = projectDir.resolve("pom.xml");
        String goal = com.editora.maven.GeneratedProject.mavenRunGoal(projectDir);
        if (goal != null) {
            // A JavaFX archetype's App cannot be launched as a plain main class (no module path), and the
            // run-configuration model has no "Maven goal" type yet — so say how the project is meant to run.
            output.appendOutput(this, tr("status.mavenProject.runWithMaven", goal), false);
        }
        if (module) {
            if (main != null) {
                ops.openPath(main.file());
            } else if (Files.isRegularFile(pom)) {
                ops.openPath(pom);
            }
            host.setStatus(tr("status.mavenProject.moduleAdded", name));
            return;
        }
        ops.openProject(projectDir, name, main);
        if (ops.projectsEnabled()) {
            if (com.editora.maven.GeneratedProject.needsMavenToRun(goal)) {
                host.setStatus(tr("status.mavenProject.createdRunWith", name, goal));
            }
            return; // the new window opens on its own pom and main class
        }
        if (Files.isRegularFile(pom)) {
            ops.openPath(pom);
        }
    }

    void stop() {
        service.stop();
    }

    void shutdown() {
        service.shutdown();
        cancelCheck();
        if (fetchExec != null) {
            fetchExec.shutdownNow();
        }
        if (lookupExec != null) {
            lookupExec.shutdownNow();
        }
        if (metadataClient != null) {
            metadataClient.shutdownNow();
        }
    }

    /** Test seam: where the catalog and Maven Central metadata are fetched from, so a test stays off the network. */
    void setHttpClientsForTest(java.util.function.Supplier<HttpClient> clients) {
        this.httpClients = connectTimeout -> clients.get();
    }

    /** Test seam: replace the subprocess launcher so a test never forks Maven. */
    void setRunnerForTest(Runner runner) {
        this.runner = runner;
    }
}
