package com.editora.ui;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.config.Settings;

import static com.editora.i18n.Messages.tr;

/**
 * The palette commands for the Markdown and writing settings that have a row in the Settings window: every
 * row's setting can be flipped or set without opening it. Each command writes the same {@link Settings}
 * field as its row, saves, re-applies the settings to the window and keeps an open Settings window in step
 * (through {@link EditorSettingsCoordinator}'s shared helpers).
 */
final class MarkdownSettingCommands {

    private MarkdownSettingCommands() {}

    /** One on/off setting and the command that toggles it. */
    private record Toggle(String id, Predicate<Settings> get, BiConsumer<Settings, Boolean> set) {}

    private static final List<Toggle> TOGGLES = List.of(
            new Toggle("view.toggleTypewriterMode", Settings::isTypewriterMode, Settings::setTypewriterMode),
            new Toggle(
                    "view.toggleStatusBarWordCount", Settings::isStatusBarWordCount, Settings::setStatusBarWordCount),
            new Toggle(
                    "view.toggleMarkdownScrollSync", Settings::isMarkdownScrollSync, Settings::setMarkdownScrollSync),
            new Toggle(
                    "view.toggleMarkdownFrontMatter",
                    s -> !s.isMarkdownHideFrontMatter(),
                    (s, shown) -> s.setMarkdownHideFrontMatter(!shown)),
            new Toggle("view.toggleMarkdownWikiLinks", Settings::isMarkdownWikiLinks, Settings::setMarkdownWikiLinks),
            new Toggle(
                    "view.toggleMarkdownTrashUnusedImages",
                    Settings::isMarkdownTrashUnusedPastedImages,
                    Settings::setMarkdownTrashUnusedPastedImages),
            new Toggle(
                    "view.toggleMarkdownSmartTypography",
                    Settings::isMarkdownSmartTypography,
                    Settings::setMarkdownSmartTypography),
            new Toggle(
                    "view.toggleMarkdownTocSkipTitle",
                    Settings::isMarkdownTocSkipTitle,
                    Settings::setMarkdownTocSkipTitle),
            new Toggle(
                    "view.toggleMarkdownTocOrdered", Settings::isMarkdownTocOrdered, Settings::setMarkdownTocOrdered),
            new Toggle(
                    "view.toggleMarkdownTocOnSave",
                    Settings::isMarkdownTocUpdateOnSave,
                    Settings::setMarkdownTocUpdateOnSave),
            new Toggle(
                    "view.toggleMarkdownImageAltPrompt",
                    Settings::isMarkdownImageAltPrompt,
                    Settings::setMarkdownImageAltPrompt),
            new Toggle(
                    "view.toggleMarkdownLintFixOnSave",
                    Settings::isMarkdownLintFixOnSave,
                    Settings::setMarkdownLintFixOnSave),
            new Toggle(
                    "view.toggleHtmlExportEmbedImages",
                    Settings::isHtmlExportEmbedImages,
                    Settings::setHtmlExportEmbedImages),
            new Toggle(
                    "view.toggleHtmlExportHighlightCode",
                    Settings::isHtmlExportHighlightCode,
                    Settings::setHtmlExportHighlightCode));

    /** The ids {@link #register} registers, for tests. */
    static List<String> toggleIds() {
        return TOGGLES.stream().map(Toggle::id).toList();
    }

    static void register(CommandRegistry registry, EditorSettingsCoordinator editor, Supplier<Settings> settings) {
        Runnable apply = () -> editor.applyViewSettingsToAllBuffers(settings.get());
        for (Toggle t : TOGGLES) {
            registry.register(Command.of(
                    t.id(),
                    () -> editor.toggleSetting(
                            t.id(),
                            () -> t.get().test(settings.get()),
                            on -> t.set().accept(settings.get(), on),
                            apply)));
        }
        registry.register(Command.of(
                "markdown.setDefaultViewMode",
                () -> choose(
                        editor,
                        settings,
                        "markdown.setDefaultViewMode",
                        "settings.markdownDefaultView.",
                        Settings.MARKDOWN_VIEW_MODES,
                        Settings::getMarkdownDefaultViewMode,
                        Settings::setMarkdownDefaultViewMode,
                        apply)));
        registry.register(Command.of(
                "markdown.setRemoteImagePolicy",
                () -> choose(
                        editor,
                        settings,
                        "markdown.setRemoteImagePolicy",
                        "settings.markdownRemoteImagePolicy.",
                        Settings.REMOTE_IMAGE_POLICIES,
                        Settings::getMarkdownRemoteImagePolicy,
                        Settings::setMarkdownRemoteImagePolicy,
                        apply)));
        registry.register(Command.of(
                "markdown.setUpdateLinksOnRename",
                () -> choose(
                        editor,
                        settings,
                        "markdown.setUpdateLinksOnRename",
                        "settings.markdownUpdateLinks.",
                        Settings.LINK_UPDATE_MODES,
                        Settings::getMarkdownUpdateLinksOnRename,
                        Settings::setMarkdownUpdateLinksOnRename,
                        apply)));
        registry.register(Command.of(
                "markdown.setPreviewFontSize",
                () -> editor.promptIntSetting(
                        "markdown.setPreviewFontSize",
                        () -> settings.get().getMarkdownPreviewFontSize(),
                        0,
                        Settings.MAX_PREVIEW_FONT_SIZE,
                        v -> settings.get().setMarkdownPreviewFontSize(v),
                        apply)));
        registry.register(Command.of(
                "markdown.setPreviewWidth",
                () -> editor.promptIntSetting(
                        "markdown.setPreviewWidth",
                        () -> settings.get().getMarkdownPreviewWidth(),
                        0,
                        Settings.MAX_PREVIEW_WIDTH,
                        v -> settings.get().setMarkdownPreviewWidth(v),
                        apply)));
        registry.register(Command.of(
                "markdown.setImageName",
                () -> editor.promptStringSetting(
                        "markdown.setImageName",
                        () -> settings.get().getMarkdownImageNamePattern(),
                        v -> settings.get().setMarkdownImageNamePattern(v),
                        apply)));
    }

    private static void choose(
            EditorSettingsCoordinator editor,
            Supplier<Settings> settings,
            String id,
            String labelPrefix,
            List<String> values,
            Function<Settings, String> get,
            BiConsumer<Settings, String> set,
            Runnable apply) {
        editor.chooseSetting(id, () -> values, v -> tr(labelPrefix + v), () -> get.apply(settings.get()), v -> {
            set.accept(settings.get(), v);
            editor.settingChosen(id, tr(labelPrefix + v), apply);
        });
    }
}
