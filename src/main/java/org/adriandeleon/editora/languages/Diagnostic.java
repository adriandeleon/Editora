package org.adriandeleon.editora.languages;

public record Diagnostic(int lineIndex, DiagnosticSeverity severity, String message) {
}

