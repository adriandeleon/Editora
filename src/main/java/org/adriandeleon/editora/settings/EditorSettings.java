package org.adriandeleon.editora.settings;

import org.adriandeleon.editora.theme.EditorTheme;

import java.util.LinkedHashSet;
import java.util.List;

public record EditorSettings(EditorTheme theme,
                             boolean wrapText,
                             boolean diagnosticsEnabled,
							 boolean miniMapVisible,
							 boolean searchBarVisible,
							 boolean toolDockVisible,
							 boolean bookmarkWindowVisible,
							 boolean breadcrumbBarVisible,
							 ToolWindowSide toolDockSide,
                             String commandPaletteShortcut,
                             String editorFontFamily,
							 int editorFontSize,
							 boolean readOnlyOpenEnabled,
							 List<String> readOnlyOpenPatterns) {
	public static final boolean DEFAULT_MINI_MAP_VISIBLE = true;
	public static final boolean DEFAULT_SEARCH_BAR_VISIBLE = false;
	public static final boolean DEFAULT_TOOL_DOCK_VISIBLE = false;
	public static final boolean DEFAULT_BOOKMARK_WINDOW_VISIBLE = false;
	public static final ToolWindowSide DEFAULT_TOOL_DOCK_SIDE = ToolWindowSide.DEFAULT;
	public static final String DEFAULT_EDITOR_FONT_FAMILY = "JetBrains Mono";
	public static final int DEFAULT_EDITOR_FONT_SIZE = 14;
	public static final boolean DEFAULT_READ_ONLY_OPEN_ENABLED = true;
	public static final List<String> DEFAULT_READ_ONLY_OPEN_PATTERNS = List.of("*.md", "*.txt", "README");

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
		List<String> normalizedPatterns = readOnlyOpenPatterns == null ? List.of() : readOnlyOpenPatterns.stream()
				.map(pattern -> pattern == null ? "" : pattern.strip())
				.filter(pattern -> !pattern.isBlank())
				.toList();
		readOnlyOpenPatterns = List.copyOf(new LinkedHashSet<>(normalizedPatterns));
	}
}

