package com.editora.editor;

/**
 * One offered quick fix or refactoring, in terms this package can hold.
 *
 * <p>{@code editor} stays free of lsp4j — the same rule that keeps {@link LspDiagnostic} and
 * {@link com.editora.completion.Completion} neutral — so the server's own action object rides along as an
 * opaque {@link #token()}. The popup shows {@link #title()} and {@link #kind()}; only the coordinator that
 * created the action knows how to apply it, and it gets its object back untouched.
 *
 * @param title what the server calls the action, shown to the user
 * @param kind the LSP action kind ({@code quickfix}, {@code refactor.extract}, …), shown muted; may be null
 * @param preferred whether the server marked this its preferred action, so it can be selected on open
 * @param token the originating action, handed back verbatim on accept
 */
public record CodeAction(String title, String kind, boolean preferred, Object token) {}
