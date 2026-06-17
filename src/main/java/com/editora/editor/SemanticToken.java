package com.editora.editor;

/**
 * A resolved highlight span from an LSP server's semantic tokens: 0-based {@code line} and
 * {@code startChar}, a character {@code length}, and a space-separated CSS class string
 * (e.g. {@code "sem-parameter"} or {@code "sem-type sem-deprecated"}).
 *
 * <p>This is a neutral value type so the {@code editor} package never imports lsp4j — it mirrors
 * {@link LspDiagnostic} / {@link LspTextEdit}. The {@code lsp} layer decodes the wire format into
 * these; {@code EditorBuffer} overlays them onto the TextMate highlight.
 */
public record SemanticToken(int line, int startChar, int length, String cssClasses) {}
