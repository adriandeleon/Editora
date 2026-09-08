package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.Tab;
import javafx.stage.Stage;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.editor.GrammarRegistry;
import com.editora.editor.LanguageRegistry;
import com.editora.editor.SpellDictionaries;

import static com.editora.i18n.Messages.tr;

/** Applies editor settings and their command actions to this window. */
final class EditorSettingsCoordinator {
    interface Host {
        TestNavigationCoordinator testNavigation();

        FileWorkflowCoordinator fileWorkflows();

        WindowChromeCoordinator chrome();

        PreviewCoordinator previews();

        EditorArea editorArea();

        Stage stage();

        ConfigManager config();

        CommandRegistry registry();

        KeymapManager keymap();

        StatusBar statusBar();

        SettingsWindow settingsWindow();

        OverlayHost overlayHost();

        WindowManager windowManager();

        BuildOutputPanel buildOutputPanel();

        DebugCoordinator debugCoordinator();

        HistoryCoordinator historyCoordinator();

        WelcomePane welcomePane();

        Tab welcomeTab();

        Tab doctorTab();

        void applyProjectSupport();

        void applyMathSupport();

        EditingCoordinator editing();

        CoordinatorHost coordinatorHost();

        GitCoordinator git();

        GitHubCoordinator github();

        MermaidCoordinator mermaid();

        DiagramCoordinator diagram();

        TypstCoordinator typst();

        HtmlPreviewCoordinator htmlPreview();

        LogViewerCoordinator logViewer();

        ExternalToolCoordinator externalToolCoordinator();

        IndexCoordinator indexCoordinator();

        TodoCoordinator todoCoordinator();

        CsvCoordinator csvCoordinator();

        SearchCoordinator searchCoordinator();

        RunCoordinator runCoordinator();

        TestRunCoordinator testRunCoordinator();

        LspCoordinator lspCoordinator();

        NotesCoordinator notesCoordinator();

        HttpClientCoordinator httpClient();

        void applyAgentSupport();

        AiCoordinator aiCoordinator();

        DoctorCoordinator doctorCoordinator();

        void applyMcpSupport();

        void applyMainGutter(EditorBuffer buffer);

        java.util.Map<String, String> invertBindings();

        void setStatus(String message);

        EditorBuffer activeBuffer();

        void openPath(Path file);

        void openPath(Path file, boolean quietIfOpen);

        EditorBuffer bufferOf(Tab tab);

        void requestSave();

        void promptText(String title, String label, String initial, java.util.function.Consumer<String> onAccept);

        void refreshSpellAllTabs();

        void applyEditorTheme(String themeName);
    }

    private final Host host;

    EditorSettingsCoordinator(Host host) {
        this.host = host;
    }

    void toggleColumnRuler() {
        Settings s = host.config().getSettings();
        s.setShowColumnRuler(!s.isShowColumnRuler());
        host.requestSave();
        applyViewSettingsToAllBuffers(s);
        host.setStatus(tr("status.toggle.ruler", tr(s.isShowColumnRuler() ? "common.on" : "common.off")));
    }

    void toggleLineHighlight() {
        Settings s = host.config().getSettings();
        s.setHighlightCurrentLine(!s.isHighlightCurrentLine());
        host.requestSave();
        applyViewSettingsToAllBuffers(s);
        host.setStatus(tr("status.toggle.lineHighlight", tr(s.isHighlightCurrentLine() ? "common.on" : "common.off")));
    }

    void toggleLineNumbers() {
        Settings s = host.config().getSettings();
        s.setShowLineNumbers(!s.isShowLineNumbers());
        host.requestSave();
        applyViewSettingsToAllBuffers(s);
        host.setStatus(tr("status.toggle.lineNumbers", tr(s.isShowLineNumbers() ? "common.on" : "common.off")));
    }

    void toggleMinimap() {
        Settings s = host.config().getSettings();
        s.setShowMinimap(!s.isShowMinimap());
        host.requestSave();
        applyViewSettingsToAllBuffers(s);
        host.setStatus(tr("status.toggle.minimap", tr(s.isShowMinimap() ? "common.on" : "common.off")));
    }

    void toggleWordWrap() {
        Settings s = host.config().getSettings();
        s.setWordWrap(!s.isWordWrap());
        host.requestSave();
        applyViewSettingsToAllBuffers(s);
        if (host.settingsWindow() != null) {
            host.settingsWindow().syncViewChecks();
        }
        host.setStatus(tr("status.toggle.wordWrap", tr(s.isWordWrap() ? "common.on" : "common.off")));
    }

    void toggleWhitespace() {
        Settings s = host.config().getSettings();
        s.setShowWhitespace(!s.isShowWhitespace());
        host.requestSave();
        applyViewSettingsToAllBuffers(s);
        host.setStatus(tr("status.toggle.whitespace", tr(s.isShowWhitespace() ? "common.on" : "common.off")));
    }

    void toggleSpellCheck() {
        Settings s = host.config().getSettings();
        s.setSpellCheck(!s.isSpellCheck());
        host.requestSave();
        applyViewSettingsToAllBuffers(s);
        host.setStatus(tr("status.toggle.spellCheck", tr(s.isSpellCheck() ? "common.on" : "common.off")));
    }

    void togglePersonalDictionary() {
        Settings s = host.config().getSettings();
        s.setPersonalDictionary(!s.isPersonalDictionary());
        host.requestSave();
        applyViewSettingsToAllBuffers(s);
        host.settingsWindow().syncPersonalDictionaryCheck();
        host.setStatus(
                tr("status.toggle.personalDictionary", tr(s.isPersonalDictionary() ? "common.on" : "common.off")));
    }

    void toggleTechnicalDictionary() {
        Settings s = host.config().getSettings();
        s.setTechnicalDictionary(!s.isTechnicalDictionary());
        host.requestSave();
        applyViewSettingsToAllBuffers(s);
        host.settingsWindow().syncTechnicalDictionaryCheck();
        host.setStatus(
                tr("status.toggle.technicalDictionary", tr(s.isTechnicalDictionary() ? "common.on" : "common.off")));
    }

    void toggleAutocomplete() {
        Settings s = host.config().getSettings();
        s.setAutocomplete(!s.isAutocomplete());
        host.requestSave();
        applyAutocomplete();
        host.settingsWindow().syncAutocompleteChecks(); // keep the Settings window in step if it's open
        host.setStatus(tr("status.toggle.autocomplete", tr(s.isAutocomplete() ? "common.on" : "common.off")));
    }

    void toggleAutocompleteProse() {
        Settings s = host.config().getSettings();
        s.setAutocompleteProse(!s.isAutocompleteProse());
        host.requestSave();
        applyAutocomplete();
        host.settingsWindow().syncAutocompleteChecks();
        host.setStatus(tr("status.toggle.autocompleteProse", tr(s.isAutocompleteProse() ? "common.on" : "common.off")));
    }

    void toggleAutocompleteSnippets() {
        Settings s = host.config().getSettings();
        s.setAutocompleteSnippets(!s.isAutocompleteSnippets());
        host.requestSave();
        applyAutocomplete();
        host.settingsWindow().syncAutocompleteChecks();
        host.setStatus(
                tr("status.toggle.autocompleteSnippets", tr(s.isAutocompleteSnippets() ? "common.on" : "common.off")));
    }

    void toggleAutocompleteMermaid() {
        Settings s = host.config().getSettings();
        s.setAutocompleteMermaid(!s.isAutocompleteMermaid());
        host.requestSave();
        applyAutocomplete();
        host.settingsWindow().syncAutocompleteChecks();
        host.setStatus(
                tr("status.toggle.autocompleteMermaid", tr(s.isAutocompleteMermaid() ? "common.on" : "common.off")));
    }

    void toggleMultiCaret() {
        Settings s = host.config().getSettings();
        s.setMultiCaret(!s.isMultiCaret());
        host.requestSave();
        applyMultiCaret();
        host.settingsWindow().syncMultiCaretCheck(); // keep the Settings window in step if it's open
        host.setStatus(tr("status.toggle.multiCaret", tr(s.isMultiCaret() ? "common.on" : "common.off")));
    }

    /** Opens a picker to set the spell-check dictionary language for the active file (persisted per file). */
    void chooseSpellLanguage() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            host.setStatus(tr("status.noFileOpen"));
            return;
        }
        QuickOpen<String> picker = new QuickOpen<>(
                "Set Spell Check Language",
                "Type to filter languages…",
                SpellDictionaries::available,
                id -> id,
                id -> "",
                id -> setSpellLanguage(buffer, id));
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.stage());
    }

    void setSpellLanguage(EditorBuffer buffer, String langId) {
        buffer.setSpellLanguage(langId);
        Path p = buffer.getPath();
        if (p != null) {
            host.config().getWorkspaceState().getSpellLanguages().put(p.toString(), langId);
            host.requestSave();
        }
        host.setStatus(tr("status.spellLanguage", langId));
    }

    /** Picker for the active keybinding theme (the same set as the Settings → Keymaps combo). */
    void chooseKeymap() {
        QuickOpen<String> picker = new QuickOpen<>(
                tr("command.keymap.select"),
                tr("palette.keymap.prompt"),
                () -> new java.util.ArrayList<>(com.editora.command.KeymapManager.AVAILABLE.keySet()),
                com.editora.command.KeymapManager::displayName,
                id -> "",
                this::applyKeymap);
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.stage());
    }

    /** Persists the chosen keymap, reloads it live across all windows, and reports it. */
    void applyKeymap(String id) {
        if (id == null) {
            return;
        }
        host.config().getSettings().setKeymap(id);
        host.config().save();
        reloadKeymap();
        host.settingsWindow().syncKeymapCombo(); // keep the Settings window combo in step if it's open
        host.setStatus(tr("status.keymap.changed", com.editora.command.KeymapManager.displayName(id)));
    }

    /** Rebuilds the shared keymap (base + user + plugin overrides) and re-applies it to every window. */
    void reloadKeymap() {
        if (host.windowManager() != null) {
            host.windowManager().reloadSharedKeymap();
        } else {
            host.keymap().loadNamed(host.config().getSettings().getKeymap());
            host.keymap()
                    .applyOverrides(
                            host.config().getSettings().keybindingsFor(com.editora.command.KeymapManager.isMac()));
        }
    }

    /** The active keymap's bindings <em>without</em> user overrides — the defaults to rebind/reset against. */
    java.util.Map<String, String> baseBindings() {
        com.editora.command.KeymapManager base = new com.editora.command.KeymapManager();
        base.loadNamed(host.config().getSettings().getKeymap());
        return base.bindings();
    }

    /** All commands with their localized title + current effective chord, for the keybinding editor list. */
    java.util.List<SettingsWindow.Shortcut> shortcutRows() {
        java.util.Map<String, String> byCommand = host.invertBindings();
        java.util.List<SettingsWindow.Shortcut> rows = new java.util.ArrayList<>();
        for (Command c : host.registry().all()) {
            rows.add(new SettingsWindow.Shortcut(c.id(), c.title(), byCommand.get(c.id())));
        }
        rows.sort(java.util.Comparator.comparing(SettingsWindow.Shortcut::title, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    /** Persists a new user-overrides map (for the running platform), then reloads the shared keymap live. */
    void applyKeybindingOverrides(java.util.Map<String, String> overrides) {
        host.config().getSettings().setKeybindingsFor(com.editora.command.KeymapManager.isMac(), overrides);
        host.config().save();
        reloadKeymap();
    }

    /** This platform's current user overrides (Cmd map on macOS, Ctrl map elsewhere) — see {@link Settings}. */
    java.util.Map<String, String> currentKeybindings() {
        return host.config().getSettings().keybindingsFor(com.editora.command.KeymapManager.isMac());
    }

    void rebindShortcut(String commandId, String chordSeq) {
        applyKeybindingOverrides(
                com.editora.command.KeybindingEdits.rebind(baseBindings(), currentKeybindings(), commandId, chordSeq));
        host.setStatus(tr("status.shortcut.bound", chordSeq, commandTitle(commandId)));
    }

    void resetShortcut(String commandId) {
        applyKeybindingOverrides(
                com.editora.command.KeybindingEdits.reset(baseBindings(), currentKeybindings(), commandId));
        host.setStatus(tr("status.shortcut.reset", commandTitle(commandId)));
    }

    void resetAllShortcuts() {
        applyKeybindingOverrides(new java.util.LinkedHashMap<>());
        host.setStatus(tr("status.shortcut.resetAll"));
    }

    /** Localized title for a command id (for status messages / conflict dialogs); the id if unknown. */
    String commandTitle(String commandId) {
        for (Command c : host.registry().all()) {
            if (c.id().equals(commandId)) {
                return c.title();
            }
        }
        return commandId;
    }

    /** Picker for the app (chrome) theme — also switches the editor theme to match. */
    void chooseAppTheme() {
        QuickOpen<String> picker = new QuickOpen<>(
                "Set App Theme",
                "Type to filter themes…",
                () -> Themes.names(),
                name -> name,
                name -> "",
                this::applyAppTheme);
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.stage());
    }

    /** Picker for the editor color theme only (leaves the chrome theme untouched). */
    void chooseEditorTheme() {
        QuickOpen<String> picker = new QuickOpen<>(
                "Set Editor Theme",
                "Type to filter themes…",
                () -> EditorThemes.names(),
                name -> name,
                name -> "",
                this::applyEditorThemeChoice);
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.stage());
    }

    /** Re-scans the user-theme folders (config dir) so newly-added themes appear without a restart. */
    void reloadUserThemes() {
        UserThemes.load(host.config().getConfigDir());
        if (host.settingsWindow() != null) {
            host.settingsWindow().syncThemes(); // rebuild the theme/editor-theme combos from the fresh list
        }
        host.setStatus(tr("status.userThemesReloaded"));
    }

    /** Applies a chrome theme and follows it with the matching editor theme (clears the user-set flag). */
    void applyAppTheme(String name) {
        Settings s = host.config().getSettings();
        s.setTheme(name);
        javafx.application.Application.setUserAgentStylesheet(Themes.stylesheetFor(name));
        s.setEditorTheme(EditorThemes.defaultFor(name)); // chrome theme drives the editor theme
        s.setEditorThemeUserSet(false);
        host.requestSave();
        applyViewSettingsToAllBuffers(s); // swaps the editor-theme stylesheet + per-buffer colors
        if (host.settingsWindow() != null) {
            host.settingsWindow().syncThemes();
        }
        host.setStatus(tr("status.appTheme", name));
    }

    /** Applies only the editor color theme (marks it user-set so it won't follow the chrome theme). */
    void applyEditorThemeChoice(String name) {
        Settings s = host.config().getSettings();
        s.setEditorTheme(name);
        s.setEditorThemeUserSet(true);
        host.requestSave();
        applyViewSettingsToAllBuffers(s);
        if (host.settingsWindow() != null) {
            host.settingsWindow().syncThemes();
        }
        host.setStatus(tr("status.editorTheme", name));
    }

    /** Lets the user override the syntax language/grammar for the active buffer. */
    void chooseLanguage() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        List<String> names = new ArrayList<>();
        names.add(LanguageRegistry.plaintext());
        names.addAll(GrammarRegistry.shared().availableLanguageNames());
        // An in-scene picker, like every other status-bar selector — filterable, keyboard-first, and it
        // does not open a second window over the editor. (The list is ~100 grammars; a ChoiceDialog's
        // combo made that unsearchable.)
        String current = names.contains(buffer.getLanguage()) ? buffer.getLanguage() : names.get(0);
        chooseSetting("buffer.setLanguage", () -> names, name -> name, () -> current, name -> {
            buffer.setLanguageOverride(name);
            host.statusBar().refresh();
            host.setStatus(tr("status.language", name));
        });
    }

    /** Changes the (persisted) tab width and applies it to every buffer. */
    void chooseTabSize() {
        Settings s = host.config().getSettings();
        chooseSetting(
                "buffer.setTabSize",
                () -> List.of("2", "4", "8"),
                size -> size,
                () -> String.valueOf(s.getTabSize()),
                choice -> {
                    int size = Integer.parseInt(choice);
                    s.setTabSize(size);
                    host.requestSave();
                    applyViewSettingsToAllBuffers(s);
                    host.statusBar().refresh();
                    host.setStatus(tr("status.tabSize", size));
                });
    }

    /** Flips a boolean setting, persists, re-applies, syncs Settings, and echoes "<title> — on/off". */
    void toggleSetting(
            String commandId,
            java.util.function.BooleanSupplier get,
            java.util.function.Consumer<Boolean> set,
            Runnable apply) {
        boolean next = !get.getAsBoolean();
        set.accept(next);
        host.requestSave();
        if (apply != null) {
            apply.run();
        }
        if (host.settingsWindow() != null) {
            host.settingsWindow().syncAll();
        }
        host.setStatus(tr("status.settingToggled", commandTitle(commandId), tr(next ? "common.on" : "common.off")));
    }

    /** Prompts for a string setting (current value pre-filled), persists, re-applies, and echoes it. */
    void promptStringSetting(
            String commandId,
            java.util.function.Supplier<String> get,
            java.util.function.Consumer<String> set,
            Runnable apply) {
        host.promptText(commandTitle(commandId), tr("palette.setting.value"), get.get(), v -> {
            String value = v.trim();
            set.accept(value);
            host.requestSave();
            if (apply != null) {
                apply.run();
            }
            if (host.settingsWindow() != null) {
                host.settingsWindow().syncAll();
            }
            host.setStatus(tr("status.settingChanged", commandTitle(commandId), value));
        });
    }

    /** Toggles the intermediate large-file tier (minimap + LSP off, highlighting on) for the active buffer,
     *  then re-syncs LSP so the session starts/stops to match. */
    void toggleLargeFileMode() {
        EditorBuffer b = host.activeBuffer();
        if (b == null) {
            return;
        }
        boolean now = !b.isHeavyFile();
        b.setHeavyFile(now);
        host.lspCoordinator().syncBuffer(b); // start (off) / stop (on) the LSP session to match the new state
        host.setStatus(tr(now ? "status.largeFileMode.on" : "status.largeFileMode.off"));
    }

    /** Prompts for an integer setting (clamped to [min,max]); reports a parse error without changing it. */
    void promptIntSetting(
            String commandId,
            java.util.function.IntSupplier get,
            int min,
            int max,
            java.util.function.IntConsumer set,
            Runnable apply) {
        host.promptText(commandTitle(commandId), tr("palette.setting.value"), Integer.toString(get.getAsInt()), v -> {
            int parsed;
            try {
                parsed = Integer.parseInt(v.trim());
            } catch (NumberFormatException ex) {
                host.setStatus(tr("status.setting.invalidNumber", v.trim()));
                return;
            }
            int clamped = Math.max(min, Math.min(max, parsed));
            set.accept(clamped);
            host.requestSave();
            if (apply != null) {
                apply.run();
            }
            if (host.settingsWindow() != null) {
                host.settingsWindow().syncAll();
            }
            host.setStatus(tr("status.settingChanged", commandTitle(commandId), Integer.toString(clamped)));
        });
    }

    /** Generic single-choice picker that sets a setting to the chosen value (label fn for display). */
    void chooseSetting(
            String commandId,
            java.util.function.Supplier<List<String>> options,
            java.util.function.Function<String, String> label,
            java.util.function.Consumer<String> onChoose) {
        chooseSetting(commandId, options, label, null, onChoose);
    }

    /** As above, but opens on {@code current} — the value in force — instead of the first row. */
    void chooseSetting(
            String commandId,
            java.util.function.Supplier<List<String>> options,
            java.util.function.Function<String, String> label,
            java.util.function.Supplier<String> current,
            java.util.function.Consumer<String> onChoose) {
        QuickOpen<String> picker = new QuickOpen<>(
                commandTitle(commandId), tr("palette.setting.pick"), options::get, label::apply, id -> "", id -> {
                    if (id != null) {
                        onChoose.accept(id);
                    }
                });
        if (current != null) {
            picker.setCurrentItem(current);
        }
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.stage());
    }

    /** Palette entry for the TODO per-part colors: pick a part (tag / priority level), then enter a web-hex
     *  color; applies live to every buffer and syncs an open Settings window. */
    void chooseTodoPartColor() {
        chooseSetting(
                "todo.setPartColor",
                () -> List.of("tag", "critical", "high", "medium", "low"),
                id -> tr("settings.todo.part." + id),
                part -> {
                    Settings s = host.config().getSettings();
                    String current =
                            switch (part) {
                                case "tag" -> s.getTodoTagColor();
                                case "critical" -> s.getTodoPriorityCriticalColor();
                                case "high" -> s.getTodoPriorityHighColor();
                                case "medium" -> s.getTodoPriorityMediumColor();
                                case "low" -> s.getTodoPriorityLowColor();
                                default -> "";
                            };
                    host.promptText(
                            commandTitle("todo.setPartColor"),
                            tr("settings.todo.part." + part),
                            current,
                            hex -> applyTodoPartColor(part, hex));
                });
    }

    /** Validates a web-hex color and stores it in the given TODO part's setting, then re-highlights. */
    void applyTodoPartColor(String part, String hex) {
        if (hex == null || !hex.strip().matches("#[0-9a-fA-F]{6}")) {
            host.setStatus(tr("status.todo.badColor"));
            return;
        }
        String c = hex.strip();
        Settings s = host.config().getSettings();
        switch (part) {
            case "tag" -> s.setTodoTagColor(c);
            case "critical" -> s.setTodoPriorityCriticalColor(c);
            case "high" -> s.setTodoPriorityHighColor(c);
            case "medium" -> s.setTodoPriorityMediumColor(c);
            case "low" -> s.setTodoPriorityLowColor(c);
            default -> {
                return;
            }
        }
        host.requestSave();
        host.todoCoordinator().applyHighlight();
        if (host.settingsWindow() != null) {
            host.settingsWindow().syncTodoPartColors();
        }
        host.setStatus(tr("status.settingChanged", tr("settings.todo.part." + part), c));
    }

    /** Picker for the global indent style (Detect / Spaces / Tabs); applies live to every buffer. */
    void chooseIndentStyle() {
        chooseSetting(
                "editor.setIndentStyle",
                () -> List.of("detect", "space", "tab"),
                SettingsWindow::indentStyleName,
                id -> {
                    Settings s = host.config().getSettings();
                    s.setIndentStyle(id);
                    host.requestSave();
                    applyViewSettingsToAllBuffers(s);
                    if (host.settingsWindow() != null) {
                        host.settingsWindow().syncAll();
                    }
                    host.setStatus(tr(
                            "status.settingChanged",
                            commandTitle("editor.setIndentStyle"),
                            SettingsWindow.indentStyleName(id)));
                });
    }

    /** Picker for how aggressively inlay parameter-name hints are suppressed (Literals only / All). */
    void chooseInlayHintMode() {
        chooseSetting(
                "lsp.setInlayHintMode", () -> List.of("literals", "all"), SettingsWindow::inlayHintModeName, id -> {
                    Settings s = host.config().getSettings();
                    s.setInlayHintMode(id);
                    host.requestSave();
                    host.lspCoordinator().applyInlayHints();
                    if (host.settingsWindow() != null) {
                        host.settingsWindow().syncAll();
                    }
                    host.setStatus(tr(
                            "status.settingChanged",
                            commandTitle("lsp.setInlayHintMode"),
                            SettingsWindow.inlayHintModeName(id)));
                });
    }

    /** Picker for the editor font family (same choices as Settings → Appearance). */
    void chooseFont() {
        chooseSetting("appearance.setFont", SettingsWindow::fontFamilyChoices, name -> name, name -> {
            Settings s = host.config().getSettings();
            s.setFontFamily(name);
            host.requestSave();
            applyViewSettingsToAllBuffers(s);
            if (host.settingsWindow() != null) {
                host.settingsWindow().syncAll();
            }
            host.setStatus(tr("status.settingChanged", commandTitle("appearance.setFont"), name));
        });
    }

    /** Picker for the UI language (Automatic + bundled locales); applies after a restart. */
    void chooseUiLanguage() {
        List<String> ids = new java.util.ArrayList<>();
        ids.add(""); // "" = Automatic (follow the system language)
        ids.addAll(com.editora.i18n.Messages.available().keySet());
        chooseSetting(
                "appearance.setUiLanguage",
                () -> ids,
                id -> id.isEmpty() ? tr("settings.language.auto") : com.editora.i18n.Messages.languageName(id),
                id -> {
                    host.config().getSettings().setUiLanguage(id);
                    host.requestSave();
                    if (host.settingsWindow() != null) {
                        host.settingsWindow().syncAll();
                    }
                    host.setStatus(tr("dialog.language.restart"));
                });
    }

    /** Picker for the PDF export page size. */
    void choosePdfPageSize() {
        chooseSetting("editor.setPdfPageSize", () -> List.of("letter", "a4"), v -> v, v -> {
            host.config().getSettings().setPdfPageSize(v);
            host.requestSave();
            if (host.settingsWindow() != null) {
                host.settingsWindow().syncAll();
            }
            host.setStatus(tr("status.settingChanged", commandTitle("editor.setPdfPageSize"), v));
        });
    }

    /** Converts the active buffer's line endings between LF and CRLF. */
    void chooseLineEndings() {
        if (!host.editing().activeEditable()) {
            return;
        }
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        chooseSetting(
                "buffer.convertLineEndings", () -> List.of("LF", "CRLF"), c -> c, buffer::getLineEnding, choice -> {
                    buffer.convertLineEndings("CRLF".equals(choice));
                    host.statusBar().refresh();
                    host.setStatus(tr("status.lineEndingsSet", choice));
                });
    }

    /** Persists a word to the shared personal dictionary, then drops every open buffer's memoized spell
     *  verdicts — the word set is shared, but each buffer's overlay caches its own results, so "Add to
     *  Dictionary" in one tab used to leave the word squiggled in the others for the rest of the session. */
    void addUserWordAndRefreshAll(String word) {
        host.config().addUserWord(word);
        // The user dictionary is shared app-wide (SharedConfig), so re-run the spell pass in EVERY window's
        // tabs — otherwise another window's buffers keep the stale squiggle on the just-added word until it
        // happens to apply a setting (#443). Mirrors broadcastSettingsApplied/broadcastMacrosChanged.
        if (host.windowManager() != null) {
            host.windowManager().broadcastUserDictionaryChanged();
        } else {
            host.refreshSpellAllTabs();
        }
    }

    void applyViewSettings(EditorBuffer buffer) {
        applyViewSettings(buffer, true);
    }

    void applyViewSettings(EditorBuffer buffer, boolean resolvePathSettings) {
        Settings s = host.config().getSettings();
        int effectiveFont = Math.max(1, (int) Math.round(s.getFontSize() * s.getFontZoom()));
        buffer.setFont(s.getFontFamily(), effectiveFont);
        // Zen/Expert (per window, "focus modes") hide distraction-free chrome without clobbering the saved
        // prefs; Simple UI mode additionally removes the whole gutter + minimap. All effective overlays.
        // Expert keeps the full editor view — line numbers, ruler, current-line highlight, minimap — so those
        // key on the real zen flag; only the whitespace guides follow the combined focus flag like Zen.
        boolean zen = host.chrome().zenActive() || host.chrome().diffUiActive();
        boolean focus = zen || host.chrome().expertActive();
        boolean simple = host.chrome().simpleModeActive();
        buffer.setColumnRulerVisible(Chrome.columnRuler(s.isShowColumnRuler(), zen));
        buffer.setNoteIndicatorsVisible(s.isNotesSupport() && s.isShowNoteIndicators());
        buffer.setLineHighlightOn(Chrome.lineHighlight(s.isHighlightCurrentLine(), zen));
        buffer.setLineNumbersVisible(Chrome.lineNumbers(s.isShowLineNumbers(), zen, simple));
        buffer.setMinimapVisible(Chrome.minimap(s.isShowMinimap(), zen, simple));
        buffer.setWordWrap(s.isWordWrap());
        buffer.setGutterVisible(Chrome.gutter(simple)); // Simple mode removes the entire gutter strip
        if (simple) {
            buffer.unfoldAll(); // collapsed regions would be stranded behind the now-hidden fold chevrons
        }
        buffer.setWhitespaceVisible(Chrome.whitespace(s.isShowWhitespace(), focus));
        buffer.setTabSize(s.getTabSize());
        buffer.setLineHighlightColor(EditorThemes.lineHighlightFor(s.getEditorTheme()));
        buffer.setMinimapColors(
                EditorThemes.minimapTextFor(s.getEditorTheme()), EditorThemes.minimapViewportFor(s.getEditorTheme()));
        buffer.setFoldPreviewColors(
                EditorThemes.editorBackgroundFor(s.getEditorTheme()),
                EditorThemes.editorForegroundFor(s.getEditorTheme()));
        buffer.setSpellLanguage(spellLanguageFor(buffer)); // per-file override, else the global default
        buffer.setSpellCheckEnabled(s.isSpellCheck());
        buffer.setUserDictionaryEnabled(s.isPersonalDictionary());
        buffer.setTechnicalDictionaryEnabled(s.isTechnicalDictionary());
        buffer.setFormatBarEnabled(s.isMarkdownFormatBar());
        buffer.setAiActionsEnabled(host.aiCoordinator().isActionsAvailable()); // floating selection Explain/Rewrite bar
        buffer.setCsvRainbowEnabled(s.isCsvRainbow()); // per-column CSV coloring (no-op for non-CSV buffers)
        buffer.setBracketColorsEnabled(s.isBracketColors()); // bracket-pair colorization (rides the highlight)
        buffer.setStructuredPreviewEnabled(s.isStructuredPreview()); // JSON/YAML/TOML tree + OpenAPI docs preview
        buffer.setSvgPreviewEnabled(s.isSvgPreview()); // rendered image preview for .svg files
        buffer.setTypstPreviewEnabled(s.isTypstSupport()); // multi-page rendered preview for .typ documents
        buffer.setCrontabPreviewEnabled(s.isCrontabPreview()); // schedule decode + next runs for crontab files
        buffer.setFstabPreviewEnabled(s.isFstabPreview()); // per-line mount decode for /etc/fstab files
        buffer.setSystemdPreviewEnabled(s.isSystemdPreview()); // directive glosses + OnCalendar decode
        buffer.setSshConfigPreviewEnabled(s.isSshConfigPreview()); // per-Host connection summary
        buffer.setDockerfilePreviewEnabled(s.isDockerfilePreview()); // per-stage build digest
        buffer.setGithubActionsPreviewEnabled(s.isGithubActionsPreview()); // workflow triggers + jobs digest
        buffer.setPomPreviewEnabled(s.isPomPreview()); // Maven pom.xml summary (wins over the XML tree)
        buffer.setStickyScrollEnabled(s.isStickyScroll()); // pinned enclosing-scope headers
        if (buffer.isStructured() || buffer.isXml() || buffer.isSvg()) {
            host.previews()
                    .ensurePreviewControls(
                            buffer); // attach/detach the 3-mode toggle as the structured/XML/SVG gate flips
        }
        buffer.setAutoRenameTag(s.isAutoRenameTag()); // paired-tag rename mirroring (html/xml buffers only)
        buffer.setOnTypeFormattingEnabled(s.isLspOnTypeFormatting()); // #740 (inert without an LSP trigger set)
        buffer.setLspPasteImportsEnabled(s.isLspPasteImports()); // #742 (inert without a jdtls session)
        buffer.setSmartSemicolonEnabled(s.isLspSmartSemicolon()); // #746 (inert without a jdtls session)
        buffer.setAutoCloseTags(s.isAutoCloseTags()); // ">" inserts the matching closer (html/xml buffers only)
        buffer.setFillColumn(s.getFillColumn());
        buffer.setAutoFillEnabled(s.isAutoFill()); // break prose lines at the fill column as you type (prose only)
        buffer.setAbbrevs(
                host.config().abbreviationMap(), s.isAbbrevMode()); // dictionary from abbreviations.json + mode
        if (resolvePathSettings) {
            applyEditorConfig(buffer); // .editorconfig overrides the global indent/EOL/ruler/charset (when on)
        } else {
            // A loading shell must not walk the filesystem on FX. prepareLoad supplies the resolved values.
            applyResolvedEditorConfig(buffer, com.editora.editorconfig.EditorConfigProperties.EMPTY);
        }
    }

    /** Whether {@code .editorconfig} support is enabled. */
    boolean editorConfigEnabled() {
        return host.config().getSettings().isEditorConfigSupport();
    }

    /**
     * Pushes the file's resolved {@code .editorconfig} properties onto {@code buffer} (indent style/size,
     * EOL, ruler column from {@code max_line_length}, and write charset), or clears the overrides when the
     * feature is off / the file is non-local / has no path. The save-time props are stored on the buffer
     * for {@code writeBuffer}. Reuses {@link com.editora.editorconfig.EditorConfig#resolveFor}.
     */
    void applyEditorConfig(EditorBuffer buffer) {
        Path path = buffer.getPath();
        if (!editorConfigEnabled() || path == null || !com.editora.vfs.Vfs.isLocal(path)) {
            applyResolvedEditorConfig(buffer, com.editora.editorconfig.EditorConfigProperties.EMPTY);
            return; // EOL override is left to a manual choice; tab size already comes from global settings
        }
        com.editora.editorconfig.EditorConfigProperties p = com.editora.editorconfig.EditorConfig.resolveFor(path);
        applyResolvedEditorConfig(buffer, p);
    }

    /** Applies an already-resolved EditorConfig result without touching the filesystem. */
    void applyResolvedEditorConfig(EditorBuffer buffer, com.editora.editorconfig.EditorConfigProperties properties) {
        com.editora.editorconfig.EditorConfigProperties p =
                properties == null ? com.editora.editorconfig.EditorConfigProperties.EMPTY : properties;
        buffer.setEditorConfigProps(p);
        applyEffectiveIndent(buffer, p);
        if (p.insertSpaces() != null || p.tabWidth() != null || p.indentSize() != null) {
            buffer.setTabSize(p.effectiveTabWidth(host.config().getSettings().getTabSize()));
        }
        if (p.endOfLine() != null) {
            buffer.setEolOverride("crlf".equals(p.endOfLine()) ? "CRLF" : "lf".equals(p.endOfLine()) ? "LF" : null);
        }
        buffer.setRulerColumn(p.maxLineLength()); // null = default 80, OFF = hide
        buffer.setCharsetOverride(p.charset());
    }

    /**
     * Resolves the effective indent override for a buffer and pushes it via {@link EditorBuffer#setIndentOverride}.
     * Precedence: a file's {@code .editorconfig} {@code indent_style} (when present) wins; else the global
     * {@link Settings#getIndentStyle()} preference ({@code space}/{@code tab}); else {@code null} → per-file
     * auto-detection ({@code Indenter.detectUnit}). For {@code space}, the size falls back to the global tab size
     * when {@code .editorconfig} didn't specify {@code indent_size}.
     */
    void applyEffectiveIndent(EditorBuffer buffer, com.editora.editorconfig.EditorConfigProperties p) {
        Boolean insertSpaces = p.insertSpaces();
        Integer size = p.indentSize();
        if (insertSpaces == null) {
            String style = host.config().getSettings().getIndentStyle();
            if ("space".equals(style)) {
                insertSpaces = Boolean.TRUE;
                if (size == null) {
                    size = host.config().getSettings().getTabSize();
                }
            } else if ("tab".equals(style)) {
                insertSpaces = Boolean.FALSE;
            }
            // "detect" leaves insertSpaces null → Indenter falls back to detectUnit
        }
        buffer.setIndentOverride(insertSpaces, size);
    }

    /**
     * Opens the {@code .editorconfig} file governing the active buffer (the status-bar indicator's click action,
     * also a palette command). Reports a status message when there's no file / no governing {@code .editorconfig}.
     */
    void openActiveEditorConfig() {
        EditorBuffer buffer = host.activeBuffer();
        Path path = buffer == null ? null : buffer.getPath();
        if (path == null || !com.editora.vfs.Vfs.isLocal(path)) {
            host.setStatus(tr("status.editorConfig.none"));
            return;
        }
        Path ec = com.editora.editorconfig.EditorConfig.nearestFile(path);
        if (ec == null) {
            host.setStatus(tr("status.editorConfig.none"));
            return;
        }
        host.openPath(ec);
    }

    /** Re-applies (or clears) {@code .editorconfig} for every open buffer — init + on settings apply. */
    void applyEditorConfigSupport() {
        if (!editorConfigEnabled()) {
            com.editora.editorconfig.EditorConfig.clearCache();
        }
        for (Tab tab : host.editorArea().tabs()) {
            EditorBuffer buffer = host.bufferOf(tab);
            if (buffer != null) {
                applyEditorConfig(buffer);
            }
        }
        if (host.statusBar() != null) {
            host.statusBar().refresh();
        }
    }

    /** The spell-check language for a buffer: its per-file override (if any/valid), else the global default. */
    String spellLanguageFor(EditorBuffer buffer) {
        String def = host.config().getSettings().getSpellLanguage();
        Path p = buffer.getPath();
        if (p == null) {
            return def;
        }
        String override = host.config().getWorkspaceState().getSpellLanguages().get(p.toString());
        return override != null && SpellDictionaries.isAvailable(override) ? override : def;
    }

    void applyViewSettingsToAllBuffers(Settings settings) {
        host.applyEditorTheme(settings.getEditorTheme());
        host.chrome().applyChromeVisibility();
        host.applyProjectSupport();
        host.git().applySupport();
        host.github().applySupport(); // re-detect gh + re-gate the GitHub surfaces
        host.historyCoordinator().applySupport(); // re-gate the Local File History tool window + refresh its list
        host.git()
                .applyBlame(); // (re)apply inline blame to the active buffer (effective gate: git + setting + !simple)
        host.notesCoordinator().applySupport();
        host.mermaid().applySupport();
        host.diagram().applySupport();
        host.typst().applySupport();
        host.searchCoordinator().applyRipgrepSupport();
        host.applyMathSupport();
        host.httpClient().applySupport();
        host.htmlPreview().applySupport();
        host.logViewer().applySupport();
        host.applyMcpSupport();
        host.applyAgentSupport();
        host.aiCoordinator()
                .applySupport(); // re-probe connectivity + re-gate the floating selection Explain/Rewrite bar
        host.todoCoordinator().applyHighlight(); // (re)compile TODO patterns + push the matcher to every buffer
        host.indexCoordinator().applySupport(); // a disabled index must not retain a project's symbols
        host.csvCoordinator().applySupport(); // re-gate the in-editor CSV grid preview on every open buffer
        host.testNavigation().applyTestRunner(); // re-gate the Test Results window (off / Simple UI mode hides it)
        host.previews().applyMarkdownLint(); // push Markdown-lint enabled state to every buffer
        host.fileWorkflows().applyAutoSave();
        applyAutocomplete();
        applyMultiCaret();
        host.lspCoordinator().applySupport(); // (re)configure LSP: command/enabled change re-detects + re-gates buffers
        host.debugCoordinator().applySupport(); // (re)configure DAP after LSP (it layers on jdtls)
        host.coordinatorHost()
                .forEachBuffer(host::applyMainGutter); // re-gate the project main-method ▶ (needs jdtls+debug)
        host.previews()
                .applyMarkdownPreviewTheme(); // re-resolve "follow app" previews + the toggle glyph after a theme
        // change
        // Match the console fonts + per-buffer view to the editor font/zoom (shared with the text-zoom path).
        applyFontsAndPerBufferView(settings);
        // If the Welcome tab is open, rebuild it so its Open Folder / Clone actions track the
        // Projects/Git toggles that may have just changed (a font-only zoom skips this rebuild).
        if (host.welcomeTab() != null) {
            host.welcomePane().refresh();
        }
    }

    /**
     * Re-applies fonts (editor buffers, Diff panes, and the console tool windows) and per-buffer view settings to
     * every open tab, plus the Welcome tab's font scale. Shared by the full {@link #applyViewSettingsToAllBuffers}
     * and the lighter {@link #textZoom} path — a font zoom changes none of the feature gates or the editor theme,
     * so {@code textZoom} calls only this and skips the editor-theme stylesheet swap + the ~20 {@code applySupport()}
     * service calls the full apply runs (a Ctrl+wheel gesture fires many notches, each of which used to re-swap the
     * scene stylesheet and re-run the whole cascade — #545).
     */
    void applyFontsAndPerBufferView(Settings settings) {
        int consoleFont = Math.max(1, (int) Math.round(settings.getFontSize() * settings.getFontZoom()));
        host.externalToolCoordinator().panel().setOutputFont(settings.getFontFamily(), consoleFont);
        host.runCoordinator().panel().setOutputFont(settings.getFontFamily(), consoleFont);
        host.debugCoordinator().panel().setConsoleFont(settings.getFontFamily(), consoleFont);
        host.buildOutputPanel().setOutputFont(settings.getFontFamily(), consoleFont);
        host.testRunCoordinator().setOutputFont(settings.getFontFamily(), consoleFont);
        for (Tab tab : host.editorArea().tabs()) {
            EditorBuffer buffer = host.bufferOf(tab);
            if (buffer != null) {
                applyViewSettings(buffer);
            } else if (tab.getUserData() instanceof DiffViewerPane diff) {
                diff.setFont(settings.getFontFamily(), consoleFont); // text zoom applies inside a Diff tab too (#533)
            }
        }
        if (host.welcomeTab() != null) {
            host.welcomePane().setFontScale(settings.getFontZoom()); // scale Welcome text to the current zoom (#540)
        }
        if (host.doctorTab() != null) {
            host.doctorCoordinator().pane().setFontScale(settings.getFontZoom()); // scale the Doctor tab like Welcome
        }
        for (Tab t : host.editorArea().tabs()) {
            if (t.getUserData() instanceof PrReviewPane pr) {
                pr.setFontScale(settings.getFontZoom()); // scale the PR review tab like Welcome
            }
        }
    }

    /** Whether multiple cursors / column selection is active. Off in Simple UI mode; saved setting unchanged. */
    boolean multiCaretEnabled() {
        return host.config().getSettings().isMultiCaret() && !host.chrome().simpleModeActive();
    }

    /** Pushes the multiple-cursors / column-selection setting to every open buffer. */
    void applyMultiCaret() {
        boolean on = multiCaretEnabled(); // effective: off in Simple UI mode
        for (Tab tab : host.editorArea().tabs()) {
            EditorBuffer buffer = host.bufferOf(tab);
            if (buffer != null) {
                buffer.setMultiCaretEnabled(on);
            }
        }
    }

    /** Pushes the autocomplete settings (master + per-source) to every open buffer. */
    void applyAutocomplete() {
        Settings s = host.config().getSettings();
        boolean mermaidAc = host.mermaid().effectiveAutocomplete();
        boolean aiInline = host.aiCoordinator().isInlineCompletionEnabled();
        for (Tab tab : host.editorArea().tabs()) {
            EditorBuffer buffer = host.bufferOf(tab);
            if (buffer != null) {
                buffer.setAutocomplete(
                        s.isAutocomplete(), s.isAutocompleteProse(), s.isAutocompleteSnippets(), mermaidAc);
                buffer.setCompletionDocEnabled(s.isCompletionDoc());
                buffer.setAiCompletionEnabled(aiInline);
            }
        }
    }
}
