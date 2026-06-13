package com.example.editora.banner;

/** Pure ASCII-box banner builder. */
final class BannerText {

    private BannerText() {
    }

    /** Wraps {@code text} in a bordered box of {@code *}, padding lines to the widest. */
    static String box(String text) {
        String[] ls = text.isEmpty() ? new String[] {""} : text.split("\n", -1);
        int w = 0;
        for (String l : ls) {
            w = Math.max(w, l.length());
        }
        String border = "*".repeat(w + 4);
        StringBuilder sb = new StringBuilder();
        sb.append(border).append('\n');
        for (String l : ls) {
            sb.append("* ").append(l).append(" ".repeat(w - l.length())).append(" *\n");
        }
        sb.append(border);
        return sb.toString();
    }
}
