package org.adriandeleon.editora.settings;

import org.adriandeleon.editora.theme.EditorTheme;

public record EditorSettings(EditorTheme theme,
                             boolean wrapText,
                             boolean diagnosticsEnabled,
							 boolean miniMapVisible,
							 boolean searchBarVisible,
							 boolean toolDockVisible,
							 boolean breadcrumbBarVisible,
							 ToolWindowSide toolDockSide,
                             String commandPaletteShortcut,
                             String editorFontFamily,
                             int editorFontSize) {
	public static final boolean DEFAULT_MINI_MAP_VISIBLE = true;
	public static final boolean DEFAULT_SEARCH_BAR_VISIBLE = false;
	public static final boolean DEFAULT_TOOL_DOCK_VISIBLE = false;
	public static final ToolWindowSide DEFAULT_TOOL_DOCK_SIDE = ToolWindowSide.DEFAULT;
	public static final String DEFAULT_EDITOR_FONT_FAMILY = "JetBrains Mono";
	public static final int DEFAULT_EDITOR_FONT_SIZE = 14;

	public EditorSettings {
		theme = theme == null ? EditorTheme.defaultTheme() : theme;
		toolDockSide = toolDockSide == null ? DEFAULT_TOOL_DOCK_SIDE : toolDockSide;
		commandPaletteShortcut = CommandPaletteShortcut.normalize(commandPaletteShortcut);
		if (CommandPaletteShortcut.isReserved(commandPaletteShortcut)) {
			commandPaletteShortcut = CommandPaletteShortcut.DEFAULT_VALUE;
		}
		editorFontFamily = editorFontFamily == null || editorFontFamily.isBlank()
				? DEFAULT_EDITOR_FONT_FAMILY
				: editorFontFamily.strip();
		if (editorFontSize < 8 || editorFontSize > 72) {
			editorFontSize = DEFAULT_EDITOR_FONT_SIZE;
		}
	}
}

