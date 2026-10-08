package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.PathKeys;
import com.editora.config.Project;
import com.editora.config.ProjectManager;
import com.editora.editor.EditorBuffer;
import com.editora.editor.GrammarRegistry;
import com.editora.editorconfig.EditorConfig;
import com.editora.editorconfig.EditorConfigProperties;
import com.editora.i18n.Messages;
import com.editora.io.PathContainment;
import com.editora.template.NewFileCatalog;
import com.editora.template.NewFileContent;
import com.editora.template.NewFileType;
import com.editora.template.PortableFileName;
import com.editora.template.Template;
import com.editora.template.TemplateEngine;
import com.editora.template.TemplateNames;
import com.editora.template.TemplateOutput;
import com.editora.template.TemplateRegistry;
import org.fxmisc.richtext.CodeArea;

import static com.editora.i18n.Messages.tr;

/**
 * Creates files and projects from templates and from the "New ▸ &lt;type&gt;" catalog.
 *
 * <p>The deciding is pure and lives in {@code template/} — what a typed name means
 * ({@link NewFileContent}), which variables to ask for and how a body renders ({@link TemplateEngine}),
 * which files an apply would create ({@link TemplateOutput}). This class prompts, runs the plan and the
 * write off the FX thread, and reports: every refusal and failure goes through {@code setError}, and a
 * wizard stays open with what was typed when its apply does not succeed.
 */
final class TemplateCoordinator {
    interface Host {
        FileWorkflowCoordinator fileWorkflows();

        Stage stage();

        ConfigManager config();

        KeymapManager keymap();

        OverlayHost overlayHost();

        ProjectPanel projectPanel();

        ProjectManager projects();

        WindowManager windowManager();

        boolean projectsEnabled();

        String homeCollapsed(String full);

        void setStatus(String message);

        void setError(String message);

        EditorBuffer activeBuffer();

        Tab addBuffer(EditorBuffer buffer);

        Tab addBuffer(EditorBuffer buffer, boolean select);

        Tab addBuffer(EditorBuffer buffer, boolean select, boolean resolvePathSettings);

        void promptText(String title, String label, String initial, Consumer<String> onAccept);
    }

    /** How an apply ends, as far as the form that started it is concerned. Called on the FX thread. */
    interface Reporter {
        /** The files were created: the form can close. */
        void done();

        /** Nothing more will happen; {@code message} says why. The form stays, with its fields intact. */
        void failed(String message);

        /**
         * Some of the files already exist. Returns true to go on and create only the missing ones; false
         * when the user has to be asked first ({@code message} is the question).
         */
        boolean confirmExisting(Object key, String message);
    }

    private final Host host;

    TemplateCoordinator(Host host) {
        this.host = host;
    }

    TemplateRegistry templates;

    /** Opens a just-created project on its primary file; replaceable so a test need not build a window. */
    java.util.function.BiConsumer<Project, TemplateOutput.Target> openProject = this::openProjectWindow;

    private void openProjectWindow(Project project, TemplateOutput.Target primary) {
        if (host.windowManager() != null) {
            // The project opens in its own window, on the file the template marks with ${cursor}.
            host.windowManager().openInWindow(project.id(), primary.path(), lineOf(primary));
        } else {
            openAndPlaceCaret(primary.path(), primary.caret(), null, null);
        }
    }

    /** Runs the plan + write off the FX thread; replaceable so a test can run it inline. */
    Executor io = task -> Thread.ofVirtual().name("template-write").start(task);

    // --- New ▸ <type> -----------------------------------------------------------------------------

    /**
     * The Project tree's "New ▸ &lt;type&gt;" flow: prompt for a name (prefilled with the type's
     * suggestion), write the file into {@code dir}, and open it at the type's caret position.
     *
     * <p>Deliberately separate from the template wizard: this is the IDE-standard "give me a Python
     * file" gesture, where a picker plus a variable form would be four interactions for one file. All
     * of the deciding — what the typed name means, where it lands, what goes in it — is the pure
     * {@link NewFileContent}, so this method only prompts, writes and reports.
     */
    void newFileOfType(Path dir, NewFileType type) {
        if (dir == null || type == null) {
            return;
        }
        // The folder is in the prompt, not just the title: from the palette it comes from the active
        // file rather than from something the user just clicked, so "which folder?" is a real question.
        host.promptText(
                tr("newfile.prompt.title", ProjectPanel.labelFor(type)),
                tr("newfile.prompt.label", host.homeCollapsed(dir.toString())),
                type.suggestedFileName(),
                input -> createFileOfType(dir, type, input));
    }

    /** Writes the planned file and opens it; reports (without creating anything) on any refusal. */
    void createFileOfType(Path dir, NewFileType type, String input) {
        // A Java file's package comes from where it is being created, so "New ▸ Class" in
        // src/main/java/demo writes `package demo;` the way an IDE does — the folder already knows. The
        // search stops at the project root: a project kept under ~/src is not in package "<user>.…".
        String basePackage = type.isJava() ? NewFileContent.packageFor(dir, projectRootFor(dir)) : "";
        NewFileContent.Decision decision = NewFileContent.decide(type, input, basePackage);
        if (decision.plan() == null) {
            host.setError(refusalMessage(decision.refusal(), input));
            return;
        }
        NewFileContent.Plan plan = decision.plan();
        // Re-check containment on the real path even though decide() already refuses `..` and absolute
        // names: this is the one place a typed string becomes a file, and a folder in the way may be a
        // symbolic link to somewhere else.
        Path target = dir.resolve(plan.relativePath()).normalize();
        if (!target.startsWith(dir.normalize()) || !PathContainment.isWithin(dir, target)) {
            host.setError(tr("status.newfile.invalidName"));
            return;
        }
        if (Files.exists(target)) {
            host.setError(tr("status.newfile.exists", plan.fileName()));
            return;
        }
        NewFileContent.Rendered rendered = NewFileContent.render(type, plan.baseName(), plan.packageName());
        try {
            TemplateOutput.create(dir, target, TemplateOutput.finish(rendered.text(), editorConfigFor(target)));
        } catch (IOException e) {
            host.setError(tr("status.newfile.failed", e.getMessage()));
            return;
        }
        String created = tr("status.newfile.created", plan.fileName());
        openAndPlaceCaret(target, rendered.caret(), null, created);
        refreshTree();
        host.setStatus(created);
    }

    /** The message for a refused name: each reason says what to change, not just "not valid". */
    static String refusalMessage(NewFileContent.Refusal refusal, String input) {
        String typed = input == null ? "" : input.strip();
        return switch (refusal == null ? NewFileContent.Refusal.INVALID_NAME : refusal) {
            case INVALID_NAME -> tr("status.newfile.invalidName");
            case ILLEGAL_CHARACTER -> tr("status.newfile.refused.illegalCharacter", typed);
            case RESERVED_NAME -> tr("status.newfile.refused.reservedName", typed);
            case HOME_SHORTHAND -> tr("status.newfile.refused.homeShorthand");
            case NOT_A_JAVA_NAME -> tr("status.newfile.refused.notJavaName", typed);
            case JAVA_KEYWORD -> tr("status.newfile.refused.javaKeyword", typed);
            case NOT_A_JAVA_FILE -> tr("status.newfile.refused.notJavaFile", typed);
            case NO_PACKAGE -> tr("status.newfile.refused.noPackage");
        };
    }

    /**
     * {@code file.newFileOfType}: the keyboard route to the same catalog — pick a type, then name it.
     * Creates in {@link #defaultNewDir()} (the active file's folder, else the project root), since the
     * palette has no folder context.
     */
    void newFileOfTypePicker() {
        QuickOpen<NewFileType> picker = new QuickOpen<>(
                tr("newfile.picker.title"),
                tr("newfile.picker.prompt"),
                () -> new ArrayList<>(NewFileCatalog.all()),
                ProjectPanel::labelFor,
                t -> newFileTypeDetail(t),
                t -> newFileOfType(defaultNewDir(), t));
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.stage());
    }

    /** A picker row's detail line: the category it lives under, and the file name it suggests. */
    static String newFileTypeDetail(NewFileType type) {
        String category = NewFileCatalog.categoryOf(type);
        String suggested = type.suggestedFileName();
        if (category == null) {
            return suggested;
        }
        String categoryLabel = tr("newfile.category." + category);
        return suggested.isEmpty() ? categoryLabel : categoryLabel + " · " + suggested;
    }

    // --- New from template: picker ----------------------------------------------------------------

    /**
     * Picks a template, runs the wizard, then creates the file(s). {@code targetDir} {@code null} = no
     * folder context: a single-file template becomes an unsaved buffer unless a folder is typed, and a
     * multi-file template asks for the folder. Non-null = the wizard's folder is pre-filled with it.
     */
    void newFromTemplate(Path targetDir) {
        pickTemplate(tr("template.picker.title"), t -> true, t -> beginTemplate(t, targetDir, null));
    }

    /**
     * {@code project.newFromTemplate}: asks for a location and a project name, creates
     * {@code <location>/<name>/}, scaffolds a multi-file template into it, registers the folder as a project
     * and opens it. Restricted to multi-file templates because a project is a tree; a single-file template
     * produces a lone file, which is what {@code template.new} is for.
     */
    void newProjectFromTemplate() {
        if (!host.projectsEnabled()) {
            return; // the palette already hides project.* when the feature is off
        }
        pickTemplate(tr("template.project.title"), Template::isMultiFile, this::beginProject);
    }

    private void pickTemplate(String title, Predicate<Template> filter, Consumer<Template> onChoose) {
        TemplateRegistry.Loaded loaded = templates.load();
        List<Template> all = loaded.templates().stream().filter(filter).toList();
        if (all.isEmpty()) {
            host.setStatus(tr("status.noTemplates"));
            return;
        }
        Set<String> ambiguous = ambiguousNames(all);
        QuickOpen<Template> picker = new QuickOpen<>(
                title,
                tr("template.picker.prompt"),
                () -> new ArrayList<>(all),
                t -> pickerLabel(t, ambiguous),
                TemplateCoordinator::pickerDetail,
                onChoose);
        // Wider than the default picker + a taller minimum: template descriptions are long, so the
        // default 620px clipped them (and showed a horizontal scrollbar).
        picker.setPreferredSize(820, 8);
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.stage());
        if (!loaded.problems().isEmpty()) {
            host.setError(problemsMessage(loaded.problems())); // a template that is missing has a reason
        }
    }

    /** The names more than one of {@code templates} share: those rows also show their id. */
    static Set<String> ambiguousNames(List<Template> templates) {
        Set<String> seen = new HashSet<>();
        Set<String> twice = new HashSet<>();
        for (Template t : templates) {
            if (!seen.add(t.name())) {
                twice.add(t.name());
            }
        }
        return twice;
    }

    /** The picker row's title: the name, plus the id when another template has the same name. */
    static String pickerLabel(Template t, Set<String> ambiguous) {
        return ambiguous.contains(t.name()) ? t.name() + " (" + t.id() + ")" : t.name();
    }

    /** The picker row's detail: where the template comes from, then its description. */
    static String pickerDetail(Template t) {
        String origin = originLabel(t);
        return t.description() == null || t.description().isBlank() ? origin : origin + " · " + t.description();
    }

    /** "bundled", "yours" or "plugin: name" — the same words the Settings list tags rows with. */
    static String originLabel(Template t) {
        return switch (t.origin()) {
            case BUNDLED -> tr("template.origin.bundled");
            case USER -> tr("template.origin.user");
            case PLUGIN ->
                t.source().isBlank() ? tr("settings.template.pluginTag") : tr("template.origin.plugin", t.source());
        };
    }

    // --- New from template: the file wizard -------------------------------------------------------

    /** Asks for the folder and the template's variables, then applies; applies directly when there is nothing to ask. */
    void beginTemplate(Template t, Path targetDir, Consumer<Path> onCreated) {
        List<TemplateEngine.TemplateVar> vars =
                TemplateEngine.promptedVariables(t, new TemplateEngine.Context(activeProject() != null));
        // Fast path: a variable-less, single-file template invoked with no folder context (palette / toolbar)
        // creates an untitled scratch buffer immediately — no wizard. A multi-file template always writes to
        // disk, so it goes through the wizard (which requires the target folder) even with no variables.
        if (vars.isEmpty() && targetDir == null && !t.isMultiFile()) {
            applyTemplate(t, null, Map.of(), onCreated);
            return;
        }
        boolean multi = t.isMultiFile();
        VBox body = new VBox(8);
        // The target folder, prefilled from the folder context (the right-clicked project folder). A
        // single-file template may leave it blank and get an unsaved buffer; a multi-file template has
        // nowhere else to go, so its field is required and there is no default to fall back on.
        TextField folderField = new TextField(targetDir == null ? "" : targetDir.toString());
        folderField.setPromptText(tr(multi ? "template.wizard.folderRequiredPrompt" : "template.wizard.folderPrompt"));
        body.getChildren()
                .addAll(
                        new Label(tr(multi ? "template.wizard.folderRequiredLabel" : "template.wizard.folder")),
                        folderRow(folderField, () -> folderChooserDir(folderField.getText(), targetDir)));
        LinkedHashMap<String, TextField> fields = variableFields(t, vars, body);
        // Focus the first variable (the thing most likely to be edited), else the folder field.
        TextField initialFocus =
                fields.isEmpty() ? folderField : fields.values().iterator().next();
        Object[] confirmed = {null};
        OverlayInput.showSubmitting(
                host.overlayHost(),
                tr("template.wizard.title", t.name()),
                body,
                initialFocus,
                tr("dialog.template.create"),
                null,
                submission -> {
                    Reporter reporter = wizardReporter(submission, confirmed);
                    String typed = folderField.getText() == null
                            ? ""
                            : folderField.getText().strip();
                    Path dir = null;
                    if (typed.isEmpty()) {
                        if (multi) {
                            reporter.failed(tr("template.wizard.folderRequired"));
                            return;
                        }
                    } else {
                        dir = resolveFolder(typed, targetDir);
                        if (dir == null) {
                            reporter.failed(tr("template.wizard.folderRelative"));
                            return;
                        }
                    }
                    apply(t, dir, answersOf(fields), onCreated, reporter);
                },
                false);
    }

    /** The folder field with its Browse button. */
    private HBox folderRow(TextField field, java.util.function.Supplier<java.io.File> initialDir) {
        field.setPrefColumnCount(28);
        com.editora.command.TextInputKeymap.install(field, host.keymap());
        Button browse = new Button(tr("dialog.clone.browse"));
        browse.setFocusTraversable(false);
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(tr("template.wizard.folderTitle"));
            java.io.File init = initialDir.get();
            if (init != null) {
                chooser.setInitialDirectory(init);
            }
            java.io.File dir = chooser.showDialog(host.stage());
            if (dir != null) {
                field.setText(dir.toString());
            }
        });
        HBox row = new HBox(6, field, browse);
        HBox.setHgrow(field, Priority.ALWAYS);
        return row;
    }

    /** One labelled field per prompted variable, pre-filled with its default, added to {@code body}. */
    private LinkedHashMap<String, TextField> variableFields(
            Template t, List<TemplateEngine.TemplateVar> vars, VBox body) {
        LinkedHashMap<String, TextField> fields = new LinkedHashMap<>();
        for (TemplateEngine.TemplateVar v : vars) {
            TextField field = new TextField(v.defaultValue());
            field.setPrefColumnCount(28);
            com.editora.command.TextInputKeymap.install(field, host.keymap());
            fields.put(v.name(), field);
            Label label = new Label(variableLabel(t, v.name()));
            label.setLabelFor(field);
            body.getChildren().addAll(label, field);
        }
        return fields;
    }

    private static LinkedHashMap<String, String> answersOf(Map<String, TextField> fields) {
        LinkedHashMap<String, String> answers = new LinkedHashMap<>();
        fields.forEach((name, f) -> answers.put(name, f.getText() == null ? "" : f.getText()));
        return answers;
    }

    /**
     * A wizard field's label: the template's own ({@code "labels"} in its JSON), else Editora's translated
     * name for a variable it knows ({@code template.var.<name>}), else the identifier made readable
     * ({@code issueTitle} → "Issue title").
     */
    static String variableLabel(Template t, String name) {
        String own = t == null ? null : t.labels().get(name);
        if (own != null && !own.isBlank()) {
            return own;
        }
        String key = "template.var." + name;
        return Messages.keys().contains(key) ? tr(key) : TemplateNames.humanize(name);
    }

    /** Routes an apply's outcome to the wizard card (which stays open on failure) and the status bar. */
    private Reporter wizardReporter(OverlayInput.Submission submission, Object[] confirmed) {
        return new Reporter() {
            @Override
            public void done() {
                submission.done();
            }

            @Override
            public void failed(String message) {
                host.setError(message);
                submission.failed(message);
            }

            @Override
            public boolean confirmExisting(Object key, String message) {
                if (key.equals(confirmed[0])) {
                    return true; // the same answers, submitted again after seeing the list
                }
                confirmed[0] = key;
                submission.failed(message);
                return false;
            }
        };
    }

    /**
     * The typed folder as a path: {@code ~} expands, and a relative path resolves against the folder
     * context, else the active project's root. With neither, a relative path has nothing sensible to be
     * relative to and is refused (null) rather than landing beside whatever file happens to be open.
     */
    Path resolveFolder(String typed, Path context) {
        String home = System.getProperty("user.home");
        Path base = context;
        if (base == null) {
            Project p = activeProject();
            base = p == null ? null : Path.of(p.root());
        }
        if (base == null && !isAbsoluteInput(typed)) {
            return null;
        }
        return PathKeys.resolveUserInput(typed, base, home);
    }

    private static boolean isAbsoluteInput(String typed) {
        if (typed.equals("~") || typed.startsWith("~/") || typed.startsWith("~\\")) {
            return true;
        }
        try {
            return Path.of(typed).isAbsolute();
        } catch (java.nio.file.InvalidPathException e) {
            return false;
        }
    }

    /** Where a folder chooser opens: the typed folder or its nearest existing ancestor, else the context. */
    java.io.File folderChooserDir(String current, Path context) {
        Path p = current == null || current.isBlank() ? null : resolveFolder(current.strip(), context);
        if (p == null) {
            p = context != null ? context : defaultNewDir();
        }
        while (p != null && !Files.isDirectory(p)) {
            p = p.getParent();
        }
        return p == null ? null : p.toFile();
    }

    // --- New project from template: the project wizard --------------------------------------------

    /** Location + project name + the template's variables; creates {@code <location>/<name>/} and opens it. */
    void beginProject(Template t) {
        List<TemplateEngine.TemplateVar> vars = TemplateEngine.promptedVariables(t, new TemplateEngine.Context(true));
        VBox body = new VBox(8);
        Path defaultParent = defaultProjectParent();
        TextField locationField = new TextField(defaultParent.toString());
        TextField nameField = new TextField();
        nameField.setPromptText(tr("template.project.namePrompt"));
        nameField.setPrefColumnCount(28);
        com.editora.command.TextInputKeymap.install(nameField, host.keymap());
        Label nameLabel = new Label(tr("template.project.name"));
        nameLabel.setLabelFor(nameField);
        Label willCreate = new Label();
        willCreate.getStyleClass().add("text-muted");
        willCreate.setWrapText(true);
        body.getChildren()
                .addAll(
                        nameLabel,
                        nameField,
                        new Label(tr("template.project.location")),
                        folderRow(locationField, () -> folderChooserDir(locationField.getText(), null)),
                        willCreate);
        LinkedHashMap<String, TextField> fields = variableFields(t, vars, body);

        // ${packageName} follows the project name until the user types their own.
        TextField packageField = fields.get("packageName");
        String templateDefault = packageField == null ? "" : packageField.getText();
        boolean[] packageTouched = {false};
        boolean[] syncing = {false};
        if (packageField != null) {
            packageField.textProperty().addListener((o, was, now) -> {
                if (!syncing[0]) {
                    packageTouched[0] = true;
                }
            });
        }
        Runnable sync = () -> {
            String name = nameField.getText() == null ? "" : nameField.getText().strip();
            Path parent = projectParent(locationField.getText());
            willCreate.setText(
                    name.isEmpty() || parent == null
                            ? ""
                            : tr(
                                    "template.project.willCreate",
                                    host.homeCollapsed(parent.resolve(name).toString())));
            if (packageField != null && !packageTouched[0]) {
                String derived = TemplateNames.packageNameFor(name);
                syncing[0] = true;
                try {
                    packageField.setText(derived.isEmpty() ? templateDefault : derived);
                } finally {
                    syncing[0] = false;
                }
            }
        };
        nameField.textProperty().addListener((o, was, now) -> sync.run());
        locationField.textProperty().addListener((o, was, now) -> sync.run());
        sync.run();

        Object[] confirmed = {null};
        OverlayInput.showSubmitting(
                host.overlayHost(),
                tr("template.project.wizardTitle", t.name()),
                body,
                nameField,
                tr("dialog.template.create"),
                null,
                submission -> {
                    Reporter reporter = wizardReporter(submission, confirmed);
                    String name = nameField.getText() == null
                            ? ""
                            : nameField.getText().strip();
                    if (name.isEmpty()) {
                        reporter.failed(tr("template.project.nameRequired"));
                        return;
                    }
                    if (!PortableFileName.isPortable(name)) {
                        reporter.failed(tr("template.project.nameInvalid", name));
                        return;
                    }
                    Path parent = projectParent(locationField.getText());
                    if (parent == null) {
                        reporter.failed(tr("template.wizard.folderRelative"));
                        return;
                    }
                    LinkedHashMap<String, String> answers = answersOf(fields);
                    answers.put("projectName", name); // the name typed here is the project's name
                    createProject(t, parent.resolve(name), name, answers, reporter);
                },
                false);
    }

    /** The typed location as an absolute folder, or null when it is blank or relative. */
    private static Path projectParent(String typed) {
        String s = typed == null ? "" : typed.strip();
        if (s.isEmpty() || !isAbsoluteInput(s)) {
            return null;
        }
        return PathKeys.resolveUserInput(s, null, System.getProperty("user.home"));
    }

    /**
     * Where a new project goes by default: beside the active project (its root's parent), else the home
     * folder. Never the active file's folder — a project scaffolded inside a source package is the
     * accident this wizard exists to prevent.
     */
    Path defaultProjectParent() {
        Project p = activeProject();
        if (p != null) {
            Path parent = Path.of(p.root()).toAbsolutePath().normalize().getParent();
            if (parent != null) {
                return parent;
            }
        }
        return Path.of(System.getProperty("user.home", "."));
    }

    /**
     * Creates {@code projectDir} (refusing one that exists and has anything in it), scaffolds {@code t}
     * there, then registers it as a project named {@code name} and opens it.
     */
    void createProject(Template t, Path projectDir, String name, Map<String, String> answers, Reporter reporter) {
        TemplateOutput.Request request = request(t, projectDir, answers, name, projectDir);
        io.execute(() -> {
            String refusal = null;
            TemplateOutput.Outcome outcome = null;
            try {
                if (Files.exists(projectDir) && (!Files.isDirectory(projectDir) || !isEmptyDir(projectDir))) {
                    refusal = tr("template.project.exists", host.homeCollapsed(projectDir.toString()));
                } else {
                    Files.createDirectories(projectDir);
                    TemplateOutput.Plan plan = TemplateOutput.plan(request);
                    if (plan.refused()) {
                        refusal = tr("status.templatePathEscape", plan.refusedPath());
                    } else {
                        outcome = TemplateOutput.write(plan);
                    }
                }
            } catch (IOException | RuntimeException e) {
                refusal = tr("status.templateWriteFailed", String.valueOf(e.getMessage()));
            }
            String refused = refusal;
            TemplateOutput.Outcome done = outcome;
            Platform.runLater(() -> {
                if (refused != null) {
                    reporter.failed(refused);
                    return;
                }
                finish(t, done, reporter, false, created -> {
                    Project project = host.projects().createOrGet(name, created.dir());
                    host.projects().save();
                    host.setStatus(tr("status.project.createdFromTemplate", project.name()));
                    openProject.accept(project, created.primary());
                });
            });
        });
    }

    private static boolean isEmptyDir(Path dir) throws IOException {
        try (var entries = Files.newDirectoryStream(dir)) {
            return !entries.iterator().hasNext();
        }
    }

    // --- applying ---------------------------------------------------------------------------------

    /**
     * Renders {@code t} with {@code answers} and creates the file(s) with no form in front: failures go to
     * the error status, and files that already exist are kept while the missing ones are created.
     */
    void applyTemplate(Template t, Path targetDir, Map<String, String> answers, Consumer<Path> onCreated) {
        apply(t, targetDir, answers, onCreated, new Reporter() {
            @Override
            public void done() {}

            @Override
            public void failed(String message) {
                host.setError(message);
            }

            @Override
            public boolean confirmExisting(Object key, String message) {
                return true;
            }
        });
    }

    /**
     * Applies {@code t}: with no folder a single-file template becomes an unsaved buffer (a multi-file one
     * is refused — it never falls back to a folder nobody chose); with a folder, the plan is computed and
     * checked before anything is written.
     */
    void apply(Template t, Path targetDir, Map<String, String> answers, Consumer<Path> onCreated, Reporter reporter) {
        if (targetDir == null) {
            if (t.isMultiFile()) {
                reporter.failed(tr("template.wizard.folderRequired"));
                return;
            }
            TemplateOutput.Untitled untitled = TemplateOutput.untitled(request(t, null, answers, null, null));
            reporter.done(); // close the form first, so the new tab gets the focus
            EditorBuffer b = new EditorBuffer();
            b.setDisplayName(untitled.fileName()); // tab title + extension-based grammar; path stays null → Save-As
            host.addBuffer(b, true);
            CodeArea area = b.getArea();
            area.replaceText(0, area.getLength(), untitled.rendered().text());
            applyLanguage(b, t);
            placeCaret(b, untitled.rendered().caret());
            area.requestFocus();
            host.setStatus(tr("status.templateCreated", untitled.fileName()));
            return;
        }
        Path dir = targetDir.toAbsolutePath().normalize();
        TemplateOutput.Request request = request(t, dir, answers, null, projectRootFor(dir));
        Object key = List.of(dir, Map.copyOf(answers), t.id());
        io.execute(() -> {
            TemplateOutput.Plan plan;
            try {
                plan = TemplateOutput.plan(request);
            } catch (RuntimeException e) {
                Platform.runLater(
                        () -> reporter.failed(tr("status.templateWriteFailed", String.valueOf(e.getMessage()))));
                return;
            }
            Platform.runLater(() -> afterPlan(t, plan, key, onCreated, reporter));
        });
    }

    /** FX thread: refuse, ask about existing files, or hand the plan to the writer. */
    private void afterPlan(
            Template t, TemplateOutput.Plan plan, Object key, Consumer<Path> onCreated, Reporter reporter) {
        if (plan.refused()) {
            reporter.failed(tr("status.templatePathEscape", plan.refusedPath()));
            return;
        }
        List<TemplateOutput.Target> existing = plan.existing();
        if (plan.missing().isEmpty()) {
            reporter.failed(
                    plan.targets().size() == 1
                            ? tr("status.templateExists", plan.targets().get(0).relative())
                            : tr(
                                    "status.template.allExist",
                                    host.homeCollapsed(plan.dir().toString())));
            return;
        }
        if (!existing.isEmpty()
                && !reporter.confirmExisting(
                        key,
                        tr(
                                "template.wizard.existingFiles",
                                names(existing),
                                tr("dialog.template.create"),
                                plan.missing().size()))) {
            return;
        }
        io.execute(() -> {
            TemplateOutput.Outcome outcome = TemplateOutput.write(plan);
            Platform.runLater(() -> finish(
                    t, outcome, reporter, true, onCreated == null ? null : done -> onCreated.accept(done.dir())));
        });
    }

    /** FX thread: report what a write did in one message, open the primary file, refresh the tree. */
    private void finish(
            Template t,
            TemplateOutput.Outcome outcome,
            Reporter reporter,
            boolean openHere,
            Consumer<TemplateOutput.Outcome> onCreated) {
        String where = host.homeCollapsed(outcome.dir().toString());
        if (!outcome.created().isEmpty()) {
            refreshTree();
        }
        if (outcome.failed()) {
            reporter.failed(
                    outcome.created().isEmpty()
                            ? tr(
                                    "status.templateWriteFailed",
                                    outcome.failedAt().relative() + ": " + outcome.error())
                            : tr(
                                    "status.template.partialFailure",
                                    outcome.failedAt().relative(),
                                    outcome.error(),
                                    names(outcome.created())));
            return;
        }
        TemplateOutput.Target primary = outcome.primary();
        if (primary == null) {
            // Every missing file appeared between the plan and the write: nothing is ours to report created.
            reporter.failed(tr("status.template.allExist", where));
            return;
        }
        String summary;
        if (outcome.created().size() == 1 && outcome.skipped().isEmpty()) {
            summary = tr("status.templateCreated", primary.relative());
        } else if (outcome.skipped().isEmpty()) {
            summary = tr("status.template.createdFiles", outcome.created().size(), where);
        } else {
            summary = tr(
                    "status.template.createdSomeKept",
                    outcome.created().size(),
                    where,
                    outcome.skipped().size());
        }
        reporter.done(); // close the form before the file takes the focus
        host.setStatus(summary);
        // The language override belongs to a single-file template; a multi-file one's files are typed by
        // their own names.
        if (openHere) {
            openAndPlaceCaret(primary.path(), primary.caret(), t.isMultiFile() ? null : t, summary);
        }
        if (onCreated != null) {
            onCreated.accept(outcome); // only after at least one file landed — an empty folder is not a project
        }
    }

    /** The 0-based line of a target's caret, for opening it in another window. */
    static int lineOf(TemplateOutput.Target target) {
        int line = 0;
        String text = target.text();
        for (int i = 0; i < Math.min(target.caret(), text.length()); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String names(List<TemplateOutput.Target> targets) {
        return targets.stream().map(TemplateOutput.Target::relative).collect(Collectors.joining(", "));
    }

    private TemplateOutput.Request request(
            Template t, Path dir, Map<String, String> answers, String projectName, Path projectRoot) {
        return new TemplateOutput.Request(
                t,
                dir,
                answers,
                host.config().getSettings().getAuthorName(),
                projectName != null ? projectName : activeProjectName(),
                projectRoot,
                java.time.LocalDateTime.now(),
                this::editorConfigFor);
    }

    /** The project's {@code .editorconfig} rules for a file about to be created, or null when off. */
    private EditorConfigProperties editorConfigFor(Path file) {
        if (!host.config().getSettings().isEditorConfigSupport()) {
            return null;
        }
        try {
            return EditorConfig.resolveFor(file);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Opens {@code target} and, once its text has loaded, puts the caret at {@code caret}, applies the
     * template's language, and restores {@code summary} — the open reports "Opened …" when it lands, and
     * that must not be the last word on what a template just created.
     */
    void openAndPlaceCaret(Path target, int caret, Template language, String summary) {
        host.fileWorkflows().openThen(target, () -> {
            EditorBuffer b = host.activeBuffer();
            if (b == null || b.getPath() == null) {
                return; // whenLoaded has selected the file's tab; anything else means it is gone
            }
            applyLanguage(b, language);
            placeCaret(b, caret);
            if (summary != null) {
                host.setStatus(summary);
            }
        });
    }

    private static void placeCaret(EditorBuffer b, int caret) {
        CodeArea a = b.getArea();
        a.moveTo(Math.max(0, Math.min(caret, a.getLength())));
        a.requestFollowCaret();
    }

    /**
     * Honours a template's {@code language}: the created buffer gets that grammar when it names a language
     * Editora has one for and the file name alone did not already select it (a {@code Jenkinsfile} template
     * that says {@code "language": "groovy"}).
     */
    private static void applyLanguage(EditorBuffer b, Template t) {
        String language = t == null || t.language() == null ? "" : t.language().strip();
        if (needsLanguageOverride(language, b.getLanguage(), hasGrammar(language))) {
            b.setLanguageOverride(language.toLowerCase(java.util.Locale.ROOT));
        }
    }

    private static boolean hasGrammar(String language) {
        try {
            return !language.isEmpty() && GrammarRegistry.shared().forLanguageName(language) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Pure: a template language is applied only when it is known and is not what the file already is. */
    static boolean needsLanguageOverride(String templateLanguage, String bufferLanguage, boolean grammarKnown) {
        return templateLanguage != null
                && !templateLanguage.isBlank()
                && grammarKnown
                && !templateLanguage.strip().equalsIgnoreCase(bufferLanguage);
    }

    private void refreshTree() {
        if (host.projectPanel() != null) {
            host.projectPanel().refreshTree();
        }
    }

    // --- context ----------------------------------------------------------------------------------

    private Project activeProject() {
        return host.projects() == null ? null : host.projects().active();
    }

    /** The active project's name for {@code ${projectName}}, or {@code ""} when there is none. */
    String activeProjectName() {
        Project p = activeProject();
        return p == null ? "" : p.name();
    }

    /** The root of the registered project that contains {@code dir} (the innermost one), or null. */
    Path projectRootFor(Path dir) {
        if (dir == null || host.projects() == null) {
            return null;
        }
        Path abs = dir.toAbsolutePath().normalize();
        Path best = null;
        for (Project p : host.projects().list()) {
            if (p.root().isBlank()) {
                continue;
            }
            Path root = Path.of(p.root()).toAbsolutePath().normalize();
            if (abs.startsWith(root) && (best == null || root.getNameCount() > best.getNameCount())) {
                best = root;
            }
        }
        return best;
    }

    /** The folder a "new in folder" action defaults to: the active file's dir, else project root, else home. */
    Path defaultNewDir() {
        EditorBuffer b = host.activeBuffer();
        if (b != null && b.getPath() != null && b.getPath().getParent() != null) {
            return b.getPath().getParent();
        }
        Project p = activeProject();
        if (p != null) {
            return Path.of(p.root());
        }
        return Path.of(System.getProperty("user.home", "."));
    }

    // --- managing user templates ------------------------------------------------------------------

    /** {@code template.reload}: re-reads every source and reports the files that had to be skipped. */
    void reloadTemplates() {
        TemplateRegistry.Loaded loaded = templates.load();
        if (loaded.problems().isEmpty()) {
            host.setStatus(tr("status.templatesReloaded"));
        } else {
            host.setError(problemsMessage(loaded.problems()));
        }
    }

    /** "2 template files were skipped — a.json: reason; b.json: reason". */
    static String problemsMessage(List<TemplateRegistry.Problem> problems) {
        String details = problems.stream()
                .map(p -> p.file().getFileName() + ": " + problemText(p))
                .collect(Collectors.joining("; "));
        return tr("status.templates.problems", problems.size(), details);
    }

    /** Why one template file was skipped, in the user's language. */
    static String problemText(TemplateRegistry.Problem p) {
        return switch (p.kind()) {
            case MALFORMED_JSON -> tr("template.problem.malformedJson", p.line(), p.detail());
            case NOT_AN_OBJECT -> tr("template.problem.notAnObject");
            case MISSING_NAME -> tr("template.problem.missingName");
            case NO_CONTENT -> tr("template.problem.noContent");
            case BAD_FILES -> tr("template.problem.badFiles");
            case RESERVED_ID -> tr("template.problem.reservedId");
            case UNREADABLE -> tr("template.problem.unreadable", p.detail());
        };
    }

    /** One row of the "Edit User Templates" picker. */
    record EditRow(String label, String detail, Runnable action) {}

    /**
     * {@code template.editUser}: a picker over the user's template files (broken ones included, with the
     * reason they did not load), every bundled or plugin template as "Customize …" (copies it — multi-file
     * ones too — to the user's folder under the same id, so it overrides the original), and "New template".
     * The chosen JSON opens in the editor. Nothing is created until a row that says so is chosen.
     */
    void editUserTemplates() {
        QuickOpen<EditRow> picker = new QuickOpen<>(
                tr("template.edit.title"),
                tr("template.picker.prompt"),
                this::editRows,
                EditRow::label,
                EditRow::detail,
                row -> row.action().run());
        picker.setPreferredSize(820, 8);
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.stage());
    }

    List<EditRow> editRows() {
        TemplateRegistry.Loaded loaded = templates.load();
        Map<Path, TemplateRegistry.Problem> broken = new LinkedHashMap<>();
        for (TemplateRegistry.Problem p : loaded.problems()) {
            broken.put(p.file(), p);
        }
        Map<String, Template> userById = new LinkedHashMap<>();
        for (Template t : templates.userTemplates()) {
            userById.put(t.id(), t);
        }
        List<EditRow> rows = new ArrayList<>();
        for (Path file : templates.userFiles()) {
            String fileName = file.getFileName().toString();
            String id = fileName.substring(0, fileName.lastIndexOf('.'));
            TemplateRegistry.Problem problem = broken.get(file);
            Template t = userById.get(id);
            rows.add(new EditRow(
                    t != null && problem == null ? t.name() : fileName,
                    problem == null ? fileName : fileName + " — " + tr("template.edit.invalid", problemText(problem)),
                    () -> openTemplateFile(file)));
        }
        rows.add(new EditRow(tr("template.edit.new"), tr("template.edit.newDetail"), this::newUserTemplate));
        for (Template t : loaded.templates()) {
            if (t.origin() != Template.Origin.USER) {
                rows.add(new EditRow(
                        tr("template.edit.customize", t.name()),
                        originLabel(t) + " · " + tr("template.edit.customizeDetail"),
                        () -> customize(t)));
            }
        }
        return rows;
    }

    private void customize(Template t) {
        try {
            openTemplateFile(templates.duplicateToUser(t));
        } catch (IOException e) {
            host.setError(tr("status.templateOpenFailed", e.getMessage()));
        }
    }

    /** Writes a starter template under a free id and opens it. */
    private void newUserTemplate() {
        try {
            Set<String> taken =
                    templates.load().templates().stream().map(Template::id).collect(Collectors.toSet());
            String id = "my-template";
            for (int n = 2; taken.contains(id) || Files.exists(templates.userFile(id)); n++) {
                id = "my-template-" + n;
            }
            Path file = templates.userDir().resolve(id + ".json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, USER_TEMPLATE_EXAMPLE, java.nio.file.StandardOpenOption.CREATE_NEW);
            openTemplateFile(file);
        } catch (IOException e) {
            host.setError(tr("status.templateOpenFailed", e.getMessage()));
        }
    }

    private void openTemplateFile(Path file) {
        host.fileWorkflows().openPath(file);
        host.setStatus(tr("status.editingTemplates", file.getFileName().toString()));
    }

    static final String USER_TEMPLATE_EXAMPLE = """
            {
              "name": "My Template",
              "description": "A starter template: edit it and save, it is live at once",
              "language": "java",
              "fileName": "${className:Example}.java",
              "labels": { "className": "Class name" },
              "body": [
                "${packageDeclaration}public class ${className:Example} {",
                "    ${cursor}",
                "}"
              ]
            }
            """;
}
