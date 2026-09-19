package com.editora.completion;

/** Conservative Java call insertion: use the server's snippet, but reuse an existing argument list. */
public final class JavaCompletionInsertion {
    private JavaCompletionInsertion() {}

    public record Plan(String text, boolean snippet, int caretOffset) {}

    public static Plan plan(Completion item, String document, int start, int end) {
        String body = item.snippet() == null ? item.insert() : item.snippet().body();
        Plan unchanged = new Plan(body, item.snippet() != null, -1);
        if (item.iconKind() != CompletionIconKind.METHOD
                && item.iconKind() != CompletionIconKind.FUNCTION
                && item.iconKind() != CompletionIconKind.CONSTRUCTOR) return unchanged;
        if (body.isEmpty()) return unchanged; // e.g. a parameter hint completion at foo(|)
        int open = body.indexOf('(');
        String name = open >= 0 ? body.substring(0, open) : body;
        String identifier = item.iconKind() == CompletionIconKind.CONSTRUCTOR && name.endsWith("<>")
                ? name.substring(0, name.length() - 2)
                : name;
        if (identifier.isEmpty()
                || !Character.isJavaIdentifierStart(identifier.charAt(0))
                || !identifier.chars().allMatch(Character::isJavaIdentifierPart)) return unchanged;
        // A method reference denotes the method; parentheses would turn it into an invalid invocation.
        if (start >= 2 && document.regionMatches(start - 2, "::", 0, 2)) return new Plan(name, false, -1);
        int following = end;
        while (following < document.length()
                && (document.charAt(following) == ' ' || document.charAt(following) == '\t')) following++;
        if (following < document.length() && document.charAt(following) == '(') {
            return new Plan(name, false, name.length() + following - end + 1);
        }
        if (open < 0) {
            boolean noArguments = item.label() != null && item.label().contains("()");
            return new Plan(name + (noArguments ? "()" : "($0)"), !noArguments, -1);
        }
        return unchanged;
    }
}
