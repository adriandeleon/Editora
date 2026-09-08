package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

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
import com.editora.config.Project;
import com.editora.config.ProjectManager;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;

import static com.editora.i18n.Messages.tr;

/** Creates files and projects from templates and archetypes. */
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

        void promptText(String title, String label, String initial, java.util.function.Consumer<String> onAccept);
    }

    private final Host host;

    TemplateCoordinator(Host host) {
        this.host = host;
    }

    com.editora.template.TemplateRegistry templates;

    /**
     * The Project tree's "New ▸ &lt;type&gt;" flow: prompt for a name (prefilled with the type's
     * suggestion), write the file into {@code dir}, and open it at the type's caret position.
     *
     * <p>Deliberately separate from the template wizard: this is the IDE-standard "give me a Python
     * file" gesture, where a picker plus a variable form would be four interactions for one file. All
     * of the deciding — what the typed name means, where it lands, what goes in it — is the pure
     * {@link com.editora.template.NewFileContent}, so this method only prompts, writes and reports.
     */
    void newFileOfType(java.nio.file.Path dir, com.editora.template.NewFileType type) {
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
    void createFileOfType(java.nio.file.Path dir, com.editora.template.NewFileType type, String input) {
        // A Java file's package comes from where it is being created, so "New ▸ Class" in
        // src/main/java/demo writes `package demo;` the way an IDE does — the folder already knows.
        String basePackage = type.isJava() ? com.editora.template.NewFileContent.packageFor(dir) : "";
        com.editora.template.NewFileContent.Plan plan =
                com.editora.template.NewFileContent.plan(type, input, basePackage);
        if (plan == null) {
            host.setError(tr("status.newfile.invalidName"));
            return;
        }
        // Re-check containment against the resolved path even though plan() already refuses `..` and
        // absolute names — the same belt-and-braces the template writer applies, since this is the one
        // place a typed string becomes a file.
        java.nio.file.Path target = dir.resolve(plan.relativePath()).normalize();
        if (!target.startsWith(dir.normalize())) {
            host.setError(tr("status.newfile.invalidName"));
            return;
        }
        if (java.nio.file.Files.exists(target)) {
            host.setError(tr("status.newfile.exists", plan.fileName()));
            return;
        }
        com.editora.template.NewFileContent.Rendered rendered =
                com.editora.template.NewFileContent.render(type, plan.baseName(), plan.packageName());
        try {
            if (target.getParent() != null) {
                java.nio.file.Files.createDirectories(target.getParent());
            }
            java.nio.file.Files.writeString(target, rendered.text());
        } catch (java.io.IOException e) {
            host.setError(tr("status.newfile.failed", e.getMessage()));
            return;
        }
        openAndPlaceCaret(target, rendered.caret());
        if (host.projectPanel() != null) {
            host.projectPanel().refreshTree();
        }
        host.setStatus(tr("status.newfile.created", plan.fileName()));
    }

    /**
     * {@code file.newFileOfType}: the keyboard route to the same catalog — pick a type, then name it.
     * Creates in {@link #defaultNewDir()} (the active file's folder, else the project root), since the
     * palette has no folder context.
     */
    void newFileOfTypePicker() {
        QuickOpen<com.editora.template.NewFileType> picker = new QuickOpen<>(
                tr("newfile.picker.title"),
                tr("newfile.picker.prompt"),
                () -> new ArrayList<>(com.editora.template.NewFileCatalog.all()),
                ProjectPanel::labelFor,
                t -> newFileTypeDetail(t),
                t -> newFileOfType(defaultNewDir(), t));
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.stage());
    }

    /** A picker row's detail line: the category it lives under, and the file name it suggests. */
    static String newFileTypeDetail(com.editora.template.NewFileType type) {
        String category = com.editora.template.NewFileCatalog.categoryOf(type);
        String suggested = type.suggestedFileName();
        if (category == null) {
            return suggested;
        }
        String categoryLabel = tr("newfile.category." + category);
        return suggested.isEmpty() ? categoryLabel : categoryLabel + " · " + suggested;
    }

    /**
     * Picks a template, runs the variable-entry wizard (if it has any unknown variables), then creates
     * the file(s). {@code targetDir} {@code null} = a new untitled in-editor buffer (single-file only);
     * non-null = write the file(s) into that folder and open the primary one.
     */
    void newFromTemplate(java.nio.file.Path targetDir) {
        newFromTemplate(targetDir, t -> true, null);
    }

    /**
     * As above, but restricted to templates matching {@code filter} and calling {@code onCreated} with the
     * folder that received the files.
     *
     * <p>The hook is what "New Project" needs: the generation, the variable wizard and the target-folder
     * field are all already right for scaffolding a tree — the only thing missing was registering the result
     * as a project afterwards, so that is threaded through rather than duplicating the flow.
     */
    void newFromTemplate(
            java.nio.file.Path targetDir,
            java.util.function.Predicate<com.editora.template.Template> filter,
            java.util.function.Consumer<java.nio.file.Path> onCreated) {
        List<com.editora.template.Template> all =
                templates.all().stream().filter(filter).toList();
        if (all.isEmpty()) {
            host.setStatus(tr("status.noTemplates"));
            return;
        }
        QuickOpen<com.editora.template.Template> picker = new QuickOpen<>(
                tr("template.picker.title"),
                tr("template.picker.prompt"),
                () -> new ArrayList<>(all),
                com.editora.template.Template::name,
                com.editora.template.Template::description,
                t -> beginTemplate(t, targetDir, onCreated));
        // Wider than the default picker + a taller minimum: template descriptions are long, so the
        // default 620px clipped them (and showed a horizontal scrollbar).
        picker.setPreferredSize(820, 8);
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.stage());
    }

    /**
     * {@code project.newFromTemplate}: scaffolds a new project from a multi-file template and opens it.
     *
     * <p>Reuses the ordinary template flow — the picker, the variable wizard and its target-folder field
     * already do the generation correctly — and only adds what was actually missing: registering the folder
     * as a project and opening its window. Restricted to multi-file templates because a project is a tree; a
     * single-file template produces a lone file, which is what {@code template.new} is already for.
     */
    void newProjectFromTemplate() {
        if (!host.projectsEnabled()) {
            return; // the palette already hides project.* when the feature is off
        }
        newFromTemplate(defaultNewDir(), com.editora.template.Template::isMultiFile, dir -> {
            Project project = host.projects().createOrGet(dir.getFileName().toString(), dir);
            host.projects().save();
            host.setStatus(tr("status.project.createdFromTemplate", project.name()));
            if (host.windowManager() != null) {
                host.windowManager().openOrFocus(project);
            }
        });
    }

    /** Discovers the template's unknown variables; prompts for them via a wizard, else applies directly. */
    void beginTemplate(
            com.editora.template.Template t,
            java.nio.file.Path targetDir,
            java.util.function.Consumer<java.nio.file.Path> onCreated) {
        List<com.editora.template.TemplateEngine.TemplateVar> vars;
        if (t.isMultiFile()) {
            List<String> texts = new ArrayList<>();
            for (com.editora.template.TemplateFile f : t.files()) {
                texts.add(f.path());
                texts.add(f.body());
            }
            vars = com.editora.template.TemplateEngine.discoverVariables(texts.toArray(new String[0]));
        } else {
            // The file-name pattern's own ${baseName}/${fileName}/${extension} can't be derived for a new
            // file, so prompt for them (the body's stay auto-derived) — otherwise ${baseName:Main}.java
            // silently used its default and the user was never asked for the name.
            vars = com.editora.template.TemplateEngine.discoverVariablesForNewFile(t.fileName(), t.body());
        }
        // Fast path: a variable-less, single-file template invoked with no folder context (palette / toolbar)
        // creates an untitled scratch buffer immediately — no wizard. A multi-file template always writes to
        // disk, so it goes through the wizard (to offer the target folder) even with no variables.
        if (vars.isEmpty() && targetDir == null && !t.isMultiFile()) {
            applyTemplate(t, null, java.util.Map.of(), onCreated);
            return;
        }

        VBox body = new VBox(8);
        // Optional target-folder field (prefilled from the folder context — e.g. the right-clicked project
        // folder). Left blank, a single-file template opens as an untitled buffer (Save prompts for a
        // location); filled (typed or Browse), the file(s) are written into that folder, creating it if needed.
        TextField folderField = new TextField(targetDir == null ? "" : targetDir.toString());
        folderField.setPromptText(tr("template.wizard.folderPrompt"));
        folderField.setPrefColumnCount(28);
        com.editora.command.TextInputKeymap.install(folderField, host.keymap());
        Button browse = new Button(tr("dialog.clone.browse"));
        browse.setFocusTraversable(false);
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(tr("template.wizard.folderTitle"));
            java.io.File init = templateFolderChooserDir(folderField.getText());
            if (init != null) {
                chooser.setInitialDirectory(init);
            }
            java.io.File dir = chooser.showDialog(host.stage());
            if (dir != null) {
                folderField.setText(dir.toString());
            }
        });
        HBox folderRow = new HBox(6, folderField, browse);
        HBox.setHgrow(folderField, Priority.ALWAYS);
        body.getChildren().addAll(new Label(tr("template.wizard.folder")), folderRow);

        java.util.LinkedHashMap<String, TextField> fields = new java.util.LinkedHashMap<>();
        for (var v : vars) {
            TextField field = new TextField(v.defaultValue());
            field.setPrefColumnCount(28);
            com.editora.command.TextInputKeymap.install(field, host.keymap());
            fields.put(v.name(), field);
            body.getChildren().addAll(new Label(v.name()), field);
        }
        // Focus the first variable (the thing most likely to be edited), else the folder field.
        TextField initialFocus =
                fields.isEmpty() ? folderField : fields.values().iterator().next();
        OverlayInput.show(
                host.overlayHost(),
                tr("template.wizard.title"),
                body,
                initialFocus,
                tr("dialog.template.create"),
                null,
                () -> {
                    java.util.LinkedHashMap<String, String> answers = new java.util.LinkedHashMap<>();
                    fields.forEach((name, f) -> answers.put(name, f.getText()));
                    // Blank → null (untitled buffer / defaultNewDir for multi-file); a relative path resolves
                    // against the folder context, else the default new-file dir; ~ expands to home.
                    java.nio.file.Path base = targetDir != null ? targetDir : defaultNewDir();
                    java.nio.file.Path dir = com.editora.config.PathKeys.resolveUserInput(
                            folderField.getText(), base, System.getProperty("user.home"));
                    applyTemplate(t, dir, answers, onCreated);
                },
                null,
                false);
    }

    /** The folder a template-wizard folder chooser should open at: the typed folder if it exists, walking up
     *  to the nearest existing ancestor, else the default new-file directory. Null only if nothing exists. */
    java.io.File templateFolderChooserDir(String current) {
        java.nio.file.Path p =
                com.editora.config.PathKeys.resolveUserInput(current, defaultNewDir(), System.getProperty("user.home"));
        if (p == null) {
            p = defaultNewDir();
        }
        while (p != null && !java.nio.file.Files.isDirectory(p)) {
            p = p.getParent();
        }
        return p == null ? null : p.toFile();
    }

    /** Renders {@code t} with {@code answers} and creates the file(s) (untitled buffer or written to disk). */
    void applyTemplate(
            com.editora.template.Template t,
            java.nio.file.Path targetDir,
            java.util.Map<String, String> answers,
            java.util.function.Consumer<java.nio.file.Path> onCreated) {
        Settings s = host.config().getSettings();
        String author = s.getAuthorName();
        String projectName = activeProjectName();
        String packageName = "";
        java.time.LocalDateTime now = java.time.LocalDateTime.now();

        if (t.isMultiFile()) {
            applyMultiFileTemplate(
                    t,
                    targetDir != null ? targetDir : defaultNewDir(),
                    answers,
                    author,
                    projectName,
                    packageName,
                    now,
                    onCreated);
            return;
        }
        // Resolve the file name first (so the body's ${fileName}/${baseName}/${extension} are correct).
        com.editora.template.TemplateVariableResolver pre = new com.editora.template.TemplateVariableResolver(
                answers, author, projectName, packageName, "", targetDir == null ? "" : targetDir.toString(), "", now);
        String fileName = com.editora.template.TemplateEngine.expand(t.fileName(), pre);
        if (fileName.isBlank()) {
            fileName = "untitled";
        }
        java.nio.file.Path target = null;
        if (targetDir != null) {
            // Contain the file name to targetDir, exactly as the multi-file path does via resolveTargetPath:
            // a `../…` or absolute fileName pattern (from a malicious/imported template) must not escape and
            // create files anywhere writable (a shell rc, an autostart entry, a git hook).
            java.nio.file.Path resolved = targetDir.resolve(fileName).normalize();
            if (!resolved.startsWith(targetDir.normalize())) {
                host.setStatus(tr("status.templatePathEscape"));
                return;
            }
            target = resolved;
        }
        com.editora.template.TemplateVariableResolver vars = new com.editora.template.TemplateVariableResolver(
                answers,
                author,
                projectName,
                packageName,
                fileName,
                targetDir == null ? "" : targetDir.toString(),
                target == null ? "" : target.toString(),
                now);
        com.editora.snippet.ParsedSnippet parsed = com.editora.template.TemplateEngine.substitute(t.body(), vars);

        if (targetDir == null) {
            EditorBuffer b = new EditorBuffer();
            b.setDisplayName(fileName); // tab title + extension-based grammar; path stays null → Save-As
            host.addBuffer(b, true);
            b.applyTemplate(parsed);
            host.setStatus(tr("status.templateCreated", fileName));
        } else if (writeTemplateFile(target, parsed)) {
            openAndPlaceCaret(target, finalCaret(parsed));
            if (host.projectPanel() != null) {
                host.projectPanel().refreshTree();
            }
            host.setStatus(tr("status.templateCreated", fileName));
        }
    }

    void applyMultiFileTemplate(
            com.editora.template.Template t,
            java.nio.file.Path dir,
            java.util.Map<String, String> answers,
            String author,
            String projectName,
            String packageName,
            java.time.LocalDateTime now,
            java.util.function.Consumer<java.nio.file.Path> onCreated) {
        com.editora.template.TemplateVariableResolver vars = new com.editora.template.TemplateVariableResolver(
                answers, author, projectName, packageName, "", dir.toString(), "", now);
        java.nio.file.Path primary = null;
        int primaryCaret = 0;
        for (com.editora.template.TemplateFile f : t.files()) {
            java.nio.file.Path target = com.editora.template.TemplateEngine.resolveTargetPath(dir, f.path(), vars);
            if (target == null) {
                host.setStatus(tr("status.templatePathEscape"));
                continue;
            }
            com.editora.snippet.ParsedSnippet parsed = com.editora.template.TemplateEngine.substitute(f.body(), vars);
            if (writeTemplateFile(target, parsed) && primary == null) {
                primary = target;
                primaryCaret = finalCaret(parsed);
            }
        }
        if (primary != null) {
            openAndPlaceCaret(primary, primaryCaret);
            if (host.projectPanel() != null) {
                host.projectPanel().refreshTree();
            }
            host.setStatus(tr("status.templateCreated", primary.getFileName().toString()));
            if (onCreated != null) {
                onCreated.accept(dir); // only after at least one file landed — an empty folder is not a project
            }
        }
    }

    /** Writes a rendered template file (UTF-8), refusing to overwrite an existing file. */
    boolean writeTemplateFile(java.nio.file.Path target, com.editora.snippet.ParsedSnippet parsed) {
        if (java.nio.file.Files.exists(target)) {
            host.setStatus(tr("status.templateExists", target.getFileName()));
            return false;
        }
        try {
            if (target.getParent() != null) {
                java.nio.file.Files.createDirectories(target.getParent());
            }
            java.nio.file.Files.writeString(target, parsed.text());
            return true;
        } catch (java.io.IOException e) {
            host.setStatus(tr("status.templateWriteFailed", e.getMessage()));
            return false;
        }
    }

    /** Opens {@code target} and places the caret at the template's {@code ${cursor}} offset. */
    void openAndPlaceCaret(java.nio.file.Path target, int caret) {
        host.fileWorkflows().openPath(target);
        EditorBuffer b = host.activeBuffer();
        if (b != null) {
            CodeArea a = b.getArea();
            int c = Math.max(0, Math.min(caret, a.getLength()));
            javafx.application.Platform.runLater(() -> {
                a.moveTo(c);
                a.requestFollowCaret();
            });
        }
    }

    /** The absolute offset of the template's {@code $0} ({@code ${cursor}}) stop, else end of text. */
    static int finalCaret(com.editora.snippet.ParsedSnippet parsed) {
        for (com.editora.snippet.TabStop stop : parsed.stops()) {
            if (stop.isFinal()) {
                return stop.ranges().get(0)[0];
            }
        }
        return parsed.text().length();
    }

    /** The active project's name for {@code ${projectName}}, or {@code ""} when there is none. */
    String activeProjectName() {
        Project p = host.projects() == null ? null : host.projects().active();
        return p == null ? "" : p.name();
    }

    /** The folder a "new in folder" template defaults to: the active file's dir, else project root, else home. */
    java.nio.file.Path defaultNewDir() {
        EditorBuffer b = host.activeBuffer();
        if (b != null && b.getPath() != null && b.getPath().getParent() != null) {
            return b.getPath().getParent();
        }
        Project p = host.projects() == null ? null : host.projects().active();
        if (p != null) {
            return java.nio.file.Path.of(p.root());
        }
        return java.nio.file.Path.of(System.getProperty("user.home", "."));
    }

    /** Opens (creating from an example if needed) a user template file under {@code <configDir>/templates}. */
    void editUserTemplates() {
        java.nio.file.Path file = templates.userDir().resolve("example.json");
        try {
            if (!java.nio.file.Files.exists(file)) {
                java.nio.file.Files.createDirectories(file.getParent());
                java.nio.file.Files.writeString(file, USER_TEMPLATE_EXAMPLE);
            }
            host.fileWorkflows().openPath(file);
            host.setStatus(tr("status.editingTemplates"));
        } catch (java.io.IOException e) {
            host.setStatus(tr("status.templateOpenFailed", e.getMessage()));
        }
    }

    static final String USER_TEMPLATE_EXAMPLE = """
            {
              "name": "My Template",
              "description": "A starter template — edit it, then run \\"Template: Reload Templates\\"",
              "language": "java",
              "fileName": "${className:Example}.java",
              "body": [
                "public class ${className:Example} {",
                "    ${cursor}",
                "}"
              ]
            }
            """;
}
