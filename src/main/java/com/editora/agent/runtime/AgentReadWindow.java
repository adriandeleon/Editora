package com.editora.agent.runtime;

/** Explicit line paging prevents a bounded preview from looking like a complete file. Runs off FX. */
public record AgentReadWindow(
        String text, int endLine, int totalLines, Integer nextLine, boolean truncated, boolean longLineTruncated) {
    public static AgentReadWindow read(String text, int line, int limit) {
        if (line < 1 || limit < 1 || limit > 200) throw new IllegalArgumentException("Invalid read window");
        int total = (int) text.lines().count();
        var lines = text.lines().skip(line - 1L).limit(limit).toList();
        var out = new StringBuilder();
        int included = 0;
        boolean longLine = false;
        for (var value : lines) {
            int separator = included == 0 ? 0 : 1;
            if (out.length() + separator + value.length() > 6000) {
                if (included == 0) {
                    out.append(AgentContext.bounded(value, 6000));
                    included++;
                    longLine = true;
                }
                break;
            }
            if (separator > 0) out.append('\n');
            out.append(value);
            included++;
        }
        int end = included == 0 ? 0 : line - 1 + included;
        Integer next = included > 0 && end < total ? end + 1 : null;
        return new AgentReadWindow(out.toString(), end, total, next, next != null || longLine, longLine);
    }
}
