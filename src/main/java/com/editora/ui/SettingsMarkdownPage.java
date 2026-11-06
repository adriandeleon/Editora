package com.editora.ui;

import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.ui.SettingsWindow.Card;
import com.editora.ui.SettingsWindow.Category;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Settings → Markdown: editing (format bar, pasted images, table of contents, typography, links), the preview, export
 * and markdownlint with its per-rule list and the active project's config note. The page's own controls and the rule
 * list live here; its switch rows sync with every other page's ({@link SettingsWindow#syncMarkdownControls}).
 */
final class SettingsMarkdownPage {

    private final SettingsWindow w;
    private final ConfigManager config;

    SettingsMarkdownPage(SettingsWindow w, ConfigManager config) {
        this.w = w;
        this.config = config;
    }

    private @Nullable TextField markdownAssetsField;
    private @Nullable Spinner<Integer> markdownTocMinSpinner;
    private @Nullable Spinner<Integer> markdownTocMaxSpinner;
    private @Nullable ComboBox<String> markdownPreviewThemeCombo;

    private @Nullable VBox markdownLintRulesBox;

    /**
     * What the active file's project markdownlint config does to the rule list below, as one localized line
     * ("" when none applies). Not observable: read again whenever the Markdown page is shown or synced.
     */
    void setMarkdownLintConfigNote(java.util.function.Supplier<String> note) {
        this.markdownLintConfigNote = note == null ? () -> "" : note;
        refreshMarkdownLintConfigNote();
    }

    private java.util.function.Supplier<String> markdownLintConfigNote = () -> "";
    private final Label markdownLintConfigNoteLabel = new Label();

    void refreshMarkdownLintConfigNote() {
        String note = markdownLintConfigNote.get();
        boolean show = note != null && !note.isBlank();
        markdownLintConfigNoteLabel.setText(show ? note : "");
        markdownLintConfigNoteLabel.setVisible(show);
        markdownLintConfigNoteLabel.setManaged(show);
    }

    /** The Markdown settings page: editing (format bar), preview/PDF (math), and linting (enable + per-rule). */
    Region build() {
        VBox p = w.page(tr("settings.cat.markdown"), tr("settings.markdown.subtitle"));
        Card editing = w.card(p, tr("settings.section.markdownEditing"));
        w.checkRow(
                editing,
                Category.MARKDOWN,
                w.markdownFormatBarCheck,
                null,
                "markdown format bar selection bold italic toolbar floating");
        w.controlRow(
                editing,
                Category.MARKDOWN,
                tr("settings.markdownAssetsFolder"),
                tr("settings.markdownAssetsFolder.desc"),
                markdownAssetsField,
                "markdown image paste drop assets folder directory screenshots pictures");
        w.textRow(
                editing,
                Category.MARKDOWN,
                "markdownImageName",
                Settings.DEFAULT_MARKDOWN_IMAGE_NAME,
                Settings::getMarkdownImageNamePattern,
                Settings::setMarkdownImageNamePattern,
                "markdown image paste drop file name pattern screenshot");
        w.switchRow(
                editing,
                Category.MARKDOWN,
                "markdownImageAltPrompt",
                false,
                Settings::isMarkdownImageAltPrompt,
                Settings::setMarkdownImageAltPrompt,
                "markdown image paste alt text description accessibility ask prompt");
        w.switchRow(
                editing,
                Category.MARKDOWN,
                "markdownTrashUnusedPastedImages",
                true,
                Settings::isMarkdownTrashUnusedPastedImages,
                Settings::setMarkdownTrashUnusedPastedImages,
                "markdown pasted dropped image unused orphan trash delete clean up save");
        HBox tocLevels = new HBox(6, markdownTocMinSpinner, new Label("–"), markdownTocMaxSpinner);
        tocLevels.setAlignment(Pos.CENTER_LEFT);
        w.controlRow(
                editing,
                Category.MARKDOWN,
                tr("settings.markdownTocLevels"),
                tr("settings.markdownTocLevels.desc"),
                tocLevels,
                "markdown table of contents toc heading levels depth minimum maximum");
        w.switchRow(
                editing,
                Category.MARKDOWN,
                "markdownTocSkipTitle",
                false,
                Settings::isMarkdownTocSkipTitle,
                Settings::setMarkdownTocSkipTitle,
                "markdown table of contents toc title first heading h1 skip omit");
        w.switchRow(
                editing,
                Category.MARKDOWN,
                "markdownTocOrdered",
                false,
                Settings::isMarkdownTocOrdered,
                Settings::setMarkdownTocOrdered,
                "markdown table of contents toc numbered ordered list");
        w.switchRow(
                editing,
                Category.MARKDOWN,
                "markdownTocUpdateOnSave",
                false,
                Settings::isMarkdownTocUpdateOnSave,
                Settings::setMarkdownTocUpdateOnSave,
                "markdown table of contents toc update regenerate on save automatic");
        w.switchRow(
                editing,
                Category.MARKDOWN,
                "markdownSmartTypography",
                true,
                Settings::isMarkdownSmartTypography,
                Settings::setMarkdownSmartTypography,
                "markdown smart typography curly quotes dashes ellipsis typographic");
        w.switchRow(
                editing,
                Category.MARKDOWN,
                "markdownWikiLinks",
                true,
                Settings::isMarkdownWikiLinks,
                Settings::setMarkdownWikiLinks,
                "markdown wiki links double brackets note backlinks obsidian zettelkasten");
        w.choiceRow(
                editing,
                Category.MARKDOWN,
                "markdownUpdateLinks",
                true,
                Settings.LINK_UPDATE_MODES,
                Settings::getMarkdownUpdateLinksOnRename,
                Settings::setMarkdownUpdateLinksOnRename,
                "markdown links update rename move file refactor ask always never");
        Card preview = w.card(p, tr("settings.section.markdownPreview"));
        w.choiceRow(
                preview,
                Category.MARKDOWN,
                "markdownDefaultView",
                true,
                Settings.MARKDOWN_VIEW_MODES,
                Settings::getMarkdownDefaultViewMode,
                Settings::setMarkdownDefaultViewMode,
                "markdown default view mode open editor split preview side by side");
        w.intRow(
                preview,
                Category.MARKDOWN,
                "markdownPreviewFontSize",
                Settings.MAX_PREVIEW_FONT_SIZE,
                1,
                Settings::getMarkdownPreviewFontSize,
                Settings::setMarkdownPreviewFontSize,
                "markdown preview font size text bigger smaller");
        w.intRow(
                preview,
                Category.MARKDOWN,
                "markdownPreviewWidth",
                Settings.MAX_PREVIEW_WIDTH,
                20,
                Settings::getMarkdownPreviewWidth,
                Settings::setMarkdownPreviewWidth,
                "markdown preview width line length column measure");
        w.switchRow(
                preview,
                Category.MARKDOWN,
                "markdownScrollSync",
                false,
                Settings::isMarkdownScrollSync,
                Settings::setMarkdownScrollSync,
                "markdown preview scroll sync synchronize split together follow");
        w.switchRow(
                preview,
                Category.MARKDOWN,
                "markdownHideFrontMatter",
                false,
                Settings::isMarkdownHideFrontMatter,
                Settings::setMarkdownHideFrontMatter,
                "markdown preview front matter yaml metadata hide");
        w.controlRow(
                preview,
                Category.MARKDOWN,
                tr("settings.markdownPreviewTheme"),
                null,
                markdownPreviewThemeCombo,
                "markdown preview theme light dark follow app color colour");
        w.choiceRow(
                preview,
                Category.MARKDOWN,
                "markdownRemoteImagePolicy",
                true,
                Settings.REMOTE_IMAGE_POLICIES,
                Settings::getMarkdownRemoteImagePolicy,
                Settings::setMarkdownRemoteImagePolicy,
                "markdown preview remote images web http network privacy tracking badge load ask always never");
        w.checkRow(
                preview,
                Category.MARKDOWN,
                w.mathSupportCheck,
                null,
                "markdown math latex katex formula equation dollar");
        Card export = w.card(p, tr("settings.section.markdownExport"));
        w.switchRow(
                export,
                Category.MARKDOWN,
                "htmlExportEmbedImages",
                true,
                Settings::isHtmlExportEmbedImages,
                Settings::setHtmlExportEmbedImages,
                "markdown html export embed images data uri self-contained standalone");
        w.switchRow(
                export,
                Category.MARKDOWN,
                "htmlExportHighlightCode",
                false,
                Settings::isHtmlExportHighlightCode,
                Settings::setHtmlExportHighlightCode,
                "markdown html export syntax highlight code blocks colors");
        Card lint = w.card(p, tr("settings.section.markdownLint"));
        w.checkRow(
                lint, Category.MARKDOWN, w.markdownLintCheck, null, "markdown lint linting warnings squiggles rules");
        w.switchRow(
                lint,
                Category.MARKDOWN,
                "markdownLintFixOnSave",
                false,
                Settings::isMarkdownLintFixOnSave,
                Settings::setMarkdownLintFixOnSave,
                "markdown lint fix issues on save automatic format");
        // A project's .markdownlint file changes which of the rules below apply to the active file; say so
        // here too, or a rule ticked in this list that the project switches off looks broken.
        markdownLintConfigNoteLabel.getStyleClass().add("settings-hint");
        markdownLintConfigNoteLabel.setWrapText(true);
        refreshMarkdownLintConfigNote();
        // Straight into the card, no row around it: hidden, it takes no room (a wrapping row left a blank gap).
        markdownLintConfigNoteLabel.getStyleClass().add("settings-row");
        lint.body().getChildren().add(markdownLintConfigNoteLabel);
        w.cardRow(
                lint,
                Category.MARKDOWN,
                markdownLintRulesEditor(),
                "markdown lint rules MD009 MD040 MD034 MD013 MD033 disable enable per-rule checklist severity error "
                        + "warning hint");
        return p;
    }

    /** The Markdown page's controls beyond its on/off switches; each writes its own setting and applies. */
    void buildMarkdownControls() {
        TextField assetsField = new TextField();
        markdownAssetsField = assetsField;
        markdownAssetsField.setPromptText(Settings.DEFAULT_MARKDOWN_ASSETS_FOLDER);
        markdownAssetsField.setPrefWidth(220);
        markdownAssetsField.setAccessibleText(tr("settings.markdownAssetsFolder"));
        w.commitOnEnterOrBlur(markdownAssetsField, () -> config.getSettings().getMarkdownAssetsFolder(), now -> {
            config.getSettings().setMarkdownAssetsFolder(now);
            // What was typed may not be what is kept (a path out of the document's folder is not): show it.
            assetsField.setText(config.getSettings().getMarkdownAssetsFolder());
            w.apply();
        });
        markdownTocMinSpinner = IntSpinners.editable(1, 6, 1, 1);
        markdownTocMaxSpinner = IntSpinners.editable(1, 6, 6, 1);
        markdownTocMinSpinner.setPrefWidth(80);
        markdownTocMaxSpinner.setPrefWidth(80);
        markdownTocMinSpinner.setAccessibleText(tr("settings.markdownTocLevels.min"));
        markdownTocMaxSpinner.setAccessibleText(tr("settings.markdownTocLevels.max"));
        markdownTocMinSpinner.valueProperty().addListener((obs, was, now) -> {
            if (w.loading || now == null) {
                return;
            }
            Settings st = config.getSettings();
            st.setMarkdownTocMinLevel(now);
            if (now > st.getMarkdownTocMaxLevel()) {
                st.setMarkdownTocMaxLevel(now); // the range stays a range: the other end follows
            }
            w.syncMarkdownControls(st);
            w.apply();
        });
        markdownTocMaxSpinner.valueProperty().addListener((obs, was, now) -> {
            if (w.loading || now == null) {
                return;
            }
            Settings st = config.getSettings();
            if (now < st.getMarkdownTocMinLevel()) {
                st.setMarkdownTocMinLevel(now);
            }
            st.setMarkdownTocMaxLevel(now);
            w.syncMarkdownControls(st);
            w.apply();
        });
        markdownPreviewThemeCombo = new ComboBox<>();
        markdownPreviewThemeCombo.getItems().setAll(Settings.MARKDOWN_PREVIEW_THEMES);
        markdownPreviewThemeCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String key) {
                return key == null || key.isEmpty()
                        ? tr("markdown.previewTheme.follow")
                        : tr("markdown.previewTheme." + key);
            }

            @Override
            public String fromString(String label) {
                return label;
            }
        });
        markdownPreviewThemeCombo.setMinWidth(170);
        markdownPreviewThemeCombo.valueProperty().addListener((obs, was, now) -> {
            if (w.loading || now == null) {
                return;
            }
            config.getSettings().setMarkdownPreviewTheme(now);
            w.apply();
        });
    }

    /**
     * The Markdown-lint per-rule list: a checkbox per rule (checked = on in the user's settings — a rule that
     * is off by default is on only once ticked) and its severity. Each change writes
     * {@code Settings} ({@link MarkdownLintRules#setEnabled}, {@link Settings#setMarkdownLintSeverity}) and
     * applies live.
     */
    private javafx.scene.Node markdownLintRulesEditor() {
        markdownLintRulesBox = new VBox(4);
        rebuildMarkdownLintRules();
        // The last card on the page, so the rules list in full: a scroller of its own inside the page's
        // scroller caught the wheel and showed two scrollbars.
        return markdownLintRulesBox;
    }

    void rebuildMarkdownLintRules() {
        if (markdownLintRulesBox == null) {
            return;
        }
        markdownLintRulesBox.getChildren().clear();
        // One row per rule: a checkbox whose label is the rule code (fixed-width so codes align) followed by
        // a muted one-line description — a readable vertical list instead of a wrapping grid of bare codes.
        for (com.editora.markdown.MarkdownLint.Rule rule : com.editora.markdown.MarkdownLint.RULES) {
            String code = rule.code();
            Label codeLabel = new Label(code);
            codeLabel.getStyleClass().add("md-lint-code");
            codeLabel.setMinWidth(58);
            Label desc = new Label(tr("mdlint.rule." + code));
            desc.getStyleClass().add("settings-hint");
            HBox label = new HBox(8, codeLabel, desc);
            label.setAlignment(Pos.CENTER_LEFT);

            CheckBox cb = new CheckBox();
            cb.setGraphic(label);
            cb.setAccessibleText(code + " " + desc.getText()); // its visible name is a graphic, not text
            // Rules that are off by default are opt-in: MarkdownLintRules knows which list holds which.
            cb.setSelected(MarkdownLintRules.enabled(config.getSettings(), code));
            cb.selectedProperty().addListener((o, was, on) -> {
                MarkdownLintRules.setEnabled(config.getSettings(), code, on);
                w.apply();
            });
            ComboBox<String> severity = new ComboBox<>();
            severity.getItems().setAll(Settings.LINT_SEVERITIES);
            severity.setConverter(new StringConverter<>() {
                @Override
                public String toString(String value) {
                    return value == null ? "" : tr("settings.markdownLintSeverity." + value);
                }

                @Override
                public String fromString(String shown) {
                    return shown;
                }
            });
            severity.setValue(config.getSettings().markdownLintSeverity(code));
            severity.setAccessibleText(tr("settings.markdownLintSeverity") + " " + code);
            severity.getProperties().put("editora.lintSeverity", code);
            severity.valueProperty().addListener((o, was, now) -> {
                if (now != null) {
                    config.getSettings().setMarkdownLintSeverity(code, now);
                    w.apply();
                }
            });
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox row = new HBox(8, cb, gap, severity);
            row.setAlignment(Pos.CENTER_LEFT);
            markdownLintRulesBox.getChildren().add(row);
        }
    }

    /** Shows the stored values in the page's own controls (the rows built with {@code switchRow} etc. sync themselves). */
    void showValues(Settings s) {
        if (markdownAssetsField != null
                && markdownTocMinSpinner != null // build() makes all four
                && markdownTocMaxSpinner != null
                && markdownPreviewThemeCombo != null) {
            markdownAssetsField.setText(s.getMarkdownAssetsFolder());
            markdownTocMinSpinner.getValueFactory().setValue(s.getMarkdownTocMinLevel());
            markdownTocMaxSpinner.getValueFactory().setValue(s.getMarkdownTocMaxLevel());
            markdownPreviewThemeCombo.setValue(s.getMarkdownPreviewTheme());
        }
    }
}
