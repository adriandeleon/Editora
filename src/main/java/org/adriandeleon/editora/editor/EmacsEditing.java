package org.adriandeleon.editora.editor;

public final class EmacsEditing {

    private EmacsEditing() {
    }

    public record EditOperation(int start, int end, String replacement, int caretPosition, String affectedText) {

        public EditOperation {
            replacement = replacement == null ? "" : replacement;
            affectedText = affectedText == null ? "" : affectedText;
        }

        public boolean isNoOp() {
            return start == end && replacement.isEmpty() && affectedText.isEmpty();
        }
    }

    public static EditOperation killRegion(String text, int selectionStart, int selectionEnd) {
        String value = safeText(text);
        int start = clamp(value, Math.min(selectionStart, selectionEnd));
        int end = clamp(value, Math.max(selectionStart, selectionEnd));
        return removeRange(value, start, end);
    }

    public static EditOperation deleteForwardChar(String text, int caretPosition) {
        String value = safeText(text);
        int caret = clamp(value, caretPosition);
        if (caret >= value.length()) {
            return noOp(caret);
        }
        return removeRange(value, caret, caret + 1);
    }

    public static EditOperation killLine(String text, int caretPosition) {
        String value = safeText(text);
        int caret = clamp(value, caretPosition);
        if (caret >= value.length()) {
            return noOp(caret);
        }

        int lineEnd = EmacsNavigation.lineEnd(value, caret);
        int end = lineEnd == caret ? caret + 1 : lineEnd;
        return removeRange(value, caret, end);
    }

    public static EditOperation killWordForward(String text, int caretPosition) {
        String value = safeText(text);
        int caret = clamp(value, caretPosition);
        return removeRange(value, caret, EmacsNavigation.forwardWord(value, caret));
    }

    public static EditOperation killWordBackward(String text, int caretPosition) {
        String value = safeText(text);
        int caret = clamp(value, caretPosition);
        return removeRange(value, EmacsNavigation.backwardWord(value, caret), caret);
    }

    public static EditOperation yank(String text, int selectionStart, int selectionEnd, String snippet) {
        String value = safeText(text);
        String insertion = snippet == null ? "" : snippet;
        int start = clamp(value, Math.min(selectionStart, selectionEnd));
        int end = clamp(value, Math.max(selectionStart, selectionEnd));
        return replaceRange(value, start, end, insertion);
    }

    private static EditOperation removeRange(String text, int start, int end) {
        if (start >= end) {
            return noOp(start);
        }
        return replaceRange(text, start, end, "");
    }

    private static EditOperation replaceRange(String text, int start, int end, String replacement) {
        String value = safeText(text);
        int boundedStart = clamp(value, start);
        int boundedEnd = clamp(value, end);
        String actualReplacement = replacement == null ? "" : replacement;
        String affectedText = value.substring(Math.min(boundedStart, boundedEnd), Math.max(boundedStart, boundedEnd));
        return new EditOperation(
                boundedStart,
                boundedEnd,
                actualReplacement,
                boundedStart + actualReplacement.length(),
                affectedText
        );
    }

    private static EditOperation noOp(int caretPosition) {
        return new EditOperation(caretPosition, caretPosition, "", caretPosition, "");
    }

    private static int clamp(String text, int position) {
        return Math.max(0, Math.min(position, safeText(text).length()));
    }

    private static String safeText(String text) {
        return text == null ? "" : text;
    }
}
